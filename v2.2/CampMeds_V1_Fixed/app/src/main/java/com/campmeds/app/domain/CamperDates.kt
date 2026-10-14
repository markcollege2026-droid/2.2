package com.campmeds.app.domain

import com.campmeds.app.data.entity.Patient
import java.time.LocalDate

/** End dates are inclusive. Automatic archive is a view, not a destructive state change. */
fun Patient.isActiveOn(day: LocalDate): Boolean =
    !isArchived && (startDate == null || day >= startDate) && (endDate == null || day <= endDate)
