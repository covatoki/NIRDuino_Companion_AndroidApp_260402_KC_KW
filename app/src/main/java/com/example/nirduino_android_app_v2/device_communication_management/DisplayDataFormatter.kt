package com.example.nirduino_android_app_v2.device_communication_management

import android.content.Context
import java.io.File

enum class ChannelType { LONG, SHORT }

object DisplayDataFormatter {

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
