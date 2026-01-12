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
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var popupMenu: PopupMenu? = null
    private var isActuallyMoving = false
    private lateinit var items: MutableList<Any>

    // Variable to track which item we are dragging over for folder creation/addition
    private var dropTargetViewHolder: RecyclerView.ViewHolder? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshApps()
        }
    }

    private val folderResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val updatedFolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra("updated_folder", Folder::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra("updated_folder")
            }
            if (updatedFolder != null) {
                // Find and update the folder in our list
                val index = items.indexOfFirst { it is Folder && it.name == updatedFolder.name }
                if (index != -1) {
                    if (updatedFolder.apps.isEmpty()) {
                        items.removeAt(index)
                    } else {
                        items[index] = updatedFolder
                    }
                    // Refresh will re-add any apps removed from folder to the main list
                    refreshApps()
                    saveAppOrder()
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "AppOrder"
        private const val LAYOUT_KEY = "app_layout_v4" // Upgraded key for unified state
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appsList = findViewById(R.id.apps_list)
        appsList.layoutManager = GridLayoutManager(this, 4, GridLayoutManager.VERTICAL, true)

        refreshApps()

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
                                appsList.adapter?.notifyItemChanged(toPosition)
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

        val intentFilter = IntentFilter("com.tharos.allappsondeck.REFRESH_APPS")
        ContextCompat.registerReceiver(this, refreshReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun getFolderNameForApps(packageNames: List<String>): String {
        val categories = packageNames.mapNotNull { pkg ->
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
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
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

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
    private fun autoSortApps() {
        val apps = getInstalledLauncherApps()
        
        val categoryGroups = apps.groupBy { app ->
            try {
                val appInfo = packageManager.getApplicationInfo(app.activityInfo.packageName, 0)
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
                        else -> "Misc"
                    }
                } else "Misc"
            } catch (_: Exception) { "Misc" }
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
            for (app in apps) {
                if (!currentPackages.contains(app.activityInfo.packageName)) {
                    newItems.add(app)
                }
            }
            
            items.clear()
            items.addAll(newItems)
        }
        
        if (appsList.adapter == null) {
            appsList.adapter = AppsAdapter(items)
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

    private fun saveAppOrder() {
        val layoutString = items.joinToString("|") { item ->
            when (item) {
                is ResolveInfo -> "A:${item.activityInfo.packageName}"
                is Folder -> "F:${item.name}:${item.apps.joinToString(",")}"
                else -> ""
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(LAYOUT_KEY, layoutString) }
    }

    inner class AppsAdapter(private val items: MutableList<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ResolveInfo -> TYPE_APP
                is Folder -> TYPE_FOLDER
                else -> throw IllegalArgumentException("Invalid type of item at position $position")
            }
        }

        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
            val appName: TextView = itemView.findViewById(R.id.app_name)

            init {
                (itemView as TextView).compoundDrawablePadding = 16
                itemView.setOnClickListener(this)
                itemView.setOnLongClickListener(this)
            }

            override fun onClick(v: View?) {
                if (popupMenu != null) {
                    popupMenu?.dismiss()
                    return
                }

                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is ResolveInfo) {
                        val packageName = item.activityInfo.packageName
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            Toast.makeText(this@MainActivity, "App not found", Toast.LENGTH_SHORT).show()
                            refreshApps()
                        }
                    }
                }
            }

            override fun onLongClick(v: View): Boolean {
                popupMenu?.dismiss()
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return false

                val item = items[pos] as? ResolveInfo ?: return false

                itemTouchHelper.startDrag(this)

                popupMenu = PopupMenu(v.context, v)
                popupMenu?.setOnDismissListener { popupMenu = null }
                popupMenu?.menu?.add("Auto Sort")
                popupMenu?.menu?.add("Create Folder")
                popupMenu?.menu?.add("More Info")
                popupMenu?.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        "Auto Sort" -> {
                            autoSortApps()
                            true
                        }
                        "Create Folder" -> {
                            val suggestedName = getFolderNameForApps(listOf(item.activityInfo.packageName))
                            val editText = EditText(this@MainActivity)
                            editText.setText(suggestedName)
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("New Folder")
                                .setView(editText)
                                .setPositiveButton("Create") { _, _ ->
                                    val name = editText.text.toString().ifEmpty { suggestedName }
                                    items[pos] = Folder(name, mutableListOf(item.activityInfo.packageName))
                                    notifyItemChanged(pos)
                                    saveAppOrder()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                        "More Info" -> {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = "package:${item.activityInfo.packageName}".toUri()
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            true
                        }
                        else -> false
                    }
                }
                popupMenu?.show()
                return true
            }
        }

        inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
            val folderName: TextView = itemView.findViewById(R.id.folder_name)
            val folderIcon: FolderIconView = itemView.findViewById(R.id.folder_icon)

            init {
                itemView.setOnClickListener(this)
                itemView.setOnLongClickListener(this)
            }

            override fun onClick(v: View?) {
                if (popupMenu != null) {
                    popupMenu?.dismiss()
                    return
                }

                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is Folder) {
                        val intent = Intent(this@MainActivity, FolderActivity::class.java)
                        intent.putExtra("folder", item)
                        folderResultLauncher.launch(intent)
                    }
                }
            }

            override fun onLongClick(v: View): Boolean {
                popupMenu?.dismiss()
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return false

                val item = items[pos] as? Folder ?: return false

                itemTouchHelper.startDrag(this)

                popupMenu = PopupMenu(v.context, v)
                popupMenu?.setOnDismissListener { popupMenu = null }
                popupMenu?.menu?.add("Rename")
                popupMenu?.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        "Rename" -> {
                            val editText = EditText(this@MainActivity)
                            editText.setText(item.name)
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Rename Folder")
                                .setView(editText)
                                .setPositiveButton("Save") { _, _ ->
                                    item.name = editText.text.toString()
                                    notifyItemChanged(pos)
                                    saveAppOrder()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                        else -> false
                    }
                }
                popupMenu?.show()
                return true
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_APP -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.app_item, parent, false)
                    AppViewHolder(view)
                }
                TYPE_FOLDER -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.folder_item, parent, false)
                    FolderViewHolder(view)
                }
                else -> throw IllegalArgumentException("Invalid view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is AppViewHolder -> {
                    val app = items[position] as ResolveInfo
                    holder.appName.text = app.loadLabel(packageManager)
                    val icon = app.loadIcon(packageManager)
                    val iconSize = (48 * holder.itemView.context.resources.displayMetrics.density).toInt()
                    icon.setBounds(0, 0, iconSize, iconSize)
                    holder.appName.setCompoundDrawables(null, icon, null, null)
                }
                is FolderViewHolder -> {
                    val folder = items[position] as Folder
                    holder.folderName.text = folder.name
                    
                    // Fetch icons for the apps in the folder
                    val folderIcons = folder.apps.take(4).mapNotNull { packageName ->
                        try {
                            packageManager.getApplicationIcon(packageName)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    holder.folderIcon.setIcons(folderIcons)
                }
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }
    }
}
