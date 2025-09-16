package com.example.nirduino_android_app_v2.layout_studio_files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LayoutStudio : AppCompatActivity() {

    private lateinit var adapter: LayoutStudioAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var layoutStore: LayoutDataStore

    private val openJsonFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }

                try {
                    val gson = Gson()
                    val jsonMap = gson.fromJson(json, Map::class.java)

                    val layoutItemJson = gson.toJson(jsonMap)
                    val importedItem = gson.fromJson(layoutItemJson, LayoutStudioItem::class.java)

                    adapter.addItem(importedItem)
                    saveLayoutItemsToDataStore(adapter.getItems())

                    // ---- Parse overlayElements safely (robust to Double/Long/Int) ----
                    val rawOverlayList = (jsonMap["overlayElements"] as? List<*>)?.mapNotNull { elem ->
                        val m = elem as? Map<*, *> ?: return@mapNotNull null
                        val idNum = m["id"] as? Number
                        val isSource = m["isSource"] as? Boolean
                        val xNum = m["x"] as? Number
                        val yNum = m["y"] as? Number

                        if (idNum == null || isSource == null || xNum == null || yNum == null) return@mapNotNull null

                        OverlayElement(
                            id = idNum.toInt(),
                            isSource = isSource,
                            x = xNum.toFloat(),
                            y = yNum.toFloat()
                        )
                    } ?: emptyList()

                    // ---- Normalize negatives by shifting so minX/minY == 0 (only if needed) ----
                    val normalizedOverlayList = normalizeOverlayElements(rawOverlayList)

                    lifecycleScope.launch {
                        layoutStore.saveOverlayElements(importedItem.layoutName, normalizedOverlayList)
                    }

                    Toast.makeText(this, "Layout imported successfully", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Invalid file format", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * If any x or y is negative, shift the entire set so the minimum x/y becomes 0.
     * Keeps values as-is if already non-negative.
     */
    private fun normalizeOverlayElements(list: List<OverlayElement>): List<OverlayElement> {
        if (list.isEmpty()) return list

        val minX = list.minOf { it.x }
        val minY = list.minOf { it.y }

        val shiftX = if (minX < 0f) -minX else 0f
        val shiftY = if (minY < 0f) -minY else 0f

        if (shiftX == 0f && shiftY == 0f) return list // nothing to do

        return list.map { e ->
            e.copy(x = e.x + shiftX, y = e.y + shiftY)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_studio)

        layoutStore = LayoutDataStore.getInstance(applicationContext)

        recyclerView = findViewById(R.id.layoutRecyclerView)
        fab = findViewById(R.id.floatingActionButton)

        val loadedItems = loadLayoutItemsFromDataStore()

        adapter = LayoutStudioAdapter(
            this,
            loadedItems,
            layoutStore,
            object : OnLayoutItemChangedListener {
                override fun onLayoutItemUpdated(position: Int, item: LayoutStudioItem) {
                    saveLayoutItemsToDataStore(adapter.getItems())
                }
            }
        )

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        fab.setOnClickListener {
            val options = arrayOf("Create blank layout", "Import from file")

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Action")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val newItem = LayoutStudioItem(
                                layoutName = "Temporary name",
                                selectedSources = mutableSetOf(),
                                selectedDetectors = mutableSetOf(),
                                layoutStatus = false
                            )
                            adapter.addItem(newItem)
                            saveLayoutItemsToDataStore(adapter.getItems())

                            lifecycleScope.launch {
                                layoutStore.saveOverlayElements(newItem.layoutName, emptyList())
                            }
                        }
                        1 -> {
                            pickLayoutJsonFile()
                        }
                    }
                }
                .show()
        }
    }

    private val filePickerRequestCode = 1001

    private fun pickLayoutJsonFile() {
        openJsonFileLauncher.launch(arrayOf("application/json"))
    }

    interface OnLayoutItemChangedListener {
        fun onLayoutItemUpdated(position: Int, item: LayoutStudioItem)
    }

    private fun saveLayoutItemsToDataStore(items: List<LayoutStudioItem>) {
        runBlocking {
            layoutStore.saveLayoutItems(items)
        }
    }

    private fun loadLayoutItemsFromDataStore(): MutableList<LayoutStudioItem> {
        return runBlocking {
            layoutStore.loadLayoutItems().toMutableList()
        }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            val updatedItems = layoutStore.loadLayoutItems()
            adapter.setItems(updatedItems.toMutableList())
        }
    }
}