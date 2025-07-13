package com.example.nirduino_android_app_v2.configure_streaming_files

import java.text.SimpleDateFormat
import java.util.*

data class ConfigurationCardItem(
    var configName: String,
    var numSubjects: Int = 1,
    var numDevicesPerSubject: Int = 1,
    var lastUpdated: String = currentTimestamp(),
    var selectedDeviceNames: MutableList<String> = mutableListOf(),
    var selectedLayoutNames: MutableList<String> = mutableListOf()
) {

    fun regenerateDeviceAndLayoutLists() {
        val total = numSubjects * numDevicesPerSubject
        if (selectedDeviceNames.size != total) {
            selectedDeviceNames.clear()
            selectedDeviceNames.addAll(List(total) { "" })
        }
        if (selectedLayoutNames.size != total) {
            selectedLayoutNames.clear()
            selectedLayoutNames.addAll(List(total) { "" })
        }
    }

    fun updateTimestamp() {
        lastUpdated = currentTimestamp()
    }

    companion object {
        private fun currentTimestamp(): String {
            val format = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            return format.format(Date())
        }
    }
}
