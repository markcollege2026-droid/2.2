package com.campmeds.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 -> v2 must keep every existing row and must remove the cascading deletes that could wipe history.
 * The v1 baseline comes from app/schemas/.../1.json (hand-authored from the v1 entities, see README).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val dbName = "migration-test"

    @Test
    fun migrate1To2_keepsAllDataAndBackfillsNames() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO patients (id,name,dob,notes) VALUES ('p1','Mary Ann Smith',NULL,'peanuts'),('p2','Cher',NULL,NULL)")
            execSQL("INSERT INTO users (id,name,role,pinHash,pinSalt) VALUES ('u1','Admin','ADMIN','h','s')")
            execSQL(
                "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes,startDate," +
                    "endDate,bottleExpiration,pillCountEntered,isException,qrCode) VALUES " +
                    "('m1','p1','0069-2587','Zyrtec','10 mg','TABLET','1 tab','08:00,20:00','2026-07-01',NULL,NULL,5,0,'qr-1')"
            )
            execSQL(
                "INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,loggedByUserId,wasOverride) VALUES " +
                    "('d1','m1','2026-07-02T08:00','GIVEN','2026-07-02T08:03','u1',0)," +
                    "('d2','m1','2026-07-02T20:00','HELD','2026-07-02T20:01','u1',1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT id, name, firstName, lastName, notes, conditions, isArchived FROM patients ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(listOf("p1", "Mary Ann Smith", "Mary Ann", "Smith", "peanuts"), (0..4).map { c.getString(it) })
            assertTrue(c.isNull(5)); assertEquals(0, c.getInt(6))
            assertTrue(c.moveToNext())
            assertEquals(listOf("p2", "Cher", "Cher", ""), (0..3).map { c.getString(it) })
        }
        db.query("SELECT name, ndc, scheduleTimes, pillCountEntered, qrCode, purpose, isArchived FROM medications WHERE id='m1'").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(listOf("Zyrtec", "0069-2587", "08:00,20:00"), (0..2).map { c.getString(it) })
            assertEquals(5, c.getInt(3)); assertEquals("qr-1", c.getString(4))
            assertTrue(c.isNull(5)); assertEquals(0, c.getInt(6))
        }
        db.query("SELECT COUNT(*) FROM dose_logs WHERE medicationId='m1' AND loggedByUserId='u1'").use { c ->
            assertTrue(c.moveToNext()); assertEquals(2, c.getInt(0))
        }
    }

    @Test
    fun afterMigration_hardDeletesCannotDestroyHistory() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO patients (id,name) VALUES ('p1','Ann Lee')")
            execSQL(
                "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes,startDate,isException,qrCode) " +
                    "VALUES ('m1','p1','','Alpha','5 mg','TABLET','1','08:00','2026-07-01',1,'qr-1')"
            )
            execSQL("INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,wasOverride) VALUES ('d1','m1','2026-07-02T08:00','GIVEN','2026-07-02T08:01',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)
        db.execSQL("PRAGMA foreign_keys = ON")

        // Deleting a patient that still has medications is refused (RESTRICT) instead of cascading.
        try {
            db.execSQL("DELETE FROM patients WHERE id='p1'")
            fail("patient delete should have been refused")
        } catch (expected: SQLiteConstraintException) { /* ok */ }

        // Deleting a medication row no longer cascades into dose_logs.
        db.execSQL("DELETE FROM medications WHERE id='m1'")
        db.query("SELECT COUNT(*) FROM dose_logs").use { c ->
            assertTrue(c.moveToNext()); assertEquals(1, c.getInt(0))
        }
    }
}
