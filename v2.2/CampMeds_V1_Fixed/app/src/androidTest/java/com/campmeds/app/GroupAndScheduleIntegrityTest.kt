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
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.domain.PlannedDose
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Repository-level guarantees of the new features, against the real (encrypted) database:
 *  - one group action creates one administration record per medication dose, and pressing it twice cannot
 *    create duplicates or touch doses that are not due;
 *  - changing a named administration time moves the schedules that use it and leaves history alone;
 *  - removing a named administration time keeps every scheduled time and only drops the name;
 *  - a group can never span patients.
 */
@RunWith(AndroidJUnit4::class)
class GroupAndScheduleIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db get() = AppDatabase.getInstance(context)
    private val repo get() = CampMedsRepository(db)

    private val today: LocalDate get() = LocalDate.now()
    private fun at(hour: Int) = today.atTime(hour, 0)

    private suspend fun seed(tag: String): Triple<Patient, User, List<Medication>> {
        val patient = Patient(name = "", firstName = "Group$tag", lastName = "Test")
        repo.savePatient(patient)
        val saved = repo.getPatient(patient.id)!!

        val groupId = "group-$tag"
        val alpha = Medication(
            patientId = saved.id, ndc = "", name = "Alpha$tag", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            startDate = today.minusDays(1), isException = true,
            isGroupAdministered = true, prepackagedGroupId = groupId
        )
        val beta = Medication(
            patientId = saved.id, ndc = "", name = "Beta$tag", strength = "10 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = today.minusDays(1), isException = true,
            isGroupAdministered = true, prepackagedGroupId = groupId
        )
        val loner = Medication(
            patientId = saved.id, ndc = "", name = "Loner$tag", strength = "1 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = today.minusDays(1), isException = true
        )
        val user = User(name = "Tester$tag", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
        db.userDao().upsert(user)
        listOf(alpha, beta, loner).forEach { repo.saveMedicationEdit(null, it) }
        return Triple(saved, user, listOf(alpha, beta, loner))
    }

    @Test
    fun oneGroupActionWritesOneRecordPerDoseAndASecondPressAddsNothing() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (_, user, meds) = seed(tag)
        val (alpha, beta, loner) = meds

        val doses = listOf(PlannedDose(alpha.id, at(8)), PlannedDose(beta.id, at(8)))

        assertEquals(2, repo.recordGroupAdministration(doses, user.id))
        assertEquals(0, repo.recordGroupAdministration(doses, user.id)) // double tap / refreshed press

        val alphaLogs = db.doseLogDao().getAll().filter { it.medicationId == alpha.id }
        assertEquals(1, alphaLogs.size)
        assertEquals(DoseStatus.GIVEN, alphaLogs.single().status)
        assertEquals(at(8), alphaLogs.single().scheduledFor)
        assertEquals(user.id, alphaLogs.single().loggedByUserId)
        assertEquals(1, db.doseLogDao().getAll().count { it.medicationId == beta.id })
        assertTrue(db.doseLogDao().getAll().none { it.medicationId == loner.id }) // not in the group, not recorded
    }

    @Test
    fun aGroupActionNeverRecordsADoseScheduledLater() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (_, user, meds) = seed(tag)
        val alpha = meds[0]

        repo.recordGroupAdministration(listOf(PlannedDose(alpha.id, at(8))), user.id)

        assertNotNull(db.doseLogDao().findExisting(alpha.id, at(8).toString()))
        assertNull(db.doseLogDao().findExisting(alpha.id, at(20).toString()))
    }

    @Test
    fun aGroupActionCannotOverwriteADecisionThatWasAlreadyMade() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (_, user, meds) = seed(tag)
        val beta = meds[1]

        repo.recordDose(beta.id, at(8), DoseStatus.REFUSED, user.id)
        val recorded = repo.recordGroupAdministration(listOf(PlannedDose(beta.id, at(8))), user.id)

        assertEquals(0, recorded)
        val log = db.doseLogDao().getAll().single { it.medicationId == beta.id }
        assertEquals(DoseStatus.REFUSED, log.status)
    }

    @Test
    fun changingANamedTimeMovesActiveSchedulesAndLeavesHistoryAlone() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (patient, user, _) = seed(tag)

        val breakfast = repo.saveAdministrationTime(null, "Breakfast$tag", LocalTime.of(8, 0))
        val (times, ids) = AdministrationTimeRules.buildSchedule(listOf(breakfast), emptyList())
        val med = Medication(
            patientId = patient.id, ndc = "", name = "Timed$tag", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = times, administrationTimeIds = ids,
            startDate = today.minusDays(1), isException = true
        )
        val saved = repo.saveMedicationEdit(null, med)

        repo.recordDose(saved.id, at(8), DoseStatus.GIVEN, user.id)
        val before = db.doseLogDao().getAll().single { it.medicationId == saved.id }

        repo.saveAdministrationTime(breakfast, breakfast.name, LocalTime.of(8, 30))

        val reloaded = db.medicationDao().getById(saved.id)!!
        assertEquals(listOf(LocalTime.of(8, 30)), reloaded.scheduleTimes)
        assertEquals(listOf(breakfast.id), reloaded.administrationTimeIds)

        // The dose that was already recorded keeps the time it was scheduled for.
        val after = db.doseLogDao().getAll().single { it.medicationId == saved.id }
        assertEquals(before.scheduledFor, after.scheduledFor)
        assertEquals(before.loggedAt, after.loggedAt)
        assertEquals(DoseStatus.GIVEN, after.status)
    }

    @Test
    fun removingANamedTimeKeepsEveryScheduledTimeAndOnlyDropsTheName() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (patient, user, _) = seed(tag)

        val bedtime = repo.saveAdministrationTime(null, "Bedtime$tag", LocalTime.of(21, 0))
        val (times, ids) = AdministrationTimeRules.buildSchedule(listOf(bedtime), listOf(LocalTime.of(8, 0)))
        val med = Medication(
            patientId = patient.id, ndc = "", name = "Removed$tag", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = times, administrationTimeIds = ids,
            startDate = today.minusDays(1), isException = true
        )
        val saved = repo.saveMedicationEdit(null, med)
        repo.recordDose(saved.id, at(8), DoseStatus.HELD, user.id)

        repo.deleteAdministrationTime(bedtime.id)

        val reloaded = db.medicationDao().getById(saved.id)!!
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), reloaded.scheduleTimes)
        assertTrue(reloaded.administrationTimeIds.all { it.isBlank() })
        assertNull(db.administrationTimeDao().getById(bedtime.id))
        assertEquals(1, db.doseLogDao().getAll().count { it.medicationId == saved.id })
    }

    @Test
    fun duplicateNamesAndCrossPatientGroupsAreRefused() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val (patient, _, meds) = seed(tag)
        val alpha = meds[0]

        repo.saveAdministrationTime(null, "Lunch$tag", LocalTime.of(12, 0))
        var duplicateRefused = false
        try {
            repo.saveAdministrationTime(null, "lunch$tag", LocalTime.of(13, 0))
        } catch (expected: IllegalArgumentException) {
            duplicateRefused = true
        }
        assertTrue(duplicateRefused)

        // A different patient may not join this patient's prepackaged group.
        val other = Patient(name = "", firstName = "Other$tag", lastName = "Patient")
        repo.savePatient(other)
        val otherSaved = repo.getPatient(other.id)!!
        val intruder = Medication(
            patientId = otherSaved.id, ndc = "", name = "Intruder$tag", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = today.minusDays(1), isException = true,
            isGroupAdministered = true, prepackagedGroupId = alpha.prepackagedGroupId
        )
        var refused = false
        try {
            repo.saveMedicationEdit(null, intruder)
        } catch (expected: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
        assertTrue(db.medicationDao().getCurrentMedications().none { it.id == intruder.id })
        // Nothing about the two patients changed.
        assertNotNull(repo.getPatient(patient.id))
        assertNotNull(repo.getPatient(other.id))
        assertEquals(alpha.prepackagedGroupId, db.medicationDao().getById(alpha.id)!!.prepackagedGroupId)
    }
}
