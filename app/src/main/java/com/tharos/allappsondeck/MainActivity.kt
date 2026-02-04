package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    private lateinit var itemTouchHelper: ItemTouchHelper
    internal var popupMenu: PopupMenu? = null
    private var isActuallyMoving = false
    private lateinit var items: MutableList<Any>

    // Variable to track which item we are dragging over for folder creation/addition
    private var dropTargetViewHolder: RecyclerView.ViewHolder? = null

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

        val callback = object : ItemTouchHelper.Callback() {
            // This method is called as an item is dragged over others
            override fun chooseDropTarget(
                selected: RecyclerView.ViewHolder,
                targets: MutableList<RecyclerView.ViewHolder>,
                curX: Int,
                curY: Int
            ): RecyclerView.ViewHolder? {
                // Find the first target that isn't the item being dragged
                val target = super.chooseDropTarget(selected, targets, curX, curY)
                if (target != null && target.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val fromPos = selected.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition

                    val draggedItem = items[fromPos]
                    val targetItem = items[toPos]

                    // Allow dropping an app onto another app (to create a folder) or onto an existing folder
                    dropTargetViewHolder = if (draggedItem is ResolveInfo && (targetItem is ResolveInfo || targetItem is Folder)) {
                        target
                    } else {
                        null
                    }
                } else {
                    dropTargetViewHolder = null
                }
                return target
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                val swipeFlags = 0
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!isActuallyMoving) {
                    isActuallyMoving = true
                    popupMenu?.dismiss()
                }
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                val movedItem = items.removeAt(fromPosition)
                items.add(toPosition, movedItem)
                appsList.adapter?.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    popupMenu?.dismiss()
                    isActuallyMoving = false
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)

                // Check if we dropped an item onto another valid target
                if (dropTargetViewHolder != null) {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = dropTargetViewHolder!!.bindingAdapterPosition

                    if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                        val draggedItem = items[fromPosition]
                        val targetItem = items[toPosition]

                        if (draggedItem is ResolveInfo) {
                            if (targetItem is ResolveInfo) {
                                // Create a new folder
                                val folderApps = mutableListOf(targetItem.activityInfo.packageName, draggedItem.activityInfo.packageName)
                                val suggestedName = getFolderNameForApps(folderApps)
                                val newFolder = Folder(suggestedName, folderApps)

                                // Replace the target item with the new folder
                                items[toPosition] = newFolder
                                // Remove the dragged item from its original position
                                items.removeAt(fromPosition)

                                appsList.adapter?.notifyItemChanged(toPosition)
                                appsList.adapter?.notifyItemRemoved(fromPosition)
                            } else if (targetItem is Folder) {
                                // Add app to existing folder
                                targetItem.apps.add(draggedItem.activityInfo.packageName)
                                // Remove the dragged app from the main list
                                items.removeAt(fromPosition)

                                appsList.adapter?.notifyItemRemoved(fromPosition)
                                // Optionally notify that folder was updated (though icon might not change)
                                val finalFolderPosition = if (fromPosition < toPosition) toPosition - 1 else toPosition
                                appsList.adapter?.notifyItemChanged(finalFolderPosition)
                            }
                        }
                    }
                }

                // Reset state
                dropTargetViewHolder = null
                if (isActuallyMoving) {
                    saveAppOrder()
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(appsList)

        refreshApps()

        val intentFilter = IntentFilter("com.tharos.allappsondeck.REFRESH_APPS")
        ContextCompat.registerReceiver(this, refreshReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
            appsList.adapter = AppsAdapter(this, items, itemTouchHelper)
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
}
