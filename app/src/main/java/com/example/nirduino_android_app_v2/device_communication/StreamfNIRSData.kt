package com.example.nirduino_android_app_v2.device_communication

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.example.nirduino_android_app_v2.device_communication_management.ChannelType
import com.example.nirduino_android_app_v2.device_communication_management.DataRound
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.device_communication_management.StimulusEvent
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutStudioItem
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.isActive
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StreamfNIRSData : AppCompatActivity() {

    private lateinit var aliasSpinner: Spinner
    private lateinit var layoutSpinner: Spinner
    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button

    private lateinit var onScreenTimer: TextView

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

    private var signalQualityIndicator: ImageView? = null
    private var batteryLevelIndicator: ImageView? = null

    private lateinit var experimentalNotes: EditText
    private lateinit var autosetLEDs: Button

    data class StimulusLabel(
        val label: String,
        var isActive: Boolean = false,
        var onsetTime: Float = 0f
    )

    var fNIRSData: List<DataRound> = emptyList()

    var ledIntensityValues = intArrayOf(
        1,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255,
        255, 255, 255, 255, // regular power
        80, 78, 80, 78,
        80, 78, 80, 78,
        80, 78, 80, 78,
        80, 78, 80, 78
    )

    // Dark, white-text-friendly, and distinct from your red/black plot lines
    private val STIM_COLORS = intArrayOf(
        Color.parseColor("#0066A8"), // deep blue
        Color.parseColor("#007A6C"), // deep teal-green
        Color.parseColor("#B46900"), // dark orange (far from red)
        Color.parseColor("#2E2B8E"), // indigo
        Color.parseColor("#4F79A7"), // blue-gray (dark)
        Color.parseColor("#8A2F7A")  // dark magenta/purple
    )

    // Label -> ARGB color used for highlights and button tints
    private val stimulusColorMap = linkedMapOf<String, Int>()

    // Expose as a list to send to ChannelPlotView later
    val STIM_COLOR_LIST: List<Int> get() = STIM_COLORS.toList()

    private lateinit var stimulusBar: LinearLayout
    private lateinit var addStimulusButton: Button
    private val stimulusLabels = mutableListOf<StimulusLabel>()

    private var fileToTransfer: File? = null
    private lateinit var createFileLauncher: ActivityResultLauncher<Intent>

    var DisplayChannelData : List<DisplayChannelData> = emptyList()

    private lateinit var channelSpinner: Spinner
    private lateinit var channelPlotView: ChannelPlotView

    var sources : List<OverlayElement> = emptyList()
    var detectors : List<OverlayElement> = emptyList()
    var channelCoords : List<DisplayChannelData> = emptyList()

    var layoutMap: Map<String, LayoutStudioItem> = emptyMap()
    var layoutNames: List<String> = emptyList()

    // rolling buffers for the selected channel
    private val maxPoints = 90  // ~60s if ~10 Hz; adjust to taste
    private var currentChannelSpinnerIndex = 0
    // Rolling plot buffers + window control
    private val tsBuffer = ArrayDeque<Float>()
    private val redBuffer = ArrayDeque<Float>()
    private val irBuffer  = ArrayDeque<Float>()

    // Stable mapping: label -> palette index
    private val stimIndexMap = linkedMapOf<String, Int>()
    private var nextStimIndex = 0

    private lateinit var redSeekbar: SeekBar
    private lateinit var irSeekbar: SeekBar

    // When the user switches channels, we already clear buffers in onItemSelected;
    // keep that behavior.

    @SuppressLint("MissingPermission")
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

        channelSpinner = findViewById(R.id.spinner_channels)
        channelPlotView = findViewById(R.id.channel_plot_view)

        channelPlotView.windowSeconds = 10.0f

        signalQualityIndicator = findViewById(R.id.image_signal)
        batteryLevelIndicator = findViewById(R.id.image_battery)

        onScreenTimer = findViewById(R.id.text_timer)

        experimentalNotes =  findViewById(R.id.notebox)

        autosetLEDs = findViewById(R.id.autoSet)

        redSeekbar = findViewById(R.id.seekbar_red)
        irSeekbar = findViewById(R.id.seekbar_IR)
        redSeekbar.min = 0
        redSeekbar.max = 16
        irSeekbar.min = 0
        irSeekbar.max = 16
        redSeekbar.isEnabled = false
        irSeekbar.isEnabled = false

        lifecycleScope.launch {

            layoutMap = layoutDataStore.getAllLayoutsByName()
            layoutNames = layoutMap.keys.toList()

            setupAliasSpinner()
            setupLayoutSpinner()
            setupChannelSpinner()
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
            } else {

                // Get all notes from experiment
                var sessionNotes = experimentalNotes.text.toString()

                BLEConnectionManager.stopService(this, sessionNotes)
                val redCircle = ContextCompat.getDrawable(this@StreamfNIRSData, R.drawable.red_circle)
                redCircle?.setBounds(0, 0, redCircle.intrinsicWidth, redCircle.intrinsicHeight)
                statusTextView.setCompoundDrawables(redCircle, null, null, null)
                statusTextView.compoundDrawablePadding = 12
                statusTextView.text = "Disconnected from $alias"
                isConnected = false
                connectButton.text = "Connect"

                // Stop connect data for updating visuals on-screen
                stopPollingServiceData()

                // Updated data streaming button
                streamToggleButton.isEnabled = false
                streamToggleButton.text = "Start Streaming"
                isStreaming = false

            }

            // Disable addition/removal of any stimulus
            addStimulusButton.isEnabled = false;
            addStimulusButton.isVisible = false;

        }

        streamToggleButton.setOnClickListener {
            if (isStreaming) {
                BLEConnectionManager.stopStreamingFromDevice()
                streamToggleButton.text = "Start Streaming"
                isStreaming = false
                Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
                stopPollingServiceData()
            } else {
                selectedLayoutName?.let { it1 ->

                    // Freeze final label order for this session before CSV header is written
                    BLEConnectionManager.setPresetStimulusLabels(stimulusLabels.map { it.label })

                    BLEConnectionManager.startStreamFromDevice(ledIntensityValues,
                        it1
                    )
                }
                streamToggleButton.text = "Stop Streaming"
                isStreaming = true
                Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()

                startPollingServiceData()
            }

        }

        autosetLEDs.setOnClickListener {

            autosetLEDs.isEnabled = false
            streamToggleButton.isEnabled = false
            statusTextView.text = "Attempting automatic LED adjustment..."

            BLEConnectionManager.requestAutomaticLEDAdjustment(channelCoords)

            // Start polling
            lifecycleScope.launch {
                while (true) {
                    val done = BLEConnectionManager.checkIfLEDsAdjusted()
                    if (done == true) {
                        autosetLEDs.isEnabled = false
                        streamToggleButton.isEnabled = true
                        statusTextView.text = "Ready to stream!"

                        updateSeekbarsForSelectedChannel()

                        break
                    }
                    delay(1) // poll every 0.5 seconds
                }
                ledIntensityValues = BLEConnectionManager.getLatestIntensityValues()!!
                Log.e("StreamfNIRSData", ledIntensityValues.toString())
            }
        }

        redSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Called when the user moves the seekbar
                    Log.d("SeekBar", "Red intensity: $progress")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Optional: do something when user starts touching
                Log.d("SeekBar", "Started adjusting Red")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {

                // Called when user stops touching
                val finalValue = seekBar?.progress ?: 0
                Log.d("SeekBar", "Final Red intensity: $finalValue")
                // Do something with the final value here

                BLEConnectionManager.stopStreamingFromDevice()
                stopPollingServiceData()

                if (channelCoords.get(channelSpinner.selectedItemPosition).type == ChannelType.LONG){
                    ledIntensityValues[2*channelCoords.get(channelSpinner.selectedItemPosition).sourceId-1] = allowedLEDIntensities.get(finalValue)
                }
                else{
                    ledIntensityValues[(2*channelCoords.get(channelSpinner.selectedItemPosition).sourceId-1) + 16] = allowedLEDIntensities.get(finalValue)
                }


            }
        })

        irSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Log.d("SeekBar", "IR intensity: $progress")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Log.d("SeekBar", "Started adjusting IR")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalValue = seekBar?.progress ?: 0

                BLEConnectionManager.stopStreamingFromDevice()
                stopPollingServiceData()

                if (channelCoords.get(channelSpinner.selectedItemPosition).type == ChannelType.LONG){
                    ledIntensityValues[2*channelCoords.get(channelSpinner.selectedItemPosition).sourceId] = allowedLEDIntensities.get(finalValue)
                }
                else{
                    ledIntensityValues[2*channelCoords.get(channelSpinner.selectedItemPosition).sourceId + 16] = allowedLEDIntensities.get(finalValue)
                }

                Log.d("SeekBar", "Final IR intensity: $finalValue")
            }
        })

    }

    // Define the allowed intensity values
    private val allowedLEDIntensities = intArrayOf(
        0, 105, 115, 125, 135, 145, 155,
        165, 175, 185, 195, 205, 215, 225, 235, 245, 255
    )

    fun updateSeekbarsForSelectedChannel() {
        val selectedIndex = channelSpinner.selectedItemPosition
        if (selectedIndex !in channelCoords.indices) return
        val ch = channelCoords[selectedIndex]

        val allowedLEDIntensities = intArrayOf(
            0, 105, 115, 125, 135, 145, 155,
            165, 175, 185, 195, 205, 215, 225, 235, 245, 255
        )

        fun intensityToProgress(value: Int): Int {
            val v = value.coerceIn(0, 255)
            var best = 0
            var bestDiff = Int.MAX_VALUE
            for (i in allowedLEDIntensities.indices) {
                val d = kotlin.math.abs(allowedLEDIntensities[i] - v)
                if (d < bestDiff) { best = i; bestDiff = d }
            }
            return best
        }

        fun progressToIntensity(p: Int): Int =
            allowedLEDIntensities[p.coerceIn(0, allowedLEDIntensities.lastIndex)]

        fun redIndexFor(sourceId: Int, type: ChannelType): Int {
            val sid = sourceId.coerceIn(1, 8)
            return if (type == ChannelType.LONG) (2 * sid - 1) else (2 * sid - 1) + 16
        }

        fun irIndexFor(sourceId: Int, type: ChannelType): Int {
            val sid = sourceId.coerceIn(1, 8)
            return if (type == ChannelType.LONG) (2 * sid) else (2 * sid) + 16
        }

        // --- Initialize SeekBars ---
        redSeekbar.max = allowedLEDIntensities.lastIndex
        irSeekbar.max = allowedLEDIntensities.lastIndex

        val rIdx = redIndexFor(ch.sourceId, ch.type)
        val iIdx = irIndexFor(ch.sourceId, ch.type)

        val redIntensity = ledIntensityValues.getOrNull(rIdx) ?: 0
        val irIntensity  = ledIntensityValues.getOrNull(iIdx) ?: 0

        redSeekbar.progress = intensityToProgress(redIntensity)
        irSeekbar.progress  = intensityToProgress(irIntensity)

        // --- Set Listeners ---
        redSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val v = progressToIntensity(progress)
                    Log.d("SeekBar", "Red intensity (mapped): $v")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Log.d("SeekBar", "Started adjusting Red")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalIntensity = progressToIntensity(seekBar?.progress ?: 0)
                ledIntensityValues[rIdx] = finalIntensity
                Log.d("SeekBar", "Final Red intensity stored: $finalIntensity at index $rIdx")
            }
        })

        irSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val v = progressToIntensity(progress)
                    Log.d("SeekBar", "IR intensity (mapped): $v")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Log.d("SeekBar", "Started adjusting IR")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalIntensity = progressToIntensity(seekBar?.progress ?: 0)
                ledIntensityValues[iIdx] = finalIntensity
                Log.d("SeekBar", "Final IR intensity stored: $finalIntensity at index $iIdx")
            }
        })
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

// Make sure these exist at the top of your Activity:
// private val stimIndexMap = linkedMapOf<String, Int>()
// private var nextStimIndex = 0
// private val stimulusColorMap = linkedMapOf<String, Int>() // label -> ARGB color

    private fun addStimulusLabel(label: String) {
        val stim = StimulusLabel(label)
        stimulusLabels.add(stim)

        // Stable index per label
        val idx = stimIndexMap.getOrPut(label) { nextStimIndex++ }

        // Store/refresh the palette color for this label (used by plot highlights)
        val baseColor = baseStimColor(idx)              // from your STIM_COLORS
        stimulusColorMap[label] = baseColor
        channelPlotView.setStimulusPalette(stimulusColorMap) // keep plot in sync

        val btn = MaterialButton(this).apply {
            text = label
            textSize = 14f
            isAllCaps = true

            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(resources.displayMetrics.density * 12f)
                .build()

            // No stroke when created (inactive look)
            strokeWidth = 0
            strokeColor = null

            stateListAnimator = null
            rippleColor = ColorStateList.valueOf(0x1F000000.toInt())

            backgroundTintList = getInactiveTintColor(idx) // darker variant
            setTextColor(ContextCompat.getColor(this@StreamfNIRSData, R.color.white))

            // stash index for quick toggle
            tag = idx

            setOnClickListener { toggleStimulus(stim, this) }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
            bottomMargin = dp(8)
        }

        stimulusBar.addView(btn, stimulusBar.childCount - 1, lp)
    }

    private fun toggleStimulus(stimulus: StimulusLabel, button: MaterialButton) {
        stimulus.isActive = !stimulus.isActive

        val idx = (button.tag as? Int)
            ?: stimIndexMap[stimulus.label]
            ?: 0

        button.setTextColor(ContextCompat.getColor(this, R.color.white))

        if (stimulus.isActive) {
            // Active: brighten + add black outline
            button.backgroundTintList = getActiveTintColor(idx)
            button.strokeWidth = (resources.displayMetrics.density * 2f).toInt()
            button.strokeColor = ColorStateList.valueOf(Color.BLACK)
        } else {
            // Inactive: darker, no outline
            button.backgroundTintList = getInactiveTintColor(idx)
            button.strokeWidth = 0
            button.strokeColor = null
        }

        Log.i("StimulusToggle",
            "Stimulus '${stimulus.label}' → ${if (stimulus.isActive) "START" else "STOP"}")

        // Notify plot to draw/remove highlight bands at the current plot time
        val nowT = tsBuffer.lastOrNull() ?: 0f
        channelPlotView.addStimulusEvent(
            label = stimulus.label,
            isStart = stimulus.isActive,
            t = nowT
        )

        // Broadcast to service as before
        BLEConnectionManager.broadcastStimulusEvent(
            StimulusEvent(label = stimulus.label, isStart = stimulus.isActive)
        )
    }

    // --- Helpers ---
    private fun baseStimColor(index: Int): Int {
        val n = STIM_COLORS.size
        val safe = ((index % n) + n) % n
        return STIM_COLORS[safe]
    }

    /** Darken/desaturate via HSV to create clear inactive states */
    private fun adjustColor(
        color: Int,
        brightnessFactor: Float = 1f,   // <1 = darker
        saturationFactor: Float = 1f,   // <1 = less saturated
        alpha: Int? = null
    ): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * brightnessFactor).coerceIn(0f, 1f)
        return Color.HSVToColor(alpha ?: Color.alpha(color), hsv)
    }

    /** Active = base color (good contrast with white text) */
    fun getActiveTintColor(stimIndex: Int): ColorStateList {
        val base = baseStimColor(stimIndex)
        val brighter = adjustColor(base, brightnessFactor = 1.15f) // brighten 15%
        return ColorStateList.valueOf(brighter)
    }

    fun getInactiveTintColor(stimIndex: Int): ColorStateList {
        val base = baseStimColor(stimIndex)
        val darker = adjustColor(base, brightnessFactor = 0.60f, saturationFactor = 0.90f)
        return ColorStateList.valueOf(darker)
    }

    private val activeTint by lazy {
        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorAccentValue))
    }
    private val inactiveTint by lazy {
        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimaryValue))
    }

    /** Apply fixed shape/padding, fixed text color, margins, and inactive tint */
    private fun styleStimulusButton(button: Button) {
        // lock shape/padding so size never changes
        button.background = ContextCompat.getDrawable(this, R.drawable.stimulus_button_bg)
        // fixed text color
        button.setTextColor(0xFF212121.toInt())
        // avoid implicit min width on Buttons
        button.minWidth = 0
        button.minimumWidth = 0
        // default tint = inactive
        button.backgroundTintList = inactiveTint

        // enforce margins
        val lp = (button.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        lp.marginEnd = dp(8)
        lp.bottomMargin = dp(8)
        button.layoutParams = lp
    }

    /** Normalize all existing stimulus buttons (except the +Add) */
    private fun normalizeExistingStimulusButtons() {
        for (i in 0 until stimulusBar.childCount) {
            val v = stimulusBar.getChildAt(i)
            if (v is Button && v.id != R.id.btn_add_stimulus) {
                styleStimulusButton(v)

                // Keep the correct tint based on current model state
                val label = v.text?.toString()
                val stim = stimulusLabels.find { it.label == label }
                v.backgroundTintList = if (stim?.isActive == true) activeTint else inactiveTint
            }
        }
    }

    private val PLOT_UPDATE_INTERVAL_MS = 200L     // update plot at most 2×/sec
    private val SQI_UPDATE_INTERVAL_MS  = 5000L    // SQI only needed 1×/sec

    private var lastPlotUpdateTime = 0L
    private var lastSQIUpdateTime  = 0L

    private fun startPollingServiceData() {

        sqiPollingJob?.cancel()
        sqiPollingJob = lifecycleScope.launch(Dispatchers.Default) {

            Log.d("DATA_POLL", "Polling engine started on background thread")

            while (isActive && isConnected) {

                val now = System.currentTimeMillis()

                // -------------------------------------------------------
                // 1️⃣  FETCH RAW DATA FROM BLE SERVICE (NON-UI THREAD)
                // -------------------------------------------------------
                val sqiValues =
                    if (now - lastSQIUpdateTime > SQI_UPDATE_INTERVAL_MS)
                        BLEConnectionManager.getLatestSQIValues()
                    else null

                val fNIRS = BLEConnectionManager.getLatestfNIRSData(maxPoints)
                val batteryLevel = BLEConnectionManager.readLatestBatteryLevel()
                val rssiLevel    = BLEConnectionManager.readLatestSignalLevel()

                // -------------------------------------------------------
                // 2️⃣  PROCESS fNIRS DATA (BACKGROUND THREAD)
                // -------------------------------------------------------
                var latestTimestamp = 0.0
                var redPoint = 0f
                var irPoint  = 0f

                try {
                    if (fNIRS.isNotEmpty()) {
                        val lastRound = fNIRS.last()
                        latestTimestamp = lastRound.timestamps.last().toDouble()

                        val ch = channelCoords[currentChannelSpinnerIndex]
                        val chId = ch.channelNumber

                        val latestRedVector = lastRound.redData.last()
                        val latestIrVector  = lastRound.irData.last()

                        redPoint = latestRedVector[chId].toFloat()
                        irPoint  = latestIrVector[chId].toFloat()

                        // Rolling buffers (BACKGROUND)
                        tsBuffer.add(latestTimestamp.toFloat())
                        redBuffer.add(redPoint)
                        irBuffer.add(irPoint)

                        while (tsBuffer.size > maxPoints) tsBuffer.removeFirst()
                        while (redBuffer.size > maxPoints) redBuffer.removeFirst()
                        while (irBuffer.size > maxPoints) irBuffer.removeFirst()
                    }
                } catch (e: Exception) {
                    Log.e("DATA_POLL", "Error processing fNIRS: $e")
                }

                // -------------------------------------------------------
                // 3️⃣  SWITCH TO UI THREAD ONLY FOR VISUAL UPDATES
                // -------------------------------------------------------
                withContext(Dispatchers.Main) {

                    // ---- Update timestamp display ----
                    onScreenTimer.text = String.format("%.2f s", latestTimestamp)

                    // ---- Update RSSI + Battery ----
                    updateConnectionQualityIndicator(rssiLevel)
                    updateBatteryLevelIndicator(batteryLevel)

                    // ---- Update SQI UI (THROTTLED) ----
                    if (sqiValues != null && (now - lastSQIUpdateTime > SQI_UPDATE_INTERVAL_MS)) {
                        updateSignalQualityViews(sqiValues)
                        lastSQIUpdateTime = now
                    }

                    // ---- Update plot UI (THROTTLED) ----
                    if (now - lastPlotUpdateTime > PLOT_UPDATE_INTERVAL_MS) {
                        channelPlotView.updateData(
                            timestamps = tsBuffer.toList(),
                            redSeries  = redBuffer.toList(),
                            irSeries   = irBuffer.toList()
                        )
                        lastPlotUpdateTime = now
                    }
                }

                // -------------------------------------------------------
                // 4️⃣  DELAY LOOP (NOW SAFE)
                // -------------------------------------------------------
                delay(pollIntervalMs)
            }

            Log.d("DATA_POLL", "Polling engine stopped")
        }
    }

    fun updateBatteryLevelIndicator(batteryPercentage: Int){

        Log.d("pollingBattery", batteryPercentage.toString())

        val accentColor = ContextCompat.getColor(this, R.color.colorAccentValue) // your accent
        val amberColor = ContextCompat.getColor(this, R.color.amber_500) // your accent
        val primaryColor = ContextCompat.getColor(this, R.color.colorPrimaryValue) // your primary

        when{
            batteryPercentage >= 100 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_full)
                batteryLevelIndicator?.setColorFilter(primaryColor)}
            batteryPercentage >= 85 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level6)
                batteryLevelIndicator?.setColorFilter(primaryColor)}
            batteryPercentage >= 62.5 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level5)
                batteryLevelIndicator?.setColorFilter(primaryColor)}
            batteryPercentage >= 50 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level4)
                batteryLevelIndicator?.setColorFilter(amberColor)}
            batteryPercentage >= 37.5 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level3)
                batteryLevelIndicator?.setColorFilter(amberColor)}
            batteryPercentage >= 25 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level2)
                batteryLevelIndicator?.setColorFilter(amberColor)}
            batteryPercentage >= 12.5 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_level1)
                batteryLevelIndicator?.setColorFilter(accentColor)}
            batteryPercentage >= 0 -> {
                batteryLevelIndicator?.setImageResource(R.drawable.battery_empty)
                batteryLevelIndicator?.setColorFilter(accentColor)}
        }

    }

    fun updateConnectionQualityIndicator(rssiLevel: Int){

        Log.d("pollingRSSI", rssiLevel.toString())

        val accentColor = ContextCompat.getColor(this, R.color.colorAccentValue) // your accent
        val primaryColor = ContextCompat.getColor(this, R.color.colorPrimaryValue) // your primary

        when {
            rssiLevel >= -60 -> { // excellent signal
                signalQualityIndicator?.setImageResource(R.drawable.signal_maximum)
                signalQualityIndicator?.setColorFilter(primaryColor)
            }
            rssiLevel >= -70 -> { // good
                signalQualityIndicator?.setImageResource(R.drawable.signal_level3)
                signalQualityIndicator?.setColorFilter(primaryColor)
            }
            rssiLevel >= -80 -> { // fair
                signalQualityIndicator?.setImageResource(R.drawable.signal_level2)
                signalQualityIndicator?.setColorFilter(accentColor)
            }
            else -> { // poor
                signalQualityIndicator?.setImageResource(R.drawable.signal_low)
                signalQualityIndicator?.setColorFilter(accentColor)
            }
        }

    }

    private fun stopPollingServiceData() {
        sqiPollingJob?.cancel()
        sqiPollingJob = null
        updateBatteryLevelIndicator(BLEConnectionManager.readLatestBatteryLevel())
    }

    private fun updateSignalQualityViews(sqiList: List<Float>) {
        sqiOverlay.updateSQI(sqiList)
        sqiOverlay.invalidate()
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

        Log.d("LAYOUT_SPINNER", "Found ${layoutNames.size} layouts: $layoutNames")

        if (layoutNames.isEmpty()) {
            statusTextView.text = "No layouts found."
            layoutSpinner.isEnabled = false
            return
        }

        // Use a stable order so indices don’t jump around between runs
        layoutNames = layoutNames.sorted()

        layoutSpinner.adapter = ArrayAdapter(
            this@StreamfNIRSData,
            android.R.layout.simple_spinner_dropdown_item,
            layoutNames
        )

        // If nothing chosen yet, select the first *without* firing the listener
        if (selectedLayoutName == null) {
            selectedLayoutName = layoutNames.firstOrNull()
            layoutSpinner.setSelection(0, /* animate = */ false)
        } else {
            // Preserve a previously set selection if present
            val idx = layoutNames.indexOf(selectedLayoutName).coerceAtLeast(0)
            layoutSpinner.setSelection(idx, /* animate = */ false)
        }

        // Only proceed if we really have a name
        val initialLayout = selectedLayoutName
        if (initialLayout == null) {
            Log.w("LAYOUT_SPINNER", "No initial layout selected; waiting for user selection.")
        } else {
            // Keep the service in sync with the currently selected layout
            BLEConnectionManager.setLayoutName(initialLayout)

            // Load overlays for the *currently selected* layout
            val overlays = layoutDataStore.loadOverlayElements(initialLayout)
            sources = overlays.filter { it.isSource }
            detectors = overlays.filter { !it.isSource }

            // Channel coords come from the service; may be empty before stream
            channelCoords = BLEConnectionManager.getChannelDisplayData()

            sqiOverlay.setOverlayData(
                sourceList = sources,
                detectorList = detectors,
                channelList = channelCoords
            )

            Log.d("LayoutSpinnerSETUP", "sources=$sources")
            Log.d("LayoutSpinnerSETUP", "detectors=$detectors")
            Log.d("LayoutSpinnerSETUP", "channelCoords=$channelCoords")
        }

        // Now handle user selection changes
        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                layoutInit(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d("LAYOUT_SPINNER", "Nothing selected")
            }
        }
    }


    fun layoutInit(position:Int){

        selectedLayoutName = layoutNames[position]
        Log.d("LAYOUT_SPINNER", "Selected layout: $selectedLayoutName")
//        statusTextView.text = "Selected layout: $selectedLayoutName"

        // Show the layout on screen
        lifecycleScope.launch {

            BLEConnectionManager.setLayoutName(selectedLayoutName!!)

            val overlays = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
            sources = overlays.filter { it.isSource }
            detectors = overlays.filter { !it.isSource }

            channelCoords = BLEConnectionManager.getChannelDisplayData()

            sqiOverlay.setOverlayData(
                sourceList = sources,
                detectorList = detectors,
                channelList = channelCoords
            )

            Log.d("CHANNEL_DATA", "Loaded ${channelCoords.size} channels")

            //
            val channelLabels = channelCoords.mapIndexed { index, _ -> "Ch ${channelCoords[index].channelNumber+1} (${channelCoords[index].type} ) S${channelCoords[index].sourceId}:D${channelCoords[index].detectorId}" }

            channelSpinner.adapter = ArrayAdapter(
                this@StreamfNIRSData,
                android.R.layout.simple_spinner_dropdown_item,
                channelLabels
            )

            channelLabels.forEachIndexed { index, label ->
                val coord = channelCoords[index]
                Log.d("ChannelInfo", "$label → Coord = (${coord.x}, ${coord.y}), Index = ${coord.channelNumber+1}, Index = ${coord.type}")
            }

            Log.d("LAYOUT_SPINNER", "Overlay updated with ${sources.size} sources and ${detectors.size} detectors")

        }

        setupChannelSpinner()

    }

    private fun setupChannelSpinner(){

        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Log.d("ChannelSpinner", "Spinner selection updated")
                currentChannelSpinnerIndex = position

                // reset rolling data when switching channels
                tsBuffer.clear()
                redBuffer.clear()
                irBuffer.clear()

                val currChannel = channelCoords[position]
                Log.d(
                    "ChannelSpinner",
                    "${currChannel.channelNumber+1} , ${currChannel.type} Source: ${currChannel.sourceId} , Detector ${currChannel.detectorId}"
                )

                updateSeekbarsForSelectedChannel()

            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

            BLEConnectionManager.startService(
                this@StreamfNIRSData,
                alias = alias,
                layoutJson = layoutJson,
                layoutName = selectedLayoutName!!   // ✅ pass it
            )

            // Prevent user from changing the device in use
            aliasSpinner.isEnabled = false;
            aliasSpinner.alpha = 0.75f

            // Prevent user from changing the layout in use
            layoutSpinner.isEnabled = false;
            layoutSpinner.alpha = 0.75f

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

                    // Enable the various on-screen features
                    redSeekbar.isEnabled = true
                    irSeekbar.isEnabled = true
                    autosetLEDs.isEnabled = true

                    BLEConnectionManager.setPresetStimulusLabels(stimulusLabels.map { it.label })

                    // Log to terminal
                    Log.i("StreamfNIRSData", "✅ Ready to stream data from $alias")
                    isConnected = true
                    connectButton.text = "Disconnect"

                    BLEConnectionManager.getDeviceBatteryLevel()
                    batteryLevelIndicator?.setColorFilter(R.color.white)

                    val currentIndex = layoutNames.indexOf(selectedLayoutName).coerceAtLeast(0)
                    layoutInit(currentIndex)  // ✅ preserves the user's selection

                    return@launch

                } else {
                    statusTextView.setCompoundDrawables(null, null, null, null)
                    statusTextView.text = "Connecting to $alias... ($it)"
                    stopPollingServiceData()
                }
                delay(500)
            }

            // Connection failed
            statusTextView.setCompoundDrawables(null, null, null, null)
            statusTextView.text = "❌ Failed to connect to $alias"
            showErrorDialog("Could not connect to $alias. Please ensure it is powered on and has sufficient battery.")

            // Update on-screen data
            stopPollingServiceData()

        }

    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
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

        var sessionNotes = experimentalNotes.text.toString()
        BLEConnectionManager.stopService(this, sessionNotes)

        stopPollingServiceData()

        BLEConnectionManager.hardResetTimer()

        if (isConnected) {
            BLEConnectionManager.stopService(this, "App closed abruptly/incorrectly")
            Log.i("StreamfNIRSData", "Foreground BLE service stopped on activity destroy")
        }

        streamToggleButton.isEnabled = false
        isStreaming = false

    }


}
