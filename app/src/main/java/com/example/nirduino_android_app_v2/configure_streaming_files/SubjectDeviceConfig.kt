package com.example.nirduino_android_app_v2.configure_streaming_files

data class SubjectDeviceConfig(
    val subjectNumber: Int,
    val deviceNumber: Int,
    var selectedDeviceName: String = "",
    var selectedLayoutName: String = ""
)
