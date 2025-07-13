package com.example.nirduino_android_app_v2.layout_studio_files

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

val Context.dataStore by preferencesDataStore(name = "layout_studio_prefs")

class LayoutDataStore private constructor(private val context: Context) {

    private val gson = Gson()
    private val LAYOUT_ITEMS_KEY = stringPreferencesKey("layout_items_json")

    companion object {
        @Volatile private var INSTANCE: LayoutDataStore? = null

        fun getInstance(context: Context): LayoutDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LayoutDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Returns Map<layoutName, LayoutStudioItem>
    suspend fun getAllLayoutsByName(): Map<String, LayoutStudioItem> {
        val preferences = context.dataStore.data.first()
        val json = preferences[LAYOUT_ITEMS_KEY] ?: return emptyMap()
        val items = gson.fromJson(json, Array<LayoutStudioItem>::class.java).toList()
        return items.associateBy { it.layoutName }
    }

    suspend fun saveLayoutItems(layoutItems: List<LayoutStudioItem>) {
        val json = gson.toJson(layoutItems)
        context.dataStore.edit { prefs ->
            prefs[LAYOUT_ITEMS_KEY] = json
        }
    }

    suspend fun loadLayoutItems(): List<LayoutStudioItem> {
        val preferences = context.dataStore.data.first()
        val json = preferences[LAYOUT_ITEMS_KEY] ?: return emptyList()
        return gson.fromJson(json, Array<LayoutStudioItem>::class.java).toList()
    }

    suspend fun saveOverlayElements(layoutName: String, overlays: List<OverlayElement>) {
        val json = gson.toJson(overlays)
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("overlay_elements_$layoutName")] = json
        }
    }

    suspend fun loadOverlayElements(layoutName: String): List<OverlayElement> {
        val preferences = context.dataStore.data.first()
        val json = preferences[stringPreferencesKey("overlay_elements_$layoutName")] ?: return emptyList()
        return gson.fromJson(json, Array<OverlayElement>::class.java).toList()
    }

    suspend fun deleteOverlayElements(layoutName: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("overlay_elements_$layoutName"))
        }
    }

    suspend fun deleteLayoutItems(layoutName: String) {
        val preferences = context.dataStore.data.first()
        val json = preferences[LAYOUT_ITEMS_KEY] ?: return
        val layoutItems = gson.fromJson(json, Array<LayoutStudioItem>::class.java).toMutableList()
        layoutItems.removeAll { it.layoutName == layoutName }
        saveLayoutItems(layoutItems)
    }
}
