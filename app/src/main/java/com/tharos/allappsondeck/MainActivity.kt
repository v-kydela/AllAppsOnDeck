package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
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

    private var activeFolder: Folder? = null
    private var activeFolderAdapter: AppsAdapter? = null
    private var activeFolderDialog: AlertDialog? = null

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

    private val appDragListener = View.OnDragListener { v, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                // Accept drags for apps, folders, and actions
                val mimeTypes = event.clipDescription
                mimeTypes.hasMimeType("vnd.android.cursor.item/app") || 
                mimeTypes.hasMimeType("vnd.android.cursor.item/folder") ||
                mimeTypes.hasMimeType("vnd.android.cursor.item/action")
            }
            DragEvent.ACTION_DROP -> {
                val dragView = event.localState as? View ?: return@OnDragListener false
                val sourceRecyclerView = dragView.parent as? RecyclerView ?: return@OnDragListener false
                val sourceAdapter = sourceRecyclerView.adapter as? AppsAdapter

                if (sourceAdapter?.isFolderAdapter == true && v != sourceRecyclerView) {
                    // Dragged from folder and dropped OUTSIDE the folder's internal list
                    // Because we "shielded" the dialog content, this only happens on the background scrim.
                    val pos = sourceRecyclerView.getChildViewHolder(dragView).bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = sourceAdapter.items[pos]
                        if (item is ResolveInfo) {
                            removeAppFromFolder(item)
                            return@OnDragListener true
                        }
                    }
                }
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
        private const val LAYOUT_KEY = "app_layout_v5" // Upgraded key to include global action item
        private const val ACTION_ITEM_KEY = "G:ACTION"
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

    private fun getAppCategories(packageName: String): List<String> {
        val categories = mutableListOf<String>()
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = appInfo.loadLabel(packageManager).toString().lowercase()

            // 1. Built-in Category
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val builtin = when (appInfo.category) {
                    ApplicationInfo.CATEGORY_GAME -> "Games"
                    ApplicationInfo.CATEGORY_AUDIO -> "Media"
                    ApplicationInfo.CATEGORY_VIDEO -> "Media"
                    ApplicationInfo.CATEGORY_IMAGE -> "Media"
                    ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                    ApplicationInfo.CATEGORY_NEWS -> "News"
                    ApplicationInfo.CATEGORY_MAPS -> "Navigation"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                    ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility"
                    else -> null
                }
                builtin?.let { categories.add(it) }
            }

            // 2. Intent-Based Categorization
            if (isAppForIntent(packageName, Intent(Intent.ACTION_VIEW, "mailto:".toUri()))) categories.add("Communication")
            if (isAppForIntent(packageName, Intent(Intent.ACTION_VIEW, "tel:".toUri()))) categories.add("Communication")
            if (isAppForIntent(packageName, Intent(Intent.ACTION_VIEW, "http://google.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE))) categories.add("Browsers")

            // 3. Keyword-Based Heuristics
            if (label.containsAny("bank", "pay", "wallet", "finance", "credit", "crypto", "invest", "stock")) categories.add("Finance")
            if (label.containsAny("flight", "airline", "hotel", "booking", "travel", "expedia", "airbnb", "trip")) categories.add("Travel")
            if (label.containsAny("taxi", "ride", "uber", "lyft", "grab", "transit", "train", "bus", "metro")) categories.add("Transit")
            if (label.containsAny("shop", "store", "market", "amazon", "ebay", "walmart", "target", "shopping", "cart")) categories.add("Shopping")
            if (label.containsAny("chat", "msg", "messenger", "whatsapp", "signal", "telegram", "discord", "slack")) categories.add("Communication")
            if (label.containsAny("mail", "outlook", "gmail", "inbox")) categories.add("Communication")
            if (label.containsAny("office", "doc", "sheet", "slide", "pdf", "note", "keep", "word", "excel", "ppt")) categories.add("Productivity")
            if (label.containsAny("photo", "gallery", "camera", "editor", "video", "player", "music", "stream")) categories.add("Media")

            // 4. Publisher Check
            if (packageName.startsWith("com.google.android") || packageName.startsWith("com.google.android.apps")) categories.add("Google")
            if (packageName.startsWith("com.microsoft.")) categories.add("Microsoft")
            if (packageName.startsWith("com.sec.android") || packageName.startsWith("com.samsung.")) categories.add("Samsung")

        } catch (_: Exception) {
            // App might have been uninstalled
        }
        return categories.distinct()
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    private fun isAppForIntent(packageName: String, intent: Intent): Boolean {
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.any { it.activityInfo.packageName == packageName }
    }

    internal fun getFolderNameForApps(packageNames: List<String>): String {
        val categoryCounts = mutableMapOf<String, Int>()
        packageNames.forEach { pkg ->
            getAppCategories(pkg).forEach { cat ->
                categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
            }
        }

        if (categoryCounts.isEmpty()) return "New Folder"

        // Find the category that appears in the most apps in this set
        return categoryCounts.maxByOrNull { it.value }?.key ?: "New Folder"
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun autoOrganizeApps() {
        val actionItem = items.find { it is GlobalActionItem }
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

        // 4. Initial Assignment: Least flexible apps (fewer category options) first
        val sortedApps = apps.sortedBy { app -> appToCategories[app]?.count { it in validCategories } ?: 0 }
        val assignments = mutableMapOf<String, MutableList<ResolveInfo>>()
        val unassignedApps = mutableListOf<ResolveInfo>()

        for (app in sortedApps) {
            val appCats = appToCategories[app]?.filter { it in validCategories } ?: emptyList()
            if (appCats.isEmpty()) {
                unassignedApps.add(app)
            } else {
                // Pick the category with the current SMALLEST assignment count for evenness
                val bestCat = appCats.minByOrNull { assignments[it]?.size ?: 0 }!!
                assignments.getOrPut(bestCat) { mutableListOf() }.add(app)
            }
        }

        // 5. Global Balancing: Iteratively swap apps from the largest folders to the smallest ones
        var changed: Boolean
        var safetyCounter = 0
        val maxIterations = apps.size * 2 // Mathematical guarantee of convergence, but adding safety valve
        
        do {
            changed = false
            safetyCounter++
            
            // Find folders sorted by size (descending)
            val fromCats = assignments.keys.sortedByDescending { assignments[it]?.size ?: 0 }
            for (fromCat in fromCats) {
                val fromApps = assignments[fromCat] ?: continue
                if (fromApps.size <= 2) continue // Don't shrink already small folders

                // Find folders sorted by size (ascending)
                val toCats = validCategories.sortedBy { assignments[it]?.size ?: 0 }
                for (toCat in toCats) {
                    if (fromCat == toCat) continue
                    val toAppsSize = assignments[toCat]?.size ?: 0
                    
                    // Convergence condition: Move only if it makes the distribution strictly more even
                    if (fromApps.size > toAppsSize + 1) {
                        val movableApp = fromApps.find { app -> appToCategories[app]?.contains(toCat) == true }
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

        // 6. Build new items list
        val newItems = mutableListOf<Any>()
        if (actionItem != null) {
            newItems.add(actionItem)
        }

        // Add folders that ended up with > 1 app
        assignments.keys.sorted().forEach { category ->
            val groupApps = assignments[category] ?: return@forEach
            if (groupApps.size > 1) {
                val packageNames = groupApps.map { it.activityInfo.packageName }.toMutableList()
                newItems.add(Folder(category, packageNames))
            } else {
                unassignedApps.addAll(groupApps)
            }
        }

        // Add single apps
        newItems.addAll(unassignedApps.sortedBy { it.loadLabel(packageManager).toString().lowercase() })

        items.clear()
        items.addAll(newItems)
        appsList.adapter?.notifyDataSetChanged()
        saveAppOrder()
        Toast.makeText(this, "Apps organized into folders", Toast.LENGTH_SHORT).show()
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
        val apps = getInstalledLauncherApps()
        val appMap = apps.associateBy { it.activityInfo.packageName }

        if (!::items.isInitialized) {
            items = mutableListOf()
            loadAppLayout(apps, appMap)
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
                    val appCategories = getAppCategories(app.activityInfo.packageName)
                    var addedToFolder = false

                    if (appCategories.isNotEmpty()) {
                        // Find all existing folders that match any of the app's categories
                        val candidateFolders = newItems.filterIsInstance<Folder>().filter { folder ->
                            appCategories.any { it.equals(folder.name, ignoreCase = true) }
                        }

                        if (candidateFolders.isNotEmpty()) {
                            // Pick the smallest folder to keep them even
                            val targetFolder = candidateFolders.minByOrNull { it.apps.size }
                            targetFolder?.apps?.add(app.activityInfo.packageName)
                            addedToFolder = true
                        }
                    }

                    if (!addedToFolder) {
                        newItems.add(insertIndex, app)
                        insertIndex++
                    }
                }
            }

            items.clear()
            items.addAll(newItems)
        }

        if (appsList.adapter == null) {
            appsList.adapter = AppsAdapter(this, items)
        } else {
            appsList.adapter?.notifyDataSetChanged()
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

        saveAppOrder()
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

        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)
        val folderAppsResolved =
            allApps.filter { app -> folder.apps.contains(app.activityInfo.packageName) }
                .toMutableList()

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

        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)
        val appMap = allApps.associateBy { it.activityInfo.packageName }

        val appsFromFolder = folder.apps.mapNotNull { appMap[it] }

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

        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)
        val appMap = allApps.associateBy { it.activityInfo.packageName }

        val newItems = items.toMutableList()
        var appsAdded = 0
        for (folder in folders) {
            val folderIndex = newItems.indexOf(folder)
            if (folderIndex != -1) {
                val appsFromFolder = folder.apps.mapNotNull { appMap[it] }
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
            val apps = getInstalledLauncherApps()
            val folderAppsResolved =
                apps.filter { folder.apps.contains(it.activityInfo.packageName) }
            activeFolderAdapter?.updateItems(ArrayList(folderAppsResolved))
            
            // Notify main list that folder icon changed
            if (folderIndex != -1) {
                appsList.adapter?.notifyItemChanged(folderIndex)
            }
        }

        saveAppOrder()
    }
}
