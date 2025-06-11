package com.example.nirduino_android_app_v2.device_manager_files

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore by preferencesDataStore(name = "mac_store")

