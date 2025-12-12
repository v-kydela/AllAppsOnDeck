package com.tharos.allappsondeck

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var popupMenu: PopupMenu? = null
    private var isDragging = false
    private lateinit var apps: MutableList<ResolveInfo>

    companion object {
        private const val PREFS_NAME = "AppOrder"
        private const val ORDER_KEY = "order"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appsList = findViewById(R.id.apps_list)
        appsList.layoutManager = GridLayoutManager(this, 4)

        apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0).toMutableList()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val orderString = prefs.getString(ORDER_KEY, null)
        if (orderString != null) {
            val savedOrder = orderString.split(',')
            if (savedOrder.isNotEmpty()) {
                val orderMap = savedOrder.withIndex().associate { it.value to it.index }
                apps.sortWith(compareBy { orderMap[it.activityInfo.packageName] ?: savedOrder.size })
            }
        }

        val adapter = AppsAdapter(apps)
        appsList.adapter = adapter

        val callback = object : ItemTouchHelper.Callback() {
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
                isDragging = true
                popupMenu?.dismiss()
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                val movedItem = apps.removeAt(fromPosition)
                apps.add(toPosition, movedItem)
                appsList.adapter?.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun isLongPressDragEnabled(): Boolean {
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = false
                    viewHolder?.let {
                        val pos = it.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val app = (appsList.adapter as AppsAdapter).getAppAt(pos)
                            popupMenu?.dismiss()
                            popupMenu = PopupMenu(it.itemView.context, it.itemView).apply {
                                menu.add(Menu.NONE, 1, 1, "App Info")
                                setOnMenuItemClickListener { item ->
                                    when (item.itemId) {
                                        1 -> {
                                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            intent.data = Uri.fromParts("package", app.activityInfo.packageName, null)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(intent)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                setOnDismissListener { popupMenu = null }
                                show()
                            }
                        }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isDragging) {
                    saveAppOrder()
                    popupMenu?.dismiss()
                    popupMenu = null
                }
                // If not dragging, menu should stay. It will be dismissed by user interaction.
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(appsList)
    }

    private fun saveAppOrder() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            val order = apps.joinToString(",") { it.activityInfo.packageName }
            putString(ORDER_KEY, order)
        }
    }

    inner class AppsAdapter(private val apps: MutableList<ResolveInfo>) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        fun getAppAt(position: Int): ResolveInfo {
            return apps[position]
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
            val appName: TextView = itemView.findViewById(R.id.app_name)
            val appIcon: ImageView = itemView.findViewById(R.id.app_icon)

            init {
                itemView.setOnClickListener(this)
            }

            override fun onClick(v: View?) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val app = apps[pos]
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
                    startActivity(launchIntent)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.app_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.appName.text = app.loadLabel(packageManager)
            holder.appIcon.setImageDrawable(app.loadIcon(packageManager))
        }

        override fun getItemCount(): Int {
            return apps.size
        }
    }
}
