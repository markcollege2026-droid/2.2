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

    /** A populated v1 install: two patients (one archived later), one user, two medications, two records. */
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

    private fun assertEverythingSurvived(db: SupportSQLiteDatabase) {
        // ---- patients: identity, notes, archived flag, and the 6 -> 7 list conversion ----
        db.query("SELECT id,name,firstName,lastName,notes,conditions,isArchived,bunk,bunks,counselor,counselors FROM patients ORDER BY id").use { c ->
            assertTrue("both patients must exist", c.moveToNext())
            assertEquals("p1", c.getString(0))
            assertEquals("Mary Ann Smith", c.getString(1))
            assertEquals("notes are clinical data and must survive", "peanut allergy", c.getString(4))
            assertEquals(0, c.getInt(6))
            assertNull("a schema-5 patient has no bunk; migration must not invent one", c.getString(7))
            assertEquals("the new list column starts empty, never with a made-up value", 0, JSONArray(c.getString(8)).length())
            assertNull(c.getString(9))
            assertEquals(0, JSONArray(c.getString(10)).length())
            assertTrue(c.moveToNext())
            assertEquals("p2", c.getString(0))
            assertEquals("an archived patient must stay archived", 1, c.getInt(6))
            assertTrue("no patient may be added or dropped", !c.moveToNext())
        }

        // ---- medications: both rows, PRN flag defaulted off (the concept is new), export name allocated ----
        db.query("SELECT id,patientId,scheduleTimes,isPrn,qrCode,sheetName,pillCountEntered,isArchived FROM medications ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("m1", c.getString(0))
            assertEquals("p1", c.getString(1))
            assertEquals("08:00", c.getString(2))
            assertEquals("an existing medication must not silently become PRN", 0, c.getInt(3))
            assertEquals("qr-1", c.getString(4))
            assertEquals(5, c.getInt(6))
            assertEquals(0, c.getInt(7))
            assertTrue(c.moveToNext())
            assertEquals("m2", c.getString(0))
            assertEquals("14:00", c.getString(2))
            assertEquals("qr-2", c.getString(4))
            assertTrue("no medication may be added or dropped", !c.moveToNext())
        }
        db.query("SELECT sheetName FROM medications").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                assertTrue("every medication must keep an export sheet name, was: $name", !name.isNullOrBlank())
            }
        }

        // ---- users: recovery answers filled at v6 must be preserved untouched by 6 -> 7 ----
        db.query("SELECT name,pinHash,pinSalt,recoveryQuestionIds,recoveryHashes,recoverySalts,recoveryFailures FROM users WHERE id='u1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Admin", c.getString(0))
            assertEquals("h", c.getString(1))
            assertEquals("s", c.getString(2))
            assertEquals("q1,q2,q3", c.getString(3))
            assertEquals("h1,h2,h3", c.getString(4))
            assertEquals("s1,s2,s3", c.getString(5))
            assertEquals("the persistent lockout counter must survive", 2, c.getInt(6))
        }

        // ---- administration history: every record, status and timestamp ----
        db.query("SELECT id,medicationId,status,loggedAt,wasOverride,heldReason,isPrn FROM dose_logs ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("d1", c.getString(0))
            assertEquals("m1", c.getString(1))
            assertEquals("GIVEN", c.getString(2))
            assertEquals("2026-07-02T08:03", c.getString(3))
            assertEquals(0, c.getInt(4))
            assertNull(c.getString(5))
            assertEquals(0, c.getInt(6))
            assertTrue(c.moveToNext())
            assertEquals("d2", c.getString(0))
            assertEquals("m2", c.getString(1))
            assertEquals("REFUSED", c.getString(2))
            assertEquals("2026-07-02T14:01", c.getString(3))
            assertTrue("no administration record may be duplicated", !c.moveToNext())
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
        assertEverythingSurvived(db)
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
        databaseAsReleasedAt(name, 7)
        helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true).close()

        // Reopen again with no migrations passed: Room validates the schema and opens the same file.
        val db = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true)
        assertEverythingSurvived(db)
        db.close()
    }

    @Test
    fun theRegisteredChainCoversOneToSeven() {
        assertEquals("one migration per version step", SCHEMA_VERSION - 1, ALL_MIGRATIONS.size)
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(SCHEMA_VERSION, ALL_MIGRATIONS.last().endVersion)
    }
}
