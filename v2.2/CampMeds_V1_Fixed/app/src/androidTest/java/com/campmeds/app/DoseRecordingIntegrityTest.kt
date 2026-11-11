package com.campmeds.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One administration record per medication and scheduled dose, no matter how the tap arrives.
 *
 * A double tap, a retry after a slow frame, or two caregivers recording the same slot must never produce two
 * records: duplicated rows would double-count a dose in the history and in the export. The rule is enforced by
 * reading the existing record and writing the update inside a single transaction (see
 * [CampMedsRepository.recordDose]).
 */
@RunWith(AndroidJUnit4::class)
class DoseRecordingIntegrityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    private lateinit var admin: User
    private lateinit var medication: Medication

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = CampMedsRepository(db)
        runBlocking {
            admin = User(name = "Admin", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
            db.userDao().upsert(admin)
            val patient = Patient(name = "Ann Lee", firstName = "Ann", lastName = "Lee")
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
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recordingTheSameDoseTwice_updatesInsteadOfDuplicating() = runBlocking {
        val scheduled = LocalDateTime.of(2026, 7, 2, 8, 0)

        repo.recordDose(medication.id, scheduled, DoseStatus.GIVEN, admin.id)
        repo.recordDose(medication.id, scheduled, DoseStatus.REFUSED, admin.id, wasOverride = true)

        val logs = db.doseLogDao().getAll()
        assertEquals("a re-recorded dose must not add a second row", 1, logs.size)
        val log = logs.single()
        assertEquals(DoseStatus.REFUSED, log.status)
        assertTrue("the override flag must follow the latest outcome", log.wasOverride)
        assertEquals(medication.id, log.medicationId)
        assertEquals(scheduled, log.scheduledFor)
    }

    @Test
    fun concurrentRecordingsForOneDose_leaveExactlyOneRow() = runBlocking {
        val scheduled = LocalDateTime.of(2026, 7, 2, 20, 0)

        coroutineScope {
            (1..12).map {
                async(Dispatchers.Default) {
                    repo.recordDose(medication.id, scheduled, DoseStatus.GIVEN, admin.id)
                }
            }.awaitAll()
        }

        assertEquals("concurrent taps must not create duplicate administration records", 1, db.doseLogDao().getAll().size)
    }

    @Test
    fun differentScheduledTimes_areKeptAsSeparateRecords() = runBlocking {
        repo.recordDose(medication.id, LocalDateTime.of(2026, 7, 2, 8, 0), DoseStatus.GIVEN, admin.id)
        repo.recordDose(medication.id, LocalDateTime.of(2026, 7, 2, 20, 0), DoseStatus.HELD, admin.id)

        val logs = db.doseLogDao().getAll()
        assertEquals(2, logs.size)
        assertEquals(setOf(DoseStatus.GIVEN, DoseStatus.HELD), logs.map { it.status }.toSet())
        assertTrue("both records must keep their own timestamp",
            logs.map { it.scheduledFor }.toSet() ==
                setOf(LocalDateTime.of(2026, 7, 2, 8, 0), LocalDateTime.of(2026, 7, 2, 20, 0)))
        assertNotNull(logs.firstOrNull { it.loggedByUserId == admin.id })
    }
}
