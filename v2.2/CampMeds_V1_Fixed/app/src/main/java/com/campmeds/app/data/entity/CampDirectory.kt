package com.campmeds.app.data.entity

import androidx.room.*
import com.campmeds.app.data.converter.NameListConverters
import java.time.LocalDate
import java.util.UUID

/** These are templates/options only: changing or removing one never rewrites a saved camper. */
@Entity(tableName = "camp_sessions")
data class CampSession(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String,
    val startDate: LocalDate, val endDate: LocalDate)

@Entity(tableName = "divisions")
data class Division(@PrimaryKey val name: String, val colorHex: String)

@Entity(tableName = "bunk_options")
data class BunkOption(@PrimaryKey val name: String,
    @field:TypeConverters(NameListConverters::class) val counselors: List<String> = emptyList(),
    val division: String? = null)
