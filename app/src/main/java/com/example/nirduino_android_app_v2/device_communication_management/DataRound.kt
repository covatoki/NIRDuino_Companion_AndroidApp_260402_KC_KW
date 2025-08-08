package com.example.nirduino_android_app_v2.device_communication_management

import com.example.nirduino_android_app_v2.device_communication_management.Stimulus

data class DataRound(
    val timestamps: MutableList<Float>,
    val redData: MutableList<List<Float>>,
    val irData: MutableList<List<Float>>,
    val stimuli: MutableList<Stimulus> = mutableListOf()
)