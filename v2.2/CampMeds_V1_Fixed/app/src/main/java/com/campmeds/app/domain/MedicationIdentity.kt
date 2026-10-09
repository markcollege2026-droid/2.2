package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication

/**
 * What makes a medication a *different* medication for record-keeping purposes.
 *
 * If any of these change on an existing record, the edit must NOT overwrite it: the old record is archived
 * (keeping its history and export sheet) and a new record with a new id and a new QR code is created.
 * Everything else (schedule, instructions, dates, expiration, pill count, purpose) is edited in place.
 * Kept in one function so the rule is easy to narrow (e.g. to name only) if desired.
 */
object MedicationIdentity {
    fun changed(old: Medication, new: Medication): Boolean =
        old.name.trim() != new.name.trim() ||
            old.ndc.trim() != new.ndc.trim() ||
            old.strength.trim() != new.strength.trim() ||
            old.form.trim() != new.form.trim()
}
