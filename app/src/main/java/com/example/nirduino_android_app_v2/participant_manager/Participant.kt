package com.example.nirduino_android_app_v2.participant_manager

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.nirduino_android_app_v2.participant_manager.StringListConverter

@Entity(tableName = "participants")
@TypeConverters(StringListConverter::class)
data class Participant(
    @PrimaryKey val subjectId: String,
    val age: String?,
    val sex: String?,
    val handedness: String?,
    val weight: String?,
    val height: String?,
    val ethnicity: String?,
    val comments: String?,

    // New tag-based fields (multi-select)
    val groups: List<String>,
    val diagnoses: List<String>,
    val medications: List<String>,

    // Meta
    val createdOn: String,
    val updatedOn: String
)
