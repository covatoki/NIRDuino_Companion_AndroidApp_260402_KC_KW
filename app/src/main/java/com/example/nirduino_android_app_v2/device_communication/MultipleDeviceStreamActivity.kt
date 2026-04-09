package com.example.nirduino_android_app_v2.device_communication

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.device_communication_management.StimulusEvent
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.google.gson.Gson
import kotlinx.coroutines.*

class MultiDeviceStreamActivity : AppCompatActivity() {

    // ── UI references ─────────────────────────────────────────────────────
    private lateinit var layoutSpinner: Spinner
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var connectAllButton: Button
    private lateinit var streamToggleButton: Button
    private lateinit var deviceCardContainer: LinearLayout
    private lateinit var globalStatusText: TextView

    // ── Data stores ───────────────────────────────────────────────────────
    private lateinit var knownDeviceStore: KnownDeviceDataStore
    private lateinit var layoutDataStore: LayoutDataStore

    // ── State ─────────────────────────────────────────────────────────────
    private var selectedLayoutName: String? = null
    private val checkedAliases = mutableSetOf<String>()
    private var isConnected = false
    private var isStreaming = false

    // alias → card root view
    private val deviceCardViews = mutableMapOf<String, View>()

    // alias → channel coords loaded from the service
    private val deviceChannelCoords = mutableMapOf<String, List<DisplayChannelData>>()

    // alias → rolling plot buffers
    private val deviceTsBuffers  = mutableMapOf<String, ArrayDeque<Float>>()
    private val deviceRedBuffers = mutableMapOf<String, ArrayDeque<Float>>()
    private val deviceIrBuffers  = mutableMapOf<String, ArrayDeque<Float>>()

    // alias → which spinner index is currently selected
    private val deviceSpinnerIndex = mutableMapOf<String, Int>()

    private var pollingJob: Job? = null

    private val maxPoints = 90
    private val PLOT_UPDATE_INTERVAL_MS = 200L
    private var lastPlotUpdateTime = 0L

    private val ledIntensityValues = intArrayOf(
        1,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255,
        80, 78, 80, 78,
        80, 78, 80, 78,
        80, 78, 80, 78,
        80, 78, 80, 78
    )

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_device_stream)

        layoutSpinner       = findViewById(R.id.spinner_layouts)
        deviceListContainer = findViewById(R.id.container_device_list)
        connectAllButton    = findViewById(R.id.btn_connect_all)
        streamToggleButton  = findViewById(R.id.btn_stream_toggle)
        deviceCardContainer = findViewById(R.id.container_device_cards)
        globalStatusText    = findViewById(R.id.text_global_status)

        streamToggleButton.isEnabled = false

        knownDeviceStore = KnownDeviceDataStore.getInstance(applicationContext)
        layoutDataStore  = LayoutDataStore.getInstance(applicationContext)

        lifecycleScope.launch {
            loadLayoutSpinner()
            loadDeviceCheckboxes()
        }

        connectAllButton.setOnClickListener { onConnectAllClicked() }
        streamToggleButton.setOnClickListener { onStreamToggleClicked() }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        if (isConnected) {
            BLEConnectionManager.stopService(this, "MultiDeviceStreamActivity destroyed")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Layout spinner
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun loadLayoutSpinner() {
        val layoutMap   = layoutDataStore.getAllLayoutsByName()
        val layoutNames = layoutMap.keys.toList()

        layoutSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            layoutNames
        )

        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLayoutName = layoutNames[position]
                BLEConnectionManager.setLayoutName(selectedLayoutName!!)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (layoutNames.isNotEmpty()) {
            selectedLayoutName = layoutNames[0]
            BLEConnectionManager.setLayoutName(selectedLayoutName!!)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Device checkboxes
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun loadDeviceCheckboxes() {
        val aliasToMac = knownDeviceStore.getAllDeviceAliasesWithMac()
        deviceListContainer.removeAllViews()

        if (aliasToMac.isEmpty()) {
            deviceListContainer.addView(TextView(this).apply { text = "No saved devices found." })
            return
        }

        for (alias in aliasToMac.keys) {
            val cb = CheckBox(this).apply {
                text = alias
                setOnCheckedChangeListener { _, checked ->
                    if (checked) checkedAliases.add(alias) else checkedAliases.remove(alias)
                }
            }
            deviceListContainer.addView(cb)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Connect / disconnect
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun onConnectAllClicked() {
        if (isConnected) { disconnectAll(); return }

        val aliases = checkedAliases.toList()
        if (aliases.isEmpty()) {
            Toast.makeText(this, "Please select at least one device.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLayoutName == null) {
            Toast.makeText(this, "Please select a layout.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isBluetoothEnabled()) {
            showErrorDialog("Bluetooth is disabled.")
            return
        }

        layoutSpinner.isEnabled    = false
        layoutSpinner.alpha        = 0.75f
        connectAllButton.isEnabled = false
        globalStatusText.text      = "Connecting to ${aliases.size} device(s)…"

        lifecycleScope.launch {
            val overlays   = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
            val layoutJson = Gson().toJson(overlays)

            BLEConnectionManager.startService(
                context    = this@MultiDeviceStreamActivity,
                aliases    = aliases,
                layoutJson = layoutJson,
                layoutName = selectedLayoutName!!
            )

            inflateDeviceCards(aliases)
            waitForAllConnections(aliases)
        }
    }

    private fun inflateDeviceCards(aliases: List<String>) {
        deviceCardContainer.removeAllViews()
        deviceCardViews.clear()
        deviceChannelCoords.clear()
        deviceTsBuffers.clear()
        deviceRedBuffers.clear()
        deviceIrBuffers.clear()
        deviceSpinnerIndex.clear()

        for (alias in aliases) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_device_status_card, deviceCardContainer, false)

            card.findViewById<TextView>(R.id.text_device_alias).text    = alias
            card.findViewById<TextView>(R.id.text_device_status).text   = "Connecting…"
            card.findViewById<TextView>(R.id.text_device_rssi).text     = "RSSI: —"
            card.findViewById<TextView>(R.id.text_device_battery).text  = "Battery: —"
            card.findViewById<TextView>(R.id.text_device_datarate).text = "Samples: —"

            val plot = card.findViewById<ChannelPlotView>(R.id.channel_plot_view)
            plot.windowSeconds = 10f

            deviceTsBuffers[alias]    = ArrayDeque()
            deviceRedBuffers[alias]   = ArrayDeque()
            deviceIrBuffers[alias]    = ArrayDeque()
            deviceSpinnerIndex[alias] = 0

            deviceCardContainer.addView(card)
            deviceCardViews[alias] = card
            Log.d(TAG, "Card inflated for $alias")
        }
    }

    private suspend fun waitForAllConnections(aliases: List<String>) {
        val maxRetries = 40  // 40 × 500 ms = 20 s
        repeat(maxRetries) { attempt ->
            delay(500)
            val statuses = BLEConnectionManager.getConnectionStatuses()
            statuses.forEach { (alias, connected) -> updateCardStatus(alias, connected) }
            Log.d(TAG, "Poll #$attempt → $statuses")

            if (BLEConnectionManager.getStreamReadinessStatus()) {
                onAllConnected(aliases)
                return
            }
            globalStatusText.text =
                "Connecting… (${statuses.values.count { it }}/${aliases.size} ready)"
        }

        globalStatusText.text = "❌ Not all devices connected after 20 s"
        showErrorDialog("Could not connect to all selected devices.")
        connectAllButton.isEnabled = true
        layoutSpinner.isEnabled    = true
    }

    private fun onAllConnected(aliases: List<String>) {
        Log.i(TAG, "✅ All ${aliases.size} device(s) connected")
        isConnected = true
        globalStatusText.text        = "✅ Connected: ${aliases.joinToString(", ")}"
        connectAllButton.text        = "Disconnect All"
        connectAllButton.isEnabled   = true
        streamToggleButton.isEnabled = true
        streamToggleButton.text      = "Start Streaming"
        BLEConnectionManager.getDeviceBatteryLevel()

        lifecycleScope.launch { populateChannelSpinners() }
    }

    /**
     * Loads channel coords from the service and wires up each card's spinner.
     * Matches exactly what StreamfNIRSData does in layoutInit().
     */
    private suspend fun populateChannelSpinners() {
        val coords = BLEConnectionManager.getChannelDisplayData()
        if (coords.isEmpty()) {
            Log.w(TAG, "Channel coords empty — will retry during polling")
            return
        }

        val labels = coords.map {
            "Ch ${it.channelNumber + 1} (${it.type}) S${it.sourceId}:D${it.detectorId}"
        }

        for ((alias, card) in deviceCardViews) {
            deviceChannelCoords[alias] = coords

            val spinner = card.findViewById<Spinner>(R.id.spinner_channels)
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    deviceSpinnerIndex[alias] = position
                    deviceTsBuffers[alias]?.clear()
                    deviceRedBuffers[alias]?.clear()
                    deviceIrBuffers[alias]?.clear()
                    Log.d(TAG, "[$alias] Channel switched to position $position")
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            Log.d(TAG, "[$alias] Spinner populated with ${labels.size} channels")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAll() {
        pollingJob?.cancel()
        isStreaming = false
        isConnected = false
        BLEConnectionManager.stopService(this, "User disconnected")

        globalStatusText.text        = "Disconnected"
        streamToggleButton.isEnabled = false
        streamToggleButton.text      = "Start Streaming"
        connectAllButton.text        = "Connect All"
        layoutSpinner.isEnabled      = true
        layoutSpinner.alpha          = 1f
        connectAllButton.isEnabled   = true

        deviceCardViews.forEach { (alias, card) ->
            card.findViewById<TextView>(R.id.text_device_status).text = "Disconnected"
            Log.d(TAG, "[$alias] marked disconnected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Streaming
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun onStreamToggleClicked() {
        if (!isStreaming) {
            val layoutName = selectedLayoutName ?: return
            BLEConnectionManager.startStreamFromDevice(ledIntensityValues, layoutName)
            isStreaming = true
            streamToggleButton.text = "Stop Streaming"
            Log.i(TAG, "▶ Streaming started")
            startPolling()
        } else {
            BLEConnectionManager.stopStreamingFromDevice()
            isStreaming = false
            streamToggleButton.text = "Start Streaming"
            Log.i(TAG, "⏹ Streaming stopped")
            pollingJob?.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Polling — mirrors StreamfNIRSData.startPollingServiceData() exactly
    // ─────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.Default) {

            Log.d(TAG, "Polling engine started")

            while (isActive && isConnected) {
                val now = System.currentTimeMillis()

                // 1. Snapshot UI-owned state on main thread
                val (snapshotCoords, snapshotIndices) = withContext(Dispatchers.Main) {
                    Pair(deviceChannelCoords.toMap(), deviceSpinnerIndex.toMap())
                }

                // 2. Fetch data from BLE service (background thread is fine)
                val rawMap  = BLEConnectionManager.getLatestfNIRSData(maxPoints)
                val rssiMap = BLEConnectionManager.readLatestSignalLevels()
                val battMap = BLEConnectionManager.readLatestBatteryLevels()
                BLEConnectionManager.requestConnectionSignalLevel()

                Log.d(TAG, "Poll: rawMap keys=${rawMap.keys}")

                // rawMap is keyed by MAC address; aliasList preserves insertion order
                // which matches the order BLEConnectionManager found the devices.
                val macList   = rawMap.keys.toList()
                val aliasList = deviceCardViews.keys.toList()

                // 3. Process each device on background thread
                for (i in aliasList.indices) {
                    val alias = aliasList[i]
                    val mac   = macList.getOrNull(i) ?: continue
                    val fNIRS = rawMap[mac] ?: continue

                    val coords       = snapshotCoords[alias] ?: continue
                    val spinnerIndex = snapshotIndices[alias] ?: 0

                    if (fNIRS.isEmpty() || coords.isEmpty()) continue

                    val lastRound = fNIRS.last()
                    if (lastRound.timestamps.isEmpty()) continue

                    // Use channelNumber as the data vector index — same as StreamfNIRSData
                    val ch   = if (spinnerIndex in coords.indices) coords[spinnerIndex] else coords[0]
                    val chId = ch.channelNumber

                    val latestRed = lastRound.redData.lastOrNull() ?: continue
                    val latestIr  = lastRound.irData.lastOrNull()  ?: continue

                    if (chId !in latestRed.indices || chId !in latestIr.indices) {
                        Log.w(TAG, "[$alias] chId=$chId out of range (redVec=${latestRed.size})")
                        continue
                    }

                    val ts  = lastRound.timestamps.last()
                    val red = latestRed[chId]
                    val ir  = latestIr[chId]

                    val tsBuf  = deviceTsBuffers[alias]  ?: continue
                    val redBuf = deviceRedBuffers[alias] ?: continue
                    val irBuf  = deviceIrBuffers[alias]  ?: continue

                    tsBuf.add(ts);  redBuf.add(red);  irBuf.add(ir)
                    while (tsBuf.size  > maxPoints) tsBuf.removeFirst()
                    while (redBuf.size > maxPoints) redBuf.removeFirst()
                    while (irBuf.size  > maxPoints) irBuf.removeFirst()

                    Log.d(TAG, "[$alias] ch=$chId ts=$ts red=$red ir=$ir buf=${tsBuf.size}")
                }

                // 4. Update UI on main thread
                withContext(Dispatchers.Main) {

                    // If spinners weren't ready at connect time, retry now
                    if (deviceChannelCoords.values.any { it.isEmpty() }) {
                        populateChannelSpinners()
                    }

                    for (i in aliasList.indices) {
                        val alias = aliasList[i]
                        val card  = deviceCardViews[alias] ?: continue

                        // Connection status
                        val connected = BLEConnectionManager.getConnectionStatuses()[alias] ?: false
                        updateCardStatus(alias, connected)

                        // RSSI
                        val rssi = rssiMap[alias]
                        if (rssi != null && rssi != 0)
                            card.findViewById<TextView>(R.id.text_device_rssi).text = "RSSI: $rssi dBm"

                        // Battery
                        val batt = battMap[alias]
                        if (batt != null && batt > 0)
                            card.findViewById<TextView>(R.id.text_device_battery).text = "Battery: $batt%"

                        // Sample count
                        card.findViewById<TextView>(R.id.text_device_datarate).text =
                            "Samples: ${deviceTsBuffers[alias]?.size ?: 0}"

                        // Plot (throttled to avoid overdrawing)
                        if (now - lastPlotUpdateTime > PLOT_UPDATE_INTERVAL_MS) {
                            val plot    = card.findViewById<ChannelPlotView>(R.id.channel_plot_view)
                            val tsList  = deviceTsBuffers[alias]?.toList()  ?: emptyList()
                            val redList = deviceRedBuffers[alias]?.toList() ?: emptyList()
                            val irList  = deviceIrBuffers[alias]?.toList()  ?: emptyList()

                            if (tsList.size >= 2) {
                                plot.updateData(tsList, redList, irList)
                            }
                        }
                    }

                    if (now - lastPlotUpdateTime > PLOT_UPDATE_INTERVAL_MS) {
                        lastPlotUpdateTime = now
                    }
                }

                delay(300)
            }

            Log.d(TAG, "Polling engine stopped")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun updateCardStatus(alias: String, connected: Boolean) {
        val card = deviceCardViews[alias] ?: return
        val tv   = card.findViewById<TextView>(R.id.text_device_status)
        tv.text = if (connected) "Connected ✅" else "Disconnected ❌"
        tv.setTextColor(
            if (connected)
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
    }

    private fun isBluetoothEnabled(): Boolean {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter?.isEnabled == true
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val TAG = "MultiDeviceStream"
    }
}