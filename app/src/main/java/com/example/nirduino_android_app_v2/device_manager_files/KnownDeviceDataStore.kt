package com.example.nirduino_android_app_v2.device_manager_files

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "known_devices_store")
private val DEVICES_KEY = stringPreferencesKey("known_devices_json")

object KnownDeviceDataStore {
    private val gson = Gson()
    private val typeToken = object : TypeToken<List<KnownDeviceItem>>() {}.type

    fun getDevices(context: Context): Flow<List<KnownDeviceItem>> {
        return context.dataStore.data.map { prefs ->
            prefs[DEVICES_KEY]?.let { json ->
                gson.fromJson(json, typeToken)
            } ?: emptyList()
        }
    }

    suspend fun addDevice(context: Context, newDevice: KnownDeviceItem) {
        context.dataStore.edit { prefs ->
            val currentList = prefs[DEVICES_KEY]?.let {
                gson.fromJson<List<KnownDeviceItem>>(it, typeToken)
            } ?: emptyList()

            // Avoid duplicates by MAC address
            if (currentList.none { it.macAddress == newDevice.macAddress }) {
                val updatedList = currentList + newDevice
                prefs[DEVICES_KEY] = gson.toJson(updatedList)
            }
        }
    }

    suspend fun removeDevice(context: Context, macAddress: String) {
        context.dataStore.edit { prefs ->
            val currentList = prefs[DEVICES_KEY]?.let {
                gson.fromJson<List<KnownDeviceItem>>(it, typeToken)
            } ?: emptyList()

            val updatedList = currentList.filterNot { it.macAddress == macAddress }
            prefs[DEVICES_KEY] = gson.toJson(updatedList)
        }
    }

}
