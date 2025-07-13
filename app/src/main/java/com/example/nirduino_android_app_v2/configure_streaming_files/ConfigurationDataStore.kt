package com.example.nirduino_android_app_v2.configure_streaming_files

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "streaming_config_store")
private val CONFIG_KEY = stringPreferencesKey("config_cards_json")

object ConfigurationDataStore {
    private val gson = Gson()

    suspend fun saveConfigs(context: Context, configs: List<ConfigurationCardItem>) {
        val json = gson.toJson(configs)

        android.util.Log.d("ConfigStore", "Saving JSON to DataStore:\n$json")

        context.dataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json
        }
    }

    suspend fun loadConfigs(context: Context): List<ConfigurationCardItem> {
        val prefs = context.dataStore.data.first()
        val json = prefs[CONFIG_KEY] ?: return emptyList()

        android.util.Log.d("ConfigStore", "Loaded JSON from DataStore:\n$json")

        return try {
            gson.fromJson(json, Array<ConfigurationCardItem>::class.java).toList()
        } catch (e: Exception) {
            android.util.Log.e("ConfigStore", "Failed to parse config JSON: ${e.message}")
            emptyList()
        }
    }

}
