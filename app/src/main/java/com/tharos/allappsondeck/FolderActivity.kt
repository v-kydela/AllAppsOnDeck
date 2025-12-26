package com.tharos.allappsondeck

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FolderActivity : AppCompatActivity() {

    private lateinit var appsList: RecyclerView
    private lateinit var folder: Folder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder)

        folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("folder", Folder::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("folder")
        }!!

        findViewById<TextView>(R.id.folder_title).text = folder.name

        // Close when clicking outside the card
        findViewById<View>(R.id.outside_touch_area).setOnClickListener {
            finish()
        }

        appsList = findViewById(R.id.apps_list)
        appsList.layoutManager = GridLayoutManager(this, 4)

        refreshList()
    }

    private fun refreshList() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)

        val folderApps = allApps.filter { app -> folder.apps.contains(app.activityInfo.packageName) }

        appsList.adapter = AppsAdapter(folderApps)
    }

    inner class AppsAdapter(private val apps: List<ResolveInfo>) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
            val appName: TextView = itemView.findViewById(R.id.app_name)
            val appIcon: ImageView = itemView.findViewById(R.id.app_icon)

            init {
                itemView.setOnClickListener(this)
                itemView.setOnLongClickListener(this)
            }

            override fun onClick(v: View?) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val app = apps[pos]
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
                    startActivity(launchIntent)
                    finish() // Close folder after launching app
                }
            }

            override fun onLongClick(v: View): Boolean {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return false

                val app = apps[pos]
                val popup = PopupMenu(this@FolderActivity, v)
                popup.menu.add("Remove from Folder")
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.title == "Remove from Folder") {
                        folder.apps.remove(app.activityInfo.packageName)
                        
                        // Notify MainActivity that the folder has changed
                        val resultIntent = Intent()
                        resultIntent.putExtra("updated_folder", folder)
                        setResult(RESULT_OK, resultIntent)
                        
                        if (folder.apps.isEmpty()) {
                            finish()
                        } else {
                            refreshList()
                        }
                        true
                    } else {
                        false
                    }
                }
                popup.show()
                return true
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.folder_app_item, parent, false)
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
