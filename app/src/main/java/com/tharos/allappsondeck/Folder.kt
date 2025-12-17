package com.tharos.allappsondeck

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Folder(
    var name: String,
    val apps: MutableList<String> = mutableListOf()
) : Parcelable
