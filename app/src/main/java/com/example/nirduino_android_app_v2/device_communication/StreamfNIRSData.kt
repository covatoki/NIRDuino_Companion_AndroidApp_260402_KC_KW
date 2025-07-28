package com.example.nirduino_android_app_v2.device_communication

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StreamfNIRSData : AppCompatActivity() {

    private lateinit var aliasSpinner: Spinner
    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var knownDeviceStore: KnownDeviceDataStore

    private var selectedAlias: String? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_fnirs_data)

        aliasSpinner = findViewById(R.id.spinner_aliases)
        statusTextView = findViewById(R.id.text_status)
        connectButton = findViewById(R.id.btn_connect)
        knownDeviceStore = KnownDeviceDataStore.getInstance(applicationContext)

        lifecycleScope.launch {
            val deviceList = knownDeviceStore.getDevices().first()
            val aliases = deviceList.map { it.alias }

            if (aliases.isEmpty()) {
                statusTextView.text = "No known devices found."
                connectButton.isEnabled = false
                return@launch
            }

            aliasSpinner.adapter = ArrayAdapter(
                this@StreamfNIRSData,
                android.R.layout.simple_spinner_dropdown_item,
                aliases
            )

            aliasSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    selectedAlias = aliases[position]
                    statusTextView.text = "Selected: ${selectedAlias}"
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        connectButton.setOnClickListener {
            val alias = selectedAlias
            if (alias != null) {
                if (!isConnected) {
                    attemptConnection(alias)
                } else {
                    BLEConnectionManager.stopService(this)
                    statusTextView.text = "Disconnected from $alias"
                    isConnected = false
                    connectButton.text = "Connect"
                }
            } else {
                showErrorDialog("Please select a device first.")
            }
        }
    }

    private fun attemptConnection(alias: String) {
        statusTextView.text = "Connecting to $alias..."

        if (!isBluetoothEnabled()) {
            showErrorDialog("Bluetooth is disabled. Please enable it and try again.")
            return
        }

        BLEConnectionManager.startService(this, alias)

        lifecycleScope.launch {
            delay(1000) // let the service actually start and begin scanning
            repeat(20) {
                if (BLEConnectionManager.getStreamReadinessStatus()) {
                    statusTextView.text = "\uD83D\uDD17 Connected to $alias."
                    Log.i("StreamfNIRSData", "✅ Streaming data from $alias")
                    isConnected = true
                    connectButton.text = "Disconnect"
                    return@launch
                } else {
                    statusTextView.text = "Connecting to $alias... ($it)"
                }
                delay(500)
            }
            statusTextView.text = "❌ Failed to connect to $alias"
            showErrorDialog("Could not connect to $alias. Please ensure it is powered on and has sufficient battery.")
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        return adapter?.isEnabled == true
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}