package com.campmeds.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.ALL_MIGRATIONS
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.SCHEMA_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every released schema version -> current, with realistic populated data.
 *
 * [MigrationTest] covers a v1 install and [SchemaFiveToSevenMigrationTest] covers the schema-5/6/7 paths that the
 * backup/restore work depends on. An install that skipped an update can also start at v2, v3 or v4, and those
 * paths are the ones that break silently when an older migration is edited later: a v3 device never runs
 * MIGRATION_1_2 or MIGRATION_2_3 again.
 *
 * The older baselines are produced here by the app's own migrations applied to the committed v1 schema, then
 * `PRAGMA user_version` is set to that version, which is exactly what a released v2/v3/v4 database looks like.
 * There is no hand-written "expected schema" involved: the final state is validated by Room against the schema
 * JSON this build generates.
 *
 * This test was ported from the schema-5 backup/restore branch: it originally stopped at schema 5 and is now
 * driven by [SCHEMA_VERSION] and [ALL_MIGRATIONS], so it covers 1 -> 7 and cannot drift when the schema moves.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFromEveryVersionTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val dbName = "migration-from-version-test"

    /** A populated v1 install: two patients, one medication, two administration records, one user. */
    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO patients (id,name,dob,notes) VALUES " +
                "('p1','Mary Ann Smith','1980-04-02','peanut allergy'),('p2','Cher',NULL,NULL)"
        )
        db.execSQL("INSERT INTO users (id,name,role,pinHash,pinSalt) VALUES ('u1','Admin','ADMIN','h','s')")
        db.execSQL(
            "INSERT INTO medications (id,patientId,ndc,name,strength,form,dosageInstructions,scheduleTimes," +
                "startDate,endDate,bottleExpiration,pillCountEntered,isException,qrCode) VALUES " +
                "('m1','p1','0069-2587','Zyrtec','10 mg','TABLET','1 tab','08:00,20:00','2026-07-01'," +
                "'2026-12-31',NULL,5,0,'qr-1')"
        )
        db.execSQL(
            "INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,loggedByUserId,wasOverride) VALUES " +
                "('d1','m1','2026-07-02T08:00','GIVEN','2026-07-02T08:03','u1',0)," +
                "('d2','m1','2026-07-02T20:00','REFUSED','2026-07-02T20:01','u1',0)"
        )
    }

    /** Builds a database that is structurally identical to a released [version] install, then closes it. */
    private fun databaseAsReleasedAt(version: Int) {
        val db = helper.createDatabase(dbName, 1)
        seedV1(db)
        ALL_MIGRATIONS.take(version - 1).forEach { it.migrate(db) }
        if (version > 1) db.execSQL("PRAGMA user_version = $version")
        db.close()
    }

    private fun assertPopulatedAndIntact(db: SupportSQLiteDatabase) {
        db.query("SELECT id,name,firstName,lastName,notes FROM patients ORDER BY id").use { c ->
            assertTrue("both patients must survive", c.moveToNext())
            assertEquals("p1", c.getString(0))
            assertEquals("peanut allergy", c.getString(4))
            assertTrue("patients must stay two", c.moveToNext())
            assertEquals("p2", c.getString(0))
            assertTrue("no extra patient may be created", !c.moveToNext())
        }

        db.query("SELECT patientId,scheduleTimes,endDate,qrCode,isArchived FROM medications WHERE id='m1'")
            .use { c ->
                assertTrue(c.moveToNext())
                assertEquals("the medication must stay attached to its patient", "p1", c.getString(0))
                assertEquals("08:00,20:00", c.getString(1))
                assertEquals("2026-12-31", c.getString(2))
                assertEquals("qr-1", c.getString(3))
                assertEquals(0, c.getInt(4))
            }

        db.query("SELECT medicationId,status,loggedAt FROM dose_logs ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("m1", c.getString(0))
            assertEquals("GIVEN", c.getString(1))
            assertEquals("2026-07-02T08:03", c.getString(2))
            assertTrue(c.moveToNext())
            assertEquals("REFUSED", c.getString(1))
            assertTrue("administration records must not be duplicated", !c.moveToNext())
        }

        // Every table of the current schema exists, so a restore or export after the upgrade cannot miss one.
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { tables += it }
        }
        listOf(
            "patients", "medications", "dose_logs", "users", "administration_times", "import_history",
            "change_history", "camp_settings", "camp_sessions", "divisions", "bunk_options"
        ).forEach {
            assertTrue("table $it must exist after migrating to v$SCHEMA_VERSION", it in tables)
        }
    }

    private fun migrateAndCheck(fromVersion: Int) {
        databaseAsReleasedAt(fromVersion)
        val db = helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true, *ALL_MIGRATIONS.drop(fromVersion - 1).toTypedArray()
        )
        assertPopulatedAndIntact(db)
        db.close()
    }

    @Test
    fun fromV2_migratesEverythingToCurrent() = migrateAndCheck(2)

    @Test
    fun fromV3_migratesEverythingToCurrent() = migrateAndCheck(3)

    @Test
    fun fromV4_migratesEverythingToCurrent() = migrateAndCheck(4)

    @Test
    fun fromV5_migratesEverythingToCurrent() = migrateAndCheck(5)

    @Test
    fun fromV6_migratesEverythingToCurrent() = migrateAndCheck(6)

    @Test
    fun fromV1_migratesEverythingToCurrent() = migrateAndCheck(1)

    /** Opening a database that is already current must not migrate, rewrite or lose anything. */
    @Test
    fun openingTheCurrentVersion_isANoOp() {
        databaseAsReleasedAt(SCHEMA_VERSION - 1)
        helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true, *ALL_MIGRATIONS.drop(SCHEMA_VERSION - 2).toTypedArray()
        ).close()

        // Reopen at the current version with no migration passed at all: Room must simply validate and open.
        val db = helper.runMigrationsAndValidate(dbName, SCHEMA_VERSION, true)
        assertPopulatedAndIntact(db)
        db.query("SELECT endDate FROM medications WHERE id='m1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-12-31", c.getString(0))
        }
        db.close()
    }

    /** A medication that never had an end date must still have none after every upgrade path. */
    @Test
    fun nullValuesAreNotInventedByMigrations() {
        databaseAsReleasedAt(2)
        val db = helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true, *ALL_MIGRATIONS.drop(1).toTypedArray()
        )
        db.query("SELECT purpose FROM medications WHERE id='m1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull("a column added later must stay empty for old records", c.getString(0))
        }
        db.close()
    }
}
