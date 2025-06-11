package com.example.nirduino_android_app_v2.layout_studio_files

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

// Jetpack DataStore declaration (must be at top level)
val Context.dataStore by preferencesDataStore(name = "layout_studio_prefs")

object LayoutDataStore {

    private val gson = Gson()

    // Key for layout items
    private val LAYOUT_ITEMS_KEY = stringPreferencesKey("layout_items_json")

    // Save LayoutStudioItem list to DataStore (for storing layout items themselves)
    suspend fun saveLayoutItems(context: Context, layoutItems: List<LayoutStudioItem>) {
        val json = gson.toJson(layoutItems)
        context.dataStore.edit { preferences ->
            preferences[LAYOUT_ITEMS_KEY] = json
        }
    }

    // Load LayoutStudioItem list from DataStore
    suspend fun loadLayoutItems(context: Context): List<LayoutStudioItem> {
        val preferences = context.dataStore.data.first()
        val json = preferences[LAYOUT_ITEMS_KEY] ?: return emptyList()
        return gson.fromJson(json, Array<LayoutStudioItem>::class.java).toList()
    }

    // Save overlays for a given layout
    suspend fun saveOverlayElements(context: Context, layoutName: String, overlays: List<OverlayElement>) {
        val json = gson.toJson(overlays)
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("overlay_elements_$layoutName")] = json
        }
    }

    // Load overlays for a given layout
    suspend fun loadOverlayElements(context: Context, layoutName: String): List<OverlayElement> {
        val preferences = context.dataStore.data.first()
        val json = preferences[stringPreferencesKey("overlay_elements_$layoutName")] ?: return emptyList()
        return gson.fromJson(json, Array<OverlayElement>::class.java).toList()
    }

    // Delete overlay elements for a given layout
    suspend fun deleteOverlayElements(context: Context, layoutName: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey("overlay_elements_$layoutName"))
        }
    }

    // Delete a layout's data from DataStore (removes layout items)
    suspend fun deleteLayoutItems(context: Context, layoutName: String) {
        val preferences = context.dataStore.data.first()
        val json = preferences[LAYOUT_ITEMS_KEY] ?: return

        // Deserialize existing layout items and filter out the one being deleted
        val layoutItems = gson.fromJson(json, Array<LayoutStudioItem>::class.java).toMutableList()
        val itemToDelete = layoutItems.find { it.layoutName == layoutName }
        if (itemToDelete != null) {
            layoutItems.remove(itemToDelete)
            // Save the updated layout items back to DataStore
            saveLayoutItems(context, layoutItems)
        }
    }

}
