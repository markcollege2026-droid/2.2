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
        val patient = Patient(name = "Persistence Test")
        db1.patientDao().upsert(patient)
        val med = Medication(
            patientId = patient.id, ndc = "0000-0000", name = "TestDrug", strength = "5 mg", form = "TABLET",
            dosageInstructions = "1 tablet", scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = LocalDate.now(), isException = true
        )
        db1.medicationDao().upsert(med)

        AppDatabase.closeAndResetForTesting()          // simulate process restart
        val db2 = AppDatabase.getInstance(context)     // must open with the SAME key

        assertNotNull(db2.patientDao().getById(patient.id))
        assertEquals("TestDrug", db2.medicationDao().getById(med.id)?.name)
        assertEquals(1, db2.medicationDao().observeForPatient(patient.id).first().size)
    }
}
