package com.example.nirduino_android_app_v2

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.about_nirduino_files.AboutNIRDuino
import com.example.nirduino_android_app_v2.authors_files.Authors
import com.example.nirduino_android_app_v2.device_manager_files.DeviceManager
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutStudio
import com.example.nirduino_android_app_v2.snirf_convert_files.SNIRFConverter
import com.example.nirduino_android_app_v2.stream_fNIRS_data_files.StreamFnirsData
import com.example.nirduino_android_app_v2.tutorials_files.Tutorials
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
            ActivitySelectorCard("Layout Studio", R.drawable.ic_layout_studio, LayoutStudio::class.java),
            ActivitySelectorCard("Stream fNIRS data", R.drawable.ic_line_plot, StreamFnirsData::class.java),
            ActivitySelectorCard("SNIRF Converter", R.drawable.ic_converter, SNIRFConverter::class.java),
            ActivitySelectorCard("About NIRDuino", R.drawable.ic_nirduino, AboutNIRDuino::class.java),
            ActivitySelectorCard("Authors", R.drawable.ic_authors, Authors::class.java),
            ActivitySelectorCard("Device Manager", R.drawable.ic_settings, DeviceManager::class.java),
            ActivitySelectorCard("Tutorials", R.drawable.ic_tutorials, Tutorials::class.java)
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
