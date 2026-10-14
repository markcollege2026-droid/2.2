package com.campmeds.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.auth.Session
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.*
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class CampFeaturesIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    private lateinit var admin: User
    private val day = LocalDate.now()
    private val days = MutableStateFlow(day)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = CampMedsRepository(db, days = days)
        admin = repo.createUser("Admin", Role.ADMIN, "1234")
        Session.login(admin)
    }
    @After fun teardown() { Session.logout(); db.close() }
    private suspend fun seed(): Pair<Patient, Medication> {
        val patient = Patient(name = "Camper", firstName = "Camper", startDate = day, endDate = day.plusDays(2),
            bunk = "B1", counselor = "C1", division = "D1")
        repo.savePatient(patient)
        val med = repo.saveMedicationEdit(null, Medication(patientId = patient.id, ndc = "", name = "Example",
            strength = "1", form = "tablet", dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = day, endDate = day.plusDays(7), isPrn = true))
        return patient to med
    }
    @Test fun heldReasonFirstWriteWinsAndParentUpdatesKeepUserAndHistory() = runBlocking {
        val (patient, med) = seed()
        assertEquals(admin.id, med.createdByUserId)
        assertEquals(admin.name, med.createdByName)
        assertTrue(repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.HELD, admin.id, heldReason = " Off site "))
        val before = repo.getHistoryForPatientOnce(patient.id).single()
        assertEquals("Off site", before.heldReason)
        assertFalse(repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.GIVEN, admin.id))
        repo.savePatient(patient.copy(bunk = "B2"))
        db.userDao().upsert(admin.copy(name = "Renamed admin"))
        val updated = repo.saveMedicationEdit(med, med.copy(strength = "2", isPrn = false))
        assertEquals(med.sheetName, updated.sheetName)
        assertEquals(med.createdByName, updated.createdByName)
        assertEquals(before, repo.getHistoryForPatientOnce(patient.id).single())
        assertEquals("D1", repo.getPatient(patient.id)!!.division)
        assertEquals(2, db.importHistoryDao().changesForRecord(med.id).size)
    }
    @Test fun concurrentDoseTapsOnlyCreateOneEntry() = runBlocking {
        val (_, med) = seed()
        val outcomes = (1..8).map { async(Dispatchers.Default) {
            repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.GIVEN, admin.id)
        } }.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertEquals(1, db.doseLogDao().getAll().size)
    }
    @Test fun discontinuationPreservesOriginalEndAndHistoryAndBlocksStaleDose() = runBlocking {
        val (patient, med) = seed()
        repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.GIVEN, admin.id)
        repo.discontinueMedication(med.id, day)
        val ended = repo.getMedication(med.id)!!
        assertEquals(day, ended.discontinuedOn)
        assertEquals(med.endDate, ended.endDate)
        assertEquals(med.sheetName, ended.sheetName)
        assertFalse(repo.recordDose(med.id, day.atTime(9, 0), DoseStatus.GIVEN, admin.id, expectedMedication = med))
        assertTrue(repo.getActiveMedications(day).isEmpty())
        assertEquals(1, repo.getHistoryForPatientOnce(patient.id).size)
        assertTrue(repo.loadExportData().sheets.single().changes.any { it.field == "Discontinued effective" })
    }
    @Test fun dayRolloverDateChangesAndRepositoryRestartRefreshArchiveWithoutDeleting() = runBlocking {
        val (patient, med) = seed()
        repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.GIVEN, admin.id)
        val emissions = kotlinx.coroutines.channels.Channel<List<Patient>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch { repo.observePatients().collect { emissions.send(it) } }
        suspend fun next() = withTimeout(10_000) { emissions.receive() }
        assertEquals(patient.id, next().single().id)
        days.value = day.minusDays(1)
        assertTrue(next().isEmpty())
        assertEquals(patient.id, repo.observeArchive().first().single().id)
        days.value = day.plusDays(2) // inclusive end date
        assertEquals(patient.id, next().single().id)
        days.value = day.plusDays(3)
        assertTrue(next().isEmpty())
        repo.savePatient(patient.copy(endDate = day.plusDays(4)))
        assertEquals(patient.id, next().single().id)
        val restarted = CampMedsRepository(db, days = days)
        assertEquals(patient.id, restarted.observePatients().first().single().id)
        assertFalse(restarted.getPatient(patient.id)!!.isArchived)
        assertEquals(1, restarted.getHistoryForPatientOnce(patient.id).size)
        job.cancelAndJoin()
    }
    @Test fun settingsAreAdminOnlyAndFieldLabelsNeverMoveValues() = runBlocking {
        val (patient, _) = seed()
        repo.saveSettings(CampSettings(startDates = listOf(day.toString(), day.toString()),
            endDates = listOf(day.plusDays(7).toString()), bunkLabel = "Cabin", counselorLabel = "Leader", divisionLabel = "Age division"))
        assertEquals(1, repo.observeSettings().first().startDates.size)
        assertEquals("B1", repo.getPatient(patient.id)!!.bunk)
        assertEquals("C1", repo.getPatient(patient.id)!!.counselor)
        assertEquals("D1", repo.getPatient(patient.id)!!.division)
        Session.logout()
        try { repo.saveSettings(CampSettings()); fail("Unauthorized settings change") } catch (expected: IllegalStateException) { }
        try { repo.discontinueMedication(repo.getCurrentMedications().single().id, day); fail("Unauthorized discontinuation") } catch (expected: IllegalStateException) { }
    }
    @Test fun recoveryRequiresEnrollmentAllAnswersAndPersistentLockThenPreservesIdentity() = runBlocking {
        val answers = mapOf("first_pet" to "private answer one", "first_job" to "private answer two", "family_phrase" to "private answer three")
        assertFalse(repo.recoverAccount(admin.id, answers, "5678"))
        assertFalse(repo.enrollRecovery("wrong", answers))
        assertTrue(repo.enrollRecovery("1234", answers))
        val secured = db.userDao().getById(admin.id)!!
        assertEquals(3, secured.recoverySalts.distinct().size)
        assertTrue(secured.recoveryHashes.none { it in answers.values })
        repeat(5) { assertFalse(repo.recoverAccount(admin.id, answers + ("first_pet" to "wrong answer"), "5678")) }
        val locked = db.userDao().getById(admin.id)!!
        assertTrue(locked.recoveryLockedUntil > System.currentTimeMillis())
        assertFalse(CampMedsRepository(db).recoverAccount(admin.id, answers, "5678"))
        db.userDao().upsert(locked.copy(recoveryLockedUntil = System.currentTimeMillis() - 1)) // simulate passage of cooldown
        val (patient, med) = seed()
        repo.recordDose(med.id, day.atTime(8, 0), DoseStatus.GIVEN, admin.id)
        assertTrue(repo.recoverAccount(admin.id, answers, "5678"))
        assertNull(repo.login("1234"))
        val reset = repo.login("5678")!!
        assertEquals(admin.id, reset.id)
        assertEquals(admin.role, reset.role)
        assertEquals(admin.id, repo.getHistoryForPatientOnce(patient.id).single().loggedByUserId)
        assertEquals(0, reset.recoveryFailures)
    }
}
