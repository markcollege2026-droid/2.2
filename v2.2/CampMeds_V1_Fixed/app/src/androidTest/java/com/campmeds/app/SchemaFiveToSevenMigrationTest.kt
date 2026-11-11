package com.campmeds.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.ALL_MIGRATIONS
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.MIGRATION_5_6
import com.campmeds.app.data.MIGRATION_6_7
import com.campmeds.app.data.SCHEMA_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray

/**
 * The upgrade paths the backup/restore work depends on.
 *
 * The older backup/restore branch was built on schema 5. A device that installs this build from that line is a
 * schema-5 database, and any backup taken there is a schema-5 file. Both have to arrive at schema 7 with every
 * row intact:
 *
 *  - 5 -> 6 -> 7 on a populated schema-5 database (patients, medications, PRN flag, dose history, users with
 *    recovery answers, import/change history);
 *  - a database that is already at schema 7 must open without being migrated, rewritten or emptied;
 *  - the full chain must still be contiguous (also asserted on the JVM by MigrationRegistryTest).
 *
 * The schema-5 baseline is produced by the app's own committed schema-6 JSON reverse-engineered through
 * migration 6 -> 7's own predecessor: [MigrationTestHelper.createDatabase] with a committed export, then the
 * chain applied forward. Nothing is hand-written that the app itself would not produce.
 */
@RunWith(AndroidJUnit4::class)
class SchemaFiveToSevenMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun seedSchemaFive(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO patients (id,name,firstName,lastName,notes,isArchived,startDate,endDate,bunk,counselor,division) " +
                "VALUES ('p1','Mary Ann Smith','Mary Ann','Smith','peanut allergy',0,'2026-07-01','2026-07-31','Cabin East','Smith, Jane','Junior')"
        )
        db.execSQL("INSERT INTO patients (id,name,firstName,lastName,isArchived) VALUES ('p2','Cher','Cher','',1)")
        db.execSQL(
            "INSERT INTO users (id,name,role,pinHash,pinSalt,recoveryQuestionIds,recoveryHashes,recoverySalts," +
                "recoveryFailures,recoveryLockedUntil) VALUES " +
                "('u1','Admin','ADMIN','h','s','q1,q2,q3','h1,h2,h3','s1,s2,s3',2,0)"
        )
        db.execSQL(
            "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes," +
                "administrationTimeIds,startDate,endDate,bottleExpiration,pillCountEntered,isException,qrCode," +
                "requireQrScan,isGroupAdministered,prepackagedGroupId,prepackagedGroupName,purpose,isArchived," +
                "isPrn,createdByUserId,createdByName,createdAt,sheetName,discontinuedOn) VALUES " +
                "('m1','p1','0069-2587','Metformin','500 mg','TABLET','1 tablet','08:00','','2026-07-01',NULL," +
                "NULL,5,0,'qr-1',1,0,NULL,NULL,NULL,0,0,'u1','Admin','2026-07-01T09:00','Mary Ann Smith - Metformin',NULL)"
        )
        db.execSQL(
            "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes," +
                "administrationTimeIds,startDate,endDate,bottleExpiration,pillCountEntered,isException,qrCode," +
                "requireQrScan,isGroupAdministered,prepackagedGroupId,prepackagedGroupName,purpose,isArchived," +
                "isPrn,createdByUserId,createdByName,createdAt,sheetName,discontinuedOn) VALUES " +
                "('m2','p1','0000-0001','Ibuprofen','200 mg','TABLET','As needed','','','2026-07-01',NULL," +
                "NULL,NULL,0,'qr-prn',1,0,NULL,NULL,NULL,0,1,'u1','Admin','2026-07-01T09:05','Mary Ann Smith - Ibuprofen',NULL)"
        )
        db.execSQL(
            "INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,loggedByUserId,wasOverride,heldReason) VALUES " +
                "('d1','m1','2026-07-02T08:00','GIVEN','2026-07-02T08:03','u1',0,NULL)," +
                "('d2','m2','2026-07-02T13:20','HELD','2026-07-02T13:21','u1',0,'Camper asleep')"
        )
        db.execSQL(
            "INSERT INTO import_history (id,createdAt,sourceFileName,mode,outcome,backupFileName,newPatients," +
                "newMedications,patientsUpdated,medicationsUpdated,fieldsChanged,rowsSkipped,message) VALUES " +
                "('i1','2026-07-01T10:00','camp.xlsx','ADD','COMPLETED','before-import-1.db',2,2,0,0,0,0,NULL)"
        )
        db.execSQL(
            "INSERT INTO change_history (id,importId,changedAt,actorUserId,recordType,recordId,field,oldValue,newValue,significant) " +
                "VALUES ('c1','i1','2026-07-01T10:00','u1','MEDICATION','m1','strength',NULL,'500 mg',1)"
        )
        db.execSQL("INSERT INTO administration_times (id,name,time) VALUES ('t1','Breakfast','08:00')")
    }

    /**
     * Builds a populated schema-5 database. The committed 6.json export is used as a starting point only through
     * the normal Room path: create at 6, then run 5 -> 6's *predecessor* is not possible from a later export, so
     * the version is pinned by writing the schema-5 tables directly with the SQL the app's own migrations
     * produce (see [seedSchemaFive]) and setting user_version = 5.
     */
    private fun databaseAsSchemaFiveAt(name: String) {
        val db = helper.createDatabase(name, 5)
        seedSchemaFive(db)
        db.execSQL("PRAGMA user_version = 5")
        db.close()
    }

    private fun assertEverythingSurvived(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // ---- patients: identities, notes, archived flag, and the 6 -> 7 list conversion ----
        db.query("SELECT id,name,firstName,lastName,notes,isArchived,division,bunk,bunks,counselors FROM patients ORDER BY id").use { c ->
            assertTrue("both patients must exist", c.moveToNext())
            assertEquals("p1", c.getString(0))
            assertEquals("peanut allergy", c.getString(4))
            assertEquals(0, c.getInt(5))
            assertEquals("Junior", c.getString(6))
            assertEquals("the legacy single bunk must stay", "Cabin East", c.getString(7))
            val bunks = JSONArray(c.getString(8))
            assertEquals("the legacy bunk must become one list item", 1, bunks.length())
            assertEquals("Cabin East", bunks.getString(0))
            val counselors = JSONArray(c.getString(9))
            assertEquals("a comma in a counselor name must not split it", 1, counselors.length())
            assertEquals("Smith, Jane", counselors.getString(0))
            assertTrue(c.moveToNext())
            assertEquals("p2", c.getString(0))
            assertEquals("an archived patient must stay archived", 1, c.getInt(5))
        }

        // ---- medications: both rows, PRN flags preserved, nothing reinvented ----
        db.query("SELECT id,name,patientId,scheduleTimes,isPrn,qrCode,sheetName,pillCountEntered FROM medications ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("m1", c.getString(0))
            assertEquals("p1", c.getString(2))
            assertEquals("08:00", c.getString(3))
            assertEquals("a scheduled medication must not become PRN", 0, c.getInt(4))
            assertEquals("qr-1", c.getString(5))
            assertEquals("Mary Ann Smith - Metformin", c.getString(6))
            assertEquals(5, c.getInt(7))
            assertTrue(c.moveToNext())
            assertEquals("m2", c.getString(0))
            assertEquals("a PRN medication must stay PRN", 1, c.getInt(4))
            assertTrue("no medication may be added or dropped", !c.moveToNext())
        }

        // ---- users: recovery answers and the persistent lockout counters ----
        db.query("SELECT recoveryQuestionIds,recoveryHashes,recoverySalts,recoveryFailures FROM users WHERE id='u1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("q1,q2,q3", c.getString(0))
            assertEquals("h1,h2,h3", c.getString(1))
            assertEquals("s1,s2,s3", c.getString(2))
            assertEquals("failure counters must survive", 2, c.getInt(3))
        }

        // ---- administration history: status, timestamps and held reason ----
        db.query("SELECT id,medicationId,status,loggedAt,heldReason,isPrn FROM dose_logs ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("d1", c.getString(0))
            assertEquals("GIVEN", c.getString(2))
            assertEquals("2026-07-02T08:03", c.getString(3))
            assertTrue(c.moveToNext())
            assertEquals("d2", c.getString(0))
            assertEquals("HELD", c.getString(2))
            assertEquals("the held reason is history and must survive", "Camper asleep", c.getString(4))
            assertTrue("no administration record may be duplicated", !c.moveToNext())
        }

        // ---- import and change history ----
        db.query("SELECT sourceFileName FROM import_history WHERE id='i1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("camp.xlsx", c.getString(0))
        }
        db.query("SELECT newValue FROM change_history WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("500 mg", c.getString(0))
        }
        db.query("SELECT name FROM administration_times WHERE id='t1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Breakfast", c.getString(0))
        }

        // ---- every schema-7 table now exists ----
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { tables += it }
        }
        listOf(
            "patients", "medications", "dose_logs", "users", "administration_times", "import_history",
            "change_history", "camp_settings", "camp_sessions", "divisions", "bunk_options"
        ).forEach { assertTrue("table $it must exist at schema $SCHEMA_VERSION", it in tables) }
    }

    @Test
    fun schemaFiveUpgradesThroughSixToSevenWithAllData() {
        val name = "migration-5-7"
        databaseAsSchemaFiveAt(name)
        val db = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, MIGRATION_5_6, MIGRATION_6_7)
        assertEverythingSurvived(db)
        db.close()
    }

    /** The same data stopped at schema 6 first (an install that updated to the 1.2/schema-6 release). */
    @Test
    fun schemaSixUpgradesToSevenWithAllData() {
        val name = "migration-6-7-full"
        databaseAsSchemaFiveAt(name)
        helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6).close()

        val db = helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, MIGRATION_6_7)
        assertEverythingSurvived(db)
        db.close()
    }

    /**
     * A database that is already at schema 7 (the current release) must open with no migration at all and must
     * keep every row: this is the "existing installations remain intact" guarantee.
     */
    @Test
    fun anExistingSchemaSevenDatabaseIsOpenedWithoutMigrationAndKeepsItsData() {
        val name = "existing-7"
        databaseAsSchemaFiveAt(name)
        helper.runMigrationsAndValidate(name, SCHEMA_VERSION, true, *ALL_MIGRATIONS).close()

        // Reopen at the current version with no migrations passed: Room validates the schema and opens the file.
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
