package com.example.nirduino_android_app_v2.device_manager_files

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat

class DeviceManager : AppCompatActivity() {

    private lateinit var knownDeviceRecyclerView: RecyclerView
    private lateinit var newDeviceRecyclerView: RecyclerView
    private lateinit var knownDeviceAdapter: KnownDevicesAdapter
    private lateinit var newDeviceAdapter: NewDeviceAdapter
    private val knownDeviceList = mutableListOf<KnownDeviceItem>()
    private val newDeviceList = mutableListOf<NewDeviceItem>()
    private lateinit var scanBt:Button

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val scanResults = mutableSetOf<String>() // to avoid duplicates

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val mac = device.address
            val name = device.name ?: "Unnamed Device"
            val rssi = result.rssi

            Log.e("DeviceFound", name)

            runOnUiThread {

                if (name.contains("NIRDuino")){

                    addNewDevice(name, rssi, mac)

                }

            }
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@DeviceManager, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }

    }


    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)

        // RecyclerViews
        knownDeviceRecyclerView = findViewById(R.id.knownDeviceRecyclerView)
        knownDeviceRecyclerView.layoutManager = LinearLayoutManager(this)

        newDeviceRecyclerView = findViewById(R.id.newDeviceRecyclerView)
        newDeviceRecyclerView.layoutManager = LinearLayoutManager(this)

        // Adapters
        knownDeviceAdapter = KnownDevicesAdapter(knownDeviceList) { device ->
            lifecycleScope.launch {
                // Remove from memory list and UI
                knownDeviceList.remove(device)
                knownDeviceAdapter.notifyDataSetChanged()

                // Remove from persistent DataStore
                KnownDeviceDataStore.removeDevice(this@DeviceManager, device.macAddress)
            }
        }

        newDeviceAdapter = NewDeviceAdapter(newDeviceList) { device ->
            lifecycleScope.launch {

                val existing = KnownDeviceDataStore.getDevices(this@DeviceManager).first()
                val alreadySaved = existing.any { it.macAddress == device.textMacAddress }

                if (!alreadySaved) {
                    // Show alias dialog only if MAC is not already saved
                    showTextInputDialog(this@DeviceManager, "Enter device alias:", "Save device") { alias ->
                        val currentDate = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date())
                        val knownDevice = KnownDeviceItem(alias, currentDate, device.textMacAddress)

                        // Add to persistent store and update UI
                        lifecycleScope.launch {
                            KnownDeviceDataStore.addDevice(this@DeviceManager, knownDevice)
                            newDeviceList.remove(device)
                            newDeviceAdapter.notifyDataSetChanged()
                        }

                    }
                } else {
                    Toast.makeText(this@DeviceManager, "Device already saved", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Attach adapters
        knownDeviceRecyclerView.adapter = knownDeviceAdapter
        newDeviceRecyclerView.adapter = newDeviceAdapter

        // ✅ Load saved known devices early
        lifecycleScope.launch {
            KnownDeviceDataStore.getDevices(this@DeviceManager).collect { savedDevices ->
                knownDeviceList.clear()
                knownDeviceList.addAll(savedDevices)
                knownDeviceAdapter.notifyDataSetChanged()
            }
        }

        // Scan button
        scanBt = findViewById(R.id.scanButton)
        scanBt.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorPrimaryValue))
        scanBt.setTextColor(ContextCompat.getColorStateList(this, R.color.white))
        scanBt.setOnClickListener {
            bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            bleScanner = bluetoothAdapter.bluetoothLeScanner

            if (!scanning) {
                // Clear list before fresh scan
                newDeviceList.clear()
                newDeviceAdapter.notifyDataSetChanged()
                scanResults.clear()

                bleScanner?.startScan(scanCallback)
                Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
                scanning = true

                scanBt.text = "SCANNING..."
                scanBt.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorAccentValue))

                Handler(Looper.getMainLooper()).postDelayed({
                    bleScanner?.stopScan(scanCallback)
                    scanning = false
                    Toast.makeText(this, "Scan complete.", Toast.LENGTH_SHORT).show()
                    scanBt.text = "SCAN"
                    scanBt.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorPrimaryValue))
                }, 1000)
            }
            else{
                scanBt.text = "SCAN"
                scanBt.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorPrimaryValue))
            }
        }

    }

    private fun addNewDevice(name: String, rssi: Int, mac: String) {
        // Check if it's already in the list
        val exists = newDeviceList.any { it.textMacAddress == mac }

        if (!exists) {
            val newItem = NewDeviceItem(name, rssi, mac)
            newDeviceList.add(newItem)
            newDeviceAdapter.notifyItemInserted(newDeviceList.size - 1)
        } else {
            // Optionally update RSSI if already exists
            val index = newDeviceList.indexOfFirst { it.textMacAddress == mac }
            if (index != -1) {
                newDeviceList[index] = NewDeviceItem(name, rssi, mac)
                newDeviceAdapter.notifyItemChanged(index)
            }
        }
    }


    fun showTextInputDialog(
        context: Context,
        prompt: String,
        buttonLabel: String,
        onSubmit: (String) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(context).apply {
            hint = "Type here..."
        }

        val submitButton = Button(context).apply {
            text = buttonLabel
        }

        layout.addView(input)
        layout.addView(submitButton)

        val dialog = AlertDialog.Builder(context)
            .setTitle(prompt)
            .setView(layout)
            .setCancelable(true)
            .create()

        submitButton.setOnClickListener {
            val userInput = input.text.toString()
            onSubmit(userInput)
            dialog.dismiss()
        }

        dialog.show()
    }

}