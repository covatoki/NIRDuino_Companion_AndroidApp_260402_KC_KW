package com.example.nirduino_android_app_v2.layout_studio_files

import java.util.UUID

data class LayoutStudioItem(
    val layoutId: String = UUID.randomUUID().toString(),  // persists uniquely
    var layoutName: String = "Dummy Layout",
    var selectedSources: MutableSet<Int> = mutableSetOf(),
    var selectedDetectors: MutableSet<Int> = mutableSetOf(),
    var layoutStatus: Boolean = true,
    var lastUpdated: Long = System.currentTimeMillis()
)