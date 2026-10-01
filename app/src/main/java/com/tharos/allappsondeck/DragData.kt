package com.tharos.allappsondeck

import android.view.View
import androidx.recyclerview.widget.RecyclerView

data class DragData(
    val dragView: View,
    val item: Any,
    val sourceRecyclerView: RecyclerView,
    val sourceAdapter: AppsAdapter,
    val sourcePosition: Int,
    val sourceFolder: Folder? = null
)
