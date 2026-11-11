package com.campmeds.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import com.campmeds.app.repository.SpreadsheetImportRepository
import com.campmeds.app.spreadsheet.HeaderMapper
import com.campmeds.app.spreadsheet.ImportDecisions
import com.campmeds.app.spreadsheet.ImportMode
import com.campmeds.app.spreadsheet.IssueKind
import com.campmeds.app.spreadsheet.RawRow
import com.campmeds.app.spreadsheet.RawSheet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Scenario B of the import contract, at database level: what an *update* import may and may not change.
 *
 * The existing [SpreadsheetImportIntegrationTest] proves atomicity, rollback, stale previews and identity changes.
 * This class covers the data-integrity rules that sit on top of those: explicit clearing, records that must be
 * left alone (archived rows, medications with history moved to another patient, records missing from the sheet).
 */
@RunWith(AndroidJUnit4::class)
class ImportDataIntegrityTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: User

    private val headers = listOf(
        "Patient ID", "Medication ID", "First Name", "Last Name", "Date of Birth", "Notes", "Conditions",
        "Medication", "NDC", "Strength", "Form", "Dosage Instructions", "Schedule Times", "Start Date",
        "End Date", "Bottle Expiration", "Purpose"
    )

    private val updateColumns by lazy { HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING).columns }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        admin = User(name = "Admin", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
        runBlocking { db.userDao().upsert(admin) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sheet(vararg rows: Map<String, String>) = RawSheet(
        fileName = "update-test.xlsx",
        headers = headers,
        rows = rows.mapIndexed { i, m -> RawRow(i + 2, headers.map { h -> m[h].orEmpty() }) }
    )

    private suspend fun patient(name: String, archived: Boolean = false, notes: String? = null): Patient {
        val p = Patient(
            name = name,
            firstName = name.substringBefore(' '),
            lastName = name.substringAfter(' ', ""),
            notes = notes,
            isArchived = archived
        )
        db.patientDao().upsert(p)
        return p
    }

    private suspend fun medication(
        patientId: String,
        name: String,
        endDate: LocalDate? = null,
        archived: Boolean = false
    ): Medication {
        val m = Medication(
            patientId = patientId,
            ndc = "0069-2587",
            name = name,
            strength = "500 mg",
            form = "TABLET",
            dosageInstructions = "1 tablet",
            scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = LocalDate.of(2026, 7, 1),
            endDate = endDate,
            qrCode = "qr-$name",
            isArchived = archived
        )
        db.medicationDao().upsert(m)
        return m
    }

    private suspend fun givenDose(medicationId: String) = db.doseLogDao().upsert(
        DoseLog(
            medicationId = medicationId,
            scheduledFor = LocalDateTime.of(2026, 7, 2, 8, 0),
            status = DoseStatus.GIVEN,
            loggedAt = LocalDateTime.of(2026, 7, 2, 8, 5),
            loggedByUserId = admin.id
        )
    )

    private suspend fun apply(repo: SpreadsheetImportRepository, s: RawSheet, decisions: ImportDecisions): SpreadsheetImportRepository.Outcome {
        val plan = repo.preview(s, updateColumns, ImportMode.UPDATE_EXISTING, decisions)
        return repo.apply(admin, s, updateColumns, ImportMode.UPDATE_EXISTING, decisions, plan.signature())
    }

    /** The reason the import wrote to import_history; makes an unexpected rollback diagnosable. */
    private suspend fun recordedReason(): String =
        db.importHistoryDao().getImports().firstOrNull()?.message.orEmpty()

    // ------------------------------------------------------------------ explicit clearing

    @Test
    fun clearToken_emptiesOnlyThatField_andHistorySurvives() = runBlocking {
        val p = patient("Ann Lee", notes = "penicillin allergy")
        val m = medication(p.id, "Metformin", endDate = LocalDate.of(2026, 12, 31))
        givenDose(m.id)

        val decisions = ImportDecisions(confirmSignificantChanges = true)
        val repo = SpreadsheetImportRepository(db, backups = null)
        val outcome = apply(
            repo,
            sheet(mapOf("Patient ID" to p.id, "Medication ID" to m.id, "End Date" to "[CLEAR]", "Notes" to "[CLEAR]")),
            decisions
        )

        assertTrue(
            "clearing must be applied, was: $outcome (recorded: ${recordedReason()})",
            outcome is SpreadsheetImportRepository.Outcome.Completed
        )
        assertNull("an explicit [CLEAR] must empty the end date", db.medicationDao().getById(m.id)!!.endDate)
        assertNull("an explicit [CLEAR] must empty the notes", db.patientDao().getById(p.id)!!.notes)
        assertEquals("the medication must not be replaced", m.id, db.medicationDao().getById(m.id)!!.id)
        assertEquals("administration history must be untouched", 1, db.doseLogDao().getAll().size)
        assertEquals(m.id, db.doseLogDao().getAll().single().medicationId)
    }

    @Test
    fun anEmptyCellIsNotAClear() = runBlocking {
        val p = patient("Ann Lee", notes = "penicillin allergy")
        val m = medication(p.id, "Metformin", endDate = LocalDate.of(2026, 12, 31))

        val repo = SpreadsheetImportRepository(db, backups = null)
        val outcome = apply(
            repo,
            sheet(mapOf("Patient ID" to p.id, "Medication ID" to m.id, "End Date" to "", "Notes" to "")),
            ImportDecisions()
        )

        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)
        assertEquals("an empty cell must not erase data",
            LocalDate.of(2026, 12, 31), db.medicationDao().getById(m.id)!!.endDate)
        assertEquals("penicillin allergy", db.patientDao().getById(p.id)!!.notes)
        assertEquals(0, (outcome as SpreadsheetImportRepository.Outcome.Completed).summary.fieldsChanged)
    }

    // ------------------------------------------------------------------ records that must be refused

    @Test
    fun archivedPatientRow_isRejected_andTheRecordIsNotTouched() = runBlocking {
        val p = patient("Old Record", archived = true, notes = "original")
        val m = medication(p.id, "Metformin")

        val repo = SpreadsheetImportRepository(db, backups = null)
        val plan = repo.preview(
            sheet(mapOf("Patient ID" to p.id, "Medication ID" to m.id, "Notes" to "changed", "Dosage Instructions" to "2")),
            updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions(confirmSignificantChanges = true)
        )
        assertTrue(
            "an archived patient must be reported as an invalid row",
            plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.message.contains("archived") }
        )

        val outcome = apply(
            repo,
            sheet(mapOf("Patient ID" to p.id, "Medication ID" to m.id, "Notes" to "changed", "Dosage Instructions" to "2")),
            ImportDecisions(confirmSignificantChanges = true)
        )
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)
        assertEquals("original", db.patientDao().getById(p.id)!!.notes)
        assertEquals("1 tablet", db.medicationDao().getById(m.id)!!.dosageInstructions)
        assertTrue("the archived record must stay archived", db.patientDao().getById(p.id)!!.isArchived)
    }

    @Test
    fun medicationWithHistory_cannotBeMovedToAnotherPatientByImport() = runBlocking {
        val from = patient("Ann Lee")
        val to = patient("Bo Chen")
        val m = medication(from.id, "Metformin")
        givenDose(m.id)

        val repo = SpreadsheetImportRepository(db, backups = null)
        val row = mapOf("Patient ID" to to.id, "Medication ID" to m.id, "Dosage Instructions" to "2 tablets")
        val plan = repo.preview(sheet(row), updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions(confirmSignificantChanges = true))
        assertTrue(
            "moving a medication with history must be refused",
            plan.issues.any { it.kind == IssueKind.INVALID_ROW && it.message.contains("moved") }
        )

        val outcome = apply(repo, sheet(row), ImportDecisions(confirmSignificantChanges = true))
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)
        assertEquals("the medication must stay with its patient", from.id, db.medicationDao().getById(m.id)!!.patientId)
        assertEquals("1 tablet", db.medicationDao().getById(m.id)!!.dosageInstructions)
        assertEquals(1, db.doseLogDao().getAll().size)
    }

    // ------------------------------------------------------------------ nothing is deleted for absence

    @Test
    fun recordsMissingFromTheSheet_areReported_andNeverDeleted() = runBlocking {
        val p1 = patient("Ann Lee")
        val m1 = medication(p1.id, "Metformin")
        val p2 = patient("Bo Chen")
        val m2 = medication(p2.id, "Aspirin")
        givenDose(m1.id)

        val repo = SpreadsheetImportRepository(db, backups = null)
        val row = mapOf("Patient ID" to p1.id, "Medication ID" to m1.id, "Dosage Instructions" to "2 tablets")
        val plan = repo.preview(sheet(row), updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions(confirmSignificantChanges = true))
        assertTrue("absence must be reported", plan.absentRecords.any { it.id == p2.id })
        assertTrue("absence must be reported", plan.absentRecords.any { it.id == m2.id })

        val outcome = apply(repo, sheet(row), ImportDecisions(confirmSignificantChanges = true))
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)

        assertEquals("no patient may be deleted or archived by an import", 2, db.patientDao().getAllIncludingArchived().size)
        assertEquals("no medication may be deleted or archived by an import", 2,
            db.medicationDao().getAllIncludingArchived().size)
        assertTrue(db.patientDao().getAllIncludingArchived().none { it.isArchived })
        assertTrue(db.medicationDao().getAllIncludingArchived().none { it.isArchived })
        assertEquals("the row that was present must still be applied", "2 tablets",
            db.medicationDao().getById(m1.id)!!.dosageInstructions)
        assertEquals("1 tablet", db.medicationDao().getById(m2.id)!!.dosageInstructions)
        assertEquals(1, db.doseLogDao().getAll().size)
    }
}
