package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalTime
import java.util.UUID

/**
 * A named administration time that an administrator maintains once and everyone picks from, e.g.
 * "Breakfast 08:00". One row per named time (spec: named administration times).
 *
 * The *time* is what schedules are calculated from — the name is only what people read and choose.
 * Medications keep the identifier of the entries they were scheduled with (Medication.administrationTimeIds)
 * so that:
 *  - renaming an entry relabels every medication that uses it without touching any schedule,
 *  - changing an entry's time can be applied to the schedules that reference it in one place, and
 *  - removing an entry leaves the medications' times exactly as they were (the schedule becomes a plain
 *    custom time instead of silently disappearing).
 *
 * Names are unique (case-insensitively) so the dropdown can never show two identical choices.
 */
@Entity(
    tableName = "administration_times",
    indices = [Index(value = ["name"], unique = true)]
)
data class AdministrationTime(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val time: LocalTime
)
