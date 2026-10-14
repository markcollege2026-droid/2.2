package com.campmeds.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campmeds.app.auth.*
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.*
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class UpdateFeaturesTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = CampMedsRepository(db)
    }
    @After fun close() { Session.logout(); db.close() }
    private suspend fun seed(): Pair<Medication, User> {
        val user = repo.createUser("Nurse", Role.ADMIN, "1234")
        Session.login(user)
        val patient = Patient(name = "Camper", startDate = LocalDate.now(), endDate = LocalDate.now())
        repo.savePatient(patient)
        return repo.saveMedicationEdit(null, Medication(patientId = patient.id, ndc = "", name = "Test",
            strength = "5mg", form = "tablet", dosageInstructions = "1 tablet", startDate = LocalDate.now(),
            scheduleTimes = listOf(LocalTime.NOON), isPrn = true)) to user
    }
    @Test fun heldReasonAtomicDuplicateAndDiscontinuation() = runBlocking {
        val (med, user) = seed()
        val due = LocalDate.now().atTime(12, 0)
        val results = coroutineScope { (1..5).map { async { repo.recordDose(med.id, due, DoseStatus.HELD, user.id, heldReason = "Awaiting review") } }.awaitAll() }
        assertEquals(1, results.count { it })
        val log = db.doseLogDao().getAll().single()
        assertEquals("Awaiting review", log.heldReason)
        assertFalse(repo.recordDose(med.id, due, DoseStatus.GIVEN, user.id))
        assertEquals(log, db.doseLogDao().getAll().single())
        val updated = repo.saveMedicationEdit(med, med.copy(name = "Renamed", strength = "10mg"))
        assertEquals(med.id, updated.id); assertEquals(med.sheetName, updated.sheetName)
        assertEquals(user.name, updated.createdByName)
        assertEquals(2, repo.medicationHistory(med.id).size)
        repo.discontinueMedication(med.id)
        assertFalse(repo.recordDose(med.id, due.plusHours(1), DoseStatus.GIVEN, user.id))
        assertEquals(LocalDate.now(), repo.getMedication(med.id)!!.effectiveEndDate)
        assertTrue(repo.getActiveMedications(LocalDate.now()).isEmpty())
        assertEquals(log, db.doseLogDao().getAll().single())
        val first = repo.loadExportData(); val second = repo.loadExportData()
        assertEquals(first, second); assertEquals(1, first.sheets.size)
        assertEquals(1, db.doseLogDao().getAll().size)
    }
    @Test fun futureAndPastArchiveReversesWithDateEdits() = runBlocking {
        val (med, _) = seed()
        val patient = repo.getPatient(med.patientId)!!
        assertEquals(1, repo.observePatients().first().size) // end date today remains active
        repo.savePatient(patient.copy(startDate = LocalDate.now().plusDays(1), endDate = LocalDate.now().plusDays(2)))
        assertTrue(repo.observePatients().first().isEmpty())
        assertEquals(patient.id, repo.observeArchive().first().single().id)
        repo.savePatient(patient.copy(startDate = LocalDate.now().minusDays(2), endDate = LocalDate.now().minusDays(1)))
        assertTrue(repo.observePatients().first().isEmpty())
        assertFalse(repo.getPatient(patient.id)!!.isArchived)
        repo.savePatient(patient)
        assertEquals(1, repo.observePatients().first().size)
        assertEquals(1, repo.getActiveMedications(LocalDate.now()).size)
    }
    @Test fun recoveryPersistsCooldownAndNeverResetsOnWrongAnswers() = runBlocking {
        val answers = linkedMapOf("pet" to "Secret pet", "street" to "Secret street", "book" to "Secret book")
        val user = repo.createUser("Admin", Role.ADMIN, "1234", answers)
        assertFalse(repo.recoverAccount(user.id, answers.mapValues { "Wrong" }, "5678"))
        assertNotNull(repo.login("1234"))
        assertNull(repo.login("5678"))
        val reopenedRepo = CampMedsRepository(db)
        assertFalse(reopenedRepo.recoverAccount(user.id, answers, "5678"))
        val locked = db.userDao().getById(user.id)!!
        assertTrue(locked.recoveryLockedUntil > System.currentTimeMillis())
        db.userDao().upsert(locked.copy(recoveryLockedUntil = 0)) // simulate cooldown expiry
        assertTrue(reopenedRepo.recoverAccount(user.id, answers, "5678"))
        assertNull(repo.login("1234")); assertNotNull(repo.login("5678"))
        assertTrue(db.userDao().getById(user.id)!!.recoveryHashes!!.split("|").none { it in answers.values })
    }
}
