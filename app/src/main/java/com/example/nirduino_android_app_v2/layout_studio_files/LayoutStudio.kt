package com.example.nirduino_android_app_v2.layout_studio_files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.widget.Toast
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class LayoutStudio : AppCompatActivity() {

    private lateinit var adapter: LayoutStudioAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton

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
                    val jsonMap = Gson().fromJson(json, Map::class.java)

                    // Convert layout portion
                    val layoutItemJson = Gson().toJson(jsonMap)
                    val importedItem = Gson().fromJson(layoutItemJson, LayoutStudioItem::class.java)

                    // Add layout to RecyclerView and save
                    adapter.addItem(importedItem)
                    saveLayoutItemsToDataStore(adapter.getItems())

                    // Parse overlay elements
                    val overlayList = (jsonMap["overlayElements"] as? List<Map<String, Any>>)?.map {
                        OverlayElement(
                            id = (it["id"] as Double).toInt(),
                            isSource = it["isSource"] as Boolean,
                            x = (it["x"] as Double).toFloat(),
                            y = (it["y"] as Double).toFloat()
                        )
                    } ?: emptyList()

                    // ✅ Save overlays in a coroutine
                    lifecycleScope.launch {
                        LayoutDataStore.saveOverlayElements(
                            this@LayoutStudio,
                            importedItem.layoutName,
                            overlayList
                        )
                    }

                    Toast.makeText(this, "Layout imported successfully", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Invalid file format", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_studio)

        recyclerView = findViewById(R.id.layoutRecyclerView)
        fab = findViewById(R.id.floatingActionButton)

        // Load saved layout items from the DataStore
        val loadedItems = loadLayoutItemsFromDataStore()

        adapter = LayoutStudioAdapter(this, loadedItems, object : OnLayoutItemChangedListener {
            override fun onLayoutItemUpdated(position: Int, item: LayoutStudioItem) {
                saveLayoutItemsToDataStore(adapter.getItems())
            }
        })
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        fab.setOnClickListener {

            val options = arrayOf("Create blank layout", "Import from file")

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Action")
                .setItems(options) { _, which ->
                    when (which) {

                        0 -> { // Create blank layout
                            val newItem = LayoutStudioItem(
                                layoutName = "Temporary name",
                                selectedSources = mutableSetOf(),
                                selectedDetectors = mutableSetOf(),
                                layoutStatus = false
                            )
                            adapter.addItem(newItem)
                            saveLayoutItemsToDataStore(adapter.getItems())

                            // ✅ Launch coroutine for suspend call
                            lifecycleScope.launch {
                                LayoutDataStore.saveOverlayElements(this@LayoutStudio, newItem.layoutName, emptyList())
                            }
                        }

                        1 -> { // Import from file
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
        val json = Gson().toJson(items)
        runBlocking {
            // Save using the single LayoutDataStore instance for LayoutStudioItems
            LayoutDataStore.saveLayoutItems(applicationContext, items)
        }
    }

    private fun loadLayoutItemsFromDataStore(): MutableList<LayoutStudioItem> {
        return runBlocking {
            // Load using the single LayoutDataStore instance for LayoutStudioItems
            val items = LayoutDataStore.loadLayoutItems(applicationContext)
            items.toMutableList()
        }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            val updatedItems = LayoutDataStore.loadLayoutItems(this@LayoutStudio)
            adapter.setItems(updatedItems.toMutableList()) // You'll need this helper method
        }
    }

}
