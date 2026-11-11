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
        assertEquals("bump this assertion together with SCHEMA_VERSION and a new migration", 5, SCHEMA_VERSION)
    }
}
