package com.example.nirduino_android_app_v2.live_data_view

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.configure_streaming_files.ConfigurationCardItem
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.google.gson.Gson

class LiveDataStream : AppCompatActivity() {

    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_live_data_stream)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val configMap = extractConfigAsStringArrays()
        val configJson = intent.getStringExtra("configJson")

        if (configMap != null && configJson != null) {
            BLEConnectionManager.startService(applicationContext, configJson)
        } else {
            Toast.makeText(this, "Could not load configuration", Toast.LENGTH_SHORT).show()
        }

        val streamButton: Button = findViewById(R.id.streamToggleButton)
        streamButton.setOnClickListener {

            if (!BLEConnectionManager.getStreamReadinessStatus()) {
                Toast.makeText(this, "Devices not ready to stream yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isStreaming) {
                BLEConnectionManager.stopStreamingFromAllDevices()
                streamButton.text = "Start Streaming"
                isStreaming = false
            } else {
                BLEConnectionManager.streamFromAllDevices()
                streamButton.text = "Stop Streaming"
                isStreaming = true
            }

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BLEConnectionManager.stopService(applicationContext)
    }

    override fun onBackPressed() {
        BLEConnectionManager.stopService(applicationContext)
        super.onBackPressed()
    }

    private fun extractConfigAsStringArrays(): Map<String, Array<String>>? {
        val configJson = intent.getStringExtra("configJson") ?: return null

        return try {
            val config = Gson().fromJson(configJson, ConfigurationCardItem::class.java)
            mapOf(
                "layoutName" to arrayOf(config.configName),
                "numSubjects" to arrayOf(config.numSubjects.toString()),
                "numDevicesPerSubject" to arrayOf(config.numDevicesPerSubject.toString()),
                "lastUpdated" to arrayOf(config.lastUpdated),
                "selectedDeviceNames" to config.selectedDeviceNames.toTypedArray(),
                "selectedLayoutNames" to config.selectedLayoutNames.toTypedArray()
            )
        } catch (e: Exception) {
            Log.e("ConfigParser", "Failed to parse configJson", e)
            null
        }
    }
}
