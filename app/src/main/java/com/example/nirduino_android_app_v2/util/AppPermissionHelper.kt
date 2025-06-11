package com.example.nirduino_android_app_v2.util

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object AppPermissionHelper {

    private const val REQUEST_CODE = 101

    fun getRequiredPermissions(): Array<String> {
        return mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }

    fun requestPermissionsIfNeeded(activity: Activity): Boolean {
        val notGranted = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, notGranted.toTypedArray(), REQUEST_CODE)
            false
        } else {
            true
        }
    }

    fun handlePermissionResult(
        context: Context,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return requestCode == REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.isEnabled == true
    }

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun ensureBluetoothAndLocationEnabled(context: Context): Boolean {
        val bluetoothEnabled = isBluetoothEnabled(context)
        val locationEnabled = isLocationEnabled(context)

        if (!bluetoothEnabled) {
            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        if (!locationEnabled) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        return bluetoothEnabled && locationEnabled
    }
}
