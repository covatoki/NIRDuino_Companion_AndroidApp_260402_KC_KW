package com.example.nirduino_android_app_v2.device_communication_management

import android.content.Context
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

enum class ChannelType { LONG, SHORT }

data class Channel(
    val sourceId: Int,
    val detectorId: Int,
    val distanceMm: Float,
    val x: Float,
    val y: Float,
    val type: ChannelType
)

data class DisplayChannelData(
    val channelNumber: Int,
    val type: ChannelType,
    val sourceId: Int,
    val detectorId: Int,
    val value: Double,
    val x: Float,
    val y: Float
)

object DisplayDataFormatter {

    fun calculateChannelGeometry(layoutOverlayElements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement>): List<Channel> {
        val sources = layoutOverlayElements.filter { it.isSource }
        val detectors = layoutOverlayElements.filter { !it.isSource }

        val channels = mutableListOf<Channel>()
        for (source in sources) {
            for (detector in detectors) {
                val dx = source.x - detector.x
                val dy = source.y - detector.y
                val distance = sqrt((dx).pow(2) + (dy).pow(2))

                val type = when {
                    distance in 25.0..35.0 -> ChannelType.LONG
                    distance <= 15 -> ChannelType.SHORT
                    else -> continue
                }

                val xMid = (source.x + detector.x) / 2
                val yMid = (source.y + detector.y) / 2

                channels.add(Channel(source.id, detector.id, distance, xMid, yMid, type))
            }
        }
        return channels
    }

    fun extractDisplayData(
        parser: DataParsingAndProcessing,
        channels: List<Channel>
    ): List<DisplayChannelData> {
        val output = mutableListOf<DisplayChannelData>()
        var channelIndex = 0

        for (channel in channels.sortedWith(compareBy({ it.y }, { it.x }))) {
            val value = when (channel.type) {
                ChannelType.LONG -> parser.dataArray[channel.sourceId][channel.detectorId]
                ChannelType.SHORT -> parser.dataArray[32][channel.detectorId]
            }

            output.add(
                DisplayChannelData(
                    channelNumber = channelIndex++,
                    type = channel.type,
                    sourceId = channel.sourceId,
                    detectorId = channel.detectorId,
                    value = value,
                    x = channel.x,
                    y = channel.y
                )
            )
        }

        return output
    }

    fun saveDisplayDataToCSV(
        context: Context,
        data: List<DisplayChannelData>,
        filename: String = "filtered_fnirs_display_data.csv"
    ) {
        val file = File(context.getExternalFilesDir(null), filename)
        file.bufferedWriter().use { out ->
            out.write("Channel,Type,Value,X,Y\n")
            data.forEach {
                out.write("${it.channelNumber},${it.type},${it.value},${it.x},${it.y}\n")
            }
        }
    }
}
