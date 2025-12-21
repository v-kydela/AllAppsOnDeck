package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
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

    // Variable to track which item we are dragging over for folder creation
    private var dropTargetViewHolder: RecyclerView.ViewHolder? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshApps()
        }
    }

    companion object {
        private const val PREFS_NAME = "AppOrder"
        private const val ORDER_KEY = "order"
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appsList = findViewById(R.id.apps_list)
        appsList.layoutManager = GridLayoutManager(this, 4)

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
                    // Only allow dropping an app onto another app to create a folder
                    dropTargetViewHolder = if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION && items[fromPos] is ResolveInfo && items[toPos] is ResolveInfo) {
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

                        // If an app is dropped on another app, create a folder
                        if (draggedItem is ResolveInfo && targetItem is ResolveInfo) {
                            // Create a new folder
                            val folderApps = mutableListOf(targetItem.activityInfo.packageName, draggedItem.activityInfo.packageName)
                            val newFolder = Folder("New Folder", folderApps)

                            // Replace the target item with the new folder
                            items[toPosition] = newFolder
                            // Remove the dragged item from its original position (before the add)
                            items.removeAt(fromPosition)

                            // Notify adapter of changes
                            appsList.adapter?.notifyItemChanged(toPosition)
                            appsList.adapter?.notifyItemRemoved(fromPosition)
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshApps() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val appMap = apps.associateBy { it.activityInfo.packageName }

        // If items is not initialized, initialize it with all apps
        if (!::items.isInitialized) {
            items = apps.toMutableList()
        } else {
            // Update items list while maintaining existing items (folders and ordered apps)
            val currentPackages = mutableSetOf<String>()
            val newItems = mutableListOf<Any>()
            
            // First, keep existing folders and apps that are still installed
            for (item in items) {
                if (item is Folder) {
                    newItems.add(item)
                } else if (item is ResolveInfo) {
                    val packageName = item.activityInfo.packageName
                    val freshApp = appMap[packageName]
                    if (freshApp != null) {
                        newItems.add(freshApp)
                        currentPackages.add(packageName)
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

    private fun saveAppOrder() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            val order = items.joinToString(",") { item ->
                when (item) {
                    is ResolveInfo -> item.activityInfo.packageName
                    is Folder -> item.name
                    else -> ""
                }
            }
            putString(ORDER_KEY, order)
        }
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
            val appIcon: ImageView = itemView.findViewById(R.id.app_icon)

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
                popupMenu?.menu?.add("More Info")
                popupMenu?.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
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
                        startActivity(intent)
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
                else -> throw IllegalArgumentException("Invalid view type")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is AppViewHolder -> {
                    val app = items[position] as ResolveInfo
                    holder.appName.text = app.loadLabel(packageManager)
                    holder.appIcon.setImageDrawable(app.loadIcon(packageManager))
                }
                is FolderViewHolder -> {
                    val folder = items[position] as Folder
                    holder.folderName.text = folder.name
                }
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }
    }
}
