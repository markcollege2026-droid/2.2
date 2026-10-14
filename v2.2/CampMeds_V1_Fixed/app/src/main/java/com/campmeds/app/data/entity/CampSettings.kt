package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stable field keys keep values separate even when an administrator renames their labels. */
@Entity(tableName = "camp_settings")
data class CampSettings(
    @PrimaryKey val id: Int = 1,
    val startDates: List<String> = emptyList(),
    val endDates: List<String> = emptyList(),
    val bunkLabel: String = "Bunk",
    val counselorLabel: String = "Counselor",
    val divisionLabel: String = "Camper division",
    @androidx.room.ColumnInfo(defaultValue = "1") val bunkEnabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val counselorEnabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val allowCounselorOverride: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "'[]'")
    @androidx.room.TypeConverters(com.campmeds.app.data.converter.NameListConverters::class)
    val counselorOptions: List<String> = emptyList()
)
