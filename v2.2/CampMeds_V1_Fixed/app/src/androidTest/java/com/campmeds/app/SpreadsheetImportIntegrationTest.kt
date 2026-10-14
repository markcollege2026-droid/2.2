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
import com.campmeds.app.spreadsheet.RawRow
import com.campmeds.app.spreadsheet.RawSheet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Database-level guarantees of the spreadsheet import, run against a real Room database (in memory, same schema).
 * Each test proves a rule from the import specification: atomic commit and rollback, cancellation, stale previews,
 * administrator-only writes, and preservation of administration history.
 */
@RunWith(AndroidJUnit4::class)
class SpreadsheetImportIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: User
    private lateinit var provider: User

    private val headers = listOf(
        "Patient ID", "Medication ID", "First Name", "Last Name", "Date of Birth", "Notes", "Conditions",
        "Medication", "NDC", "Strength", "Form", "Dosage Instructions", "Schedule Times", "Start Date",
        "End Date", "Bottle Expiration", "Purpose"
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        admin = User(name = "Admin", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
        provider = User(name = "Provider", role = Role.PROVIDER, pinHash = "h", pinSalt = "s")
        runBlocking {
            db.userDao().upsert(admin)
            db.userDao().upsert(provider)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sheet(vararg rows: Map<String, String>) = RawSheet(
        fileName = "import-test.xlsx",
        headers = headers,
        rows = rows.mapIndexed { i, m -> RawRow(i + 2, headers.map { h -> m[h].orEmpty() }) }
    )

    private val columns = HeaderMapper.suggest(headers, ImportMode.NEW_RECORDS).columns

    private fun seedPatientWithMedicationAndHistory(): Triple<Patient, Medication, DoseLog> = runBlocking {
        val patient = Patient(name = "Ann Lee", firstName = "Ann", lastName = "Lee")
        val medication = Medication(
            patientId = patient.id,
            ndc = "0069-2587",
            name = "Metformin",
            strength = "500 mg",
            form = "TABLET",
            dosageInstructions = "1 tablet",
            scheduleTimes = listOf(LocalTime.of(8, 0)),
            administrationTimeIds = emptyList(),
            startDate = LocalDate.of(2026, 7, 1),
            qrCode = "qr-seed"
        )
        val log = DoseLog(
            medicationId = medication.id,
            scheduledFor = LocalDateTime.of(2026, 7, 2, 8, 0),
            status = DoseStatus.GIVEN,
            loggedAt = LocalDateTime.of(2026, 7, 2, 8, 5),
            loggedByUserId = admin.id
        )
        db.patientDao().upsert(patient)
        db.medicationDao().upsert(medication)
        db.doseLogDao().upsert(log)
        Triple(patient, medication, log)
    }

    @Test
    fun newRecordImport_createsRecords_andWritesHistory_withoutDoses() = runBlocking {
        val repo = SpreadsheetImportRepository(db, backups = null)
        val s = sheet(
            mapOf("First Name" to "Zoe", "Last Name" to "Quinn", "Medication" to "Aspirin",
                "Dosage Instructions" to "81 mg", "Schedule Times" to "08:00", "Start Date" to "2026-07-01")
        )
        val plan = repo.preview(s, columns, ImportMode.NEW_RECORDS, ImportDecisions())
        val outcome = repo.apply(admin, s, columns, ImportMode.NEW_RECORDS, ImportDecisions(), plan.signature())

        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)
        assertEquals(1, db.patientDao().getAllIncludingArchived().size)
        assertEquals(1, db.medicationDao().getAllIncludingArchived().size)
        assertTrue(db.doseLogDao().getAll().isEmpty())
        assertEquals("COMPLETED", db.importHistoryDao().getImports().single().outcome)
    }

    @Test
    fun failedImport_rollsBackEveryChange() = runBlocking {
        val repo = SpreadsheetImportRepository(db, backups = null, beforeCommit = { error("simulated failure") })
        val s = sheet(
            mapOf("First Name" to "Yan", "Medication" to "Vitamin C", "Dosage Instructions" to "1",
                "Schedule Times" to "09:00", "Start Date" to "2026-07-01")
        )
        val plan = repo.preview(s, columns, ImportMode.NEW_RECORDS, ImportDecisions())
        val outcome = repo.apply(admin, s, columns, ImportMode.NEW_RECORDS, ImportDecisions(), plan.signature())

        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Failed)
        assertTrue(db.patientDao().getAllIncludingArchived().isEmpty())
        assertTrue(db.medicationDao().getAllIncludingArchived().isEmpty())
        val failed = db.importHistoryDao().getImports().single()
        assertEquals("FAILED", failed.outcome)
        assertTrue(db.importHistoryDao().changesForImport(failed.id).isEmpty())
    }

    @Test
    fun cancelledPreview_leavesDatabaseUntouched() = runBlocking {
        val (patient, medication, _) = seedPatientWithMedicationAndHistory()
        val repo = SpreadsheetImportRepository(db, backups = null)
        val s = sheet(
            mapOf("Patient ID" to patient.id, "Medication ID" to medication.id, "Dosage Instructions" to "2 tablets")
        )
        val plan = repo.preview(s, HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING).columns, ImportMode.UPDATE_EXISTING, ImportDecisions())
        assertTrue(plan.hasChanges)
        // Cancel = simply never calling apply().
        assertEquals("1 tablet", db.medicationDao().getById(medication.id)?.dosageInstructions)
        assertTrue(db.importHistoryDao().getImports().isEmpty())
    }

    @Test
    fun updateImport_keepsAdministrationHistoryIntact() = runBlocking {
        val (patient, medication, log) = seedPatientWithMedicationAndHistory()
        val repo = SpreadsheetImportRepository(db, backups = null)
        val updateColumns = HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING).columns
        val s = sheet(
            mapOf("Patient ID" to patient.id, "Medication ID" to medication.id, "Schedule Times" to "08:00, 20:00")
        )
        val plan = repo.preview(s, updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions(confirmSignificantChanges = true))
        val outcome = repo.apply(
            admin, s, updateColumns, ImportMode.UPDATE_EXISTING,
            ImportDecisions(confirmSignificantChanges = true), plan.signature()
        )
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)

        val logsAfter = db.doseLogDao().getAll()
        assertEquals(1, logsAfter.size)
        assertEquals(log, logsAfter.single())
        val changes = db.importHistoryDao().changesForRecord(medication.id)
        assertTrue(changes.any { it.significant && it.field == "Schedule Times" })
    }

    @Test
    fun identityChange_updatesInPlace_andKeepsItsHistory() = runBlocking {
        val (patient, medication, log) = seedPatientWithMedicationAndHistory()
        val repo = SpreadsheetImportRepository(db, backups = null)
        val updateColumns = HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING).columns
        val s = sheet(mapOf("Patient ID" to patient.id, "Medication ID" to medication.id, "Medication" to "Metformin XR"))
        val decisions = ImportDecisions(confirmSignificantChanges = true)
        val plan = repo.preview(s, updateColumns, ImportMode.UPDATE_EXISTING, decisions)
        assertTrue(plan.medicationUpdates.single().identityChanged)
        val outcome = repo.apply(admin, s, updateColumns, ImportMode.UPDATE_EXISTING, decisions, plan.signature())
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Completed)

        assertTrue(db.medicationDao().getById(medication.id)?.isArchived == false)
        assertEquals("Metformin XR", db.medicationDao().getById(medication.id)?.name)
        assertEquals(medication.qrCode, db.medicationDao().getById(medication.id)?.qrCode)
        assertEquals(log.medicationId, medication.id)
        assertEquals(1, db.doseLogDao().getAll().size)
        assertEquals(1, db.medicationDao().getAllIncludingArchived().size)
    }

    @Test
    fun staleReviewedPreview_isRejected_andNothingChanges() = runBlocking {
        val (patient, medication, _) = seedPatientWithMedicationAndHistory()
        val repo = SpreadsheetImportRepository(db, backups = null)
        val updateColumns = HeaderMapper.suggest(headers, ImportMode.UPDATE_EXISTING).columns
        val s = sheet(mapOf("Patient ID" to patient.id, "Medication ID" to medication.id, "Dosage Instructions" to "2 tablets"))
        val plan = repo.preview(s, updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions())

        // Someone changes the record after the preview was shown.
        db.medicationDao().upsert(medication.copy(dosageInstructions = "3 tablets"))

        val outcome = repo.apply(admin, s, updateColumns, ImportMode.UPDATE_EXISTING, ImportDecisions(), plan.signature())
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Rejected)
        assertEquals("3 tablets", db.medicationDao().getById(medication.id)?.dosageInstructions)
    }

    @Test
    fun nonAdministrator_cannotApplyImport() = runBlocking {
        val repo = SpreadsheetImportRepository(db, backups = null)
        val s = sheet(mapOf("First Name" to "Ivy", "Medication" to "X", "Dosage Instructions" to "1",
            "Schedule Times" to "08:00", "Start Date" to "2026-07-01"))
        val plan = repo.preview(s, columns, ImportMode.NEW_RECORDS, ImportDecisions())
        val outcome = repo.apply(provider, s, columns, ImportMode.NEW_RECORDS, ImportDecisions(), plan.signature())
        assertTrue(outcome is SpreadsheetImportRepository.Outcome.Rejected)
        assertTrue(db.patientDao().getAllIncludingArchived().isEmpty())
    }
}
