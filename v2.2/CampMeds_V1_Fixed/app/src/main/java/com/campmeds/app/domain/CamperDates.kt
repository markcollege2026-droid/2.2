package com.campmeds.app.domain

import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Medication
import java.time.LocalDate

/** End dates are inclusive. Derived state means extending a stay restores a camper automatically. */
fun Patient.isActiveOn(day: LocalDate): Boolean = !isArchived &&
    (startDate == null || startDate <= day) && (endDate == null || endDate >= day)

fun Medication.isActiveOn(day: LocalDate): Boolean = !isArchived && startDate <= day &&
    (endDate == null || endDate >= day) && (discontinuedOn == null || discontinuedOn > day)
