package com.tharos.allappsondeck

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

        appsList = findViewById(R.id.apps_list)
        appsList.layoutManager = GridLayoutManager(this, 4)

        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)

        val folderApps = allApps.filter { app -> folder.apps.contains(app.activityInfo.packageName) }

        appsList.adapter = AppsAdapter(folderApps)

        supportActionBar?.title = folder.name
    }

    inner class AppsAdapter(private val apps: List<ResolveInfo>) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

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
