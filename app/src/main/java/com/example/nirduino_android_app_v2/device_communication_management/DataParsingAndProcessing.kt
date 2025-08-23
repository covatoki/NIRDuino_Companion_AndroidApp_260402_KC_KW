package com.example.nirduino_android_app_v2.device_communication_management

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.collections.*
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DataParsingAndProcessing {

    val sqiFlowEmitter = MutableSharedFlow<List<Float>>(replay = 0, extraBufferCapacity = 5)

    private val numSources = 33
    private val numDetectors = 16
    private val numDataPointsPerSource = 17
    private val numBytesPerValue = 4

    val receivedDataArray = ByteArray(numSources * numDataPointsPerSource * numBytesPerValue)
    val darkCurrentMeasurements = DoubleArray(16)
    var ledIntensityValues = IntArray(33) { 8 }
    val dataArray = Array(numSources) { DoubleArray(numDetectors) }

    var durationDataRound: Int = 0
    var durationDataRoundSeconds: Float = 0f

    private var isDataSet1Ready = false
    private var isDataSet2Ready = false
    private var isDataSet3Ready = false
    private var isDataSet4Ready = false
    var isDataReady = false

    var timestampSeconds: Float = 0f
    var channelDisplayData: List<DisplayChannelData> = emptyList()
    private val accumulatedDataForSQI = mutableListOf<List<Double>>()
    lateinit var layoutOverlayElements: List<OverlayElement>

    val roundWiseData: MutableList<DataRound> = mutableListOf()

    private val bufferedTimestamps = mutableListOf<Float>()
    private val bufferedRedSamples = mutableListOf<List<Float>>()
    private val bufferedIrSamples = mutableListOf<List<Float>>()

    var batteryLevel = 0

    private val currentRound: DataRound?
        get() = roundWiseData.lastOrNull()

    private val csvLines = mutableListOf<String>()

    var latestSignalRating: List<Float> = emptyList()
        private set

    private val sqiWindowSeconds = 15.0f

    fun getHeader(): String {
        val header = StringBuilder()
        header.append("Time,Stimulus")

        for (s in 1..8) {
            for (d in 1..16) {
                header.append(", S${s}_D${d}_740nm_RP")
                header.append(", S${s}_D${d}_850nm_RP")
                header.append(", ledPowerLevel_740nm_RP, ledPowerLevel_850nmRP")
                header.append(", S${s}_D${d}_740nm_LP")
                header.append(", S${s}_D${d}_850nm_LP")
                header.append(", ledPowerLevel_740nm_LP, ledPowerLevel_850nmLP")
            }
        }

        for (d in 1..16) {
            header.append(", D${d}_DC")
        }

        return header.toString()
    }

    fun convertByteToChannelData(wrap: ByteBuffer): Boolean {

        wrap.order(ByteOrder.LITTLE_ENDIAN)

        Log.d("RECEIVED_BLE", wrap.capacity().toString())

        when (wrap.capacity()) {
            289 -> {
                for (i in 1..31) {
                    ledIntensityValues[i] = wrap.getInt((i - 1) * 4)
                }
            }
            480 -> {
                val dataSetNumber = wrap.getInt(0)
                val offset = when (dataSetNumber) {
                    1 -> 0
                    2 -> 476
                    3 -> 952
                    4 -> 1428
                    else -> return false
                }
                transferToReceivedDataArray(offset, wrap)
                when (dataSetNumber) {
                    1 -> isDataSet1Ready = true
                    2 -> isDataSet2Ready = true
                    3 -> isDataSet3Ready = true
                    4 -> isDataSet4Ready = true
                }
            }
            344 -> {
                transferToReceivedDataArray(1904, wrap)
                generateDataArray()
                durationDataRoundSeconds = durationDataRound / 1000.0f
                durationDataRound = 0
                isDataReady = isDataSet1Ready && isDataSet2Ready && isDataSet3Ready && isDataSet4Ready
                isDataSet1Ready = false
                isDataSet2Ready = false
                isDataSet3Ready = false
                isDataSet4Ready = false

                if (isDataReady) {

                    logDataPoint()

                    // Extract the long and short channels using the layout data
                    extractfNIRSChannelDataUsingLayout()

                    // Get the relevant fNIRS data for the channels
                    isolateSpecificChannelData(logging = false)

                    // Get the SQI scores for the relevant channels
                    updateSQIScores(sqiWindowSeconds, false)
                }
            }
            4 ->{
                Log.e("BATTERY_CONNECT",  "Battery level information: " + (wrap.getInt(0)).toString())
                batteryLevel = wrap.getInt(0)
            }
            else -> return false
        }
        return isDataReady
    }

    fun handleStimulusEvent(event: StimulusEvent) {
        if (event.isStart) {
            currentRound?.stimuli?.add(Stimulus(event.label, timestampSeconds, 0f))
        } else {
            val matching = currentRound?.stimuli?.lastOrNull {
                it.label == event.label && it.duration == 0f
            }
            if (matching != null) {
                matching.duration = timestampSeconds - matching.onset
            }
        }
    }

    private fun transferToReceivedDataArray(overwriteStartIndex: Int, wrap: ByteBuffer) {
        for (i in 4 until wrap.capacity()) {
            receivedDataArray[overwriteStartIndex + i - 4] = wrap.get(i)
        }
    }

    fun estimateSamplingRate(): Float {
        if (bufferedTimestamps.size < 2) return 0f
        val intervals = bufferedTimestamps.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average().toFloat()
        return if (avgInterval > 0f) 1f / avgInterval else 0f
    }

    fun logDataPoint() {
        val lineBuilder = StringBuilder()
        lineBuilder.append(timestampSeconds)
        lineBuilder.append(", ")  // Placeholder for stimulus label, can update later
        lineBuilder.append(getStimulusString())

        for (s in 0..7) {
            for (d in 0..15) {
                lineBuilder.append(", ").append(dataArray[s * 2][d])           // Red RP
                lineBuilder.append(", ").append(dataArray[s * 2 + 1][d])       // IR RP
                lineBuilder.append(", ").append(ledIntensityValues[(s + 1) * 2 - 1])
                lineBuilder.append(", ").append(ledIntensityValues[(s + 1) * 2])
                lineBuilder.append(", ").append(dataArray[s * 2 + 16][d])      // Red LP
                lineBuilder.append(", ").append(dataArray[s * 2 + 1 + 16][d])  // IR LP
                lineBuilder.append(", ").append(ledIntensityValues[(s + 1) * 2 - 1 + 16])
                lineBuilder.append(", ").append(ledIntensityValues[(s + 1) * 2 + 16])
            }
        }

        for (d in 0 until 16) {
            lineBuilder.append(", ").append(darkCurrentMeasurements[d])
        }

        csvLines.add(lineBuilder.toString())
    }

    fun getStimulusString(): String {

        var stimulusString = ""

        var index = 0;
        for(stimulus in currentRound?.stimuli!!){

            if (index <= currentRound?.stimuli!!.size){
                stimulusString = stimulusString + index.toString() + ":"
            }
            else{
                stimulusString = stimulusString + index.toString()
            }

            index++
        }

        return currentRound?.stimuli.toString()
    }

    private fun generateDataArray() {
        val wrap = ByteBuffer.wrap(receivedDataArray)
        wrap.order(ByteOrder.LITTLE_ENDIAN)

        for (i in receivedDataArray.indices step 4) {
            val currentElement = i / 4
            val sourceIndex = currentElement / 17
            val detectorIndex = currentElement % 17

            val currentInt = wrap.getInt(i)

            if (detectorIndex == 16) {
                durationDataRound += currentInt
            } else if (sourceIndex == 32) {
                darkCurrentMeasurements[detectorIndex] = convertADCValueToDouble(currentInt, 1)
            } else if (detectorIndex in 0 until numDetectors && sourceIndex in 0 until numSources - 1) {
                dataArray[sourceIndex][detectorIndex] = convertADCValueToDouble(currentInt, 1)
            }

        }
    }

    private fun convertADCValueToDouble(currInteger: Int, pgaValue: Int): Double {
        val VREF = 2.50
        val adjustedInt = if ((currInteger shr 23) and 1 == 1) currInteger - 16777216 else currInteger
        val outputVoltage = ((2 * VREF) / 8388608.0) * adjustedInt
        return outputVoltage / pgaValue
    }

    fun extractfNIRSChannelDataUsingLayout() {

        if (!::layoutOverlayElements.isInitialized) {
            Log.e("DataParsing", "Layout overlay not initialized!")
            return
        }

        val sources = layoutOverlayElements.filter { it.isSource }
        val detectors = layoutOverlayElements.filter { !it.isSource }

//        Log.i("LayoutCheck", "=== Source Positions ===")
//        sources.forEach {
//            Log.i("LayoutCheck", "Source ID=${it.id} → (x=${it.x}, y=${it.y})")
//        }
//
//        Log.i("LayoutCheck", "=== Detector Positions ===")
//        detectors.forEach {
//            Log.i("LayoutCheck", "Detector ID=${it.id} → (x=${it.x}, y=${it.y})")
//        }

        val result = mutableListOf<DisplayChannelData>()
        var channelIndex = 0

        for (source in sources) {
            for (detector in detectors) {

                val dx = source.x - detector.x
                val dy = source.y - detector.y
                val distance = hypot(dx.toDouble(), dy.toDouble())
                val xMid = (source.x + detector.x) / 2f
                val yMid = (source.y + detector.y) / 2f

                val type = when {
                    distance in 25.0..35.0 -> ChannelType.LONG
                    distance <= 15.0 -> ChannelType.SHORT
                    else -> continue
                }

                val sourceIndex = when (type) {
                    ChannelType.LONG -> (source.id - 1)
                    ChannelType.SHORT -> 16 + (source.id - 1)
                }

//                Log.d("Layout", "Source ID=${source.id}, Detector ID=${detector.id}, SourceIndex=$sourceIndex")

                if (sourceIndex !in 0 until dataArray.size || detector.id-1 !in 0 until dataArray[0].size) {
                    Log.w("DataParsing", "Invalid source/detector index: S=$sourceIndex, D=${detector.id}")
                    continue
                }

                val value = dataArray[sourceIndex][detector.id-1]

                result.add(
                    DisplayChannelData(
                        channelNumber = channelIndex++,
                        type = type,
                        sourceId = source.id,
                        sourceX = source.x,
                        sourceY = source.y,
                        detectorId = detector.id,
                        detectorX = detector.x,
                        detectorY = detector.y,
                        value = value,
                        x = xMid,
                        y = yMid
                    )
                )
            }
        }

        timestampSeconds += durationDataRoundSeconds

        channelDisplayData = result

    }

    fun getBufferedDataSQI(windowSeconds: Float = 5.0f): Triple<List<Float>, List<List<Float>>, List<List<Float>>> {
        val cutoffTime = timestampSeconds - windowSeconds

        val startIndex = bufferedTimestamps.indexOfFirst { it >= cutoffTime }
        if (startIndex == -1) return Triple(emptyList(), emptyList(), emptyList())

        val timestamps = bufferedTimestamps.subList(startIndex, bufferedTimestamps.size)
        val red = bufferedRedSamples.subList(startIndex, bufferedRedSamples.size)
        val ir = bufferedIrSamples.subList(startIndex, bufferedIrSamples.size)

        return Triple(timestamps, red, ir)
    }

    fun updateSQIScores(windowSeconds: Float = 5.0f, logData:Boolean=false) {
        val fs = estimateSamplingRate()
        val minSamples = max(2f, windowSeconds * fs).toInt()

        val (timestamps, red, ir) = getBufferedDataSQI(windowSeconds)
        if (timestamps.size >= minSamples) {
            latestSignalRating = rateSignalQuality(red, ir, fs)
            if (logData) Log.i("SQI","Updated SQI (fs=%.2f Hz) = %s"
                .format(fs, latestSignalRating.joinToString { "%.2f".format(it) }))
        } else {
            Log.w("SQI", "Not enough data yet: have ${timestamps.size}, need $minSamples.")
        }
    }

    fun rateSignalQuality(
        redData: List<List<Float>>,     // Raw detector intensity at 740 nm (Volts)
        irData: List<List<Float>>,      // Raw detector intensity at 850 nm (Volts)
        fs: Float,                      // Sampling frequency (Hz)
        bias: Float = 1.5f              // Detector bias voltage to subtract
    ): List<Float> {
        val numSamples = redData.size
        if (numSamples == 0 || redData[0].isEmpty()) return emptyList()

        val numChannels = redData[0].size
        val sqiScores = MutableList(numChannels) { 1f }

        // Allowed raw input voltage range BEFORE bias removal
        val minAllowed = 1.40
        val maxAllowed = 4.80

        for (ch in 0 until numChannels) {

            val currChannel = ch + 1

            // ── Step 0: Raw-voltage sanity check (pre-bias) ─────────────────────
            val rawRed = redData.map { it[ch].toDouble() }
            val rawIr  = irData.map { it[ch].toDouble() }

            if (rawRed.any { it < minAllowed || it > maxAllowed } ||
                rawIr.any  { it < minAllowed || it > maxAllowed }) {
                sqiScores[ch] = 1f
                Log.w("SQICalc", "Ch $currChannel: raw input outside ${minAllowed}–${maxAllowed} V → SQI=1")
                continue
            }

//            Log.w("SQICalc", "Sampling Frequency: $fs")

            // ── Step 1: Bias correction ─────────────────────────────────────────
            val Ired = rawRed.map { it - bias }.toDoubleArray()
            val Iir  = rawIr.map  { it - bias }.toDoubleArray()

            // ── Step 2: Convert intensity → OD (baseline = mean of segment) ─────
            val I0_red = Ired.average()
            val I0_ir  = Iir.average()

            val OD2 = DoubleArray(numSamples) { i -> ln(I0_red/Ired[i]) } // 740 nm
            val OD1 = DoubleArray(numSamples) { i -> ln(I0_ir/Iir[i]) }   // 850 nm

            // ── Stage 1A: Simple SNR on RAW voltages (no OD) ─────────────────────────────

            // Pull raw volts for this channel
            val vRed = redData.map { it[ch].toDouble() }.toDoubleArray()
            val vIR  = irData.map { it[ch].toDouble() }.toDoubleArray()

            // Means
            val mR = vRed.average()
            val mI = vIR.average()

            // Standard deviations
            fun std(x: DoubleArray, mean: Double): Double {
                if (x.isEmpty()) return 0.0
                var s = 0.0
                for (v in x) s += (v - mean) * (v - mean)
                return sqrt(s / x.size)
            }
            val sR = std(vRed, mR)
            val sI = std(vIR,  mI)

            // --- Choose SNR definition ---
            // Bias‑corrected mean (recommended for photodiode rails with DC bias):
            val snrRed = abs(mR) / sR
            val snrIR  = abs(mI) / sI

            // If you truly want *strict raw* without removing detector bias, replace the two lines above with:
            // val snrRed = mR / sR
            // val snrIR  = mI  / sI

            // Use the better wavelength
            val snrBest = max(snrRed, snrIR)

            // Threshold (tune for your system). 2.0 ~= mean is 2× std
            val snrThresh = 2.0

            if (snrBest < snrThresh) {
                sqiScores[ch] = 1f
                Log.i("SQICalc", "Ch $currChannel: RAW-V SNR fail (${String.format("%.2f", snrBest)} < $snrThresh) → SQI=1")
                continue
            }

            // ── Stage 1B: Flatline check ────────────────────────────────────────
            if (getStandardDeviation(OD1) == 0.0 || getStandardDeviation(OD2) == 0.0) {
                sqiScores[ch] = 1f
                Log.i("SQICalc", "Ch $currChannel: flatline OD → SQI=1")
                continue
            }

            // ── Step 3: MBLL (HbO2/Hb concentrations, µM) ───────────────────────
            val e = arrayOf(
                doubleArrayOf(0.798, 0.757),  // 850 nm: [HbO2, Hb]
                doubleArrayOf(0.382, 1.322)   // 740 nm: [HbO2, Hb]
            )
            val dpf = 6.0
            val d = 3.0  // cm
            val L = d * dpf

            val oxy  = DoubleArray(numSamples)
            val deoxy = DoubleArray(numSamples)
            for (i in 0 until numSamples) {
                val a00 = e[0][0]; val a01 = e[0][1]
                val a10 = e[1][0]; val a11 = e[1][1]
                val b0 = OD1[i] / L
                val b1 = OD2[i] / L
                val det = a00 * a11 - a01 * a10
                if (det != 0.0) {
                    oxy[i]   = (b0 * a11 - b1 * a01) / det
                    deoxy[i] = (a00 * b1 - a10 * b0) / det
                } else {
                    oxy[i] = 0.0; deoxy[i] = 0.0
                }
            }

            // ── Step 4: Detrend + Bandpass (0.4–3 Hz) ───────────────────────────
            val band = doubleArrayOf(0.4, 3.0)
            val OD1_filt = firFilter(detrend(OD1), fs, band)
            val OD2_filt = firFilter(detrend(OD2), fs, band)
            val oxy_filt = firFilter(detrend(oxy), fs, band)
            val dxy_filt = firFilter(detrend(deoxy), fs, band)

            // ── Stage 1C: Hb imbalance ──────────────────────────────────────────
            fun rms(x: DoubleArray) = sqrt(x.sumOf { it*it } / x.size)
            val ratio = rms(oxy_filt) / rms(dxy_filt)
            if (ratio < 0.67) {
                sqiScores[ch] = 1f
                Log.i("SQICalc", "Ch $currChannel: Ratio is $ratio Hb imbalance < 0.67 → SQI=1")
                continue
            }

            // ── Stage 2: Autocorrelation difference (very high quality) ─────────
            val ac1 = autocorrelate(OD1_filt)
            val ac2 = autocorrelate(OD2_filt)
            val stdDiff = getStandardDeviation(ac1.zip(ac2) { a, b -> a - b })
            if ((1 / stdDiff) > 40) {
                sqiScores[ch] = 5f
                Log.i("SQICalc", "Ch $currChannel: autocorr criterion met → SQI=5")
                continue
            }
            else{
                Log.i("SQICalc", "Ch $currChannel: final SQI is $")
                sqiScores[ch] = 3.0f
            }

        }

        return sqiScores
    }


    fun getStandardDeviation(data: List<Double>): Double {
        if (data.size < 2) return 0.0
        val mean = data.average()
        val variance = data.sumOf { (it - mean).pow(2) } / (data.size - 1)
        return sqrt(variance)
    }

    fun getStandardDeviation(data: DoubleArray): Double =
        getStandardDeviation(data.toList())

    fun detrend(signal: DoubleArray): DoubleArray {
        val mean = signal.average()
        return signal.map { it - mean }.toDoubleArray()
    }


    fun firFilter(signal: DoubleArray, fs: Float, cutoff: DoubleArray): DoubleArray {
        val nyquist = fs / 2.0
        val taps = 101
        val output = DoubleArray(signal.size)

        for (i in signal.indices) {
            var acc = 0.0
            for (j in 0 until taps) {
                val t = i - j
                if (t in signal.indices) {
                    val sinc = when {
                        j - taps / 2 == 0 -> 2 * (cutoff[1] - cutoff[0]) / fs
                        else -> {
                            val x = Math.PI * (j - taps / 2)
                            (sin(2 * Math.PI * cutoff[1] * (j - taps / 2) / fs) -
                                    sin(2 * Math.PI * cutoff[0] * (j - taps / 2) / fs)) / x
                        }
                    }
                    val window = 0.54 - 0.46 * cos(2 * Math.PI * j / (taps - 1))
                    acc += signal[t] * sinc * window
                }
            }
            output[i] = acc
        }

        return output
    }

    fun autocorrelate(signal: DoubleArray): DoubleArray {
        val norm = signal.sumOf { it * it }
        val result = DoubleArray(signal.size)
        for (lag in signal.indices) {
            var acc = 0.0
            for (i in 0 until signal.size - lag) {
                acc += signal[i] * signal[i + lag]
            }
            result[lag] = acc / norm
        }
        return result
    }

    // ─── Extension Function ───────────────────────────────────────────────
    fun List<Float>.standardDeviation(): Float {
        if (this.size < 2) return 0f
        val mean = this.sum() / this.size
        val variance = this.fold(0f) { acc, x -> acc + (x - mean) * (x - mean) } / (this.size - 1)
        return kotlin.math.sqrt(variance)
    }

    fun resetTimeStamps(){
        // restart timestamps at Zero after Stop commmand given
        timestampSeconds = 0.0f;
    }

    fun isolateSpecificChannelData(tag: String = "fNIRSDisplayData", logging: Boolean = false) {

        if (channelDisplayData.isEmpty()) {
            Log.w(tag, "No display data to log.")
            return
        }

        var redData = mutableListOf<Float>()
        var irData = mutableListOf<Float>()

        val valuesLine = StringBuilder()
        valuesLine.append("Timestamp: %.2fs".format(timestampSeconds))

        for (channel in channelDisplayData){

            if (channel.type == ChannelType.LONG){

                val redIndex = (channel.sourceId-1)*2
                val irIndex = (channel.sourceId)*2

                val red  = dataArray[redIndex][channel.detectorId-1]
                val ir  = dataArray[irIndex][channel.detectorId-1]

                redData.add(red.toFloat())
                irData.add(ir.toFloat())

                if (logging){
                    valuesLine.append(", Ch${channel.channelNumber}R: %.3f".format(red))
                    valuesLine.append(", Ch${channel.channelNumber}IR: %.3f".format(ir))
                }

            }
            else{

                val redIndex = (channel.sourceId-1)*2 + 16
                val irIndex = (channel.sourceId)*2 + 16

                val red  = dataArray[redIndex][channel.detectorId-1]
                val ir  = dataArray[irIndex][channel.detectorId-1]

                redData.add(red.toFloat())
                irData.add(ir.toFloat())

                if (logging){
                    valuesLine.append(", Ch${channel.channelNumber}R: %.3f".format(red))
                    valuesLine.append(", Ch${channel.channelNumber}IR: %.3f".format(ir))
                }
            }

        }

        // Append the latest time stamp to the current round's fNIRS data
        appendSampleToCurrentRound(timestampSeconds, redData, irData)

        // Add this below:
        bufferedTimestamps.add(timestampSeconds)
        bufferedRedSamples.add(redData)
        bufferedIrSamples.add(irData)

        if(logging){
            Log.d(tag, valuesLine.toString())
        }

        pruneBuffers(sqiWindowSeconds)  // Keep only last 5 seconds

    }

    fun pruneBuffers(maxWindowSeconds: Float = 5.0f) {
        val cutoffTime = timestampSeconds - maxWindowSeconds
        val startIndex = bufferedTimestamps.indexOfFirst { it >= cutoffTime }

        if (startIndex > 0) {
            bufferedTimestamps.subList(0, startIndex).clear()
            bufferedRedSamples.subList(0, startIndex).clear()
            bufferedIrSamples.subList(0, startIndex).clear()
        }
    }

    fun startNewDataRound(ledIntensityValues: IntArray) {

        bufferedTimestamps.clear()
        bufferedRedSamples.clear()
        bufferedIrSamples.clear()
        latestSignalRating = emptyList()

        this.ledIntensityValues = ledIntensityValues
        resetTimeStamps()

        roundWiseData.add(
            DataRound(
                timestamps = mutableListOf(),
                redData = mutableListOf(),
                irData = mutableListOf(),
                stimuli = mutableListOf()
            )
        )
        resetTimeStamps()
    }

    fun appendSampleToCurrentRound(
        currentTimestamp: Float,
        redValues: List<Float>,    // length = nChannels
        irValues: List<Float>      // length = nChannels
    ) {

        val roundIndex = roundWiseData.lastIndex
        roundWiseData[roundIndex].timestamps.add(currentTimestamp)
        roundWiseData[roundIndex].redData.add(redValues)
        roundWiseData[roundIndex].irData.add(irValues)

    }

    fun saveDataLog(fileName: String) {
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "NIRDuino Companion/RawSessionData/"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        val file = File(baseDir, fileName)

        val content = StringBuilder()
        content.append(getHeader()).append("\n")
        csvLines.forEach { content.append(it).append("\n") }

        file.writeText(content.toString())
        Log.i("BackupCSV", "Saved backup CSV to ${file.absolutePath}")
    }


    fun saveSessionToFile(context: Context, deviceAlias: String, layoutName:String = "unknown"): String {

        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val timestampStr = sdf.format(java.util.Date())

        val fileName_csv = "${deviceAlias}_${layoutName}_$timestampStr.csv"
        val fileName_json = "${deviceAlias}_${layoutName}_$timestampStr.json"

        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "NIRDuino Companion/RawSessionData/"
        )
        if (!baseDir.exists()) baseDir.mkdirs()


        // Store data in large, legacy .csv format
        saveDataLog(fileName_csv)

        // Store data in new json format
        val file = File(baseDir, fileName_json)

        // Build the export structure
        val exportData = mapOf(
            "deviceAlias" to deviceAlias,
            "layoutName" to layoutName,
            "layout" to channelDisplayData.map {
                mapOf(
                    "channelNumber" to it.channelNumber,
                    "sourceId" to it.sourceId,
                    "detectorId" to it.detectorId,
                    "sourceX" to it.sourceX,
                    "sourceY" to it.sourceX,
                    "detectorX" to it.detectorX,
                    "detectorY" to it.detectorY,
                    "x" to it.x,
                    "y" to it.y,
                    "type" to it.type.name
                )
            },
            "rounds" to roundWiseData.map { round ->
                mapOf(
                    "timestamps" to round.timestamps,
                    "redData" to round.redData,
                    "irData" to round.irData,
                    "stimuli" to round.stimuli.map { stim ->
                        mapOf(
                            "label" to stim.label,
                            "onset" to stim.onset,
                            "duration" to stim.duration
                        )
                    }
                )
            }
        )

        // Save as JSON
        val json = Gson().toJson(exportData)
        file.writeText(json)

        Log.i("SessionSave", "Saved data to ${file.absolutePath}")

        return file.absolutePath
    }


}
