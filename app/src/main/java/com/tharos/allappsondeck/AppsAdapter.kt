package com.tharos.allappsondeck

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ResolveInfo
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
     * Base ViewHolder class handling clicks and long clicks.
     */
    abstract inner class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
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
