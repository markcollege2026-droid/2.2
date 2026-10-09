package com.campmeds.app.data.converter

import androidx.room.TypeConverter
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Role
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * All Room type converters for the app. Times/dates are stored as ISO-8601 strings so the
 * encrypted SQLite file stays human-diffable if ever inspected during development.
 */
class Converters {

    // ---- LocalDate ----
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    // ---- LocalDateTime ----
    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? = value?.let { LocalDateTime.parse(it) }

    // ---- LocalTime list (medication schedule times) ----
    @TypeConverter
    fun fromTimeList(value: List<LocalTime>?): String? = value?.joinToString(",") { it.toString() }

    @TypeConverter
    fun toTimeList(value: String?): List<LocalTime> =
        if (value.isNullOrBlank()) emptyList()
        else value.split(",").map { LocalTime.parse(it) }

    // ---- Enums ----
    @TypeConverter
    fun fromRole(value: Role): String = value.name

    @TypeConverter
    fun toRole(value: String): Role = Role.valueOf(value)

    @TypeConverter
    fun fromDoseStatus(value: DoseStatus): String = value.name

    @TypeConverter
    fun toDoseStatus(value: String): DoseStatus = DoseStatus.valueOf(value)
}
