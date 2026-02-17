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
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

class MainActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    internal var popupMenu: PopupMenu? = null
    private lateinit var items: MutableList<Any>

    internal var isDragging = false
    private var startX = 0f
    private var startY = 0f
    var longPressedView: View? = null

    val appTouchListener = View.OnTouchListener { v, event ->        // This is a simple click-detection mechanism.
        // We need this to manually call performClick() for accessibility.
        val isClick = (event.action == MotionEvent.ACTION_UP &&
                !isDragging &&
                (event.eventTime - event.downTime) < android.view.ViewConfiguration.getTapTimeout())

        // If a click is detected, perform the click and stop processing this touch event.
        if (isClick) {
            v.performClick()
            // By returning here, we prevent the 'when' block from executing for a simple click.
            return@OnTouchListener true
        }

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
                        val position = appsList.getChildViewHolder(viewToDrag)?.bindingAdapterPosition
                        if (position != null) {
                            val clipDataItem = ClipData.Item(position.toString())
                            val mimeTypes = arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
                            val clipData = ClipData("drag-app", mimeTypes, clipDataItem)
                            val dragShadowBuilder = View.DragShadowBuilder(viewToDrag)

                            // Pass the local reference 'viewToDrag' as the local state.
                            // This object will not be nulled out during the drag operation.
                            appsList.startDragAndDrop(clipData, dragShadowBuilder, viewToDrag, 0)

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
        if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) {
            // Get the original view back from the localState
            val view = event.localState as? View
            // Post the visibility change to ensure it runs on the UI thread safely
            view?.post { view.visibility = View.VISIBLE }
            // Reset the dragging state
            isDragging = false
        }
        true // Indicate the event was handled
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshApps()
        }
    }

    private val settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // App settings screen closed, refresh the list
        refreshApps()
    }

    companion object {
        private const val PREFS_NAME = "AppOrder"
        private const val LAYOUT_KEY = "app_layout_v4" // Upgraded key for unified state
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        appsList = findViewById(R.id.apps_list)

        ViewCompat.setOnApplyWindowInsetsListener(appsList) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        appsList.layoutManager = GridLayoutManager(this, 4, GridLayoutManager.VERTICAL, true)

        refreshApps()

        val intentFilter = IntentFilter("com.tharos.allappsondeck.REFRESH_APPS")
        ContextCompat.registerReceiver(this, refreshReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        appsList.setOnTouchListener(appTouchListener)
        appsList.setOnDragListener(appDragListener)
    }

    fun showAppDetails(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:$packageName".toUri()
        settingsResultLauncher.launch(intent)
    }

    private fun getAppCategory(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (appInfo.category) {
                    ApplicationInfo.CATEGORY_GAME -> "Games"
                    ApplicationInfo.CATEGORY_AUDIO -> "Audio"
                    ApplicationInfo.CATEGORY_VIDEO -> "Video"
                    ApplicationInfo.CATEGORY_IMAGE -> "Images"
                    ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                    ApplicationInfo.CATEGORY_NEWS -> "News"
                    ApplicationInfo.CATEGORY_MAPS -> "Maps"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                    else -> null
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun getFolderNameForApps(packageNames: List<String>): String {
        val categories = packageNames.mapNotNull { getAppCategory(it) }

        if (categories.isEmpty()) return "New Folder"

        // If all apps share a category, use it
        val firstCategory = categories.first()
        if (categories.all { it == firstCategory }) {
            return firstCategory
        }

        // Otherwise, find the most frequent category
        return categories.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "New Folder"
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun autoSortApps() {
        val apps = getInstalledLauncherApps()

        val categoryGroups = apps.groupBy { app ->
            getAppCategory(app.activityInfo.packageName) ?: "Misc"
        }

        val newItems = mutableListOf<Any>()

        categoryGroups.forEach { (category, groupApps) ->
            if (category != "Misc" && groupApps.size > 1) {
                val packageNames = groupApps.map { it.activityInfo.packageName }.toMutableList()
                newItems.add(Folder(category, packageNames))
            } else {
                newItems.addAll(groupApps)
            }
        }

        items.clear()
        items.addAll(newItems)
        appsList.adapter?.notifyDataSetChanged()
        saveAppOrder()
        Toast.makeText(this, "Apps sorted into folders", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }

    private fun getInstalledLauncherApps(): List<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(mainIntent, 0).filter { it.activityInfo.packageName != packageName }
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

            // First, keep existing folders and apps that are still installed
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
                }
            }

            // Then, add any new apps that weren't in the list
            val newApps = apps.filter { !currentPackages.contains(it.activityInfo.packageName) }
            if (newApps.isNotEmpty()) {
                for (app in newApps) {
                    val category = getAppCategory(app.activityInfo.packageName)
                    var addedToFolder = false
                    if (category != null) {
                        val targetFolder =
                            newItems.find { it is Folder && it.name.equals(category, ignoreCase = true) } as? Folder
                        if (targetFolder != null) {
                            targetFolder.apps.add(app.activityInfo.packageName)
                            addedToFolder = true
                        }
                    }

                    if (!addedToFolder) {
                        newItems.add(app)
                    }
                }
                saveAppOrder()
            }


            items.clear()
            items.addAll(newItems)
        }

        if (appsList.adapter == null) {
            appsList.adapter = AppsAdapter(this, items)
        } else {
            appsList.adapter?.notifyDataSetChanged()
        }
    }

    private fun loadAppLayout(allApps: List<ResolveInfo>, appMap: Map<String, ResolveInfo>) {
        val layoutString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(LAYOUT_KEY, null)
        if (layoutString == null) {
            items.addAll(allApps)
            return
        }

        val seenPackages = mutableSetOf<String>()
        layoutString.split("|").forEach { entry ->
            if (entry.startsWith("F:")) {
                val parts = entry.substring(2).split(":")
                if (parts.size == 2) {
                    val folderName = parts[0]
                    val folderApps = parts[1].split(",").filter { appMap.containsKey(it) }.toMutableList()
                    if (folderApps.isNotEmpty()) {
                        items.add(Folder(folderName, folderApps))
                        seenPackages.addAll(folderApps)
                    }
                }
            } else if (entry.startsWith("A:")) {
                val pkg = entry.substring(2)
                if (appMap.containsKey(pkg)) {
                    items.add(appMap[pkg]!!)
                    seenPackages.add(pkg)
                }
            }
        }

        allApps.forEach { if (!seenPackages.contains(it.activityInfo.packageName)) items.add(it) }
    }

    internal fun saveAppOrder() {
        val layoutString = items.joinToString("|") { item ->
            when (item) {
                is ResolveInfo -> "A:${item.activityInfo.packageName}"
                is Folder -> "F:${item.name}:${item.apps.joinToString(",")}"
                else -> ""
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(LAYOUT_KEY, layoutString) }
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun showFolderDialog(folder: Folder) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_view, null)
        val folderTitle = dialogView.findViewById<TextView>(R.id.folder_title)
        val folderAppsList = dialogView.findViewById<RecyclerView>(R.id.apps_list)

        folderTitle.text = folder.name
        folderAppsList.layoutManager = GridLayoutManager(this, 4)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)
        val folderApps = allApps.filter { app -> folder.apps.contains(app.activityInfo.packageName) }.toMutableList()

        class FolderAppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val appName: TextView = itemView.findViewById(R.id.app_name)
            val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        }

        val adapter = object : RecyclerView.Adapter<FolderAppViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderAppViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.folder_app_item, parent, false)
                return FolderAppViewHolder(view)
            }

            override fun onBindViewHolder(holder: FolderAppViewHolder, position: Int) {
                val app = folderApps[position]

                holder.appName.text = app.loadLabel(packageManager)
                holder.appIcon.setImageDrawable(app.loadIcon(packageManager))

                holder.itemView.setOnClickListener {
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
                    startActivity(launchIntent)
                    dialog.dismiss()
                }

                holder.itemView.setOnLongClickListener {
                    val popup = PopupMenu(this@MainActivity, it)
                    popup.menu.add("Remove from Folder")
                    popup.menu.add("More Info")
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.title) {
                            "Remove from Folder" -> {
                                val currentPosition = holder.bindingAdapterPosition
                                if (currentPosition != RecyclerView.NO_POSITION) {
                                    val removedAppInfo = folderApps.removeAt(currentPosition)
                                    folder.apps.remove(removedAppInfo.activityInfo.packageName)
                                    notifyItemRemoved(currentPosition)
                                    notifyItemRangeChanged(currentPosition, folderApps.size)

                                    // Also add it back to the main list
                                    items.add(removedAppInfo)
                                    appsList.adapter?.notifyItemInserted(items.size - 1)
                                }

                                saveAppOrder()

                                if (folder.apps.isEmpty()) {
                                    // Remove the folder itself if it's now empty
                                    val folderIndex = items.indexOf(folder)
                                    if (folderIndex != -1) {
                                        items.removeAt(folderIndex)
                                        appsList.adapter?.notifyItemRemoved(folderIndex)
                                    }
                                    dialog.dismiss()
                                } else {
                                    // Refresh the folder icon in the main grid
                                    val folderIndex = items.indexOf(folder)
                                    if (folderIndex != -1) {
                                        appsList.adapter?.notifyItemChanged(folderIndex)
                                    }
                                }
                                true
                            }
                            "More Info" -> {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = "package:${app.activityInfo.packageName}".toUri()
                                settingsResultLauncher.launch(intent)
                                dialog.dismiss()
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                    true
                }
            }

            override fun getItemCount(): Int = folderApps.size
        }

        folderAppsList.adapter = adapter

        dialog.show()
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
        items.addAll(appsFromFolder)

        appsList.adapter?.notifyDataSetChanged()

        saveAppOrder()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun emptyAllFolders() {
        val folders = items.filterIsInstance<Folder>()
        if (folders.isEmpty()) {
            Toast.makeText(this, "No folders found to empty.", Toast.LENGTH_SHORT).show()
            return
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)
        val appMap = allApps.associateBy { it.activityInfo.packageName }

        val appsFromFolders = mutableListOf<ResolveInfo>()
        folders.forEach { folder ->
            appsFromFolders.addAll(folder.apps.mapNotNull { appMap[it] })
        }

        items.removeAll(folders)
        items.addAll(appsFromFolders)

        appsList.adapter?.notifyDataSetChanged()
        saveAppOrder()

        Toast.makeText(this, "All folders have been emptied.", Toast.LENGTH_SHORT).show()
    }
}