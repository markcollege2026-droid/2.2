package com.campmeds.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.MIGRATION_1_2
import com.campmeds.app.data.MIGRATION_2_3
import com.campmeds.app.data.MIGRATION_3_4
import com.campmeds.app.data.MIGRATION_5_6
import com.campmeds.app.data.MIGRATION_4_5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 -> v2 -> v3 must keep every existing row and must not become destructive at either step.
 *
 *  - 1 -> 2 backfills patient names and removes the cascading deletes that could wipe history.
 *  - 2 -> 3 adds the per-medication settings and the named administration times, leaving existing
 *    medications exactly as they behaved before (QR scan required, nothing grouped, every scheduled time a
 *    custom time).
 *
 * The v1 baseline comes from app/schemas/.../1.json (hand-authored from the v1 entities, see README); the current
 * schema (v5, which adds the import history tables) is the one this build generates, which is why the tests
 * validate against it after migrating.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val dbName = "migration-test"

    private fun v1Data() {
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
    }

    @Test
    fun migrate1To2To3_keepsAllDataAndAddsSafeDefaults() {
        v1Data()

        val db = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        // ---- patients (v1 -> v2 backfill still applied) ----
        db.query("SELECT id, name, firstName, lastName, notes, conditions, isArchived FROM patients ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(listOf("p1", "Mary Ann Smith", "Mary Ann", "Smith", "peanuts"), (0..4).map { c.getString(it) })
            assertTrue(c.isNull(5)); assertEquals(0, c.getInt(6))
            assertTrue(c.moveToNext())
            assertEquals(listOf("p2", "Cher", "Cher", ""), (0..3).map { c.getString(it) })
        }

        // ---- medications: existing values kept, new settings default to today's behaviour ----
        db.query(
            "SELECT name, ndc, scheduleTimes, administrationTimeIds, requireQrScan, isGroupAdministered, " +
                "prepackagedGroupId, pillCountEntered, qrCode, purpose, isArchived FROM medications WHERE id='m1'"
        ).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Zyrtec", c.getString(0))
            assertEquals("0069-2587", c.getString(1))
            assertEquals("08:00,20:00", c.getString(2))
            assertEquals("", c.getString(3))          // every existing time is a custom time
            assertEquals(1, c.getInt(4))              // scanning was required before, and still is
            assertEquals(0, c.getInt(5))              // nothing is grouped automatically
            assertTrue(c.isNull(6))
            assertEquals(5, c.getInt(7))
            assertEquals("qr-1", c.getString(8))
            assertTrue(c.isNull(9))
            assertEquals(0, c.getInt(10))
        }

        // ---- v6 defaults do not invent clinical or staff information ----
        db.query("SELECT isPrn,createdByUserId,createdByName,sheetName,discontinuedAt FROM medications WHERE id='m1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
            assertEquals("Mary Ann Smith Zyrtec", c.getString(3)); assertTrue(c.isNull(4))
        }
        db.query("SELECT startDate,endDate,bunk,counselor,division FROM patients WHERE id='p1'").use { c ->
            assertTrue(c.moveToFirst()); (0..4).forEach { assertTrue(c.isNull(it)) }
        }
        db.query("SELECT heldReason FROM dose_logs").use { c -> while (c.moveToNext()) assertTrue(c.isNull(0)) }
        db.query("SELECT recoveryQuestions,recoveryFailures,recoveryLockedUntil FROM users WHERE id='u1'").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertEquals(0, c.getInt(1)); assertEquals(0L, c.getLong(2))
        }
        // ---- history untouched ----
        db.query("SELECT COUNT(*) FROM dose_logs WHERE medicationId='m1' AND loggedByUserId='u1'").use { c ->
            assertTrue(c.moveToNext()); assertEquals(2, c.getInt(0))
        }

        // ---- the new table exists, starts empty, and accepts an entry ----
        db.query("SELECT COUNT(*) FROM administration_times").use { c ->
            assertTrue(c.moveToNext()); assertEquals(0, c.getInt(0))
        }
        db.execSQL("INSERT INTO administration_times (id, name, time) VALUES ('t1','Breakfast','08:00')")
        db.query("SELECT name, time FROM administration_times WHERE id='t1'").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Breakfast", c.getString(0))
            assertEquals("08:00", c.getString(1))
        }

        // ---- spreadsheet import history (migration 4 -> 5): present and empty ----
        db.query("SELECT COUNT(*) FROM import_history").use { c ->
            assertTrue(c.moveToNext()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM change_history").use { c ->
            assertTrue(c.moveToNext()); assertEquals(0, c.getInt(0))
        }

        // ---- names stay unique ----
        try {
            db.execSQL("INSERT INTO administration_times (id, name, time) VALUES ('t2','Breakfast','09:00')")
            fail("duplicate administration-time name should have been refused")
        } catch (expected: SQLiteConstraintException) { /* ok */ }
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
        val db = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

    @Test
    fun migrateToCurrent_addsNullableGroupNameAndKeepsExistingRows() {
        // Room only exports the schema of the current version, so this migrates straight to it and validates
        // against the exported current schema; the new column must exist, be null for existing rows, and no row
        // may be lost.
        v1Data()

        val db = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM medications").use { c ->
            c.moveToFirst()
            org.junit.Assert.assertEquals(1, c.getInt(0))
        }
        db.query("SELECT prepackagedGroupName FROM medications WHERE id = 'm1'").use { c ->
            c.moveToFirst()
            org.junit.Assert.assertTrue(c.isNull(0))
        }
        db.close()
    }
}
