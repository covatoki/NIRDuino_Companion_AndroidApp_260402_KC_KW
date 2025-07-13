package com.example.nirduino_android_app_v2.configure_streaming_files

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConfigureStreaming : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var adapter: ConfigurationCardAdapter
    private val configurationCards = mutableListOf<ConfigurationCardItem>()

    private val openJsonFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val json = reader.readText()
                try {
                    val gson = com.google.gson.Gson()

                    @Suppress("UNCHECKED_CAST")
                    val configMap = gson.fromJson(json, Map::class.java) as Map<String, Any>

                    val item = ConfigurationCardItem(
                        configName = configMap["configName"] as String,
                        numSubjects = (configMap["numSubjects"] as Double).toInt(),
                        numDevicesPerSubject = (configMap["numDevicesPerSubject"] as Double).toInt(),
                        lastUpdated = configMap["lastUpdated"] as String,
                        selectedDeviceNames = (configMap["selectedDeviceNames"] as List<*>).map { it.toString() }.toMutableList(),
                        selectedLayoutNames = (configMap["selectedLayoutNames"] as List<*>).map { it.toString() }.toMutableList()
                    )

                    configurationCards.add(item)
                    adapter.notifyItemInserted(configurationCards.size - 1)
                    saveConfigs()

                } catch (e: Exception) {
                    e.printStackTrace()
                    android.widget.Toast.makeText(this@ConfigureStreaming, "Failed to load configuration", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configure_streaming)

        recyclerView = findViewById(R.id.layoutRecyclerView)
        fab = findViewById(R.id.floatingActionButton)

        // Launch coroutine to load known devices and layouts before adapter setup
        lifecycleScope.launch {
            val deviceStore = KnownDeviceDataStore.getInstance(this@ConfigureStreaming)

            // ✅ Load device aliases from KnownDeviceDataStore
            val knownDeviceItems = deviceStore.getDevices().first()
            val knownDeviceNames = knownDeviceItems.map { it.alias }

            // ✅ Load layout names from LayoutDataStore
            val layoutItems = LayoutDataStore.getInstance(this@ConfigureStreaming).loadLayoutItems()
            val layoutNames = layoutItems.map { item -> item.layoutName }

            // ✅ Create and assign adapter
            adapter = ConfigurationCardAdapter(
                cards = configurationCards,
                knownDeviceNames = knownDeviceNames,
                layoutNames = layoutNames,
                onDuplicate = { item ->
                    val copy = item.copy(configName = "${item.configName} Copy")
                    configurationCards.add(copy)
                    adapter.notifyItemInserted(configurationCards.size - 1)
                    saveConfigs()
                },
                onDelete = { index ->
                    configurationCards.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    saveConfigs()
                },
                onUpdate = {
                    saveConfigs()
                }
            )

            recyclerView.layoutManager = LinearLayoutManager(this@ConfigureStreaming)
            recyclerView.adapter = adapter

            // ✅ Load saved configurations after adapter is ready
            loadSavedConfigs()

            fab.setOnClickListener {
                val options = arrayOf("Create Blank Configuration", "Load from File")

                val builder = android.app.AlertDialog.Builder(this@ConfigureStreaming)

                builder.setTitle("Add Configuration")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Blank
                                val newItem = ConfigurationCardItem(configName = "New Configuration")
                                configurationCards.add(newItem)
                                adapter.notifyItemInserted(configurationCards.size - 1)
                                saveConfigs()
                            }
                            1 -> { // Load from file
                                openJsonFileLauncher.launch(arrayOf("application/json"))
                            }
                        }
                    }
                    .show()
            }

        }
    }

    private fun saveConfigs() {
        lifecycleScope.launch {
            ConfigurationDataStore.saveConfigs(this@ConfigureStreaming, configurationCards)
        }
    }

    private fun loadSavedConfigs() {
        lifecycleScope.launch {
            val saved = ConfigurationDataStore.loadConfigs(this@ConfigureStreaming)
            configurationCards.clear()
            configurationCards.addAll(saved)
            adapter.notifyDataSetChanged()
            Log.d("UIUpdate", "Loaded ${configurationCards.size} cards from datastore")
        }
    }
}