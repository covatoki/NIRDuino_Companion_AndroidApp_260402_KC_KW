package com.example.nirduino_android_app_v2.run_experiement

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nirduino_android_app_v2.MyApplication
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.databinding.ActivityExperimentsBinding
import com.example.nirduino_android_app_v2.device_communication.StreamfNIRSData.StimulusLabel
import com.example.nirduino_android_app_v2.device_communication_management.BLEConnectionManager
import com.example.nirduino_android_app_v2.device_communication_management.ChannelType
import com.example.nirduino_android_app_v2.device_communication_management.DataRound
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutStudioItem
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.nirduino_android_app_v2.device_communication_management.StimulusEvent

class ExperimentActivity : AppCompatActivity(), OnExperimentClickListener {

    private lateinit var binding: ActivityExperimentsBinding
    private lateinit var layoutDataStore: LayoutDataStore
    var layoutMap: Map<String, LayoutStudioItem> = emptyMap()
    var layoutNames: List<String> = emptyList()
    private lateinit var knownDeviceStore: KnownDeviceDataStore

    private var selectedAlias: String? = null
    private var selectedLayoutName: String? = null
    private var isConnected = false
    private var isStreaming = false
    private var isStreamToggleEnable = false

    var sources: List<OverlayElement> = emptyList()
    var detectors: List<OverlayElement> = emptyList()
    var channelCoords: List<DisplayChannelData> = emptyList()
    private val stimulusLabels = mutableListOf<StimulusLabel>()
    private var sqiPollingJob: Job? = null

    private var currentChannelSpinnerIndex = 0
    private var pollIntervalMs: Long = 200  // Adjustable polling interval in milliseconds
    private val maxPoints = 90  // ~60s if ~10 Hz; adjust to taste
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

    private val tsBuffer = ArrayDeque<Float>()
    private val redBuffer = ArrayDeque<Float>()
    private val irBuffer = ArrayDeque<Float>()

    private var stopTypingJob: Job? = null
    private var questionTimerStart: Long = 0L
    private var questionTimerEnd = 0L
    private var currentCorrectAnswer = 0
    private var currentQuestionIndex = 0
    private var totalQuestions = 0
    private var currentQuestion = ""
    private val resultLog = StringBuilder()
    private var experimentJob: Job? = null
    private var experimentModel: ExperimentModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityExperimentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnStreamToggle.isEnabled = false
        isStreamToggleEnable = false

        // Example usage
        val adapter = ExperimentAdapter(this, MyApplication.getInstance().experimentList, this)
        binding.rvExperiment.layoutManager = LinearLayoutManager(this)
        binding.rvExperiment.adapter = adapter

        knownDeviceStore = KnownDeviceDataStore.getInstance(applicationContext)
        layoutDataStore = LayoutDataStore.getInstance(applicationContext)

        binding.seekbarRed.isEnabled = false
        binding.seekbarIR.isEnabled = false
        lifecycleScope.launch {

            layoutMap = layoutDataStore.getAllLayoutsByName()
            layoutNames = layoutMap.keys.toList()

            setupAliasSpinner()
            setupLayoutSpinner()
//            setupChannelSpinner()
        }

        binding.channelSpinnerRow.isVisible = false

        setupTypingListener()
        setupClickListener()
    }

    private fun setupClickListener() {
        binding.btnConnect.setOnClickListener {

            val alias = selectedAlias
            if (alias == null || selectedLayoutName == null) {
                showErrorDialog("Please select both a device and a layout.")
                return@setOnClickListener
            }

            if (!isConnected) {
                attemptConnection(alias)
            } else {

                // Get all notes from experiment
                val sessionNotes = ""
                BLEConnectionManager.stopService(this, sessionNotes)
                val redCircle =
                    ContextCompat.getDrawable(this@ExperimentActivity, R.drawable.red_circle)
                redCircle?.setBounds(0, 0, redCircle.intrinsicWidth, redCircle.intrinsicHeight)
                binding.textStatus.setCompoundDrawables(redCircle, null, null, null)
                binding.textStatus.compoundDrawablePadding = 12
                binding.textStatus.text = "Disconnected from $alias"
                isConnected = false
                binding.btnConnect.text = "Connect"

                // Updated data streaming button
                binding.btnStreamToggle.isEnabled = false
                isStreamToggleEnable = false
                binding.btnStreamToggle.text = "Start Streaming"
                isStreaming = false

            }

            // Disable addition/removal of any stimulus
//            addStimulusButton.isEnabled = false;
//            addStimulusButton.isVisible = false;

        }

        binding.btnStreamToggle.setOnClickListener {
            if (isStreaming) {
                BLEConnectionManager.stopStreamingFromDevice()
                binding.btnStreamToggle.text = "Start Streaming"
                isStreaming = false
                Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()

            } else {
                selectedLayoutName?.let { it1 ->

                    // Freeze final label order for this session before CSV header is written
                    BLEConnectionManager.setPresetStimulusLabels(stimulusLabels.map { it.label })

                    BLEConnectionManager.startStreamFromDevice(
                        ledIntensityValues,
                        it1
                    )
                }
                binding.btnStreamToggle.text = "Stop Streaming"
                isStreaming = true
                Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()

            }

        }
    }

    private fun sendStimulusState(isStimOn: Boolean, label: String) {
        // isStimOn = true  → 1 (stim)
        // isStimOn = false → 0 (rest)
        BLEConnectionManager.broadcastStimulusEvent(
            StimulusEvent(
                label = label,
                isStart = isStimOn
            )
        )
    }

    private fun setupChannelSpinner() {

        binding.spinnerChannels.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    Log.d("ChannelSpinner", "Spinner selection updated")
                    currentChannelSpinnerIndex = position

                    // reset rolling data when switching channels
                    tsBuffer.clear()
                    redBuffer.clear()
                    irBuffer.clear()

                    val currChannel = channelCoords[position]
                    Log.d(
                        "ChannelSpinner",
                        "${currChannel.channelNumber + 1} , ${currChannel.type} Source: ${currChannel.sourceId} , Detector ${currChannel.detectorId}"
                    )

                    updateSeekbarsForSelectedChannel()

                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    fun updateSeekbarsForSelectedChannel() {
        val selectedIndex = binding.spinnerChannels.selectedItemPosition
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
                if (d < bestDiff) {
                    best = i; bestDiff = d
                }
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
        binding.seekbarRed.max = allowedLEDIntensities.lastIndex
        binding.seekbarIR.max = allowedLEDIntensities.lastIndex

        val rIdx = redIndexFor(ch.sourceId, ch.type)
        val iIdx = irIndexFor(ch.sourceId, ch.type)

        val redIntensity = ledIntensityValues.getOrNull(rIdx) ?: 0
        val irIntensity = ledIntensityValues.getOrNull(iIdx) ?: 0

        binding.seekbarRed.progress = intensityToProgress(redIntensity)
        binding.seekbarIR.progress = intensityToProgress(irIntensity)

        // --- Set Listeners ---
        binding.seekbarRed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        binding.seekbarIR.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        return adapter?.isEnabled == true
    }

    private fun attemptConnection(alias: String) {
        binding.textStatus.text = "Connecting to $alias..."

        if (!isBluetoothEnabled()) {
            showErrorDialog("Bluetooth is disabled. Please enable it and try again.")
            return
        }

        lifecycleScope.launch {

            val overlays = layoutDataStore.loadOverlayElements(selectedLayoutName!!)
            val gson = Gson()
            val layoutJson = gson.toJson(overlays)

            BLEConnectionManager.startService(
                this@ExperimentActivity,
                alias = alias,
                layoutJson = layoutJson,
                layoutName = selectedLayoutName!!   // ✅ pass it
            )

            // Prevent user from changing the device in use
            binding.spinnerAliases.isEnabled = false;
            binding.spinnerAliases.alpha = 0.75f

            // Prevent user from changing the layout in use
            binding.spinnerLayouts.isEnabled = false;
            binding.spinnerLayouts.alpha = 0.75f

        }

        lifecycleScope.launch {
            delay(1000)
            repeat(20) {
                if (BLEConnectionManager.getStreamReadinessStatus()) {

                    val greenCircle =
                        ContextCompat.getDrawable(this@ExperimentActivity, R.drawable.green_circle)
                    greenCircle?.setBounds(
                        0,
                        0,
                        greenCircle.intrinsicWidth,
                        greenCircle.intrinsicHeight
                    )
                    binding.textStatus.setCompoundDrawables(greenCircle, null, null, null)
                    binding.textStatus.compoundDrawablePadding = 12
                    binding.textStatus.text = "Connected to $alias"

                    // Enable the button
                    binding.btnStreamToggle.isEnabled = true
                    isStreamToggleEnable = true
                    binding.btnStreamToggle.text = "Start Streaming"

                    // Enable the various on-screen features
                    binding.seekbarRed.isEnabled = true
                    binding.seekbarIR.isEnabled = true

                    BLEConnectionManager.setPresetStimulusLabels(stimulusLabels.map { it.label })

                    // Log to terminal
                    Log.i("StreamfNIRSData", "✅ Ready to stream data from $alias")
                    isConnected = true
                    binding.btnConnect.text = "Disconnect"

                    lifecycleScope.launch {
                        // ... after you've confirmed connection is ready ...

                        delay(2000) // 2 seconds artificial delay

                        autoSetLeds()
                    }

//                    BLEConnectionManager.getDeviceBatteryLevel()
                    binding.imageBattery.setColorFilter(R.color.white)

                    val currentIndex = layoutNames.indexOf(selectedLayoutName).coerceAtLeast(0)
                    layoutInit(currentIndex)  // ✅ preserves the user's selection

                    return@launch

                } else {
                    binding.textStatus.setCompoundDrawables(null, null, null, null)
                    binding.textStatus.text = "Connecting to $alias... ($it)"

                }
                delay(500)
            }

            // Connection failed
            binding.textStatus.setCompoundDrawables(null, null, null, null)
            binding.textStatus.text = "❌ Failed to connect to $alias"
            showErrorDialog("Could not connect to $alias. Please ensure it is powered on and has sufficient battery.")

        }
    }

    @SuppressLint("MissingPermission")
    private fun autoSetLeds(){
//        autosetLEDs.isEnabled = false
        isStreamToggleEnable = false
        binding.btnStreamToggle.isEnabled = false
        binding.textStatus.text = "Attempting automatic LED adjustment..."

        BLEConnectionManager.requestAutomaticLEDAdjustment(channelCoords)

        // Start polling
        lifecycleScope.launch {
            while (true) {
                val done = BLEConnectionManager.checkIfLEDsAdjusted()
                if (done == true) {
                    isStreamToggleEnable = true
                    binding.btnStreamToggle.isEnabled = true
                    binding.textStatus.text = "Ready to stream!"

                    updateSeekbarsForSelectedChannel()

                    break
                }
                delay(1) // poll every 0.5 seconds
            }
            ledIntensityValues = BLEConnectionManager.getLatestIntensityValues()!!
            Log.e("StreamfNIRSData", ledIntensityValues.toString())
        }
    }

    private suspend fun setupAliasSpinner() {
        val deviceList = knownDeviceStore.getDevices().first()
        val aliases = deviceList.map { it.alias }

        if (aliases.isEmpty()) {
            binding.textStatus.text = "No known devices found."
            binding.btnConnect.isEnabled = false
            return
        }

        binding.spinnerAliases.adapter = ArrayAdapter(
            this@ExperimentActivity,
            android.R.layout.simple_spinner_dropdown_item,
            aliases
        )

        binding.spinnerAliases.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedAlias = aliases[position]
                    binding.textStatus.text = "Selected: $selectedAlias"
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private suspend fun setupLayoutSpinner() {

        Log.d("LAYOUT_SPINNER", "Found ${layoutNames.size} layouts: $layoutNames")

        if (layoutNames.isEmpty()) {
            binding.textStatus.text = "No layouts found."
            binding.spinnerLayouts.isEnabled = false
            return
        }

        // Use a stable order so indices don’t jump around between runs
        layoutNames = layoutNames.sorted()

        binding.spinnerLayouts.adapter = ArrayAdapter(
            this@ExperimentActivity,
            android.R.layout.simple_spinner_dropdown_item,
            layoutNames
        )

        // If nothing chosen yet, select the first *without* firing the listener
        if (selectedLayoutName == null) {
            selectedLayoutName = layoutNames.firstOrNull()
            binding.spinnerLayouts.setSelection(0, /* animate = */ false)
        } else {
            // Preserve a previously set selection if present
            val idx = layoutNames.indexOf(selectedLayoutName).coerceAtLeast(0)
            binding.spinnerLayouts.setSelection(idx, /* animate = */ false)
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

            Log.d("LayoutSpinnerSETUP", "sources=$sources")
            Log.d("LayoutSpinnerSETUP", "detectors=$detectors")
            Log.d("LayoutSpinnerSETUP", "channelCoords=$channelCoords")
        }

        // Now handle user selection changes
        binding.spinnerLayouts.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    layoutInit(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    Log.d("LAYOUT_SPINNER", "Nothing selected")
                }
            }
    }

    fun layoutInit(position: Int) {

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

//            sqiOverlay.setOverlayData(
//                sourceList = sources,
//                detectorList = detectors,
//                channelList = channelCoords
//            )

            Log.d("CHANNEL_DATA", "Loaded ${channelCoords.size} channels")

            //
            val channelLabels =
                channelCoords.mapIndexed { index, _ -> "Ch ${channelCoords[index].channelNumber + 1} (${channelCoords[index].type} ) S${channelCoords[index].sourceId}:D${channelCoords[index].detectorId}" }

            binding.spinnerChannels.adapter = ArrayAdapter(
                this@ExperimentActivity,
                android.R.layout.simple_spinner_dropdown_item,
                channelLabels
            )

            channelLabels.forEachIndexed { index, label ->
                val coord = channelCoords[index]
                Log.d(
                    "ChannelInfo",
                    "$label → Coord = (${coord.x}, ${coord.y}), Index = ${coord.channelNumber + 1}, Index = ${coord.type}"
                )
            }

            Log.d(
                "LAYOUT_SPINNER",
                "Overlay updated with ${sources.size} sources and ${detectors.size} detectors"
            )

        }

        setupChannelSpinner()

    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onExperimentSelected(model: ExperimentModel) {
        if (isStreamToggleEnable) {

            // Ensure this experiment’s label is included once
            if (stimulusLabels.none { it.label == model.name }) {
                stimulusLabels.add(StimulusLabel(model.name))
            }

            if (binding.experimentLayout.isVisible) {
                binding.experimentLayout.visibility = View.GONE
                binding.runExperimentLayout.visibility = View.VISIBLE
                startExperiment(model)
            }

            binding.btnStreamToggle.performClick()

        } else {
            Toast.makeText(this@ExperimentActivity,"Please check connection", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (binding.runExperimentLayout.isVisible) {
            if (stopTypingJob?.isActive == true) {
                stopTypingJob?.cancel()
            }
            if (experimentJob?.isActive == true) {
                experimentJob?.cancel()
            }
            hideKeyboard(binding.etAnswer)
            binding.etAnswer.visibility = View.GONE
            binding.experimentLayout.visibility = View.VISIBLE
            binding.runExperimentLayout.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }

    private fun setupTypingListener() {
        binding.etAnswer.addTextChangedListener { text ->

            // Start timer when user types first character
//            if (text?.isNotEmpty() == true) {
//
//            }

            // Cancel old stop-typing detector
            stopTypingJob?.cancel()

            // Start a new 2-second "no typing" detector
            stopTypingJob = lifecycleScope.launch {
                delay(2000)  // 2 sec no typing

                if (binding.etAnswer.text.toString().trim().isEmpty()) {
                    return@launch
                }

                onUserStoppedTyping()
            }
        }
    }


    private fun onUserStoppedTyping() {
        // If empty → don't move to next question
        val userAnswer = binding.etAnswer.text.toString().trim()
        if (userAnswer.isEmpty()) return

        questionTimerEnd = System.currentTimeMillis()

        val timeTaken = if (questionTimerStart > 0) {
            (questionTimerEnd - questionTimerStart) / 1000
        } else 0

        resultLog.append(
            "$currentQuestion = $currentCorrectAnswer | Your answer: $userAnswer | Time: ${timeTaken}s\n"
        )

        binding.etAnswer.setText("")

        loadNextArithmeticQuestion()
    }


    private fun loadNextArithmeticQuestion() {
        if (currentQuestionIndex >= totalQuestions) return

        // Generate question
        val (question, answer) = generateArithmeticQuestion()

        currentQuestion = question
        currentCorrectAnswer = answer

        // Show question
        setTestAndColor("$question = ?", experimentModel?.stimColor!!)

        questionTimerStart = System.currentTimeMillis()

        // Reset typing state
        binding.etAnswer.setText("")
//        questionTimerStart = 0L

        currentQuestionIndex++
    }

    private fun startExperiment(experimentModel: ExperimentModel) {
        this.experimentModel = experimentModel

        experimentJob = lifecycleScope.launch {
            // 1) First message (5 sec)
            setTestAndColor("Please sit in a relaxed\nmanner", experimentModel.restColor)
            delay(5000)

            // 2) Next combined message (3 sec)
            setTestAndColor(
                "Please sit in a relaxed\nmanner\n\n" + experimentModel.startDescription,
                experimentModel.restColor
            )
            delay(3000)

            // 3) Loop totalCycle times
            repeat(experimentModel.totalCycle) {
                if (experimentModel.name == "Arithmetic") {

                    binding.etAnswer.visibility = View.VISIBLE
                    showKeyboard(binding.etAnswer)

                    totalQuestions = experimentModel.totalWorkingSeconds / 2
                    currentQuestionIndex = 0

                    loadNextArithmeticQuestion()  // Start first question

                    // Wait until all questions done
                    while (currentQuestionIndex < totalQuestions) {
                        delay(100)  // lightweight check
                    }

                    // REST PERIOD
                    hideKeyboard(binding.etAnswer)
                    binding.etAnswer.visibility = View.GONE
                    setTestAndColor(experimentModel.stopStreamText, experimentModel.restColor)
                    delay(experimentModel.totalStopSeconds * 1000L)
                } else {
                    // NEW: keep streaming; just toggle stimulus state
                    // 🔴 Stim ON  → 1
                    sendStimulusState(
                        isStimOn = true,
                        label = experimentModel.name  // or a fixed label like "TASK"
                    )
                    setTestAndColor(experimentModel.startStreamText, experimentModel.stimColor)
                    delay(experimentModel.totalWorkingSeconds * 1000L)

                    // ⚪ Stim OFF → 0 (rest)
                    sendStimulusState(
                        isStimOn = false,
                        label = experimentModel.name
                    )
                    setTestAndColor(experimentModel.stopStreamText, experimentModel.restColor)
                    delay(experimentModel.totalStopSeconds * 1000L)
                }

            }

            // 4) Final thank you
            setTestAndColor(
                "Thank you for\ncompleting this\nexperiment".trimIndent() + "\n" + resultLog.toString(),
                experimentModel.restColor
            )
            hideKeyboard(binding.etAnswer)
            binding.etAnswer.visibility = View.GONE
            binding.btnStreamToggle.performClick()
        }
    }

    fun setTestAndColor(text: String, textColor: Int) {
        binding.tvTitle.text = text
        binding.tvTitle.setTextColor(textColor)
    }

    private fun generateArithmeticQuestion(): Pair<String, Int> {
        val a = (10..99).random()
        val b = (10..99).random()
        val question = "$a + $b"
        return question to (a + b)
    }

    private fun showKeyboard(editText: EditText) {
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(editText: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }
}
