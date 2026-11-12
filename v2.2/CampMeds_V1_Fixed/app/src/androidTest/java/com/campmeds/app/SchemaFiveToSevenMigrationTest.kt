package com.campmeds.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.ALL_MIGRATIONS
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.MIGRATION_1_2
import com.campmeds.app.data.MIGRATION_2_3
import com.campmeds.app.data.MIGRATION_3_4
import com.campmeds.app.data.MIGRATION_4_5
import com.campmeds.app.data.MIGRATION_5_6
import com.campmeds.app.data.MIGRATION_6_7
import com.campmeds.app.data.SCHEMA_VERSION
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The upgrade paths the backup/restore work depends on.
 *
 * The older backup/restore branch was built on **schema 5**. A device that installs this build from that line
 * holds a schema-5 database, and any backup taken there is a schema-5 file. Both have to reach schema 7 with
 * every row intact:
 *
 *  - 5 -> 6 -> 7 on a populated schema-5 database;
 *  - 6 -> 7 on a populated schema-6 database (the "1.2" release line);
 *  - a database that is already at schema 7 (the current release) must open without migration and keep its data.
 *
 * Each older baseline is produced here by the app's *own* migrations applied to the committed v1 export, then
 * `PRAGMA user_version` is set to that version — which is exactly what a released v5/v6 database looks like.
 * There is no hand-written "expected schema": the final state is validated by Room against the schema JSON this
 * build generates, so a change to any migration that breaks the chain fails here.
 */
@RunWith(AndroidJUnit4::class)
class SchemaFiveToSevenMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val chain = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

    /**
     * A populated v1 install: two patients, one user, two medications, two administration records.
     *
     * `isArchived` does not exist in v1 and is added by 1 -> 2 with a default of 0, so archiving is asserted on
     * the patient's own row explicitly below: a migration must never invent an archive state either way.
     */
    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO patients (id,name,dob,notes) VALUES " +
                "('p1','Mary Ann Smith','1980-04-02','peanut allergy'),('p2','Cher',NULL,NULL)"
        )
        db.execSQL("INSERT INTO users (id,name,role,pinHash,pinSalt) VALUES ('u1','Admin','ADMIN','h','s')")
        db.execSQL(
            "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes," +
                "startDate,endDate,bottleExpiration,pillCountEntered,isException,qrCode) VALUES " +
                "('m1','p1','0069-2587','Metformin','500 mg','TABLET','1 tablet','08:00','2026-07-01'," +
                "'2026-07-31',NULL,5,0,'qr-1')," +
                "('m2','p1','0000-0001','Ibuprofen','200 mg','TABLET','As needed','14:00','2026-07-01'," +
                "NULL,NULL,NULL,0,'qr-2')"
        )
        db.execSQL(
            "INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,loggedByUserId,wasOverride) " +
                "VALUES ('d1','m1','2026-07-02T08:00','GIVEN','2026-07-02T08:03','u1',0)," +
                "('d2','m2','2026-07-02T14:00','REFUSED','2026-07-02T14:01','u1',0)"
        )
    }

    /**
     * Builds a database that is structurally identical to a released [version] install, then closes it.
     * Only the v1 export is committed, so every older baseline comes from the app's own migration chain.
     */
    private fun databaseAsReleasedAt(name: String, version: Int) {
        val db = helper.createDatabase(name, 1)
        seedV1(db)
        chain.take(version - 1).forEach { it.migrate(db) }
        if (version > 1) db.execSQL("PRAGMA user_version = $version")

        // Data that only exists from schema 5 on: import/change history and named administration times. Filled
        // after the chain so the assertions below cover "existing rows in the tables added by 4 -> 5".
        if (version >= 5) {
            db.execSQL(
                "INSERT INTO import_history (id,createdAt,sourceFileName,mode,outcome,backupFileName,newPatients," +
                    "newMedications,patientsUpdated,medicationsUpdated,fieldsChanged,rowsSkipped,message) VALUES " +
                    "('i1','2026-07-01T10:00','camp.xlsx','ADD','COMPLETED','before-import-1.db',2,2,0,0,0,0,NULL)"
            )
            db.execSQL(
                "INSERT INTO change_history (id,importId,changedAt,actorUserId,recordType,recordId,field,oldValue," +
                    "newValue,significant) VALUES ('c1','i1','2026-07-01T10:00','u1','MEDICATION','m1','strength'," +
                    "NULL,'500 mg',1)"
            )
            db.execSQL("INSERT INTO administration_times (id,name,time) VALUES ('t1','Breakfast','08:00')")
        }
        // From schema 6 on the recovery answers and the persistent lockout counters exist; fill them so the
        // 6 -> 7 step is proven not to touch them.
        if (version >= 6) {
            db.execSQL(
                "UPDATE users SET recoveryQuestionIds='q1,q2,q3', recoveryHashes='h1,h2,h3', " +
                    "recoverySalts='s1,s2,s3', recoveryFailures=2 WHERE id='u1'"
            )
        }
        db.close()
    }

    /**
     * [recoverySeeded] is false for the schema-5 path: the recovery-answer and lockout columns are *added* by
     * migration 5 -> 6, so a schema-5 file cannot contain them. Migration must leave them at their declared
     * defaults there, and must preserve them where they already exist.
     */
    private fun assertEverythingSurvived(db: SupportSQLiteDatabase, recoverySeeded: Boolean = true) {
        // ---- patients: identity, notes, archived flag, and the 6 -> 7 list conversion ----
        db.query(
            "SELECT name,firstName,lastName,notes,conditions,isArchived,bunk,bunks,counselor,counselors " +
                "FROM patients WHERE id='p1'"
        ).use { c ->
            assertTrue("the first patient must exist", c.moveToFirst())
            assertEquals("Mary Ann Smith", c.getString(0))
            assertEquals("Mary Ann", c.getString(1))
            assertEquals("Smith", c.getString(2))
            assertEquals("notes are clinical data and must survive", "peanut allergy", c.getString(3))
            // `conditions` is added by 1 -> 2 with a NULL default; nothing may invent a value for it.
            assertNull("a column added by a migration must stay empty for old records", c.getString(4))
            assertEquals(0, c.getInt(5))
            assertNull("a schema-5 patient has no bunk; migration must not invent one", c.getString(6))
            assertEquals("the new list column starts empty, never with a made-up value", 0, JSONArray(c.getString(7)).length())
            assertNull(c.getString(8))
            assertEquals(0, JSONArray(c.getString(9)).length())
        }
        db.query("SELECT COUNT(*) FROM patients").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no patient may be added or dropped", 2, c.getInt(0))
        }
        db.query("SELECT isArchived FROM patients WHERE id='p2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a patient that was not archived must not become archived", 0, c.getInt(0))
        }

        // ---- medications: both rows, PRN flag defaulted off (the concept is new), export name allocated ----
        db.query(
            "SELECT patientId,scheduleTimes,isPrn,qrCode,pillCountEntered,isArchived FROM medications WHERE id='m1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("p1", c.getString(0))
            assertEquals("08:00", c.getString(1))
            assertEquals("an existing medication must not silently become PRN", 0, c.getInt(2))
            assertEquals("qr-1", c.getString(3))
            assertEquals(5, c.getInt(4))
            assertEquals(0, c.getInt(5))
        }
        db.query("SELECT scheduleTimes,qrCode FROM medications WHERE id='m2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("14:00", c.getString(0))
            assertEquals("qr-2", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM medications").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no medication may be added or dropped", 2, c.getInt(0))
        }
        db.query("SELECT sheetName FROM medications").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                assertTrue("every medication must keep an export sheet name, was: $name", !name.isNullOrBlank())
            }
        }

        // ---- users: recovery answers and the persistent lockout counter ----
        db.query("SELECT name,pinHash,pinSalt,recoveryQuestionIds,recoveryHashes,recoverySalts,recoveryFailures FROM users WHERE id='u1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Admin", c.getString(0))
            assertEquals("the PIN hash must not be rewritten by an upgrade", "h", c.getString(1))
            assertEquals("s", c.getString(2))
            if (recoverySeeded) {
                assertEquals("recovery questions must survive", "q1,q2,q3", c.getString(3))
                assertEquals("h1,h2,h3", c.getString(4))
                assertEquals("s1,s2,s3", c.getString(5))
                assertEquals("the persistent lockout counter must survive", 2, c.getInt(6))
            } else {
                // Added by 5 -> 6 with NOT NULL DEFAULT '': they must be empty, never invented.
                assertEquals("", c.getString(3))
                assertEquals("", c.getString(4))
                assertEquals("", c.getString(5))
                assertEquals(0, c.getInt(6))
            }
        }

        // ---- administration history: every record, status and timestamp ----
        db.query("SELECT medicationId,status,loggedAt,wasOverride,heldReason,isPrn FROM dose_logs WHERE id='d1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("m1", c.getString(0))
            assertEquals("GIVEN", c.getString(1))
            assertEquals("2026-07-02T08:03", c.getString(2))
            assertEquals(0, c.getInt(3))
            assertNull(c.getString(4))
            assertEquals(0, c.getInt(5))
        }
        db.query("SELECT medicationId,status,loggedAt,heldReason,isPrn FROM dose_logs WHERE id='d2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("m2", c.getString(0))
            assertEquals("REFUSED", c.getString(1))
            assertEquals("2026-07-02T14:01", c.getString(2))
            assertNull("a refusal has no held reason", c.getString(3))
            assertEquals(0, c.getInt(4))
        }
        db.query("SELECT COUNT(*) FROM dose_logs").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no administration record may be duplicated or lost", 2, c.getInt(0))
        }

        // ---- import history, change history and named administration times ----
        db.query("SELECT sourceFileName FROM import_history WHERE id='i1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("camp.xlsx", c.getString(0))
        }
        db.query("SELECT newValue FROM change_history WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("500 mg", c.getString(0))
        }
        db.query("SELECT name,time FROM administration_times WHERE id='t1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Breakfast", c.getString(0)); assertEquals("08:00", c.getString(1))
        }

        // ---- every schema-7 table exists, so a restore or export cannot miss one ----
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { tables += it }
        }
        listOf(
            "patients", "medications", "dose_logs", "users", "administration_times", "import_history",
            "change_history", "camp_settings", "camp_sessions", "divisions", "bunk_options"
        ).forEach { assertTrue("table $it must exist at schema $SCHEMA_VERSION", it in tables) }
    }

    /** The path taken by a device updating from the older backup/restore build (schema 5). */
    @Test
    fun schemaFiveUpgradesThroughSixToSevenWithAllData() {
        val name = "migration-5-7"
        databaseAsReleasedAt(name, 5)
        val db = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, MIGRATION_5_6, MIGRATION_6_7)
        assertEverythingSurvived(db, recoverySeeded = false)
        db.close()
    }

    /** The path taken by a device on the 1.2 / schema-6 release. */
    @Test
    fun schemaSixUpgradesToSevenWithAllData() {
        val name = "migration-6-7-full"
        databaseAsReleasedAt(name, 6)
        val db = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, MIGRATION_6_7)
        assertEverythingSurvived(db)
        db.close()
    }

    /** A fresh install (empty v1 database straight through the whole chain) must also arrive intact. */
    @Test
    fun freshInstallRunsTheWholeChain() {
        val db = helper.createDatabase("migration-full-chain", 1)
        db.close()
        val migrated = helper.runMigrationsAndValidate("migration-full-chain", SCHEMA_VERSION, true, *ALL_MIGRATIONS)
        migrated.query("SELECT COUNT(*) FROM patients").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        migrated.close()
    }

    /**
     * A database already at schema 7 (the current release) must open with no migration at all and must keep
     * every row: the "existing installations remain intact" guarantee.
     */
    @Test
    fun anExistingSchemaSevenDatabaseIsOpenedWithoutMigrationAndKeepsItsData() {
        val name = "existing-7"
        // Bring a populated schema-6 database to 7 through the real migration, which is exactly what an
        // installed copy looks like after updating: version 7, this build's schema and Room's identity record.
        databaseAsReleasedAt(name, 6)
        helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, MIGRATION_6_7).close()

        // Reopen with NO migrations passed. Room validates the stored schema instead of upgrading it, so a
        // column, index or table added without a schema-version bump fails here rather than on a device. The
        // rows must be untouched: opening a current database is a no-op, not a rewrite.
        val reopened = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true)
        assertEverythingSurvived(reopened)
        reopened.close()
    }

    @Test
    fun theRegisteredChainCoversOneToSeven() {
        assertEquals("one migration per version step", SCHEMA_VERSION - 1, ALL_MIGRATIONS.size)
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(SCHEMA_VERSION, ALL_MIGRATIONS.last().endVersion)
    }
}
