package com.campmeds.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Deleting patients/medications and renaming medications must never lose administration history. */
@RunWith(AndroidJUnit4::class)
class HistoryIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db get() = AppDatabase.getInstance(context)
    private val repo get() = CampMedsRepository(db)

    private suspend fun seed(tag: String): Triple<Patient, Medication, LocalDateTime> {
        val patient = Patient(name = "", firstName = "Ann$tag", lastName = "Lee")
        repo.savePatient(patient)
        val saved = repo.getPatient(patient.id)!!
        val med = Medication(
            patientId = saved.id, ndc = "", name = "Alpha$tag", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)), startDate = LocalDate.now().minusDays(1),
            isException = true
        )
        repo.saveMedication(med)
        val due = LocalDate.now().atTime(8, 0)
        val user = User(name = "T$tag", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
        db.userDao().upsert(user) // the user must exist before a dose can reference it
        repo.recordDose(med.id, due, DoseStatus.GIVEN, user.id)
        return Triple(saved, med, due)
    }

    @Test fun deletingPatientKeepsHistoryAndExportRows() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (patient, med, _) = seed(tag)
        assertEquals("Ann$tag Lee", patient.name)

        repo.archivePatient(patient.id)

        assertFalse(repo.observePatients().first().any { it.id == patient.id })
        assertFalse(db.medicationDao().getActiveOn(LocalDate.now().toString()).any { it.id == med.id })
        assertEquals(1, db.doseLogDao().getAll().count { it.medicationId == med.id })
        val sheet = repo.loadExportData().sheets.single { it.medication.id == med.id }
        assertEquals("Ann$tag Lee", sheet.patientName)
        assertEquals(1, sheet.doseLogs.size)
    }

    @Test fun deletingMedicationKeepsHistory() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (_, med, _) = seed(tag)
        repo.archiveMedication(med.id)
        assertEquals(1, db.doseLogDao().getAll().count { it.medicationId == med.id })
        assertTrue(repo.loadExportData().sheets.any { it.medication.id == med.id })
    }

    @Test fun renamingAMedicationCreatesANewRecordAndKeepsTheOldOne() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (patient, med, _) = seed(tag)

        val replacement = repo.saveMedicationEdit(med, med.copy(name = "Beta$tag"))

        assertNotEquals(med.id, replacement.id)
        assertNotEquals(med.qrCode, replacement.qrCode)
        assertTrue(db.medicationDao().getById(med.id)!!.isArchived)
        assertEquals("Alpha$tag", db.medicationDao().getById(med.id)!!.name)       // old record untouched
        assertEquals("Beta$tag", db.medicationDao().getById(replacement.id)!!.name)
        assertEquals(1, db.doseLogDao().getAll().count { it.medicationId == med.id }) // old history still on the old record
        val sheets = repo.loadExportData().sheets.filter { it.medication.patientId == patient.id }
        assertEquals(setOf("Alpha$tag", "Beta$tag"), sheets.map { it.medication.name }.toSet())
    }

    @Test fun nonIdentityEditUpdatesInPlace() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (_, med, _) = seed(tag)
        val result = repo.saveMedicationEdit(med, med.copy(purpose = "Allergies", dosageInstructions = "2"))
        assertEquals(med.id, result.id)
        assertEquals("Allergies", db.medicationDao().getById(med.id)!!.purpose)
    }
}
