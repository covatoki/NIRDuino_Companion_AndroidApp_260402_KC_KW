package com.example.nirduino_android_app_v2.device_manager_files

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceStore by preferencesDataStore(name = "known_devices_store")
private val DEVICES_KEY = stringPreferencesKey("known_devices_json")

class KnownDeviceDataStore private constructor(private val context: Context) {

    private val gson = Gson()
    private val typeToken = object : TypeToken<List<KnownDeviceItem>>() {}.type

    companion object {
        @Volatile private var INSTANCE: KnownDeviceDataStore? = null

        fun getInstance(context: Context): KnownDeviceDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KnownDeviceDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun getDevices(): Flow<List<KnownDeviceItem>> {
        return context.deviceStore.data.map { prefs ->
            prefs[DEVICES_KEY]?.let { json ->
                gson.fromJson(json, typeToken)
            } ?: emptyList()
        }
    }

    suspend fun addDevice(newDevice: KnownDeviceItem) {
        context.deviceStore.edit { prefs ->
            val currentList = prefs[DEVICES_KEY]?.let {
                gson.fromJson<List<KnownDeviceItem>>(it, typeToken)
            } ?: emptyList()

            if (currentList.none { it.macAddress == newDevice.macAddress }) {
                val updatedList = currentList + newDevice
                prefs[DEVICES_KEY] = gson.toJson(updatedList)
            }
        }
    }

    suspend fun removeDevice(macAddress: String) {
        context.deviceStore.edit { prefs ->
            val currentList = prefs[DEVICES_KEY]?.let {
                gson.fromJson<List<KnownDeviceItem>>(it, typeToken)
            } ?: emptyList()

            val updatedList = currentList.filterNot { it.macAddress == macAddress }
            prefs[DEVICES_KEY] = gson.toJson(updatedList)
        }
    }

    suspend fun getAllDeviceAliasesWithMac(): Map<String, String> {
        val list = context.deviceStore.data.first()[DEVICES_KEY]?.let {
            gson.fromJson<List<KnownDeviceItem>>(it, typeToken)
        } ?: emptyList()
        return list.associate { it.alias to it.macAddress }
    }
}
