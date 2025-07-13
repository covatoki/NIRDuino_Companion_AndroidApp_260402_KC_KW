package com.example.nirduino_android_app_v2.participant_manager

import androidx.room.TypeConverter

class StringListConverter {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString(",")

    @TypeConverter
    fun toList(data: String): List<String> = if (data.isEmpty()) emptyList() else data.split(",")
}
