package com.example.nirduino_android_app_v2.run_experiement

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExperimentModel(
    val name: String = "",
    var description: String = "",
    val totalCycle: Int = 0,
    val relaxDescription: String = "",
    val startDescription: String = "",
    val startStreamText: String = "",
    val stopStreamText: String = "",
    val totalWorkingSeconds: Int = 0,
    val totalStopSeconds: Int = 0,
    val restColor: Int = 0,
    val stimColor: Int = 0,
): Parcelable{
    init {
        description = description
            .replace("{work}", totalWorkingSeconds.toString())
            .replace("{stop}", totalStopSeconds.toString())
            .replace("{cycle}", totalCycle.toString())
    }
}