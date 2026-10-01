package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.DragEvent
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    internal var popupMenu: PopupMenu? = null
    internal lateinit var items: MutableList<Any>

    internal var isDragging = false
    private var startX = 0f
    private var startY = 0f
    var longPressedView: View? = null

    internal var activeFolder: Folder? = null
    internal var activeFolderAdapter: AppsAdapter? = null
    internal var activeFolderDialog: AlertDialog? = null

    private var refreshJob: Job? = null
    
    // Cache for intent-based apps to avoid expensive IPC
    internal var cachedApps: Map<String, ResolveInfo> = emptyMap()
    internal val iconCache = ConcurrentHashMap<String, Drawable>()
    internal val labelCache = ConcurrentHashMap<String, String>()
    private var browserApps = setOf<String>()
    private var emailApps = setOf<String>()
    private var dialerApps = setOf<String>()
    private val categoryCache = ConcurrentHashMap<String, List<String>>()
    private var isCategorySetsPrefetched = false

    @SuppressLint("ClickableViewAccessibility")
    val appTouchListener = View.OnTouchListener { v, event ->
        // This is a simple click-detection mechanism.
        // We need this to manually call performClick() for accessibility.
        val isClick =
            (event.action == MotionEvent.ACTION_UP && !isDragging && (event.eventTime - event.downTime) < android.view.ViewConfiguration.getTapTimeout())

        // If a click is detected, perform the click and stop processing this touch event.
        if (isClick) {
            v.performClick()
            // By returning here, we prevent the 'when' block from executing for a simple click.
            return@OnTouchListener true
        }

        val recyclerView = v as? RecyclerView ?: return@OnTouchListener false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isDragging = false // Reset dragging flag
                // Return false so that onLongClick can still be triggered.
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val viewToDrag = longPressedView
                // Check if a long press has occurred and we are not already dragging
                if (viewToDrag != null && !isDragging) {
                    val touchSlop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
                    // Check if the finger has moved far enough to be considered a drag
                    if (abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop) {
                        popupMenu?.dismiss() // Dismiss the menu
                        val position =
                            recyclerView.getChildViewHolder(viewToDrag)?.bindingAdapterPosition
                        if (position != null) {
                            val item = (recyclerView.adapter as? AppsAdapter)?.items?.get(position)

                            val mimeType = when (item) {
                                is ResolveInfo -> "vnd.android.cursor.item/app"
                                is Folder -> "vnd.android.cursor.item/folder"
                                is GlobalActionItem -> "vnd.android.cursor.item/action"
                                else -> ClipDescription.MIMETYPE_TEXT_PLAIN
                            }

                            val clipDataItem = ClipData.Item(position.toString())
                            val mimeTypes = arrayOf(mimeType)
                            val clipData = ClipData("drag-app", mimeTypes, clipDataItem)
                            val dragShadowBuilder = View.DragShadowBuilder(viewToDrag)

                            // Pass the local reference 'viewToDrag' as the local state.
                            // This object will not be nulled out during the drag operation.
                            recyclerView.startDragAndDrop(
                                clipData, dragShadowBuilder, viewToDrag, 0
                            )

                            // Hide the original view to prevent the "duplicate" effect.
                            viewToDrag.visibility = View.INVISIBLE
                        }
                        isDragging = true // Mark that we are now dragging
                        longPressedView = null // It's now safe to nullify the class property.
                    }
                }
                // If dragging, consume the event
                isDragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // This block now only runs if the gesture ended but wasn't a click (e.g., a long-press without a drag).
                longPressedView = null
                isDragging = false
                false // Allow other events to process if needed
            }

            else -> false
        }
    }

    private val appDragListener = View.OnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                // Accept drags for apps, folders, and actions
                val mimeTypes = event.clipDescription
                mimeTypes.hasMimeType("vnd.android.cursor.item/app") || 
                mimeTypes.hasMimeType("vnd.android.cursor.item/folder") ||
                mimeTypes.hasMimeType("vnd.android.cursor.item/action")
            }
            DragEvent.ACTION_DROP -> {
                false
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val view = event.localState as? View
                view?.post {
                    view.visibility = View.VISIBLE
                    // Sometimes a forced requestLayout on the parent helps stabilize Dialogs
                    (view.parent as? View)?.requestLayout()
                }
                isDragging = false
                true
            }
            else -> true
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshApps()
        }
    }

    private val settingsResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // App settings screen closed, refresh the list
            refreshApps()
        }

    companion object {
        private const val PREFS_NAME = "AppOrder"
        private const val KEY_PINNED_APPS = "pinned_apps_v1"
        private const val LAYOUT_KEY = "app_layout_v5" // Upgraded key to include global action item
        private const val ACTION_ITEM_KEY = "G:ACTION"
        private const val CATEGORY_CACHE_PREFS = "CategoryCache"

        private val DEFAULT_PINNED_APP_KEYWORDS = listOf(
            "camera", "clock", "deskclock", "chrome", "files",
            "documentsui", "filemanager", "myfiles", "maps",
            "vending", "playstore", "play store"
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        appsList = findViewById(R.id.apps_list)

        // Set a default layout manager immediately to prevent flickering while waiting for insets
        appsList.layoutManager = GridLayoutManager(this, 4, GridLayoutManager.VERTICAL, true)

        ViewCompat.setOnApplyWindowInsetsListener(appsList) { view, insets ->
            // Using getInsetsIgnoringVisibility ensures that we reserve space for the system bars
            // even if they are currently hidden or transitioning, preventing the "jump" or flicker.
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            
            val density = resources.displayMetrics.density
            val horizontalPadding = (12 * density).toInt()

            val paddingLeft = max(systemBars.left, displayCutout.left) + horizontalPadding
            val paddingTop = max(systemBars.top, displayCutout.top)
            val paddingRight = max(systemBars.right, displayCutout.right) + horizontalPadding
            val paddingBottom = max(systemBars.bottom, displayCutout.bottom)

            // Padding the RecyclerView itself with clipToPadding="false" (set in XML)
            // allows it to remain full-screen while keeping content clear of system bars.
            view.updatePadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            
            // Recalculate span count using the new usable width
            val screenWidthPx = resources.displayMetrics.widthPixels
            val usableWidthPx = screenWidthPx - paddingLeft - paddingRight
            val usableWidthDp = usableWidthPx / density
            
            val itemWidthDp = resources.getDimension(R.dimen.grid_item_width) / density
            val spanCount = (usableWidthDp / itemWidthDp).toInt().coerceAtLeast(4)
            
            // Update spanCount without recreating the LayoutManager to avoid unnecessary re-layouts
            val currentLayout = appsList.layoutManager as? GridLayoutManager
            if (currentLayout != null) {
                if (currentLayout.spanCount != spanCount) {
                    currentLayout.spanCount = spanCount
                }
            } else {
                appsList.layoutManager = GridLayoutManager(this, spanCount, GridLayoutManager.VERTICAL, true)
            }
            
            insets
        }

        refreshApps()

        val intentFilter = IntentFilter("com.tharos.allappsondeck.REFRESH_APPS")
        ContextCompat.registerReceiver(
            this, refreshReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        appsList.setOnTouchListener(appTouchListener)
        appsList.setOnDragListener(appDragListener)
    }

    fun showAppDetails(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:$packageName".toUri()
        // FLAG_ACTIVITY_NEW_TASK makes it show as "Settings" in recents
        // FLAG_ACTIVITY_CLEAR_TASK prevents multiple app info screens from stacking
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        settingsResultLauncher.launch(intent)
    }

    private fun ensureCategorySetsPrefetched() {
        if (isCategorySetsPrefetched) return
        prefetchCategorySets()
        isCategorySetsPrefetched = true
    }

    private fun prefetchCategorySets() {
        browserApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, "http://google.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()

        emailApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, "mailto:".toUri()),
            PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()

        dialerApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, "tel:".toUri()),
            PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()
    }

    private fun getAppCategories(packageName: String): List<String> {
        categoryCache[packageName]?.let { return it }
        
        // 1. Try persistent cache
        val prefs = getSharedPreferences(CATEGORY_CACHE_PREFS, MODE_PRIVATE)
        prefs.getString(packageName, null)?.let { cached ->
            val list = cached.split("|").filter { it.isNotEmpty() }
            categoryCache[packageName] = list
            return list
        }

        val categories = mutableListOf<String>()
        try {
            ensureCategorySetsPrefetched()
            val isBrowser = browserApps.contains(packageName)
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = appInfo.loadLabel(packageManager).toString().lowercase()
            val pkgName = packageName.lowercase()

            val isMainGoogleApp = pkgName == "com.google.android.googlequicksearchbox" || label == "google"

            // 2. Built-in Category (Fast)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!isMainGoogleApp || appInfo.category != ApplicationInfo.CATEGORY_AUDIO) {
                    val builtin = when (appInfo.category) {
                        ApplicationInfo.CATEGORY_GAME -> FolderCategories.GAMES
                        ApplicationInfo.CATEGORY_AUDIO -> FolderCategories.MEDIA
                        ApplicationInfo.CATEGORY_VIDEO -> FolderCategories.MEDIA
                        ApplicationInfo.CATEGORY_IMAGE -> FolderCategories.MEDIA
                        ApplicationInfo.CATEGORY_SOCIAL -> if (!isBrowser) FolderCategories.COMMUNICATION else null
                        ApplicationInfo.CATEGORY_NEWS -> FolderCategories.NEWS
                        ApplicationInfo.CATEGORY_MAPS -> FolderCategories.NAVIGATION
                        ApplicationInfo.CATEGORY_PRODUCTIVITY -> FolderCategories.PRODUCTIVITY
                        ApplicationInfo.CATEGORY_ACCESSIBILITY -> FolderCategories.ACCESSIBILITY
                        else -> null
                    }
                    builtin?.let { categories.add(it) }
                }
            }

            // 3. Keyword & Package Heuristics (Fastest)
            if (matchesKeywords(label, packageName,
                    "game", "games", "gaming", "steam", "epicgames", "epic games", "roblox", "minecraft",
                    "playstation", "xbox", "nintendo", "twitch", "riot", "emulator", "arcade",
                    "puzzle", "sudoku", "chess", "casino", "poker", "cards", "solitaire", "rpg", "mmo",
                    "fps", "quest", "craft", "clash", "saga", "genshin", "honkai", "pokemon", "retroarch",
                    "dolphin", "ppsspp", "yuzu", "citra", "gamepass", "geforce now"
                )) categories.add(FolderCategories.GAMES)

            if (matchesKeywords(label, packageName,
                    "taxi", "ride", "uber", "lyft", "grab", "transit", "train", "bus", "metro", "subway",
                    "tram", "ferry", "rail", "commute", "transport", "mobility", "orca", "umo", "clipper",
                    "ventra", "mta", "bart", "septa", "marta", "charliecard", "oyster", "navigo", "suica",
                    "pasmo", "icoca", "hopcard", "fare", "ticket", "toll", "bird", "lime", "spin", "scooter",
                    "waymo", "bolt", "freenow", "gett", "didi", "cabify", "olacabs", "taxify"
                )) categories.add(FolderCategories.TRANSIT)

            if (matchesKeywords(label, packageName,
                    "flight", "airline", "hotel", "booking", "travel", "expedia", "airbnb", "trip", "kayak",
                    "priceline", "agoda", "trivago", "tripadvisor", "hopper", "hostel", "flightradar",
                    "delta", "united", "american airlines", "southwest", "lufthansa", "ryanair",
                    "easyjet", "emirates", "tsa", "passport"
                )) categories.add(FolderCategories.TRAVEL)

            if (matchesKeywords(label, packageName,
                    "bank", "pay", "wallet", "finance", "credit", "crypto", "invest", "stock", "chase",
                    "wells", "citi", "bofa", "fidelity", "vanguard", "schwab", "robinhood", "coinbase",
                    "venmo", "cash app", "paypal", "zelle", "revolut", "wise", "stripe", "mint", "ynab",
                    "monzo", "capital one", "discover", "turbotax"
                )) categories.add(FolderCategories.FINANCE)

            if (matchesKeywords(label, packageName,
                    "shop", "store", "market", "amazon", "ebay", "walmart", "target", "shopping", "cart",
                    "etsy", "aliexpress", "temu", "shein", "rakuten", "klarna", "afterpay", "poshmark",
                    "mercari", "chewy", "bestbuy", "costco", "samsclub"
                )) categories.add(FolderCategories.SHOPPING)

            if (matchesKeywords(label, packageName,
                    "food", "drink", "restaurant", "doordash", "ubereats", "grubhub", "postmates", "instacart",
                    "seamless", "yelp", "opentable", "resy", "mcdonald", "starbucks", "domino", "pizza",
                    "dunkin", "chipotle", "subway", "taco bell", "coffee", "bakery"
                )) categories.add(FolderCategories.FOOD)

            if (matchesKeywords(label, packageName,
                    "health", "fitness", "workout", "gym", "strava", "fitbit", "garmin", "myfitnesspal",
                    "nike", "running", "yoga", "meditation", "headspace", "calm", "pharmacy", "cvs",
                    "walgreens", "doctor", "clinic", "medical", "patient"
                )) categories.add(FolderCategories.HEALTH)

            if (matchesKeywords(label, packageName,
                    "smarthome", "smart home", "google home", "alexa", "smartthings", "hue", "ring",
                    "nest", "tuya", "kasa", "tapo", "homekit", "nanoleaf", "wyze", "ecobee", "simplisafe",
                    "arlo", "blink", "lifx"
                )) categories.add(FolderCategories.SMART_HOME)

            if (matchesKeywords(label, packageName,
                    "office", "doc", "sheet", "slide", "pdf", "note", "keep", "word", "excel", "ppt",
                    "workspace", "notion", "evernote", "obsidian", "todoist", "ticktick", "trello",
                    "jira", "asana", "scanner", "drive", "dropbox", "box", "onedrive", "calculator",
                    "calendar", "clock", "alarm", "file manager"
                )) categories.add(FolderCategories.PRODUCTIVITY)

            if (!isMainGoogleApp) {
                if (matchesKeywords(label, packageName,
                        "photo", "gallery", "camera", "editor", "video", "player", "music", "stream", "netflix",
                        "youtube", "hulu", "disney", "hbo", "spotify", "pandora", "soundcloud", "deezer",
                        "tidal", "vlc", "plex", "jellyfin", "audible", "podcast", "cinema", "movie", "prime video"
                    )) categories.add(FolderCategories.MEDIA)
            }

            if (matchesKeywords(label, packageName,
                    "news", "nytimes", "wapo", "bbc", "cnn", "foxnews", "reuters", "bloomberg", "wsj",
                    "guardian", "medium", "feedly", "pocket", "flipboard", "newspaper"
                )) categories.add(FolderCategories.NEWS)

            if (matchesKeywords(label, packageName,
                    "map", "navigation", "gps", "waze", "compass", "speedometer", "location"
                )) categories.add(FolderCategories.NAVIGATION)

            if (!isBrowser) {
                if (matchesKeywords(label, packageName,
                        "chat", "msg", "messenger", "whatsapp", "signal", "telegram", "discord", "slack",
                        "social", "text", "viber", "line", "wechat", "qq", "kakaotalk", "meet", "teams", "zoom",
                        "skype", "element", "session", "matrix", "messages", "message", "forum", "reddit",
                        "twitter", "instagram", "facebook", "snapchat", "tiktok", "linkedin", "dating", "tinder",
                        "bumble", "hinge", "contact", "phone", "dialer", "call", "mail", "outlook", "gmail", "inbox"
                    )) categories.add(FolderCategories.COMMUNICATION)
            }

            // 4. Publisher Check (Fast)
            if (pkgName.startsWith("com.google.android") || pkgName.startsWith("com.google.android.apps")) categories.add(FolderCategories.GOOGLE)
            if (pkgName.startsWith("com.microsoft.")) categories.add(FolderCategories.MICROSOFT)
            if (pkgName.startsWith("com.sec.android") || pkgName.startsWith("com.samsung.")) categories.add(FolderCategories.SAMSUNG)

            // 5. Pre-fetched Intent Categorization (Fast - avoided expensive IPC)
            if (isBrowser) {
                categories.add(FolderCategories.BROWSERS)
            } else if (categories.size < 2) {
                if (emailApps.contains(packageName)) categories.add(FolderCategories.COMMUNICATION)
                if (dialerApps.contains(packageName)) categories.add(FolderCategories.COMMUNICATION)
            }

            if (isBrowser) {
                categories.remove(FolderCategories.COMMUNICATION)
            }

        } catch (_: Exception) {
            // App might have been uninstalled
        }
        val result = categories.distinct()
        categoryCache[packageName] = result
        prefs.edit { putString(packageName, result.joinToString("|")) }
        return result
    }

    private fun matchesKeywords(label: String, packageName: String, vararg keywords: String): Boolean {
        val labelLower = label.lowercase()
        val pkgLower = packageName.lowercase()
        val pkgSegments = pkgLower.split(".", "_", "-")

        return keywords.any { kw ->
            val kwLower = kw.lowercase()
            if (labelLower.contains(kwLower)) return@any true
            pkgSegments.any { seg ->
                seg == kwLower || seg.startsWith(kwLower) || (kwLower.length >= 4 && seg.contains(kwLower))
            }
        }
    }

    private fun isDefaultPinnedApp(packageName: String): Boolean {
        val label = labelCache[packageName] ?: cachedApps[packageName]?.let {
            try { it.loadLabel(packageManager).toString() } catch (_: Exception) { "" }
        } ?: ""
        return matchesKeywords(label, packageName, *DEFAULT_PINNED_APP_KEYWORDS.toTypedArray())
    }

    private fun getDefaultPinnedAppPackages(): Set<String> {
        return cachedApps.keys.filter { isDefaultPinnedApp(it) }.toSet()
    }

    internal fun isAppPinned(packageName: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(KEY_PINNED_APPS)) {
            return isDefaultPinnedApp(packageName)
        }
        val pinnedSet = prefs.getStringSet(KEY_PINNED_APPS, null) ?: emptySet()
        return pinnedSet.contains(packageName)
    }

    internal fun togglePinApp(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = if (!prefs.contains(KEY_PINNED_APPS)) {
            getDefaultPinnedAppPackages().toMutableSet()
        } else {
            prefs.getStringSet(KEY_PINNED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        }

        val becomingPinned = !current.contains(packageName)
        if (current.contains(packageName)) {
            current.remove(packageName)
            Toast.makeText(this, "App unpinned from main list", Toast.LENGTH_SHORT).show()
        } else {
            current.add(packageName)
            Toast.makeText(this, "App pinned to main list", Toast.LENGTH_SHORT).show()
        }
        prefs.edit { putStringSet(KEY_PINNED_APPS, current) }

        if (becomingPinned) {
            cachedApps[packageName]?.let { appResolveInfo ->
                val folder = items.find { it is Folder && it.apps.contains(packageName) } as? Folder
                if (folder != null) {
                    removeAppFromFolder(appResolveInfo)
                    return
                }
            }
        }
        
        refreshApps()
    }

    internal fun unpinAllApps() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putStringSet(KEY_PINNED_APPS, emptySet()) }
        refreshApps()
        Toast.makeText(this, "All apps unpinned", Toast.LENGTH_SHORT).show()
    }

    internal fun restoreDefaultPinnedApps() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { remove(KEY_PINNED_APPS) }

        val defaultPinnedApps = cachedApps.values.filter { isDefaultPinnedApp(it.activityInfo.packageName) }

        for (app in defaultPinnedApps) {
            val folder = items.find { it is Folder && it.apps.contains(app.activityInfo.packageName) } as? Folder
            if (folder != null) {
                removeAppFromFolder(app)
            }
        }

        refreshApps()
        Toast.makeText(this, "Default pinned apps restored", Toast.LENGTH_SHORT).show()
    }

    internal fun getFolderNameForApps(packageNames: List<String>): String {
        val categoryCounts = mutableMapOf<String, Int>()
        packageNames.forEach { pkg ->
            getAppCategories(pkg).forEach { cat ->
                categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
            }
        }

        if (categoryCounts.isEmpty()) return FolderCategories.DEFAULT_FOLDER_NAME

        // Find the category that appears in the most apps in this set
        return categoryCounts.maxByOrNull { it.value }?.key ?: FolderCategories.DEFAULT_FOLDER_NAME
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun autoOrganizeApps() {
        lifecycleScope.launch {
            val actionItem = items.find { it is GlobalActionItem }
            
            // Move heavy computation to Default dispatcher
            val result = withContext(Dispatchers.Default) {
                ensureCategorySetsPrefetched()
                getSharedPreferences(CATEGORY_CACHE_PREFS, MODE_PRIVATE).edit { clear() }
                categoryCache.clear()
                val apps = getInstalledLauncherApps()

                // 1. Get all categories for all apps
                val appToCategories = apps.associateWith { getAppCategories(it.activityInfo.packageName) }

                // 2. Count potential members for each category
                val potentialCounts = mutableMapOf<String, Int>()
                appToCategories.values.flatten().forEach {
                    potentialCounts[it] = (potentialCounts[it] ?: 0) + 1
                }

                // 3. Keep only categories that have at least 2 potential members
                val validCategories = potentialCounts.filter { it.value > 1 }.keys.toList()

                val publisherCategories = FolderCategories.PUBLISHER_CATEGORIES

                fun selectBestCategory(app: ResolveInfo, appCats: List<String>, currentAssignments: Map<String, List<ResolveInfo>>): String {
                    val pkgName = app.activityInfo.packageName
                    val label = app.loadLabel(packageManager).toString().lowercase()
                    if (appCats.contains(FolderCategories.GOOGLE) && (pkgName == "com.google.android.googlequicksearchbox" || label == "google")) {
                        return FolderCategories.GOOGLE
                    }
                    if (appCats.contains(FolderCategories.BROWSERS)) {
                        return FolderCategories.BROWSERS
                    }
                    if (appCats.contains(FolderCategories.COMMUNICATION)) {
                        return FolderCategories.COMMUNICATION
                    }
                    val functionalCats = appCats.filter { it !in publisherCategories }
                    val targetCats = functionalCats.ifEmpty { appCats }
                    return targetCats.minByOrNull { currentAssignments[it]?.size ?: 0 }!!
                }

                // 4. Initial Assignment: Least flexible apps first
                val sortedApps = apps.sortedBy { app -> appToCategories[app]?.count { it in validCategories } ?: 0 }
                val assignments = mutableMapOf<String, MutableList<ResolveInfo>>()
                val unassignedApps = mutableListOf<ResolveInfo>()

                for (app in sortedApps) {
                    val pkgName = app.activityInfo.packageName
                    val appCats = appToCategories[app]?.filter { it in validCategories } ?: emptyList()
                    if (appCats.isEmpty() || isAppPinned(pkgName)) {
                        unassignedApps.add(app)
                    } else {
                        val bestCat = selectBestCategory(app, appCats, assignments)
                        assignments.getOrPut(bestCat) { mutableListOf() }.add(app)
                    }
                }

                // 5. Global Balancing logic (already optimized, but runs off-thread now)
                var changed: Boolean
                var safetyCounter = 0
                val maxIterations = apps.size * 2
                
                do {
                    changed = false
                    safetyCounter++
                    val fromCats = assignments.keys.sortedByDescending { assignments[it]?.size ?: 0 }
                    for (fromCat in fromCats) {
                        val fromApps = assignments[fromCat] ?: continue
                        if (fromApps.size <= 2) continue
                        val toCats = validCategories.sortedBy { assignments[it]?.size ?: 0 }
                        for (toCat in toCats) {
                            if (fromCat == toCat) continue
                            val toAppsSize = assignments[toCat]?.size ?: 0
                            if (fromApps.size > toAppsSize + 1) {
                                val movableApp = fromApps.find { app ->
                                    val pkgName = app.activityInfo.packageName
                                    val label = app.loadLabel(packageManager).toString().lowercase()
                                    val isMainGoogleApp = pkgName == "com.google.android.googlequicksearchbox" || label == "google"
                                    val cats = appToCategories[app] ?: emptyList()
                                    cats.contains(toCat) &&
                                        !(fromCat == FolderCategories.COMMUNICATION && cats.contains(FolderCategories.COMMUNICATION)) &&
                                        !(fromCat == FolderCategories.BROWSERS && cats.contains(FolderCategories.BROWSERS)) &&
                                        !(fromCat == FolderCategories.GOOGLE && isMainGoogleApp)
                                }
                                if (movableApp != null) {
                                    fromApps.remove(movableApp)
                                    assignments.getOrPut(toCat) { mutableListOf() }.add(movableApp)
                                    changed = true
                                    break
                                }
                            }
                        }
                        if (changed) break
                    }
                } while (changed && safetyCounter < maxIterations)
                
                Triple(assignments, unassignedApps, validCategories)
            }

            val assignments = result.first
            val unassignedApps = result.second

            // 6. Build new items list on Main thread
            val newItems = mutableListOf<Any>()
            if (actionItem != null) {
                newItems.add(actionItem)
            }

            assignments.keys.sorted().forEach { category ->
                val groupApps = assignments[category] ?: return@forEach
                if (groupApps.size > 1) {
                    val packageNames = groupApps.map { it.activityInfo.packageName }.toMutableList()
                    newItems.add(Folder(category, packageNames))
                } else {
                    unassignedApps.addAll(groupApps)
                }
            }

            val (pinnedApps, nonPinnedApps) = unassignedApps.partition { isAppPinned(it.activityInfo.packageName) }

            fun getLabel(app: ResolveInfo) = labelCache[app.activityInfo.packageName]
                ?: app.loadLabel(packageManager).toString()

            newItems.addAll(pinnedApps.sortedBy { getLabel(it).lowercase() })
            newItems.addAll(nonPinnedApps.sortedBy { getLabel(it).lowercase() })

            items.clear()
            items.addAll(newItems)
            appsList.adapter?.notifyDataSetChanged()
            withContext(Dispatchers.IO) { saveAppOrder() }
            Toast.makeText(this@MainActivity, "Apps organized into folders", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Close menus, dialogs, and reset drag states when returning home
        popupMenu?.dismiss()
        activeFolderDialog?.dismiss()
        isDragging = false
        longPressedView = null
    }

    override fun onResume() {
        super.onResume()
        // Close folder dialog and popups when returning to home screen
        popupMenu?.dismiss()
        activeFolderDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }

    private fun getInstalledLauncherApps(): List<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val (apps, appMap) = withContext(Dispatchers.IO) {
                val installed = getInstalledLauncherApps()
                val newAppMap = installed.associateBy { it.activityInfo.packageName }

                // Clean up old entries for uninstalled apps
                val installedPackages = newAppMap.keys
                iconCache.keys.retainAll(installedPackages)
                labelCache.keys.retainAll(installedPackages)

                installed to newAppMap
            }
            cachedApps = appMap

            if (!::items.isInitialized) {
                items = mutableListOf()
                withContext(Dispatchers.IO) { loadAppLayout(apps, appMap) }
            } else {
                // Update items list while maintaining existing items (folders and ordered apps)
                val currentPackages = mutableSetOf<String>()
                val newItems = mutableListOf<Any>()
                val hasActionItem = items.any { it is GlobalActionItem }

                // First, keep existing items that are still installed
                for (item in items) {
                    if (item is Folder) {
                        item.apps.removeAll { !appMap.containsKey(it) }
                        if (item.apps.isNotEmpty()) {
                            newItems.add(item)
                            currentPackages.addAll(item.apps)
                        }
                    } else if (item is ResolveInfo) {
                        val pkg = item.activityInfo.packageName
                        if (appMap.containsKey(pkg)) {
                            newItems.add(appMap[pkg]!!)
                            currentPackages.add(pkg)
                        }
                    } else if (item is GlobalActionItem) {
                        newItems.add(item)
                    }
                }
                if (!hasActionItem) {
                    newItems.add(0, GlobalActionItem)
                }

                // Then, add any new apps that weren't in the list
                val newApps = apps.filter { !currentPackages.contains(it.activityInfo.packageName) }
                if (newApps.isNotEmpty()) {
                    // Find first non-folder, non-action item position to insert new apps
                    var insertIndex = newItems.indexOfFirst { it is ResolveInfo }
                    if (insertIndex == -1) { // If no apps, add at the end
                        insertIndex = newItems.size
                    }

                    for (app in newApps) {
                        newItems.add(insertIndex, app)
                        insertIndex++
                    }
                }

                items.clear()
                items.addAll(newItems)
            }

            if (appsList.adapter == null) {
                appsList.adapter = AppsAdapter(this@MainActivity, items)
            } else {
                appsList.adapter?.notifyDataSetChanged()
            }

            // Background pre-warming (Icons & Labels) - DO NOT WAIT for this in the main refresh flow
            lifecycleScope.launch(Dispatchers.IO) {
                apps.forEach { app ->
                    val pkg = app.activityInfo.packageName
                    if (!iconCache.containsKey(pkg)) {
                        iconCache[pkg] = app.loadIcon(packageManager)
                    }
                    if (!labelCache.containsKey(pkg)) {
                        labelCache[pkg] = app.loadLabel(packageManager).toString()
                    }
                }
                withContext(Dispatchers.Main) {
                    appsList.adapter?.notifyDataSetChanged()
                    activeFolderAdapter?.notifyDataSetChanged()
                }
            }

            // Update active folder if it exists
            activeFolder?.let { folder ->
                val folderAppsResolved =
                    apps.filter { app -> folder.apps.contains(app.activityInfo.packageName) }
                activeFolderAdapter?.updateItems(ArrayList(folderAppsResolved))
                if (folder.apps.isEmpty()) {
                    activeFolderDialog?.dismiss()
                }
            }

            withContext(Dispatchers.IO) { saveAppOrder() }
        }
    }

    private fun loadAppLayout(allApps: List<ResolveInfo>, appMap: Map<String, ResolveInfo>) {
        val layoutString =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(LAYOUT_KEY, null)
        var actionItemLoaded = false

        if (layoutString == null) {
            items.add(GlobalActionItem)
            items.addAll(allApps)
            return
        }

        val seenPackages = mutableSetOf<String>()
        layoutString.split("|").forEach { entry ->
            when {
                entry.startsWith("F:") -> {
                    val parts = entry.substring(2).split(":")
                    if (parts.size >= 2) {
                        val folderName = parts[0]
                        val folderApps =
                            parts[1].split(",").filter { appMap.containsKey(it) }.toMutableList()
                        if (folderApps.isNotEmpty()) {
                            items.add(Folder(folderName, folderApps))
                            seenPackages.addAll(folderApps)
                        }
                    }
                }

                entry.startsWith("A:") -> {
                    val pkg = entry.substring(2)
                    if (appMap.containsKey(pkg)) {
                        items.add(appMap[pkg]!!)
                        seenPackages.add(pkg)
                    }
                }

                entry == ACTION_ITEM_KEY -> {
                    items.add(GlobalActionItem)
                    actionItemLoaded = true
                }
            }
        }

        // If the action item wasn't in the saved layout (e.g., old version), add it now.
        if (!actionItemLoaded) {
            items.add(0, GlobalActionItem)
        }

        allApps.forEach { if (!seenPackages.contains(it.activityInfo.packageName)) items.add(it) }
    }

    internal fun saveAppOrder() {
        val layoutString = items.joinToString("|") { item ->
            when (item) {
                is ResolveInfo -> "A:${item.activityInfo.packageName}"
                is Folder -> "F:${item.name}:${item.apps.joinToString(",")}"
                is GlobalActionItem -> ACTION_ITEM_KEY
                else -> ""
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(LAYOUT_KEY, layoutString) }
    }

    @SuppressLint("NotifyDataSetChanged", "ClickableViewAccessibility")
    internal fun showFolderDialog(folder: Folder) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_view, null)
        val folderTitle = dialogView.findViewById<TextView>(R.id.folder_title)
        val folderAppsList = dialogView.findViewById<RecyclerView>(R.id.apps_list)

        folderTitle.text = folder.name
        
        // Calculate span count for the folder dialog
        val density = resources.displayMetrics.density
        val screenWidthPx = resources.displayMetrics.widthPixels
        
        // Use 90% width (matching dialog.window.setLayout below)
        val dialogWidthPx = screenWidthPx * 0.9
        // Account for 16dp horizontal margins from the XML
        val usableWidthPx = dialogWidthPx - (32 * density)
        val usableWidthDp = usableWidthPx / density
        
        // Use smaller width for folder items for better visual hierarchy
        val itemWidthDp = resources.getDimension(R.dimen.folder_grid_item_width) / density
        val spanCount = (usableWidthDp / itemWidthDp).toInt().coerceAtLeast(3)

        folderAppsList.layoutManager = GridLayoutManager(this, spanCount, GridLayoutManager.VERTICAL, false)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val folderAppsResolved = folder.apps.mapNotNull { cachedApps[it] }.toMutableList()

        val adapter = AppsAdapter(this, ArrayList(folderAppsResolved), true)
        folderAppsList.adapter = adapter
        folderAppsList.setOnTouchListener(appTouchListener)
        folderAppsList.setOnDragListener(appDragListener) // Restore reordering

        // Shield the dialog content area so drops on title/padding do nothing
        dialogView.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_STARTED) {
                event.clipDescription.hasMimeType("vnd.android.cursor.item/app")
            } else true // Consume all other events, including ACTION_DROP
        }

        activeFolder = folder
        activeFolderAdapter = adapter
        activeFolderDialog = dialog

        dialog.setOnDismissListener {
            activeFolder = null
            activeFolderAdapter = null
            activeFolderDialog = null
            refreshApps()
        }

        dialog.show()
        
        // Fix width to prevent the dialog from expanding to fill the screen
        val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        
        // Handle drops on the dimmed background area (the window scrim)
        dialog.window?.decorView?.setOnDragListener(appDragListener)
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun emptyFolder(folder: Folder) {
        val folderIndex = items.indexOf(folder)
        if (folderIndex == -1) return

        val appsFromFolder = folder.apps.mapNotNull { cachedApps[it] }

        items.removeAt(folderIndex)
        items.addAll(folderIndex, appsFromFolder) // Insert apps at the folder's previous position

        appsList.adapter?.notifyDataSetChanged()

        if (activeFolder == folder) {
            activeFolderDialog?.dismiss()
        }

        saveAppOrder()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun emptyAllFolders() {
        val folders = items.filterIsInstance<Folder>().toList()
        if (folders.isEmpty()) {
            Toast.makeText(this, "No folders found to empty.", Toast.LENGTH_SHORT).show()
            return
        }

        val newItems = items.toMutableList()
        var appsAdded = 0
        for (folder in folders) {
            val folderIndex = newItems.indexOf(folder)
            if (folderIndex != -1) {
                val appsFromFolder = folder.apps.mapNotNull { cachedApps[it] }
                newItems.removeAt(folderIndex)
                newItems.addAll(folderIndex, appsFromFolder)
                appsAdded += appsFromFolder.size
            }
        }

        items.clear()
        items.addAll(newItems)

        appsList.adapter?.notifyDataSetChanged()

        // If an active folder was emptied as part of this, dismiss it
        activeFolder?.let { folder ->
            if (folder.apps.isEmpty() || !items.contains(folder)) {
                activeFolderDialog?.dismiss()
            }
        }

        saveAppOrder()

        Toast.makeText(this, "All folders have been emptied.", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun removeAppFromFolder(app: ResolveInfo) {
        val folder =
            items.find { it is Folder && it.apps.contains(app.activityInfo.packageName) } as? Folder
                ?: return
        val folderIndex = items.indexOf(folder)

        folder.apps.remove(app.activityInfo.packageName)
        
        // Surgical update for the main list instead of notifyDataSetChanged()
        val insertPos = if (folderIndex != -1) folderIndex + 1 else items.size
        items.add(insertPos, app)
        appsList.adapter?.notifyItemInserted(insertPos)

        if (folder.apps.isEmpty()) {
            val idx = items.indexOf(folder)
            if (idx != -1) {
                items.removeAt(idx)
                appsList.adapter?.notifyItemRemoved(idx)
            }
            if (activeFolder == folder) {
                activeFolderDialog?.dismiss()
            }
        } else if (activeFolder == folder) {
            val folderAppsResolved = folder.apps.mapNotNull { cachedApps[it] }
            activeFolderAdapter?.updateItems(ArrayList(folderAppsResolved))
            
            // Notify main list that folder icon changed
            if (folderIndex != -1) {
                appsList.adapter?.notifyItemChanged(folderIndex)
            }
        }

        saveAppOrder()
    }
}
