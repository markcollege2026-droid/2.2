package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication
import java.util.UUID

/**
 * Rules for prepackaged medication groups: medications that were packaged together and are meant to be
 * administered together (spec: group administration for prepackaged medications).
 *
 * Deliberately narrow:
 *  - a group only ever contains medications of ONE patient ([membersOf] enforces the patient),
 *  - nothing is grouped automatically — a medication joins a group only because the form said so
 *    ([Medication.isGroupAdministered] + [Medication.prepackagedGroupId]),
 *  - being scheduled at the same time is never a reason to group anything.
 */
object PrepackagedGroups {

    /** One prepackaged group and its member medications (all of the same patient). */
    data class Group(val id: String, val members: List<Medication>) {
        /** Human label used in dropdowns and cards, e.g. "Zyrtec + Flonase". */
        val label: String get() = members.joinToString(" + ") { it.name }
    }

    /**
     * The members of [groupId] that belong to [patientId]. A medication of another patient that somehow
     * carries the same identifier is ignored rather than administered with this patient's medications.
     */
    fun membersOf(medications: List<Medication>, groupId: String, patientId: String): List<Medication> =
        medications.filter {
            !it.isArchived && it.isGroupAdministered && it.prepackagedGroupId == groupId && it.patientId == patientId
        }.sortedBy { it.name.lowercase() }

    /** Every prepackaged group of one patient, ordered by label — used for the form's dropdown. */
    fun groupsOfPatient(medications: List<Medication>, patientId: String): List<Group> =
        medications.filter {
            !it.isArchived && it.isGroupAdministered && !it.prepackagedGroupId.isNullOrBlank() && it.patientId == patientId
        }
            .groupBy { it.prepackagedGroupId!! }
            .map { (id, members) -> Group(id, members.sortedBy { it.name.lowercase() }) }
            .sortedBy { it.label.lowercase() }

    /**
     * Validation for saving a medication: a grouped medication must carry a group identifier, and must not
     * point at a group that belongs to a different patient. Returns an error message or null.
     */
    fun validate(medication: Medication, existingMedications: List<Medication>): String? {
        val groupId = medication.prepackagedGroupId
        if (!medication.isGroupAdministered) {
            return if (groupId.isNullOrBlank()) null
            else "Turn off \"Administer together with other selected medications\", or choose a group."
        }
        if (groupId.isNullOrBlank()) return "Choose which prepackaged group this medication belongs to."
        val foreign = existingMedications.any {
            it.id != medication.id && !it.isArchived && it.prepackagedGroupId == groupId &&
                it.patientId != medication.patientId
        }
        return if (foreign) "That prepackaged group belongs to a different patient." else null
    }

    /** A fresh group identifier, used when the form says "New group". */
    fun newGroupId(): String = UUID.randomUUID().toString()
}
