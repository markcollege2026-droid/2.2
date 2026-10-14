package com.campmeds.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.DatabaseKeyProvider
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/** V1 bug 1 regression: the encrypted DB must reopen after a "restart" and still contain its data. */
@RunWith(AndroidJUnit4::class)
class DatabasePersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun passphraseIsIdenticalOnFirstAndLaterRetrievals() {
        val first = DatabaseKeyProvider.getOrCreatePassphrase(context)
        val second = DatabaseKeyProvider.getOrCreatePassphrase(context)
        val third = DatabaseKeyProvider.getOrCreatePassphrase(context)
        assertArrayEquals(first, second)
        assertArrayEquals(first, third)
    }

    @Test
    fun patientAndMedicationSurviveCloseAndReopen() = runBlocking {
        val db1 = AppDatabase.getInstance(context)
        val patient = Patient(name = "Persistence Test", startDate = LocalDate.now().plusDays(1),
            endDate = LocalDate.now().plusDays(7), bunk = "Bunk A", counselor = "Counselor A", division = "Division A")
        db1.patientDao().upsert(patient)
        val med = Medication(
            patientId = patient.id, ndc = "0000-0000", name = "TestDrug", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1 tablet", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = LocalDate.now(), isException = true, isPrn = true,
            createdByUserId = "creator-id", createdByName = "Original creator", sheetName = "Persistence Test TestDrug",
            discontinuedOn = LocalDate.now().plusDays(5)
        )
        db1.medicationDao().upsert(med)
        val user = com.campmeds.app.data.entity.User(name = "Recovery persistence", role = com.campmeds.app.data.entity.Role.PROVIDER,
            pinHash = "hash", pinSalt = "salt", recoveryQuestionIds = listOf("first_pet", "first_job", "family_phrase"),
            recoveryHashes = listOf("hash1", "hash2", "hash3"), recoverySalts = listOf("salt1", "salt2", "salt3"),
            recoveryFailures = 5, recoveryLockedUntil = System.currentTimeMillis() + 1_800_000)
        db1.userDao().upsert(user)
        val log = com.campmeds.app.data.entity.DoseLog(medicationId = med.id, scheduledFor = LocalDate.now().atTime(8, 0),
            status = com.campmeds.app.data.entity.DoseStatus.HELD, loggedAt = java.time.LocalDateTime.now(),
            loggedByUserId = user.id, heldReason = "Original held reason")
        db1.doseLogDao().upsert(log)

        AppDatabase.closeAndResetForTesting()          // simulate process restart
        val db2 = AppDatabase.getInstance(context)     // must open with the SAME key

        assertEquals(patient, db2.patientDao().getById(patient.id))
        assertEquals(med, db2.medicationDao().getById(med.id))
        assertEquals(user, db2.userDao().getById(user.id))
        assertEquals(log, db2.doseLogDao().findExisting(med.id, log.scheduledFor.toString()))
        assertEquals("TestDrug", db2.medicationDao().getById(med.id)?.name)
        assertEquals(1, db2.medicationDao().observeForPatient(patient.id).first().size)
    }
}
