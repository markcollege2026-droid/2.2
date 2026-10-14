package com.campmeds.app.domain

import com.campmeds.app.data.entity.*
import java.time.LocalDateTime

/** Explicit field list avoids reflection and keeps both old and new values in the encrypted audit trail. */
fun medicationChanges(old: Medication, new: Medication, actor: String?): List<ChangeHistoryEntry> {
    fun values(m: Medication): Map<String, Any?> = linkedMapOf(
        "name" to m.name, "NDC" to m.ndc, "strength" to m.strength, "form" to m.form,
        "dosage" to m.dosageInstructions, "schedule" to m.scheduleTimes,
        "administration times" to m.administrationTimeIds, "start date" to m.startDate,
        "end date" to m.endDate, "bottle expiration" to m.bottleExpiration,
        "pill count" to m.pillCountEntered, "purpose" to m.purpose, "PRN" to m.isPrn,
        "QR required" to m.requireQrScan, "grouped" to m.isGroupAdministered,
        "group ID" to m.prepackagedGroupId, "group name" to m.prepackagedGroupName,
        "manual entry" to m.isException, "archived" to m.isArchived,
        "effective end date" to m.effectiveEndDate, "discontinued at" to m.discontinuedAt
    )
    val before = values(old)
    val now = LocalDateTime.now()
    return values(new).filter { (key, value) -> before[key] != value }.map { (key, value) ->
        ChangeHistoryEntry(importId = "manual", changedAt = now, actorUserId = actor,
            recordType = "MEDICATION", recordId = old.id, field = key,
            oldValue = before[key]?.toString(), newValue = value?.toString(), significant = true)
    }
}
