package com.campmeds.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guard on the migration *registry* (no Android needed, so it runs in milliseconds on every build).
 *
 * The instrumented tests prove that the migrations themselves preserve data. This one catches the failure that
 * would otherwise only appear on a real device upgrade of an old install: a missing or misordered entry in
 * [ALL_MIGRATIONS], which makes Room throw instead of migrating. [AppDatabase] registers exactly this list and
 * takes its version from [SCHEMA_VERSION], so the three cannot drift apart.
 */
class MigrationRegistryTest {

    @Test
    fun migrationsCoverEveryVersionFromOneToCurrent_withoutGapsOrDuplicates() {
        assertTrue("there must be at least one migration", ALL_MIGRATIONS.isNotEmpty())
        assertEquals(
            "every version from 1 to $SCHEMA_VERSION needs exactly one migration",
            SCHEMA_VERSION - 1,
            ALL_MIGRATIONS.size
        )

        var expectedStart = 1
        val seen = mutableSetOf<Int>()
        for (migration in ALL_MIGRATIONS) {
            assertTrue(
                "migration ${migration.startVersion}->${migration.endVersion} is out of order",
                migration.startVersion == expectedStart
            )
            assertEquals(
                "migration from ${migration.startVersion} must go to ${migration.startVersion + 1}",
                migration.startVersion + 1,
                migration.endVersion
            )
            assertTrue("version ${migration.startVersion} is covered twice", seen.add(migration.startVersion))
            expectedStart = migration.endVersion
        }
        assertEquals("the chain must end at the current schema version", SCHEMA_VERSION, expectedStart)
    }

    @Test
    fun currentSchemaVersionMatchesTheReleasedDatabaseVersion() {
        // Deliberately a literal: if someone bumps one without the other, this fails and forces a decision.
        // 7 is the highest schema the CampMeds app has ever shipped; the backup/restore work added no
        // migration of its own, so the version must not move.
        assertEquals("bump this assertion together with SCHEMA_VERSION and a new migration", 7, SCHEMA_VERSION)
    }

    /**
     * The migrations the older backup/restore build only knew about (it was based on schema 5) must stay
     * registered here. If one were dropped, a device that skipped an update would crash instead of upgrading,
     * and a schema-5 backup could no longer be restored.
     */
    @Test
    fun migrationsFromTheOlderSchemaFiveBuildAreStillRegistered() {
        val versions = ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7).forEach { step ->
            assertTrue("migration $step is missing from ALL_MIGRATIONS", step in versions)
        }
    }
}
