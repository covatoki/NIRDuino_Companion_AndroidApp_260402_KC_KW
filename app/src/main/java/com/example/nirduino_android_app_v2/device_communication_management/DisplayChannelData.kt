package com.example.nirduino_android_app_v2.device_communication_management

data class DisplayChannelData(
    val channelNumber: Int,
    val type: ChannelType,
    val sourceId: Int,
    val sourceX: Float,
    val sourceY: Float,
    val detectorId: Int,
    val detectorX: Float,
    val detectorY: Float,
    val value: Double,
    val x: Float,
    val y: Float
)