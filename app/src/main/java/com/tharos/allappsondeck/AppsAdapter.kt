package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ResolveInfo
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for rendering apps, folders, and action items in RecyclerViews.
 * Used for both the primary launcher grid and active folder overlay grid.
 */
class AppsAdapter(
    private val mainActivity: MainActivity,
    internal val items: MutableList<Any>,
    internal val isFolderAdapter: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_ACTION = 2
        private const val MENU_AUTO_ORGANIZE = "Auto Organize"
        private const val MENU_UNPIN_ALL = "Unpin All Apps"
        private const val MENU_RESTORE_DEFAULT_PINNED = "Restore Default Pinned Apps"
        private const val MENU_CREATE_FOLDER = "Create Folder"
        private const val MENU_EMPTY_ALL_FOLDERS = "Empty All Folders"
        private const val MENU_APP_INFO = "App Info"
        private const val MENU_RENAME = "Rename"
        private const val MENU_EMPTY_FOLDER = "Empty Folder"
        private const val MENU_REMOVE_FROM_FOLDER = "Remove from Folder"
        private const val MENU_REFRESH = "Refresh"
        @Suppress("unused")
        private const val MENU_TEMP_HIDE = "Temporarily Hide"
    }

    /**
     * Updates adapter items and notifies dataset observers.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: List<Any>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Base ViewHolder class handling clicks, long clicks, drag events, and caret drop target indicators.
     */
    abstract inner class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener, View.OnDragListener {
        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
            itemView.setOnDragListener(this)
        }

        /**
         * Calculates drop location and positions the drop caret insertion indicator.
         */
        private fun updateDropCaret(v: View, event: DragEvent, canDropInMiddle: Boolean) {
            val recyclerView = v.parent as? RecyclerView ?: return
            val caret = (recyclerView.parent as? ViewGroup)?.findViewById<View>(R.id.drop_caret) ?: return
            
            val dragData = event.localState as? DragData
            val fromPosition = if (dragData != null && dragData.sourceRecyclerView == recyclerView) {
                dragData.sourcePosition
            } else -1
            
            val toPosition = bindingAdapterPosition
            if (toPosition == RecyclerView.NO_POSITION) {
                caret.visibility = View.INVISIBLE
                return
            }

            val dropX = event.x
            val viewWidth = v.width
            val oneThird = viewWidth / 3

            val isLeft = dropX <= oneThird
            val isRight = dropX >= viewWidth - oneThird
            val isMiddle = canDropInMiddle && !isLeft && !isRight

            v.alpha = if (isMiddle) 0.5f else 1.0f

            if (isLeft || isRight) {
                val dropTargetPos = if (isLeft) toPosition else toPosition + 1
                val wouldActuallyMoveTo = if (fromPosition != -1 && fromPosition < dropTargetPos) dropTargetPos - 1 else dropTargetPos

                if (fromPosition != -1 && wouldActuallyMoveTo == fromPosition) {
                    caret.visibility = View.INVISIBLE
                } else {
                    val caretWidthPx = 4 * v.context.resources.displayMetrics.density
                    
                    // Simple sibling math: RecyclerView's relative pos + Item's relative pos
                    // This works perfectly because caret is a sibling of RecyclerView
                    val targetX = (if (isLeft) v.left else v.right).toFloat() + recyclerView.left
                    val targetY = v.top.toFloat() + recyclerView.top

                    caret.x = targetX - (caretWidthPx / 2f)
                    caret.y = targetY
                    
                    if (caret.layoutParams.height != v.height) {
                        caret.layoutParams.height = v.height
                        caret.requestLayout()
                    }
                    
                    caret.visibility = View.VISIBLE
                }
            } else {
                caret.visibility = View.INVISIBLE
            }
        }

        private fun hideDropCaret(v: View) {
            v.alpha = 1.0f
            val recyclerView = v.parent as? RecyclerView ?: return
            val caret = (recyclerView.parent as? ViewGroup)?.findViewById<View>(R.id.drop_caret)
            caret?.visibility = View.INVISIBLE
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

        /**
         * Drag listener handling drag entry, movement, drop caret display, and drop execution.
         */
        override fun onDrag(v: View, event: DragEvent): Boolean {
            val toPosition = bindingAdapterPosition
            if (toPosition == RecyclerView.NO_POSITION) return false

            val isDraggingApp = event.clipDescription?.hasMimeType("vnd.android.cursor.item/app") ?: false

            val canDropInMiddle = when (this) {
                is AppViewHolder -> !isFolderAdapter && isDraggingApp
                is FolderViewHolder -> isDraggingApp
                else -> false
            }

            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    mainActivity.popupMenu?.dismiss()
                    mainActivity.isDragging = true
                    
                    // Prime the caret height before the user moves their finger
                    val recyclerView = v.parent as? RecyclerView
                    if (recyclerView != null) {
                        val caret = (recyclerView.parent as? ViewGroup)?.findViewById<View>(R.id.drop_caret)
                        if (caret != null && caret.layoutParams.height != v.height) {
                            caret.layoutParams.height = v.height
                            caret.requestLayout()
                        }
                    }
                    return true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    updateDropCaret(v, event, canDropInMiddle)
                    return true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    updateDropCaret(v, event, canDropInMiddle)
                    return true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    hideDropCaret(v)
                    return true
                }
                DragEvent.ACTION_DROP -> {
                    hideDropCaret(v)
                    val dragData = event.localState as? DragData ?: return false

                    val dropX = event.x
                    val viewWidth = v.width
                    val oneThird = viewWidth / 3

                    val isLeft = dropX <= oneThird
                    val isRight = dropX >= viewWidth - oneThird
                    val isMiddle = canDropInMiddle && !isLeft && !isRight

                    if (isLeft || isRight) {
                        val dropTargetPos = if (isLeft) toPosition else toPosition + 1
                        return handlePositionDrop(dragData, dropTargetPos)
                    } else if (isMiddle) {
                        return handleSpecificDrop(dragData, toPosition)
                    } else {
                        val dropTargetPos = if (dropX < viewWidth / 2f) toPosition else toPosition + 1
                        return handlePositionDrop(dragData, dropTargetPos)
                    }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    hideDropCaret(v)
                    mainActivity.isDragging = false
                    mainActivity.longPressedView = null
                    return true
                }
                else -> return false
            }
        }

        /**
         * Handles inserting or reordering items at target position using specific adapter change notifications.
         */
        private fun handlePositionDrop(dragData: DragData, targetPos: Int): Boolean {
            val item = dragData.item
            val sourceFolder = dragData.sourceFolder

            if (isFolderAdapter) {
                if (sourceFolder != null && item is ResolveInfo) {
                    val fromIndex = dragData.sourcePosition
                    if (fromIndex != -1 && fromIndex < sourceFolder.apps.size) {
                        if (fromIndex == targetPos || fromIndex == targetPos - 1) return true

                        val pkg = sourceFolder.apps.removeAt(fromIndex)
                        val finalPos = if (fromIndex < targetPos) targetPos - 1 else targetPos
                        val clampedPos = finalPos.coerceIn(0, sourceFolder.apps.size)
                        sourceFolder.apps.add(clampedPos, pkg)

                        val movedItem = items.removeAt(fromIndex)
                        items.add(clampedPos, movedItem)

                        notifyItemMoved(fromIndex, clampedPos)
                        mainActivity.saveAppOrder()
                        return true
                    }
                }
                return false
            } else {
                if (sourceFolder != null) {
                    // Dragged OUT of a folder onto the main activity list
                    if (item is ResolveInfo) {
                        val pkg = item.activityInfo.packageName
                        sourceFolder.apps.remove(pkg)

                        val folderIdx = items.indexOf(sourceFolder)
                        if (sourceFolder.apps.isEmpty()) {
                            if (folderIdx != -1) {
                                items.removeAt(folderIdx)
                                notifyItemRemoved(folderIdx)
                            }
                        } else if (folderIdx != -1) {
                            notifyItemChanged(folderIdx)
                        }

                        val clampedPos = targetPos.coerceIn(0, items.size)
                        items.add(clampedPos, item)
                        notifyItemInserted(clampedPos)

                        mainActivity.saveAppOrder()
                        return true
                    }
                } else {
                    // Dragged within main list
                    val fromIndex = dragData.sourcePosition
                    if (fromIndex != -1 && fromIndex < items.size) {
                        if (fromIndex == targetPos || fromIndex == targetPos - 1) return true

                        val movedItem = items.removeAt(fromIndex)
                        val finalPos = if (fromIndex < targetPos) targetPos - 1 else targetPos
                        val clampedPos = finalPos.coerceIn(0, items.size)
                        items.add(clampedPos, movedItem)

                        notifyItemMoved(fromIndex, clampedPos)
                        mainActivity.saveAppOrder()
                        return true
                    }
                }
            }
            return false
        }

        abstract fun handleSpecificDrop(dragData: DragData, toPosition: Int): Boolean
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ResolveInfo -> TYPE_APP
            is Folder -> TYPE_FOLDER
            is GlobalActionItem -> TYPE_ACTION
            else -> throw IllegalArgumentException("Invalid type of item at position $position")
        }
    }

    /**
     * ViewHolder for single app items.
     */
    inner class AppViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val appName: TextView = itemView.findViewById(R.id.app_name)
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val pinIcon: ImageView = itemView.findViewById(R.id.pin_icon)

        override fun handleItemClick() {
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = items[pos]
                if (item is ResolveInfo) {
                    val packageName = item.activityInfo.packageName
                    val launchIntent = mainActivity.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )
                        val options = androidx.core.app.ActivityOptionsCompat.makeScaleUpAnimation(
                            itemView, 0, 0, itemView.width, itemView.height
                        ).toBundle()
                        mainActivity.startActivity(launchIntent, options)
                        // Close folder dialog if it's open
                        mainActivity.closeFolderOverlay()
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
                if(isFolderAdapter) {
                    mainActivity.popupMenu?.menu?.add(MENU_REMOVE_FROM_FOLDER)
                } else {
                    mainActivity.popupMenu?.menu?.add(MENU_CREATE_FOLDER)
                }
                
                val isPinned = mainActivity.isAppPinned(item.activityInfo.packageName)
                val pinMenuTitle = if (isPinned) "Unpin from Main List" else "Pin to Main List"
                mainActivity.popupMenu?.menu?.add(pinMenuTitle)

                mainActivity.popupMenu?.menu?.add(MENU_APP_INFO)
                mainActivity.popupMenu?.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        "Pin to Main List", "Unpin from Main List" -> {
                            mainActivity.togglePinApp(item.activityInfo.packageName)
                            true
                        }
                        MENU_CREATE_FOLDER -> {
                            val suggestedName = mainActivity.getFolderNameForApps(listOf(item.activityInfo.packageName))
                            val editText = EditText(mainActivity)
                            editText.setText(suggestedName)
                            AlertDialog.Builder(mainActivity)
                                .setTitle(FolderCategories.DEFAULT_FOLDER_NAME)
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
                        MENU_APP_INFO -> {
                            mainActivity.showAppDetails(item.activityInfo.packageName)
                            true
                        }
                        MENU_REMOVE_FROM_FOLDER -> {
                            mainActivity.removeAppFromFolder(item)
                            true
                        }
                        else -> false
                    }
                }
            }
        }

        override fun handleSpecificDrop(dragData: DragData, toPosition: Int): Boolean {
            if (isFolderAdapter) return false
            val fromItem = dragData.item
            val toItem = items.getOrNull(toPosition) ?: return false

            if (fromItem is ResolveInfo && toItem is ResolveInfo) {
                val sourceFolder = dragData.sourceFolder
                val fromPkg = fromItem.activityInfo.packageName
                val toPkg = toItem.activityInfo.packageName

                if (sourceFolder != null) {
                    sourceFolder.apps.remove(fromPkg)
                    val folderIdx = items.indexOf(sourceFolder)
                    if (sourceFolder.apps.isEmpty()) {
                        if (folderIdx != -1) {
                            items.removeAt(folderIdx)
                            notifyItemRemoved(folderIdx)
                        }
                    } else if (folderIdx != -1) {
                        notifyItemChanged(folderIdx)
                    }
                } else {
                    val fromPos = dragData.sourcePosition
                    if (fromPos != -1 && fromPos < items.size) {
                        items.removeAt(fromPos)
                        notifyItemRemoved(fromPos)
                    }
                }

                val targetIndex = items.indexOf(toItem)
                if (targetIndex != -1) {
                    val folderApps = mutableListOf(toPkg, fromPkg)
                    val suggestedName = mainActivity.getFolderNameForApps(folderApps)
                    val newFolder = Folder(suggestedName, folderApps)
                    items[targetIndex] = newFolder
                    notifyItemChanged(targetIndex)
                }

                mainActivity.saveAppOrder()
                return true
            }
            return false
        }
    }

    /**
     * ViewHolder for folder items.
     */
    inner class FolderViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val folderName: TextView = itemView.findViewById(R.id.folder_name)
        val folderIcon: FolderIconView = itemView.findViewById(R.id.folder_icon)

        override fun handleItemClick() {
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = items[pos]
                if (item is Folder) {
                    mainActivity.showFolderOverlay(item)
                }
            }
        }

        override fun createPopupMenu() {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return

            val item = items[pos] as? Folder ?: return

            mainActivity.popupMenu?.menu?.add(MENU_RENAME)
            mainActivity.popupMenu?.menu?.add(MENU_EMPTY_FOLDER)
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
                    else -> false
                }
            }
        }

        override fun handleSpecificDrop(dragData: DragData, toPosition: Int): Boolean {
            val fromItem = dragData.item
            val toFolder = items.getOrNull(toPosition) as? Folder ?: return false

            if (fromItem is ResolveInfo) {
                val sourceFolder = dragData.sourceFolder
                val fromPkg = fromItem.activityInfo.packageName

                if (sourceFolder == toFolder) return false

                if (sourceFolder != null) {
                    sourceFolder.apps.remove(fromPkg)
                    val folderIdx = items.indexOf(sourceFolder)
                    if (sourceFolder.apps.isEmpty()) {
                        if (folderIdx != -1) {
                            items.removeAt(folderIdx)
                            notifyItemRemoved(folderIdx)
                        }
                    } else if (folderIdx != -1) {
                        notifyItemChanged(folderIdx)
                    }
                } else {
                    val fromPos = dragData.sourcePosition
                    if (fromPos != -1 && fromPos < items.size) {
                        items.removeAt(fromPos)
                        notifyItemRemoved(fromPos)
                    }
                }

                if (!toFolder.apps.contains(fromPkg)) {
                    toFolder.apps.add(fromPkg)
                }

                val targetIndex = items.indexOf(toFolder)
                if (targetIndex != -1) {
                    notifyItemChanged(targetIndex)
                }

                mainActivity.saveAppOrder()
                return true
            }
            return false
        }
    }

    /**
     * ViewHolder for global action items.
     */
    inner class ActionViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val actionName: TextView = itemView.findViewById(R.id.action_name)

        private fun populateMenu(popup: PopupMenu) {
            popup.menu.add(MENU_AUTO_ORGANIZE)
            popup.menu.add(MENU_UNPIN_ALL)
            popup.menu.add(MENU_RESTORE_DEFAULT_PINNED)
            popup.menu.add(MENU_EMPTY_ALL_FOLDERS)
            popup.menu.add(MENU_REFRESH)
            popup.menu.add(MENU_APP_INFO)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    MENU_AUTO_ORGANIZE -> {
                        mainActivity.autoOrganizeApps()
                        true
                    }
                    MENU_UNPIN_ALL -> {
                        mainActivity.unpinAllApps()
                        true
                    }
                    MENU_RESTORE_DEFAULT_PINNED -> {
                        mainActivity.restoreDefaultPinnedApps()
                        true
                    }
                    MENU_EMPTY_ALL_FOLDERS -> {
                        mainActivity.emptyAllFolders()
                        true
                    }
                    MENU_REFRESH -> {
                        mainActivity.refreshApps()
                        true
                    }
                    MENU_APP_INFO -> {
                        mainActivity.showAppDetails(mainActivity.packageName)
                        true
                    }
                    else -> false
                }
            }
        }

        override fun handleItemClick() {
            val popup = PopupMenu(itemView.context, itemView)
            populateMenu(popup)
            popup.show()
        }

        override fun createPopupMenu() {
            mainActivity.popupMenu?.let { populateMenu(it) }
        }

        override fun handleSpecificDrop(dragData: DragData, toPosition: Int): Boolean {
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
            TYPE_ACTION -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.global_action_item, parent, false)
                ActionViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AppViewHolder -> {
                val app = items[position] as ResolveInfo
                val pkg = app.activityInfo.packageName
                
                val appName = mainActivity.labelCache[pkg] ?: app.loadLabel(mainActivity.packageManager)
                holder.appName.text = appName
                holder.appIcon.contentDescription = appName
                
                val icon = mainActivity.iconCache[pkg] ?: app.loadIcon(mainActivity.packageManager)
                holder.appIcon.setImageDrawable(icon)

                val isPinned = mainActivity.isAppPinned(pkg)
                holder.pinIcon.visibility = if (isPinned) View.VISIBLE else View.GONE
            }
            is FolderViewHolder -> {
                val folder = items[position] as Folder
                holder.folderName.text = folder.name
                val folderIcons = folder.apps.take(4).mapNotNull { packageName ->
                    mainActivity.iconCache[packageName] ?: try {
                        mainActivity.packageManager.getApplicationIcon(packageName)
                    } catch (_: Exception) {
                        null
                    }
                }
                holder.folderIcon.setIcons(folderIcons)
            }
            is ActionViewHolder -> {
                holder.actionName.text = mainActivity.getString(R.string.actions_label)
            }
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }
}
