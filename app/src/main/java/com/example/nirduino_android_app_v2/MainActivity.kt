package com.example.nirduino_android_app_v2

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.device_manager_files.DeviceManager
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutStudio
import com.example.nirduino_android_app_v2.snirf_convert_files.SNIRFConverter
import com.example.nirduino_android_app_v2.tutorials_files.Tutorials
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.nirduino_android_app_v2.device_communication.StreamfNIRSData
import com.example.nirduino_android_app_v2.participant_manager.ParticipantManager
import com.example.nirduino_android_app_v2.util.AppPermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    private fun requestAllPermissionsIfNeeded(): Boolean {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 101)
            false
        } else {
            true
        }
    }

    private fun calculateSpanCount(): Int {
        val configuration = resources.configuration
        val screenWidthDp = configuration.screenWidthDp

        return when {
            screenWidthDp >= 600 -> 3 // tablets
            screenWidthDp >= 400 -> 2 // typical phones
            else -> 1 // small phones
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.cardRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, calculateSpanCount())

        val cards = listOf(
            ActivitySelectorCard("Manage participants", R.drawable.ic_people, ParticipantManager::class.java),
            ActivitySelectorCard("Connect devices", R.drawable.ic_chip, DeviceManager::class.java),
            ActivitySelectorCard("Design layout", R.drawable.ic_activity_zone, LayoutStudio::class.java),
            ActivitySelectorCard("Stream fNIRS data", R.drawable.ic_line_plot, StreamfNIRSData::class.java),
            ActivitySelectorCard("Run experiments", R.drawable.ic_play, StreamfNIRSData::class.java),
            ActivitySelectorCard("Convert to SNIRF", R.drawable.ic_converter, SNIRFConverter::class.java),
//            ActivitySelectorCard("Get information", R.drawable.ic_tutorials, Tutorials::class.java)
        )

        // On card click
        recyclerView.adapter = ActivitySelectorAdapter(cards) { item ->

            val intent = Intent(this, item.activityClass)
            startActivity(intent)

        }

        // Request all permissions at startup
//        val permissionsGranted =
        AppPermissionHelper.requestPermissionsIfNeeded(this)

//        // Check Bluetooth + Location enabled
//        val servicesEnabled = AppPermissionHelper.ensureBluetoothAndLocationEnabled(this)


    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val granted = AppPermissionHelper.handlePermissionResult(this, requestCode, permissions, grantResults)

        if (granted) {
            // ✅ Only check Bluetooth + Location after permissions are granted
            AppPermissionHelper.ensureBluetoothAndLocationEnabled(this)
        }
    }

}
