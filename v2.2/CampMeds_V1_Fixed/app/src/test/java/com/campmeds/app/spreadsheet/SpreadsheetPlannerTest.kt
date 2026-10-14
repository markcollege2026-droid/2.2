package com.campmeds.app.spreadsheet

import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Pure planner rules. Every test here corresponds to a guarantee in the import specification. */
class SpreadsheetPlannerTest {

    private val headers = listOf(
        "Patient ID", "Medication ID", "First Name", "Last Name", "Date of Birth", "Notes", "Conditions",
        "Medication", "NDC", "Strength", "Form", "Dosage Instructions", "Schedule Times", "Start Date",
        "End Date", "Bottle Expiration", "Purpose", "PRN"
    )

    private val p1 = "11111111-1111-4111-8111-111111111111"
    private val p2 = "22222222-2222-4222-8222-222222222222"
    private val m1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val m2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun sheetOf(vararg rows: Map<String, String>): RawSheet = RawSheet(
        fileName = "test.xlsx",
        headers = headers,
        rows = rows.mapIndexed { index, map -> RawRow(index + 2, headers.map { map[it].orEmpty() }) }
    )

    private fun columns(mode: ImportMode) = HeaderMapper.suggest(headers, mode).columns

    private fun patient(id: String, first: String, last: String, dob: LocalDate? = null, notes: String? = null) =
        Patient(id = id, name = "$first $last", firstName = first, lastName = last, dob = dob, notes = notes)

    private fun medication(
        id: String,
        patientId: String,
        name: String = "Lisinopril",
        strength: String = "10 mg",
        form: String = "TABLET",
        dosage: String = "1 tablet",
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        end: LocalDate? = null,
        purpose: String? = null
    ) = Medication(
        id = id,
        patientId = patientId,
        ndc = "0069-2587",
        name = name,
        strength = strength,
        form = form,
        dosageInstructions = dosage,
        scheduleTimes = times,
        administrationTimeIds = times.map { "" },
        startDate = LocalDate.of(2026, 7, 1),
        endDate = end,
        qrCode = "qr-$id",
        purpose = purpose
    )

    private fun snapshot(
        patients: List<Patient> = emptyList(),
        medications: List<Medication> = emptyList(),
        history: Set<String> = emptySet()
    ) = ImportSnapshot(patients, medications, emptyList(), history)

    private val baseSnapshot = snapshot(
        patients = listOf(patient(p1, "Mary", "Smith", LocalDate.of(2015, 3, 1))),
        medications = listOf(medication(m1, p1), medication(m2, p1, name = "Metformin", strength = "500 mg"))
    )

    private fun updateRow(
        patientId: String = p1,
        medicationId: String = m1,
        vararg extra: Pair<String, String>
    ): Map<String, String> = mapOf("Patient ID" to patientId, "Medication ID" to medicationId, *extra)

    private fun update(sheet: RawSheet, snap: ImportSnapshot = baseSnapshot) =
        SpreadsheetPlanner.plan(sheet, columns(ImportMode.UPDATE_EXISTING), ImportMode.UPDATE_EXISTING, snap)

    private fun newRecords(
        sheet: RawSheet,
        snap: ImportSnapshot = snapshot(),
        decisions: ImportDecisions = ImportDecisions()
    ) = SpreadsheetPlanner.plan(sheet, columns(ImportMode.NEW_RECORDS), ImportMode.NEW_RECORDS, snap, decisions)

    // ------------------------------------------------------------------ identity

    @Test
    fun reorderingRows_doesNotChangeIdentitiesOrChanges() {
        val rows = listOf(
            updateRow(p1, m1, "Notes" to "Peanut allergy", "Dosage Instructions" to "2 tablets"),
            updateRow(p1, m2, "Strength" to "850 mg"),
            updateRow(p1, m2, "Last Name" to "Smithers")
        )
        val forward = update(sheetOf(*rows.toTypedArray()))
        val reversed = update(sheetOf(*rows.reversed().toTypedArray()))

        assertEquals(
            forward.medicationUpdates.map { it.medicationId to it.changes }.toSet(),
            reversed.medicationUpdates.map { it.medicationId to it.changes }.toSet()
        )
        assertEquals(
            forward.patientUpdates.map { it.patientId to it.changes }.toSet(),
            reversed.patientUpdates.map { it.patientId to it.changes }.toSet()
        )
        assertTrue(forward.patientCreates.isEmpty() && reversed.patientCreates.isEmpty())
    }

    @Test
    fun repeatedPatientRows_createOnePatient_andOneMedicationPerRow() {
        val sheet = sheetOf(
            mapOf("First Name" to "Ana", "Last Name" to "Lopez", "Medication" to "Insulin", "Dosage Instructions" to "10 units",
                "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No"),
            mapOf("First Name" to "Ana", "Last Name" to "Lopez", "Medication" to "Aspirin", "Dosage Instructions" to "81 mg",
                "Schedule Times" to "09:00", "Start Date" to "2026-07-01", "PRN" to "No"),
            mapOf("First Name" to "ana", "Last Name" to "LOPEZ", "Medication" to "Vitamin D", "Dosage Instructions" to "1",
                "Schedule Times" to "12:00", "Start Date" to "2026-07-02", "PRN" to "Yes")
        )
        val plan = newRecords(sheet)
        assertEquals(1, plan.patientCreates.size)
        assertEquals(3, plan.medicationCreates.size)
        assertTrue(plan.medicationCreates.all { it.newPatientKey == plan.patientCreates.single().key })
        assertTrue(plan.patientCandidates.isEmpty())
    }

    // ------------------------------------------------------------------ new records

    @Test
    fun newRecords_sameNameWithConflictingDob_isConflictAndCreatesNothing() {
        val sheet = sheetOf(
            mapOf("First Name" to "Sam", "Last Name" to "Park", "Date of Birth" to "2014-01-05",
                "Medication" to "Ibuprofen", "Dosage Instructions" to "1", "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No"),
            mapOf("First Name" to "Sam", "Last Name" to "Park", "Date of Birth" to "2012-09-30",
                "Medication" to "Cetirizine", "Dosage Instructions" to "1", "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No")
        )
        val plan = newRecords(sheet)
        assertTrue(plan.patientCreates.isEmpty())
        assertTrue(plan.medicationCreates.isEmpty())
        assertTrue(plan.issues.any { it.kind == IssueKind.CONFLICT && it.field == "Date of Birth" })
    }

    @Test
    fun newRecords_samePatientInDatabase_needsDecision_andNeverMergesSilently() {
        val snap = snapshot(patients = listOf(patient(p1, "Mary", "Smith", LocalDate.of(1990, 5, 5))))
        val sheet = sheetOf(
            mapOf("First Name" to "Mary", "Last Name" to "Smith", "Medication" to "Lisinopril", "Dosage Instructions" to "1",
                "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No")
        )
        val undecided = newRecords(sheet, snap)
        assertEquals(listOf("name:mary smith"), undecided.unresolvedDecisions)
        assertTrue(undecided.patientCreates.isEmpty())
        assertTrue(undecided.medicationCreates.isEmpty())
        assertTrue(undecided.blockingReason(ImportDecisions()) != null)

        val asNew = newRecords(sheet, snap, ImportDecisions(patientChoices = mapOf("name:mary smith" to PatientChoice.CreateNew)))
        assertEquals(1, asNew.patientCreates.size)
        assertEquals(1, asNew.medicationCreates.size)
        assertTrue(asNew.unresolvedDecisions.isEmpty())

        val linked = newRecords(sheet, snap, ImportDecisions(patientChoices = mapOf("name:mary smith" to PatientChoice.UseExisting(p1))))
        assertTrue(linked.patientCreates.isEmpty())
        assertEquals(p1, linked.medicationCreates.single().draft.patientId)
        assertEquals(p1, linked.patientLinks.single().patientId)
    }

    @Test
    fun newRecords_medicationAlreadyForPatient_isDuplicateCandidate() {
        val snap = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith")),
            medications = listOf(medication(m1, p1, name = "Lisinopril", strength = "10 mg", form = "TABLET"))
        )
        val sheet = sheetOf(
            mapOf("First Name" to "Mary", "Last Name" to "Smith", "Medication" to "lisinopril", "Strength" to "10 MG",
                "Form" to "tablet", "Dosage Instructions" to "1", "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No")
        )
        val decided = newRecords(
            sheet, snap,
            ImportDecisions(
                patientChoices = mapOf("name:mary smith" to PatientChoice.UseExisting(p1)),
                medicationChoices = mapOf("row:2" to MedicationChoice.SKIP)
            )
        )
        assertEquals(1, decided.medicationCandidates.size)
        assertTrue(decided.medicationCreates.isEmpty())
        assertEquals(listOf(2), decided.skippedRows)

        val added = newRecords(
            sheet, snap,
            ImportDecisions(
                patientChoices = mapOf("name:mary smith" to PatientChoice.UseExisting(p1)),
                medicationChoices = mapOf("row:2" to MedicationChoice.ADD)
            )
        )
        assertEquals(1, added.medicationCreates.size)
        assertEquals(p1, added.medicationCreates.single().draft.patientId)
    }

    @Test
    fun newRecords_rowWithExistingCampMedsId_isInvalid_andUpdatesNothing() {
        val sheet = sheetOf(
            mapOf("Patient ID" to p1, "First Name" to "Mary", "Last Name" to "Smith")
        )
        val plan = newRecords(sheet, baseSnapshot)
        assertTrue(plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "Patient ID" })
        assertTrue(plan.patientCreates.isEmpty())
        assertTrue(plan.patientUpdates.isEmpty())
    }

    @Test
    fun newRecords_missingRequiredMedicationFields_areInvalidWithRowAndField() {
        val sheet = sheetOf(
            mapOf("First Name" to "Ben", "Medication" to "Amoxicillin", "Schedule Times" to "08:00", "Start Date" to "2026-07-01", "PRN" to "No")
        )
        val plan = newRecords(sheet)
        val issue = plan.issues.single { it.kind == IssueKind.INVALID_ROW }
        assertEquals(2, issue.rowNumber)
        assertEquals("Dosage Instructions", issue.field)
        assertTrue(plan.medicationCreates.isEmpty())
    }

    // ------------------------------------------------------------------ update existing

    @Test
    fun update_nameChangeWithPreservedId_updatesPatient_andCreatesNothing() {
        val sheet = sheetOf(updateRow(p1, m1, "First Name" to "Maria", "Last Name" to "Smith-Jones"))
        val plan = update(sheet)
        assertTrue(plan.patientCreates.isEmpty())
        val change = plan.patientUpdates.single()
        assertEquals(p1, change.patientId)
        assertEquals("Maria", change.target.firstName)
        assertTrue(change.changes.any { it.field == "First Name" && it.oldValue == "Mary" && it.newValue == "Maria" })
        assertTrue(change.changes.all { it.significant })
    }

    @Test
    fun update_editingOneCell_changesOnlyThatField() {
        val sheet = sheetOf(updateRow(p1, m1, "Dosage Instructions" to "2 tablets"))
        val plan = update(sheet)
        assertTrue(plan.patientUpdates.isEmpty())
        val change = plan.medicationUpdates.single()
        assertEquals(listOf("Dosage Instructions"), change.changes.map { it.field })
        assertEquals("2 tablets", change.target.dosageInstructions)
        assertEquals("Lisinopril", change.target.name)
        assertEquals("10 mg", change.target.strength)
        assertEquals(listOf(LocalTime.of(8, 0)), change.target.scheduleTimes)
    }

    @Test
    fun update_missingIds_areBlocked() {
        val noPatientId = sheetOf(mapOf("Medication ID" to m1, "Dosage Instructions" to "2"))
        val plan = update(noPatientId)
        assertTrue(plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "Patient ID" })
        assertTrue(plan.medicationUpdates.isEmpty())

        val newMedWithoutId = sheetOf(mapOf("Patient ID" to p1, "Medication" to "Aspirin", "Dosage Instructions" to "81"))
        val plan2 = update(newMedWithoutId)
        assertTrue(plan2.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "Medication ID" })
        assertTrue(plan2.medicationCreates.isEmpty())
    }

    @Test
    fun update_malformedUnknownOrMistypedIds_areBlocked() {
        val malformed = update(sheetOf(updateRow(patientId = "12345", medicationId = m1, "Strength" to "20 mg")))
        assertTrue(malformed.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "Patient ID" })
        assertTrue(malformed.medicationUpdates.isEmpty())

        val unknown = update(sheetOf(updateRow(patientId = "99999999-9999-4999-8999-999999999999", medicationId = m1)))
        assertTrue(unknown.issues.any { it.kind == IssueKind.INVALID_ROW })

        val medIdInPatientColumn = update(sheetOf(updateRow(patientId = m1, medicationId = m1, "Strength" to "20 mg")))
        val issue = medIdInPatientColumn.issues.single()
        assertEquals("Patient ID", issue.field)
        assertTrue(medIdInPatientColumn.medicationUpdates.isEmpty())
    }

    @Test
    fun update_duplicatedIdWithConflictingValues_isBlocked() {
        val sheet = sheetOf(
            updateRow(p1, m1, "Dosage Instructions" to "1 tablet"),
            updateRow(p1, m1, "Dosage Instructions" to "2 tablets")
        )
        val plan = update(sheet)
        assertTrue(plan.issues.any { it.kind == IssueKind.CONFLICT && it.field == "Dosage Instructions" })
        assertTrue(plan.medicationUpdates.isEmpty())
    }

    @Test
    fun update_duplicatedIdWithSameValues_isOneUpdate() {
        val sheet = sheetOf(
            updateRow(p1, m1, "Strength" to "20 mg"),
            updateRow(p1, m1, "Strength" to "20 mg")
        )
        val plan = update(sheet)
        assertEquals(1, plan.medicationUpdates.size)
        assertTrue(plan.issues.isEmpty())
    }

    @Test
    fun update_blankCells_neverClearExistingValues() {
        val snap = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith", LocalDate.of(2015, 3, 1), notes = "Peanut allergy")),
            medications = listOf(medication(m1, p1, end = LocalDate.of(2027, 1, 1), purpose = "Blood pressure"))
        )
        val sheet = sheetOf(updateRow(p1, m1, "Notes" to "", "End Date" to "", "Purpose" to "", "Date of Birth" to ""))
        val plan = update(sheet, snap)
        assertTrue(plan.patientUpdates.isEmpty())
        assertTrue(plan.medicationUpdates.isEmpty())
        assertFalse(plan.hasChanges)
    }

    @Test
    fun update_clearToken_emptiesOptionalField_andIsSignificant() {
        val snap = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith", notes = "Peanut allergy")),
            medications = listOf(medication(m1, p1, end = LocalDate.of(2027, 1, 1)))
        )
        val sheet = sheetOf(updateRow(p1, m1, "End Date" to "[CLEAR]", "Notes" to "[CLEAR]"))
        val plan = update(sheet, snap)
        val medChange = plan.medicationUpdates.single().changes.single()
        assertTrue(medChange.clears)
        assertTrue(medChange.significant)
        assertNull(plan.medicationUpdates.single().target.endDate)
        assertTrue(plan.requiresConfirmation)
        assertNull(plan.patientUpdates.single().target.notes)
    }

    @Test
    fun update_clearTokenOnRequiredField_isInvalid() {
        val sheet = sheetOf(updateRow(p1, m1, "First Name" to "[CLEAR]"))
        val plan = update(sheet)
        assertTrue(plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "First Name" })
        assertTrue(plan.patientUpdates.isEmpty())
    }

    @Test
    fun update_missingRows_neverCauseDeletionOrChange() {
        val extra = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith"), patient(p2, "Tom", "Brown")),
            medications = listOf(medication(m1, p1), medication(m2, p2, name = "Aspirin"))
        )
        val sheet = sheetOf(updateRow(p1, m1, "Strength" to "10 mg"))
        val plan = update(sheet, extra)
        assertFalse(plan.hasChanges)
        assertTrue(plan.absentRecords.any { it.id == p2 && it.type == "PATIENT" })
        assertTrue(plan.absentRecords.any { it.id == m2 && it.type == "MEDICATION" })
    }

    @Test
    fun update_significantMedicationChange_requiresConfirmation_butPurposeDoesNot() {
        val scheduleChange = update(sheetOf(updateRow(p1, m1, "Schedule Times" to "08:00, 20:00")))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), scheduleChange.medicationUpdates.single().target.scheduleTimes)
        assertTrue(scheduleChange.requiresConfirmation)
        assertNotNull(scheduleChange.medicationUpdates.single().target.administrationTimeIds)

        val purposeOnly = update(sheetOf(updateRow(p1, m1, "Purpose" to "Heart")))
        assertEquals(listOf("Purpose"), purposeOnly.medicationUpdates.single().changes.map { it.field })
        assertFalse(purposeOnly.medicationUpdates.single().changes.single().significant)
        assertFalse(purposeOnly.requiresConfirmation)
    }

    @Test
    fun update_identityChange_isFlagged_forDatedHistory() {
        val withHistory = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith")),
            medications = listOf(medication(m1, p1)),
            history = setOf(m1)
        )
        val plan = update(sheetOf(updateRow(p1, m1, "Medication" to "Lisinopril XR")), withHistory)
        val change = plan.medicationUpdates.single()
        assertTrue(change.identityChanged)
        assertEquals(m1, change.medicationId)
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun update_patientReassignment_isBlockedWhenHistoryExists() {
        val withHistory = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith"), patient(p2, "Tom", "Brown")),
            medications = listOf(medication(m1, p1)),
            history = setOf(m1)
        )
        val blocked = update(sheetOf(updateRow(p2, m1)), withHistory)
        assertTrue(blocked.medicationUpdates.isEmpty())
        assertTrue(blocked.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "Patient ID" })

        val allowed = update(sheetOf(updateRow(p2, m1)), snapshot(
            patients = listOf(patient(p1, "Mary", "Smith"), patient(p2, "Tom", "Brown")),
            medications = listOf(medication(m1, p1))
        ))
        val reassign = allowed.medicationUpdates.single()
        assertEquals(p2, reassign.target.patientId)
        assertTrue(reassign.changes.any { it.field == "Patient" && it.significant })
    }

    @Test
    fun update_invalidValues_areRejectedWithRowAndField() {
        val badDate = update(sheetOf(updateRow(p1, m1, "Start Date" to "2026-02-31")))
        val dateIssue = badDate.issues.single()
        assertEquals(2, dateIssue.rowNumber)
        assertEquals("Start Date", dateIssue.field)
        assertTrue(badDate.medicationUpdates.isEmpty())

        val badTime = update(sheetOf(updateRow(p1, m1, "Schedule Times" to "25:00")))
        assertEquals("Schedule Times", badTime.issues.single().field)
        assertTrue(badTime.medicationUpdates.isEmpty())
    }

    @Test
    fun update_endBeforeStart_isRejected() {
        val plan = update(sheetOf(updateRow(p1, m1, "Start Date" to "2026-07-10", "End Date" to "2026-07-01")))
        assertTrue(plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.field == "End Date" })
        assertTrue(plan.medicationUpdates.isEmpty())
    }

    @Test
    fun planning_isReadOnlyAndRepeatable() {
        val sheet = sheetOf(updateRow(p1, m1, "Dosage Instructions" to "2 tablets"))
        val before = baseSnapshot.medications.map { it.copy() }
        val first = update(sheet)
        val second = update(sheet)
        assertEquals(first.signature(), second.signature())
        assertEquals(before, baseSnapshot.medications)
    }

    @Test
    fun blockingReason_requiresDecisionsThenConfirmation() {
        val snap = snapshot(
            patients = listOf(patient(p1, "Mary", "Smith")),
            medications = listOf(medication(m1, p1))
        )
        val sheet = sheetOf(updateRow(p1, m1, "Schedule Times" to "09:00"))
        val plan = update(sheet, snap)
        assertNotNull(plan.blockingReason(ImportDecisions()))
        assertNull(plan.blockingReason(ImportDecisions(confirmSignificantChanges = true)))
    }

    @Test
    fun headerMapper_recognisesReorderedHeaders_andFlagsDuplicates() {
        val reordered = listOf("Last Name", "Medication", "first name", "Patient ID", "Medication ID", "Notes", "Notes")
        val mapping = HeaderMapper.suggest(reordered, ImportMode.UPDATE_EXISTING)
        assertEquals(0, mapping.columns[Field.LAST_NAME])
        assertEquals(1, mapping.columns[Field.MEDICATION])
        assertEquals(2, mapping.columns[Field.FIRST_NAME])
        assertEquals(listOf("Notes"), mapping.duplicateHeaders)
        assertTrue(mapping.needsReview)

        val clean = HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING)
        assertFalse(clean.needsReview)
        assertTrue(clean.missingRequired.isEmpty())
    }

    @Test
    fun headerMapper_missingRequiredColumns_needReview() {
        val mapping = HeaderMapper.suggest(listOf("Medication", "Dosage"), ImportMode.UPDATE_EXISTING)
        assertEquals(setOf(Field.PATIENT_ID, Field.MEDICATION_ID), mapping.missingRequired.toSet())
        assertTrue(mapping.needsReview)
    }
    @Test fun newMedicationRequiresExplicitPrnChoiceAndUpdateBlankKeepsIt() {
        val row = mapOf("First Name" to "Camper", "Medication" to "Example", "Dosage Instructions" to "1",
            "Schedule Times" to "08:00", "Start Date" to "2026-07-01")
        assertTrue(newRecords(sheetOf(row)).issues.any { it.field == "PRN" })
        val yes = newRecords(sheetOf(row + ("PRN" to "Yes")))
        assertTrue(yes.medicationCreates.single().draft.isPrn)
        val no = newRecords(sheetOf(row + ("PRN" to "No")))
        assertFalse(no.medicationCreates.single().draft.isPrn)
        assertTrue(newRecords(sheetOf(row + ("PRN" to "maybe"))).issues.any { it.field == "PRN" })
        val prnSnapshot = baseSnapshot.copy(medications = baseSnapshot.medications.map { it.copy(isPrn = true) })
        val changed = update(sheetOf(updateRow(p1, m1, "Dosage Instructions" to "2 tablets")), prnSnapshot)
        assertTrue(changed.medicationUpdates.single().target.isPrn)
    }

}
