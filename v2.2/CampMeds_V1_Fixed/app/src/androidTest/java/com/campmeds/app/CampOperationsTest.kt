package com.campmeds.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.auth.Session
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
class CampOperationsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    private lateinit var admin: User
    private val day = LocalDate.now()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = CampMedsRepository(db)
        admin = repo.createUser("Admin", Role.ADMIN, "1234"); Session.login(admin)
    }
    @After fun cleanup() { Session.logout(); db.close() }
    private suspend fun camper(name: String): Patient {
        val p = Patient(name = name, firstName = name, division = "Junior")
        repo.savePatient(p); return repo.getPatient(p.id)!!
    }
    private suspend fun med(p: Patient, prn: Boolean = false) = repo.saveMedicationEdit(null,
        Medication(patientId = p.id, ndc = "", name = "Example", strength = "1", form = "tablet", dosageInstructions = "As prescribed",
            scheduleTimes = if (prn) emptyList() else listOf(LocalTime.of(8,0), LocalTime.of(20,0)), startDate = day, isPrn = prn))

    @Test fun prnWithoutTimeRecordsOncePerExplicitRequestWithHistoryIntact() = runBlocking {
        val p = camper("PRN camper"); val m = med(p, true)
        val request = LocalDateTime.now().minusSeconds(1)
        assertTrue(m.scheduleTimes.isEmpty())
        assertTrue(repo.recordDose(m.id, request, DoseStatus.GIVEN, admin.id, expectedMedication = m, isPrn = true))
        assertFalse(repo.recordDose(m.id, request, DoseStatus.HELD, admin.id, expectedMedication = m, isPrn = true))
        val log = repo.getHistoryForPatientOnce(p.id).single()
        assertTrue(log.isPrn); assertEquals(DoseStatus.GIVEN,log.status)
        assertTrue(repo.previewBulkHold(setOf(p.id),day.atTime(23,0)).isEmpty())
    }
    @Test fun bulkHoldOnlyTouchesConfirmedSelectedDueDosesAndNeverOverwritesHistory() = runBlocking {
        val a=camper("A"); val b=camper("B"); val c=camper("Unselected")
        val ma=med(a); val mb=med(b); val mc=med(c); med(a,true)
        val before = DoseLog(medicationId=mb.id, scheduledFor=day.atTime(8,0), status=DoseStatus.GIVEN,
            loggedAt=day.atTime(8,1), loggedByUserId=admin.id)
        db.doseLogDao().upsert(before)
        val preview=repo.previewBulkHold(setOf(a.id,b.id),day.atTime(10,0))
        assertEquals(listOf(ma.id),preview.map { it.medication.id })
        assertEquals(1,repo.applyBulkHold(preview,"Shared reason",day.atTime(21,0))) // newly due 20:00 excluded
        assertEquals(0,repo.applyBulkHold(preview,"Different reason",day.atTime(21,0)))
        assertEquals(before,db.doseLogDao().findExisting(mb.id,day.atTime(8,0).toString()))
        assertEquals("Shared reason",db.doseLogDao().findExisting(ma.id,day.atTime(8,0).toString())!!.heldReason)
        assertNull(db.doseLogDao().findExisting(ma.id,day.atTime(20,0).toString()))
        assertTrue(repo.getHistoryForPatientOnce(c.id).isEmpty())
        assertEquals(2,db.doseLogDao().getAll().size)
    }
    @Test fun bulkHoldRechecksChangesConcurrentOutcomesAndDayRollover() = runBlocking {
        val p=camper("A"); val m=med(p)
        val preview=repo.previewBulkHold(setOf(p.id),day.atTime(10,0))
        assertEquals(0,repo.applyBulkHold(preview,null,day.plusDays(1).atTime(10,0)))
        repo.saveMedicationEdit(m,m.copy(strength="2"))
        assertEquals(0,repo.applyBulkHold(preview,null,day.atTime(10,0)))
        val fresh=repo.previewBulkHold(setOf(p.id),day.atTime(10,0))
        val results=listOf(async { repo.applyBulkHold(fresh,null,day.atTime(10,0)) },async { repo.applyBulkHold(fresh,null,day.atTime(10,0)) }).awaitAll()
        assertEquals(1,results.sum()); assertEquals(1,db.doseLogDao().getAll().size)
    }
    @Test fun optionsAndSessionsNeverRetroactivelyChangePatientsOrMedicationDates() = runBlocking {
        repo.saveBunk(BunkOption("A",listOf("Jane, Smith","Jo"),"Junior"))
        repo.saveBunk(BunkOption("B",listOf("Alex","Jo"),"Junior"))
        repo.saveSettings(CampSettings(allowCounselorOverride=false))
        val p=camper("Camper")
        repo.savePatient(p.copy(bunks=listOf("A","B")))
        val saved=repo.getPatient(p.id)!!
        assertEquals(listOf("Jane, Smith","Jo","Alex"),saved.counselors)
        repo.savePatient(saved.copy(counselors=listOf("Not allowed")))
        assertEquals(saved.counselors,repo.getPatient(p.id)!!.counselors)
        repo.saveBunk(BunkOption("A",listOf("New counselor"),"Junior"))
        assertEquals(saved.counselors,repo.getPatient(p.id)!!.counselors)
        repo.saveSettings(CampSettings(allowCounselorOverride=true))
        repo.savePatient(saved.copy(counselors=listOf("Manual")))
        assertEquals(listOf("Manual"),repo.getPatient(p.id)!!.counselors)
        val session=CampSession(name="Session one",startDate=day,endDate=day.plusDays(7))
        repo.saveSession(session)
        val m=med(p)
        repo.savePatient(repo.getPatient(p.id)!!.copy(startDate=session.startDate,endDate=session.endDate))
        val original=repo.saveMedicationEdit(m,m.copy(startDate=session.startDate,endDate=session.endDate))
        repo.saveSession(session.copy(startDate=day.plusDays(2),endDate=day.plusDays(9)))
        repo.removeSession(session.id)
        assertEquals(day,repo.getPatient(p.id)!!.startDate)
        assertEquals(day.plusDays(7),repo.getPatient(p.id)!!.endDate)
        assertEquals(original,repo.getMedication(m.id))
        repo.saveDivision(Division("Junior","#123456"))
        assertEquals("#123456",repo.observeDivisions().first().single().colorHex)
    }
    @Test fun providerCanHoldButCannotConfigureOptionsAndLogoutCannotHold() = runBlocking {
        val p=camper("A"); med(p)
        val provider=repo.createUser("Nurse",Role.PROVIDER,"5678"); Session.login(provider)
        val preview=repo.previewBulkHold(setOf(p.id),day.atTime(10,0))
        assertEquals(1,repo.applyBulkHold(preview,null,day.atTime(10,0)))
        try { repo.saveBunk(BunkOption("Unauthorized")); fail() } catch (expected: IllegalStateException) { }
        try { repo.saveDivision(Division("Unauthorized","#123456")); fail() } catch (expected: IllegalStateException) { }
        try { repo.saveSession(CampSession(name="Unauthorized",startDate=day,endDate=day)); fail() } catch (expected: IllegalStateException) { }
        Session.logout()
        try { repo.applyBulkHold(preview,null,day.atTime(10,0)); fail() } catch (expected: IllegalStateException) { }
    }
}
