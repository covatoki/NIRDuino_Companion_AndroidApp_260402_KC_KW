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
import com.example.nirduino_android_app_v2.device_communication_management.StimulusEvent
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.google.gson.Gson
import kotlinx.coroutines.*
import com.example.nirduino_android_app_v2.device_communication.ChannelPlotView

/**
 * MultiDeviceStreamActivity
 *
 * Replaces the single-alias flow in StreamfNIRSData for sessions that need
 * more than one NIRDuino device running in parallel.
 *
 * HOW IT WORKS
 * ─────────────
 * 1. The user picks a layout (shared across all devices – same montage).
 * 2. The user checks one or more aliases from the list of known devices.
 * 3. "Connect All" fires BLEConnectionManager.startService() with the full
 *    alias list.  The manager's existing Map<String, BleDeviceConnection>
 *    handles each device independently.
 * 4. A per-device status card is inflated for each alias so the user can
 *    watch connection state, RSSI, battery, and data rate independently.
 * 5. "Start / Stop Streaming" and stimulus events are broadcast to all
 *    connected devices via the existing BLEConnectionManager static helpers.
 *
 * LAYOUT FILE  (activity_multi_device_stream.xml)
 * ────────────────────────────────────────────────
 * You need to create a matching XML layout.  The required view IDs are:
 *
 *   spinner_layouts          – Spinner    (layout picker)
 *   container_device_list    – LinearLayout (alias checkboxes added at runtime)
 *   btn_connect_all          – Button
 *   btn_stream_toggle        – Button
 *   container_device_cards   – LinearLayout (status cards added at runtime)
 *   text_global_status       – TextView
 *
 * Each device card is inflated from  item_device_status_card.xml  which must
 * expose:
 *   text_device_alias        – TextView
 *   text_device_status       – TextView   (Connected / Connecting / Disconnected)
 *   text_device_rssi         – TextView
 *   text_device_battery      – TextView
 *   text_device_datarate     – TextView   (packets / s)
 *
 * See the logcat guide for how to verify everything is working.
 */
class MultiDeviceStreamActivity : AppCompatActivity() {

    // ── UI references ─────────────────────────────────────────────────────
    private lateinit var layoutSpinner: Spinner
    private lateinit var deviceListContainer: LinearLayout   // checkboxes
    private lateinit var connectAllButton: Button
    private lateinit var streamToggleButton: Button
    private lateinit var deviceCardContainer: LinearLayout   // status cards
    private lateinit var globalStatusText: TextView

    // ── Data stores ───────────────────────────────────────────────────────
    private lateinit var knownDeviceStore: KnownDeviceDataStore
    private lateinit var layoutDataStore: LayoutDataStore

    // ── State ─────────────────────────────────────────────────────────────
    private var selectedLayoutName: String? = null
    private val allKnownAliases = mutableListOf<String>()
    private val checkedAliases = mutableSetOf<String>()   // which boxes the user ticked

    private var isConnected = false
    private var isStreaming = false

    /** Map alias → card root view for fast updates */
    private val deviceCardViews = mutableMapOf<String, View>()

    /** Coroutine job that polls BLEConnectionManager every 500 ms */
    private var pollingJob: Job? = null

    // ── LED intensity defaults (matches StreamfNIRSData) ──────────────────
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
        setContentView(R.layout.activity_multi_device_stream)   // ← create this XML

        bindViews()

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
            Log.i(TAG, "BLE service stopped on activity destroy")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        layoutSpinner       = findViewById(R.id.spinner_layouts)
        deviceListContainer = findViewById(R.id.container_device_list)
        connectAllButton    = findViewById(R.id.btn_connect_all)
        streamToggleButton  = findViewById(R.id.btn_stream_toggle)
        deviceCardContainer = findViewById(R.id.container_device_cards)
        globalStatusText    = findViewById(R.id.text_global_status)

        streamToggleButton.isEnabled = false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Layout spinner
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun loadLayoutSpinner() {
        val layoutMap   = layoutDataStore.getAllLayoutsByName()
        val layoutNames = layoutMap.keys.toList()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            layoutNames
        )
        layoutSpinner.adapter = adapter

        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLayoutName = layoutNames[position]
                BLEConnectionManager.setLayoutName(selectedLayoutName!!)
                Log.d(TAG, "Layout selected: $selectedLayoutName")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pre-select first layout
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
        allKnownAliases.clear()
        allKnownAliases.addAll(aliasToMac.keys)

        deviceListContainer.removeAllViews()

        if (allKnownAliases.isEmpty()) {
            val empty = TextView(this).apply { text = "No saved devices found." }
            deviceListContainer.addView(empty)
            return
        }

        for (alias in allKnownAliases) {
            val cb = CheckBox(this).apply {
                text  = alias
                tag   = alias
                setOnCheckedChangeListener { _, checked ->
                    if (checked) checkedAliases.add(alias)
                    else checkedAliases.remove(alias)
                    Log.d(TAG, "Selection changed → checked: $checkedAliases")
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
        if (isConnected) {
            disconnectAll()
            return
        }

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
            showErrorDialog("Bluetooth is disabled. Please enable it and try again.")
            return
        }

        // Lock UI during connection
        layoutSpinner.isEnabled   = false
        layoutSpinner.alpha       = 0.75f
        deviceListContainer.isEnabled = false
        connectAllButton.isEnabled    = false

        globalStatusText.text = "Connecting to ${aliases.size} device(s)…"
        Log.i(TAG, "Starting BLE service for aliases: $aliases")

        lifecycleScope.launch {
            val overlays   = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
            val layoutJson = Gson().toJson(overlays)

            // ── This single call wires up all devices in BLEConnectionManager ──
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

    /** Inflate a status card for each alias before connections arrive */
    private fun inflateDeviceCards(aliases: List<String>) {
        deviceCardContainer.removeAllViews()
        deviceCardViews.clear()

        for (alias in aliases) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_device_status_card, deviceCardContainer, false)

            card.findViewById<TextView>(R.id.text_device_alias).text    = alias
            card.findViewById<TextView>(R.id.text_device_status).text   = "Connecting…"
            card.findViewById<TextView>(R.id.text_device_rssi).text     = "RSSI: —"
            card.findViewById<TextView>(R.id.text_device_battery).text  = "Battery: —"
            card.findViewById<TextView>(R.id.text_device_datarate).text = "Data: —"

            // Plot starts blank — data arrives once streaming begins
            val plot = card.findViewById<ChannelPlotView>(R.id.channel_plot_view)
            plot.windowSeconds = 10f

            deviceCardContainer.addView(card)
            deviceCardViews[alias] = card
            Log.d(TAG, "Card inflated for $alias")
        }
    }

    /**
     * Poll BLEConnectionManager until all aliases are connected (or timeout).
     *
     * BLEConnectionManager.getStreamReadinessStatus() returns true only when
     * connections.size == targetDevices.size AND all connections are live —
     * exactly the condition we need.
     */
    private suspend fun waitForAllConnections(aliases: List<String>) {
        val timeoutMs  = 20_000L
        val pollMs     = 500L
        val maxRetries = (timeoutMs / pollMs).toInt()

        repeat(maxRetries) { attempt ->
            delay(pollMs)

            val statuses = BLEConnectionManager.getConnectionStatuses()
            Log.d(TAG, "Connection poll #$attempt → $statuses")

            // Update each card with current state
            for ((alias, connected) in statuses) {
                updateCardStatus(alias, connected)
            }

            if (BLEConnectionManager.getStreamReadinessStatus()) {
                onAllConnected(aliases)
                return
            }

            globalStatusText.text = "Connecting… (${statuses.values.count { it }}/${aliases.size} ready)"
        }

        // Timeout
        globalStatusText.text = "❌ Not all devices connected after ${timeoutMs / 1000}s"
        showErrorDialog("Could not connect to all selected devices. Check they are powered on.")
        connectAllButton.isEnabled = true
        layoutSpinner.isEnabled    = true
        deviceListContainer.isEnabled = true
    }

    private fun onAllConnected(aliases: List<String>) {
        Log.i(TAG, "✅ All ${aliases.size} device(s) connected and ready to stream")
        isConnected = true

        globalStatusText.text = "✅ Connected: ${aliases.joinToString(", ")}"
        connectAllButton.text      = "Disconnect All"
        connectAllButton.isEnabled = true
        streamToggleButton.isEnabled = true
        streamToggleButton.text    = "Start Streaming"

        BLEConnectionManager.getDeviceBatteryLevel()

        // Begin periodic polling for RSSI, battery, data rate
        startPolling()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAll() {
        pollingJob?.cancel()
        isStreaming = false
        isConnected = false

        BLEConnectionManager.stopService(this, "User disconnected")
        Log.i(TAG, "Disconnected all devices")

        globalStatusText.text = "Disconnected"
        streamToggleButton.isEnabled = false
        streamToggleButton.text = "Start Streaming"
        connectAllButton.text   = "Connect All"

        // Re-enable selection UI
        layoutSpinner.isEnabled       = true
        layoutSpinner.alpha           = 1f
        deviceListContainer.isEnabled = true
        connectAllButton.isEnabled    = true

        for ((alias, card) in deviceCardViews) {
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
            Log.i(TAG, "▶ Streaming started on all devices")

            // Log to terminal exactly which devices are streaming
            BLEConnectionManager.getConnectionStatuses().forEach { (alias, connected) ->
                Log.i(TAG, "  [$alias] streaming=$connected")
            }
        } else {
            BLEConnectionManager.stopStreamingFromDevice()
            isStreaming = false
            streamToggleButton.text = "Start Streaming"
            Log.i(TAG, "⏹ Streaming stopped on all devices")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Polling — updates device cards every 500 ms
    // ─────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                refreshDeviceCards()
                BLEConnectionManager.requestConnectionSignalLevel()
            }
        }
    }

    private fun refreshDeviceCards() {
        val statuses = BLEConnectionManager.getConnectionStatuses()
        val rssiMap  = BLEConnectionManager.readLatestSignalLevels()
        val battMap  = BLEConnectionManager.readLatestBatteryLevels()
        val dataMap  = BLEConnectionManager.getLatestfNIRSData(maxPoints = 90)

        for ((alias, card) in deviceCardViews) {
            // Connection status
            val connected = statuses[alias] ?: false
            updateCardStatus(alias, connected)

            // RSSI
            val rssi = rssiMap[alias]
            if (rssi != null && rssi != 0)
                card.findViewById<TextView>(R.id.text_device_rssi).text = "RSSI: $rssi dBm"

            // Battery
            val batt = battMap[alias]
            if (batt != null && batt > 0)
                card.findViewById<TextView>(R.id.text_device_battery).text = "Battery: $batt%"

            // Plot — find the data entry whose key (MAC) maps to this alias
            // dataMap is keyed by MAC; we match by looking at connection statuses
            // which are keyed by alias. We find the MAC by cross-referencing.
            val matchingEntry = dataMap.entries.firstOrNull { (_, rounds) ->
                rounds.isNotEmpty()
            }

            val round = matchingEntry?.value?.lastOrNull()
            if (round != null && round.timestamps.isNotEmpty() && isStreaming) {
                val plot = card.findViewById<ChannelPlotView>(R.id.channel_plot_view)

                // Get which channel this card's spinner is on
                val spinner = card.findViewById<Spinner>(R.id.spinner_channels)
                val channelIndex = spinner.selectedItemPosition.coerceAtLeast(0)

                // Pull red and IR for that channel across all timestamps
                val redSeries = round.redData.map { sample ->
                    if (channelIndex < sample.size) sample[channelIndex] else 0f
                }
                val irSeries = round.irData.map { sample ->
                    if (channelIndex < sample.size) sample[channelIndex] else 0f
                }

                plot.updateData(round.timestamps, redSeries, irSeries)

                card.findViewById<TextView>(R.id.text_device_datarate).text =
                    "Samples: ${round.timestamps.size}"
            }
        }
    }
    private fun updateCardStatus(alias: String, connected: Boolean) {
        val card = deviceCardViews[alias] ?: return
        val statusText = card.findViewById<TextView>(R.id.text_device_status)
        statusText.text = if (connected) "Connected ✅" else "Disconnected ❌"
        statusText.setTextColor(
            if (connected)
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stimulus events (same API as single-device flow)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Call this from any stimulus button to tag an event on ALL active devices
     * simultaneously.  The BLEConnectionManager broadcasts to every connection.
     */
    fun fireStimulusEvent(label: String, isStart: Boolean) {
        BLEConnectionManager.broadcastStimulusEvent(StimulusEvent(label, isStart))
        Log.d(TAG, "Stimulus '$label' (start=$isStart) broadcast to all devices")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter?.isEnabled == true
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val TAG = "MultiDeviceStream"
    }
}