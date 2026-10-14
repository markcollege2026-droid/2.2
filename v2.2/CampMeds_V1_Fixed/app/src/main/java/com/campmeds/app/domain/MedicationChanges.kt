package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.ChangeHistoryEntry
import java.time.LocalDateTime

object MedicationChanges {
    private fun values(m: Medication): Map<String, String?> = linkedMapOf(
        "Name" to m.name, "NDC" to m.ndc, "Strength" to m.strength, "Form" to m.form,
        "Dosage instructions" to m.dosageInstructions, "Schedule" to m.scheduleTimes.joinToString(),
        "Administration times" to m.administrationTimeIds.joinToString(),
        "Start date" to m.startDate.toString(), "End date" to m.endDate?.toString(),
        "Bottle expiration" to m.bottleExpiration?.toString(), "Pill count" to m.pillCountEntered?.toString(),
        "PRN" to m.isPrn.toString(), "Purpose" to m.purpose, "QR required" to m.requireQrScan.toString(),
        "Grouped" to m.isGroupAdministered.toString(), "Group" to m.prepackagedGroupId,
        "Group name" to m.prepackagedGroupName, "Manual entry" to m.isException.toString(),
        "Archived" to m.isArchived.toString(), "Discontinued effective" to m.discontinuedOn?.toString()
    )
    fun between(old: Medication, new: Medication, actor: String?, now: LocalDateTime = LocalDateTime.now()): List<ChangeHistoryEntry> {
        val previous = values(old)
        return values(new).filter { (key, value) -> previous[key] != value }.map { (key, value) ->
            ChangeHistoryEntry(importId = "manual", changedAt = now, actorUserId = actor,
                recordType = "MEDICATION", recordId = old.id, field = key,
                oldValue = previous[key], newValue = value, significant = true)
        }
    }
}
