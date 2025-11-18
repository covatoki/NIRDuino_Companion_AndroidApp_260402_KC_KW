package com.example.nirduino_android_app_v2

import android.app.Application
import com.example.nirduino_android_app_v2.run_experiement.ExperimentModel

class MyApplication : Application() {
    lateinit var experimentList: List<ExperimentModel>

    companion object {
        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication =
            instance ?: synchronized(this) {
                instance ?: MyApplication().also { instance = it }
            }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        experimentList()
    }

    private fun experimentList() {
        experimentList = listOf(
            ExperimentModel(
                name = "Breath Holding",
                description = "This experiment will consist of {cycle} cycles of:\n• Regular breathing for {work} seconds\n• Breath holding for {stop} seconds",
                totalCycle = 10,
                startDescription = "Please be ready to hold your breath in 3, 2, 1..",
                startStreamText = "HOLD",
                stopStreamText = "BREATH",
                totalWorkingSeconds = 5,
                totalStopSeconds = 5,
                restColor = resources.getColor(R.color.breath_holding_rest),
                stimColor = resources.getColor(R.color.breath_holding_stim)
            ),

            ExperimentModel(
                name = "Finger Tapping",
                description = "This experiment will consist of {cycle} cycles of:\n• Relaxing for {work} seconds\n• Finger tapping for {stop} seconds",
                totalCycle = 5,
                startDescription = "Please be ready to tap your finger in 3, 2, 1..",
                startStreamText = "Please tap your finger",
                stopStreamText = "RELAX",
                totalWorkingSeconds = 5,
                totalStopSeconds = 5,
                restColor = resources.getColor(R.color.finger_tapping_rest),
                stimColor = resources.getColor(R.color.finger_tapping_stim)
            ),

            ExperimentModel(
                name = "Arithmetic",
                description = "This experiment will consist of {cycle} cycles of:\n• Relaxing for {work} seconds\n• Arithmetic task for {stop} seconds",
                totalCycle = 5,
                startDescription = "Please be ready to solve arithmetic problems in 3, 2, 1..",
                startStreamText = "22 + 55",
                stopStreamText = "RELAX",
                totalWorkingSeconds = 10,
                totalStopSeconds = 5,
                restColor = resources.getColor(R.color.arithmetic_rest),
                stimColor = resources.getColor(R.color.arithmetic_stim)
            )
        )
    }
}