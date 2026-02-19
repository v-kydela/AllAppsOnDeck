package com.tharos.allappsondeck

import android.app.AlertDialog
import android.content.pm.ResolveInfo
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
    private val mainActivity: MainActivity,
    private val items: MutableList<Any>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
        private const val MENU_AUTO_SORT = "Auto Sort"
        private const val MENU_CREATE_FOLDER = "Create Folder"
        private const val MENU_EMPTY_ALL_FOLDERS = "Empty All Folders"
        private const val MENU_MORE_INFO = "More Info"
        private const val MENU_RENAME = "Rename"
        private const val MENU_EMPTY_FOLDER = "Empty Folder"
    }

    abstract inner class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener, View.OnDragListener {
        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
            itemView.setOnDragListener(this)
        }

        override fun onClick(v: View?) {
            if (mainActivity.popupMenu != null) {
                mainActivity.popupMenu?.dismiss()
                return
            }
            handleItemClick()
        }

        abstract fun handleItemClick()

        override fun onLongClick(v: View): Boolean {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return false

            mainActivity.longPressedView = v

            mainActivity.popupMenu?.dismiss()
            mainActivity.popupMenu = PopupMenu(v.context, v)
            mainActivity.popupMenu?.setOnDismissListener {
                mainActivity.popupMenu = null
                if (!mainActivity.isDragging) {
                    mainActivity.longPressedView = null
                }
            }

            createPopupMenu()

            mainActivity.popupMenu?.show()
            return true
        }

        abstract fun createPopupMenu()

        override fun onDrag(v: View, event: DragEvent): Boolean {
            val toPosition = bindingAdapterPosition
            if (toPosition == RecyclerView.NO_POSITION) return false

            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    mainActivity.popupMenu?.dismiss()
                    mainActivity.isDragging = true // Set dragging flag
                    return true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    // Determine highlight based on drop location within the view
                    val dropX = event.x
                    val viewWidth = v.width
                    val oneThird = viewWidth / 3

                    // Highlight only if dropping in the middle (folder zone)
                    if (dropX > oneThird && dropX < viewWidth - oneThird) {
                        v.alpha = 0.5f
                    }
                    return true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    // Continuously update highlight based on location
                    v.alpha = 1f // Reset first
                    val dropX = event.x
                    val viewWidth = v.width
                    val oneThird = viewWidth / 3
                    if (dropX > oneThird && dropX < viewWidth - oneThird) {
                        v.alpha = 0.5f
                    }
                    return true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    v.alpha = 1f
                    return true
                }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    // Get fromPosition ONLY on drop. This is the crucial fix.
                    val fromPosition = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull() ?: return false

                    if (toPosition == fromPosition) return true

                    val dropX = event.x
                    val viewWidth = v.width
                    val oneThird = viewWidth / 3

                    when {
                        // Drop on left third
                        dropX <= oneThird -> {
                            // Reorder BEFORE
                            val movedItem = items.removeAt(fromPosition)
                            val finalPosition = if (fromPosition < toPosition) toPosition - 1 else toPosition
                            if (finalPosition <= items.size) {
                                items.add(finalPosition, movedItem)
                                notifyItemMoved(fromPosition, finalPosition)
                            }
                            mainActivity.saveAppOrder()
                        }
                        // Drop on right third
                        dropX >= viewWidth - oneThird -> {
                            // Reorder AFTER
                            val movedItem = items.removeAt(fromPosition)
                            val targetPos = toPosition + 1
                            val finalPosition = if (fromPosition < targetPos) targetPos - 1 else targetPos
                            if (finalPosition <= items.size) {
                                items.add(finalPosition, movedItem)
                                notifyItemMoved(fromPosition, finalPosition)
                            }
                            mainActivity.saveAppOrder()
                        }
                        // Drop in the middle
                        else -> {
                            handleSpecificDrop(fromPosition, toPosition)
                        }
                    }
                    return true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1f
                    mainActivity.isDragging = false // Unset dragging flag
                    mainActivity.longPressedView = null // Clear reference
                    // Also ensure any lingering alpha from a canceled drag is reset
                    (v.parent as? RecyclerView)
                        ?.findViewHolderForAdapterPosition(toPosition)?.itemView?.alpha = 1f
                    return true
                }
                else -> return false
            }
        }

        abstract fun handleSpecificDrop(fromPosition: Int, toPosition: Int): Boolean
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ResolveInfo -> TYPE_APP
            is Folder -> TYPE_FOLDER
            else -> throw IllegalArgumentException("Invalid type of item at position $position")
        }
    }

    inner class AppViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val appName: TextView = itemView.findViewById(R.id.app_name)

        init {
            appName.compoundDrawablePadding = 16
        }

        override fun handleItemClick() {
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = items[pos]
                if (item is ResolveInfo) {
                    val packageName = item.activityInfo.packageName
                    val launchIntent = mainActivity.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        mainActivity.startActivity(launchIntent)
                    } else {
                        Toast.makeText(mainActivity, "App not found", Toast.LENGTH_SHORT).show()
                        mainActivity.refreshApps()
                    }
                }
            }
        }

        override fun createPopupMenu() {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return

            val item = items[pos]

            if (item is ResolveInfo) {
                mainActivity.popupMenu?.menu?.add(MENU_AUTO_SORT)
                mainActivity.popupMenu?.menu?.add(MENU_CREATE_FOLDER)
                mainActivity.popupMenu?.menu?.add(MENU_EMPTY_ALL_FOLDERS)
                mainActivity.popupMenu?.menu?.add(MENU_MORE_INFO)
                mainActivity.popupMenu?.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        MENU_AUTO_SORT -> {
                            mainActivity.autoSortApps()
                            true
                        }

                        MENU_CREATE_FOLDER -> {
                            val suggestedName = mainActivity.getFolderNameForApps(listOf(item.activityInfo.packageName))
                            val editText = EditText(mainActivity)
                            editText.setText(suggestedName)
                            AlertDialog.Builder(mainActivity)
                                .setTitle("New Folder")
                                .setView(editText)
                                .setPositiveButton("Create") { _, _ ->
                                    val name = editText.text.toString().ifEmpty { suggestedName }
                                    items[pos] = Folder(name, mutableListOf(item.activityInfo.packageName))
                                    notifyItemChanged(pos)
                                    mainActivity.saveAppOrder()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }

                        MENU_EMPTY_ALL_FOLDERS -> {
                            mainActivity.emptyAllFolders()
                            true
                        }

                        MENU_MORE_INFO -> {
                            mainActivity.showAppDetails(item.activityInfo.packageName)
                            true
                        }

                        else -> false
                    }
                }
            }
        }

        override fun handleSpecificDrop(fromPosition: Int, toPosition: Int): Boolean {
            val fromItem = items[fromPosition]
            val toItem = items[toPosition]

            if (fromItem is ResolveInfo && toItem is ResolveInfo) {
                // Create a new folder
                val folderApps = mutableListOf(toItem.activityInfo.packageName, fromItem.activityInfo.packageName)
                val suggestedName = mainActivity.getFolderNameForApps(folderApps)
                val newFolder = Folder(suggestedName, folderApps)

                val finalToPosition = if (fromPosition < toPosition) toPosition - 1 else toPosition
                items.removeAt(fromPosition)
                items[finalToPosition] = newFolder

                notifyItemChanged(finalToPosition)
                notifyItemRemoved(fromPosition)

                mainActivity.saveAppOrder()
                return true
            }
            return false
        }
    }

    inner class FolderViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val folderName: TextView = itemView.findViewById(R.id.folder_name)
        val folderIcon: FolderIconView = itemView.findViewById(R.id.folder_icon)

        override fun handleItemClick() {
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = items[pos]
                if (item is Folder) {
                    mainActivity.showFolderDialog(item)
                }
            }
        }

        override fun createPopupMenu() {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return

            val item = items[pos] as? Folder ?: return

            mainActivity.popupMenu?.menu?.add(MENU_RENAME)
            mainActivity.popupMenu?.menu?.add(MENU_EMPTY_FOLDER)
            mainActivity.popupMenu?.menu?.add(MENU_AUTO_SORT)
            mainActivity.popupMenu?.menu?.add(MENU_EMPTY_ALL_FOLDERS)
            mainActivity.popupMenu?.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    MENU_RENAME -> {
                        val editText = EditText(mainActivity)
                        editText.setText(item.name)
                        AlertDialog.Builder(mainActivity)
                            .setTitle("Rename Folder")
                            .setView(editText)
                            .setPositiveButton("Save") { _, _ ->
                                item.name = editText.text.toString()
                                notifyItemChanged(pos)
                                mainActivity.saveAppOrder()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }

                    MENU_EMPTY_FOLDER -> {
                        mainActivity.emptyFolder(item)
                        true
                    }

                    MENU_AUTO_SORT -> {
                        mainActivity.autoSortApps()
                        true
                    }

                    MENU_EMPTY_ALL_FOLDERS -> {
                        mainActivity.emptyAllFolders()
                        true
                    }

                    else -> false
                }
            }
        }

        override fun handleSpecificDrop(fromPosition: Int, toPosition: Int): Boolean {
            val fromItem = items[fromPosition]
            val toItem = items[toPosition]

            if (fromItem is ResolveInfo && toItem is Folder) {
                // Add app to existing folder
                toItem.apps.add(fromItem.activityInfo.packageName)
                items.removeAt(fromPosition)

                val finalToPosition = if (fromPosition < toPosition) toPosition - 1 else toPosition
                notifyItemRemoved(fromPosition)
                notifyItemChanged(finalToPosition)
                mainActivity.saveAppOrder()
                return true
            }
            return false
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
                holder.appName.text = app.loadLabel(mainActivity.packageManager)
                val icon = app.loadIcon(mainActivity.packageManager)
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
                        mainActivity.packageManager.getApplicationIcon(packageName)
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
