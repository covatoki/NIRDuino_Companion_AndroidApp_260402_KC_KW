package com.example.nirduino_android_app_v2.device_communication_management

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Kotlin adaptation of DataParsingAndProcessing integrated inside BLEDeviceConnection

class DataParsingAndProcessing {

    private val numSources = 33
    private val numDetectors = 16
    private val numDataPointsPerSource = 17
    private val numBytesPerValue = 4
    private val bufferSize = 100

    val receivedDataArray = ByteArray(numSources * numDataPointsPerSource * numBytesPerValue)
    val darkCurrentMeasurements = DoubleArray(16)
    val ledIntensityValues = IntArray(32) { 8 }
    val dataArray = Array(numSources) { DoubleArray(numDetectors) }

    var durationDataRound: Int = 0
    var durationDataRoundSeconds: Float = 0f

    private var isDataSet1Ready = false
    private var isDataSet2Ready = false
    private var isDataSet3Ready = false
    private var isDataSet4Ready = false
    var isDataReady = false

    fun convertByteToChannelData(wrap: ByteBuffer): Boolean {
        wrap.order(ByteOrder.LITTLE_ENDIAN)

        when (wrap.capacity()) {
            289 -> {
                for (i in 1..31) {
                    ledIntensityValues[i - 1] = wrap.getInt((i - 1) * 4)
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
                processDataArray()
                durationDataRoundSeconds = durationDataRound / 1000.0f
                durationDataRound = 0
                isDataReady = isDataSet1Ready && isDataSet2Ready && isDataSet3Ready && isDataSet4Ready
                isDataSet1Ready = false
                isDataSet2Ready = false
                isDataSet3Ready = false
                isDataSet4Ready = false
            }
            else -> return false
        }
        return isDataReady
    }

    private fun transferToReceivedDataArray(overwriteStartIndex: Int, wrap: ByteBuffer) {
        for (i in 4 until wrap.capacity()) {
            receivedDataArray[overwriteStartIndex + i - 4] = wrap.get(i)
        }
    }

    private fun processDataArray() {
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
            } else {
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
}
