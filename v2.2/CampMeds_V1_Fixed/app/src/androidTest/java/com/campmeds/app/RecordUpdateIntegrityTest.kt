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
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Updating a record must never look like deleting and re-creating it.
 *
 * Both DAOs used `@Insert(onConflict = REPLACE)`, which SQLite implements as *delete the conflicting row, then
 * insert*. In this schema that is destructive:
 *  - a patient row is referenced by medications.patientId with ON DELETE RESTRICT, so editing any patient who
 *    had a medication failed outright (the patient form and import update mode alike);
 *  - a user row is referenced by dose_logs.loggedByUserId with ON DELETE SET NULL, so re-saving a user would
 *    have blanked who administered past doses.
 *
 * `@Upsert` updates in place. These tests are the regression guard.
 */
@RunWith(AndroidJUnit4::class)
class RecordUpdateIntegrityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    private lateinit var admin: User
    private lateinit var patient: Patient
    private lateinit var medication: Medication
    private lateinit var dose: DoseLog

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = CampMedsRepository(db)
        runBlocking {
            admin = User(name = "Admin", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
            db.userDao().upsert(admin)
            patient = Patient(name = "Ann Lee", firstName = "Ann", lastName = "Lee", notes = "penicillin")
            db.patientDao().upsert(patient)
            medication = Medication(
                patientId = patient.id,
                ndc = "0069-2587",
                name = "Metformin",
                strength = "500 mg",
                form = "TABLET",
                dosageInstructions = "1 tablet",
                scheduleTimes = listOf(LocalTime.of(8, 0)),
                startDate = LocalDate.of(2026, 7, 1),
                qrCode = "qr-1"
            )
            db.medicationDao().upsert(medication)
            dose = DoseLog(
                medicationId = medication.id,
                scheduledFor = LocalDateTime.of(2026, 7, 2, 8, 0),
                status = DoseStatus.GIVEN,
                loggedAt = LocalDateTime.of(2026, 7, 2, 8, 5),
                loggedByUserId = admin.id
            )
            db.doseLogDao().upsert(dose)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun editingPatientDetails_keepsMedicationsAndHistory() = runBlocking {
        // The exact action the patient form performs.
        repo.savePatient(patient.copy(notes = "penicillin, latex", lastName = "Lee-Smith"))

        val updated = db.patientDao().getById(patient.id)
        assertNotNull("the patient must still exist", updated)
        assertEquals("penicillin, latex", updated!!.notes)
        assertEquals("Ann Lee-Smith", updated.name)
        assertEquals("the medication must stay attached to the patient", patient.id,
            db.medicationDao().getById(medication.id)!!.patientId)
        assertEquals("administration history must be untouched", 1, db.doseLogDao().getAll().size)
        assertEquals(dose, db.doseLogDao().getAll().single())
    }

    @Test
    fun editingPatientDetails_worksForAPatientWithoutMedications() = runBlocking {
        val empty = Patient(name = "Bo Chen", firstName = "Bo", lastName = "Chen")
        repo.savePatient(empty)
        repo.savePatient(empty.copy(conditions = "asthma"))

        assertEquals("asthma", db.patientDao().getById(empty.id)!!.conditions)
        assertEquals(2, db.patientDao().getAllIncludingArchived().size)
    }

    @Test
    fun savingAnExistingUser_keepsDoseAttribution() = runBlocking {
        db.userDao().upsert(admin.copy(name = "Admin Renamed"))

        val reloaded = db.doseLogDao().getAll().single()
        assertEquals("re-saving a user must not orphan the doses they recorded", admin.id, reloaded.loggedByUserId)
        assertEquals("Admin Renamed", db.userDao().getById(admin.id)!!.name)
    }
}
