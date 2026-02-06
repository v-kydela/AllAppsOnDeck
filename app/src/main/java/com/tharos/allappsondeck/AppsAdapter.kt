package com.tharos.allappsondeck

import android.app.AlertDialog
import android.content.pm.ResolveInfo
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
    private val items: MutableList<Any>,
    private val itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper
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
            if (mainActivity.popupMenu != null) {
                mainActivity.popupMenu?.dismiss()
                return
            }

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

        override fun onLongClick(v: View): Boolean {
            mainActivity.popupMenu?.dismiss()
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return false

            val item = items[pos] as? ResolveInfo ?: return false

            itemTouchHelper.startDrag(this)

            mainActivity.popupMenu = PopupMenu(v.context, v)
            mainActivity.popupMenu?.setOnDismissListener { mainActivity.popupMenu = null }
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
            mainActivity.popupMenu?.show()
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
            if (mainActivity.popupMenu != null) {
                mainActivity.popupMenu?.dismiss()
                return
            }

            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = items[pos]
                if (item is Folder) {
                    mainActivity.showFolderDialog(item)
                }
            }
        }

        override fun onLongClick(v: View): Boolean {
            mainActivity.popupMenu?.dismiss()
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return false

            val item = items[pos] as? Folder ?: return false

            itemTouchHelper.startDrag(this)

            mainActivity.popupMenu = PopupMenu(v.context, v)
            mainActivity.popupMenu?.setOnDismissListener { mainActivity.popupMenu = null }
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
            mainActivity.popupMenu?.show()
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
