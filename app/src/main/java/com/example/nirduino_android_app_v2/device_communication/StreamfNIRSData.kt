package com.example.nirduino_android_app_v2.device_communication

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.device_communication_management.StimulusEvent
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive

class StreamfNIRSData : AppCompatActivity() {

    private lateinit var aliasSpinner: Spinner
    private lateinit var layoutSpinner: Spinner
    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button

    private lateinit var knownDeviceStore: KnownDeviceDataStore
    private lateinit var layoutDataStore: LayoutDataStore

    private var selectedAlias: String? = null
    private var selectedLayoutName: String? = null
    private var isConnected = false

    private lateinit var streamToggleButton: Button
    private var isStreaming = false

    private lateinit var sqiOverlay: SignalQualityOverlay

    private var pollIntervalMs: Long = 300  // Adjustable polling interval in milliseconds
    private var sqiPollingJob: Job? = null

    data class StimulusLabel(
        val label: String,
        var isActive: Boolean = false,
        var onsetTime: Float = 0f
    )

    var ledIntensityValues: IntArray = intArrayOf(
        1,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255,  // regular power
        75, 64, 75, 64,
        75, 64, 75, 64,
        75, 64, 75, 64,
        75, 64, 75, 64
    ) // low power

    private lateinit var stimulusBar: LinearLayout
    private lateinit var addStimulusButton: Button
    private val stimulusLabels = mutableListOf<StimulusLabel>()

    private var fileToTransfer: File? = null
    private lateinit var createFileLauncher: ActivityResultLauncher<Intent>

    var channelDisplayData : List<DisplayChannelData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_fnirs_data)

        sqiOverlay = findViewById(R.id.sqi_overlay)
        aliasSpinner = findViewById(R.id.spinner_aliases)
        layoutSpinner = findViewById(R.id.spinner_layouts)
        statusTextView = findViewById(R.id.text_status)
        connectButton = findViewById(R.id.btn_connect)
        streamToggleButton = findViewById(R.id.btn_stream_toggle)
        streamToggleButton.isEnabled = false

        knownDeviceStore = KnownDeviceDataStore.getInstance(applicationContext)
        layoutDataStore = LayoutDataStore.getInstance(applicationContext)

        lifecycleScope.launch {
            setupAliasSpinner()
            setupLayoutSpinner()
        }

        stimulusBar = findViewById(R.id.stimulus_bar)
        addStimulusButton = findViewById(R.id.btn_add_stimulus)

        addStimulusButton.setOnClickListener {
            showAddStimulusDialog()
        }

        connectButton.setOnClickListener {
            val alias = selectedAlias
            if (alias == null || selectedLayoutName == null) {
                showErrorDialog("Please select both a device and a layout.")
                return@setOnClickListener
            }

            if (!isConnected) {
                attemptConnection(alias)

                // Update visuals
                stopPollingServiceData()
            } else {
                BLEConnectionManager.stopService(this)
                val redCircle = ContextCompat.getDrawable(this@StreamfNIRSData, R.drawable.red_circle)
                redCircle?.setBounds(0, 0, redCircle.intrinsicWidth, redCircle.intrinsicHeight)
                statusTextView.setCompoundDrawables(redCircle, null, null, null)
                statusTextView.compoundDrawablePadding = 12
                statusTextView.text = "Disconnected from $alias"
                isConnected = false
                connectButton.text = "Connect"

                // Stop connect data for updating visuals on-screen
                stopPollingServiceData()
                clearSignalQualityViews()

                // Updated data streaming button
                streamToggleButton.isEnabled = false
                streamToggleButton.text = "Start Streaming"
                isStreaming = false


            }
        }

        streamToggleButton.setOnClickListener {
            if (isStreaming) {
                BLEConnectionManager.stopStreamingFromDevice()
                streamToggleButton.text = "Start Streaming"
                isStreaming = false
                Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
                stopPollingServiceData()
            } else {
                BLEConnectionManager.startStreamFromDevice(ledIntensityValues)
                streamToggleButton.text = "Stop Streaming"
                isStreaming = true
                Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()
                startPollingServiceData()
            }

        }

    }

    private fun showAddStimulusDialog() {
        val input = EditText(this)
        input.hint = "Enter stimulus label"

        AlertDialog.Builder(this)
            .setTitle("Add Stimulus")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty()) {
                    addStimulusLabel(label)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addStimulusLabel(label: String) {
        val stim = StimulusLabel(label)
        stimulusLabels.add(stim)

        val button = Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener { toggleStimulus(stim, this) }
        }

        stimulusBar.addView(button, stimulusBar.childCount - 1) // Insert before "+ Add" button
    }

    private fun toggleStimulus(stimulus: StimulusLabel, button: Button) {
        stimulus.isActive = !stimulus.isActive

        // Update button color
        val colorRes = if (stimulus.isActive) R.color.teal_200 else R.color.gray
        button.setBackgroundColor(ContextCompat.getColor(this, colorRes))

        // Create and send the stimulus event (timestamp handled in the service)
        val event = StimulusEvent(
            label = stimulus.label,
            isStart = stimulus.isActive
        )

        BLEConnectionManager.broadcastStimulusEvent(event)

    }

    private fun startPollingServiceData() {
        sqiPollingJob?.cancel()  // kill old job
        sqiPollingJob = lifecycleScope.launch {
            Log.d("SQI_POLL", "⏳ Polling job started")

            while (isActive) {
                if (!isConnected) {
                    Log.w("SQI_POLL", "❌ Stopping polling: not connected")
                    break
                }

                val newSQI = BLEConnectionManager.getLatestSQIValues()
                if (!newSQI.isNullOrEmpty()) {
                    updateSignalQualityViews(newSQI)
                } else {
                    Log.w("SQI_POLL", "SQI list is empty or null")
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun clearSignalQualityViews() {
        sqiOverlay.updateSQI(emptyList())  // Clear SQI and redraw
    }

    private fun stopPollingServiceData() {
        sqiPollingJob?.cancel()
        sqiPollingJob = null
    }

    private fun updateSignalQualityViews(sqiList: List<Float>) {
        sqiOverlay.updateSQI(sqiList)
    }

    private suspend fun setupAliasSpinner() {
        val deviceList = knownDeviceStore.getDevices().first()
        val aliases = deviceList.map { it.alias }

        if (aliases.isEmpty()) {
            statusTextView.text = "No known devices found."
            connectButton.isEnabled = false
            return
        }

        aliasSpinner.adapter = ArrayAdapter(
            this@StreamfNIRSData,
            android.R.layout.simple_spinner_dropdown_item,
            aliases
        )

        aliasSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedAlias = aliases[position]
                statusTextView.text = "Selected: $selectedAlias"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private suspend fun setupLayoutSpinner() {
        val layoutMap = layoutDataStore.getAllLayoutsByName()
        val layoutNames = layoutMap.keys.toList()

        Log.d("LAYOUT_SPINNER", "Found ${layoutNames.size} layouts: $layoutNames")

        if (layoutNames.isEmpty()) {
            statusTextView.text = "No layouts found."
            layoutSpinner.isEnabled = false
            return
        }

        layoutSpinner.adapter = ArrayAdapter(
            this@StreamfNIRSData,
            android.R.layout.simple_spinner_dropdown_item,
            layoutNames
        )

        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLayoutName = layoutNames[position]
                Log.d("LAYOUT_SPINNER", "Selected layout: $selectedLayoutName")
                statusTextView.text = "Selected layout: $selectedLayoutName"

                // Show the layout on screen
                lifecycleScope.launch {

                    val overlays = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
                    val sources = overlays.filter { it.isSource }
                    val detectors = overlays.filter { !it.isSource }

                    val channelCoords = computeChannelCoordinates(overlays)

                    sqiOverlay.setOverlayData(
                        sourceList = sources,
                        detectorList = detectors,
                        channelList = channelCoords
                    )

                    Log.d("LAYOUT_SPINNER", "Overlay updated with ${sources.size} sources and ${detectors.size} detectors")

                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d("LAYOUT_SPINNER", "Nothing selected")
            }
        }
    }


    private fun attemptConnection(alias: String) {
        statusTextView.text = "Connecting to $alias..."

        if (!isBluetoothEnabled()) {
            showErrorDialog("Bluetooth is disabled. Please enable it and try again.")
            return
        }

        lifecycleScope.launch {

            val overlays = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
            val gson = Gson()
            val layoutJson = gson.toJson(overlays)
            BLEConnectionManager.startService(this@StreamfNIRSData, alias, layoutJson)

            channelDisplayData = BLEConnectionManager.getChannelDisplayData()
            Log.d("CHANNEL_DATA", "Loaded ${channelDisplayData.size} channels")

            // Combine all overlay elements and channel coordinates
            val sources = overlays.filter { it.isSource }
            val detectors = overlays.filter { !it.isSource }

            val channelCoords = computeChannelCoordinates(overlays)

            // Normalize all together using SignalQualityOverlay's internal logic
            sqiOverlay.setOverlayData(
                sourceList = sources,
                detectorList = detectors,
                channelList = channelCoords
            )

        }

        lifecycleScope.launch {
            delay(1000)
            repeat(20) {
                if (BLEConnectionManager.getStreamReadinessStatus()) {

                    val greenCircle = ContextCompat.getDrawable(this@StreamfNIRSData, R.drawable.green_circle)
                    greenCircle?.setBounds(0, 0, greenCircle.intrinsicWidth, greenCircle.intrinsicHeight)
                    statusTextView.setCompoundDrawables(greenCircle, null, null, null)
                    statusTextView.compoundDrawablePadding = 12
                    statusTextView.text = "Connected to $alias"

                    // Enable the button
                    streamToggleButton.isEnabled = true
                    streamToggleButton.text = "Start Streaming"

                    // Log to terminal
                    Log.i("StreamfNIRSData", "✅ Ready to stream data from $alias")
                    isConnected = true
                    connectButton.text = "Disconnect"



                    return@launch

                } else {
                    statusTextView.setCompoundDrawables(null, null, null, null)
                    statusTextView.text = "Connecting to $alias... ($it)"
                    stopPollingServiceData()
                    clearSignalQualityViews()

                }
                delay(500)
            }

            // Connection failed
            statusTextView.setCompoundDrawables(null, null, null, null)
            statusTextView.text = "❌ Failed to connect to $alias"
            showErrorDialog("Could not connect to $alias. Please ensure it is powered on and has sufficient battery.")

            // Update on-screen data
            stopPollingServiceData()
            clearSignalQualityViews()

        }

    }

    private fun computeChannelCoordinates(overlays: List<OverlayElement>): List<Triple<Float, Float, Int>> {
        val sources = overlays.filter { it.isSource }
        val detectors = overlays.filter { !it.isSource }

        val result = mutableListOf<Triple<Float, Float, Int>>()
        var channelIndex = 0

        for (source in sources) {
            for (detector in detectors) {
                val dx = source.x - detector.x
                val dy = source.y - detector.y
                val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())

                if (distance in 28.0..31.0 || distance <= 15.0) {
                    val xMid = (source.x + detector.x) / 2f
                    val yMid = (source.y + detector.y) / 2f
                    result.add(Triple(xMid, yMid, channelIndex++))
                }
            }
        }

        return result
    }


    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        return adapter?.isEnabled == true
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()

        stopPollingServiceData()

        if (isConnected) {
            BLEConnectionManager.stopService(this)
            Log.i("StreamfNIRSData", "Foreground BLE service stopped on activity destroy")
        }

        streamToggleButton.isEnabled = false
        isStreaming = false

    }


}
