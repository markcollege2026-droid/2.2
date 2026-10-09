package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/**
 * [id] is the stable identifier (also what the patient QR code encodes).
 * [name] is the full display name ("First Last"). It is kept as a stored column for backward
 * compatibility with V1 data and is always (re)derived from [firstName]/[lastName] by
 * CampMedsRepository.savePatient, so callers never have to keep the two in sync by hand.
 * [isArchived] = "deleted" from the user's point of view. Patients are never hard-deleted so that
 * administration history (and the patient's name on exports) is preserved.
 */
@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dob: LocalDate? = null,
    val notes: String? = null, // allergies, etc. — free text
    val firstName: String = "",
    val lastName: String = "",
    val conditions: String? = null,
    val isArchived: Boolean = false
)
