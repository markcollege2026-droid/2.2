package com.campmeds.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.campmeds.app.domain.splitFullName

/**
 * v1 -> v2. Preserves every existing row.
 *
 *  patients     : + firstName, lastName (backfilled from the old single `name`), conditions, isArchived
 *  medications  : + purpose, isArchived; FK to patients changes CASCADE -> RESTRICT (table rebuilt)
 *  dose_logs    : FK to medications (CASCADE) removed (table rebuilt); FK to users (SET NULL) kept
 *
 * Order matters. SQLite cannot alter a foreign key, so two tables are rebuilt. `dose_logs` is rebuilt
 * FIRST so that nothing references `medications` any more when `medications` is dropped; dropping a
 * parent table with foreign keys enabled performs an implicit DELETE, and with the old CASCADE key that
 * would have deleted the dose history.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------- patients ----------
        db.execSQL("ALTER TABLE `patients` ADD COLUMN `firstName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `patients` ADD COLUMN `lastName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `patients` ADD COLUMN `conditions` TEXT")
        db.execSQL("ALTER TABLE `patients` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0")

        // Backfill: last whitespace-separated word = last name, everything before it = first name.
        // A single word becomes the first name. `name` itself is left untouched.
        val updates = mutableListOf<Triple<String, String, String>>()
        db.query("SELECT `id`, `name` FROM `patients`").use { c ->
            while (c.moveToNext()) {
                val (first, last) = splitFullName(c.getString(1) ?: "")
                updates += Triple(c.getString(0), first, last)
            }
        }
        for ((id, first, last) in updates) {
            db.execSQL(
                "UPDATE `patients` SET `firstName` = ?, `lastName` = ? WHERE `id` = ?",
                arrayOf<Any?>(first, last, id)
            )
        }

        // ---------- dose_logs (rebuilt first: leaf table, nothing references it) ----------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dose_logs_new` (`id` TEXT NOT NULL, `medicationId` TEXT NOT NULL, " +
                "`scheduledFor` TEXT NOT NULL, `status` TEXT NOT NULL, `loggedAt` TEXT NOT NULL, " +
                "`loggedByUserId` TEXT, `wasOverride` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`loggedByUserId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL(
            "INSERT INTO `dose_logs_new` (`id`, `medicationId`, `scheduledFor`, `status`, `loggedAt`, " +
                "`loggedByUserId`, `wasOverride`) " +
                "SELECT `id`, `medicationId`, `scheduledFor`, `status`, `loggedAt`, `loggedByUserId`, `wasOverride` " +
                "FROM `dose_logs`"
        )
        db.execSQL("DROP TABLE `dose_logs`")
        db.execSQL("ALTER TABLE `dose_logs_new` RENAME TO `dose_logs`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_logs_medicationId` ON `dose_logs` (`medicationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_logs_loggedByUserId` ON `dose_logs` (`loggedByUserId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_logs_scheduledFor` ON `dose_logs` (`scheduledFor`)")

        // ---------- medications ----------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `medications_new` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, " +
                "`ndc` TEXT NOT NULL, `name` TEXT NOT NULL, `strength` TEXT NOT NULL, `form` TEXT NOT NULL, " +
                "`dosageInstructions` TEXT NOT NULL, `scheduleTimes` TEXT NOT NULL, `startDate` TEXT NOT NULL, " +
                "`endDate` TEXT, `bottleExpiration` TEXT, `pillCountEntered` INTEGER, " +
                "`isException` INTEGER NOT NULL, `qrCode` TEXT NOT NULL, `purpose` TEXT, " +
                "`isArchived` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
        )
        db.execSQL(
            "INSERT INTO `medications_new` (`id`, `patientId`, `ndc`, `name`, `strength`, `form`, " +
                "`dosageInstructions`, `scheduleTimes`, `startDate`, `endDate`, `bottleExpiration`, " +
                "`pillCountEntered`, `isException`, `qrCode`, `purpose`, `isArchived`) " +
                "SELECT `id`, `patientId`, `ndc`, `name`, `strength`, `form`, `dosageInstructions`, " +
                "`scheduleTimes`, `startDate`, `endDate`, `bottleExpiration`, `pillCountEntered`, " +
                "`isException`, `qrCode`, NULL, 0 FROM `medications`"
        )
        db.execSQL("DROP TABLE `medications`")
        db.execSQL("ALTER TABLE `medications_new` RENAME TO `medications`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_medications_patientId` ON `medications` (`patientId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_medications_qrCode` ON `medications` (`qrCode`)")
    }
}

/**
 * v2 -> v3. Preserves every existing row; adds the per-medication settings for the new features and the
 * shared list of named administration times.
 *
 *  medications          : + administrationTimeIds, requireQrScan, isGroupAdministered, prepackagedGroupId
 *                         (table rebuilt, like 1 -> 2, so the new NOT NULL columns carry no SQL DEFAULT
 *                         that Room's schema validation would then have to match; the FK to patients stays
 *                         RESTRICT so history still cannot be cascaded away)
 *  administration_times : new table, empty to start with — administrators create the entries themselves
 *
 * Defaults chosen for existing data:
 *  - administrationTimeIds = ''  → every existing schedule is a custom time, exactly as it is today
 *  - requireQrScan = 1           → the existing "scan before recording" workflow is unchanged
 *  - isGroupAdministered = 0, prepackagedGroupId = NULL → nothing is grouped by itself
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------- medications (rebuilt so the new columns are declared exactly as Room expects) ----------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `medications_new` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, " +
                "`ndc` TEXT NOT NULL, `name` TEXT NOT NULL, `strength` TEXT NOT NULL, `form` TEXT NOT NULL, " +
                "`dosageInstructions` TEXT NOT NULL, `scheduleTimes` TEXT NOT NULL, " +
                "`administrationTimeIds` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT, " +
                "`bottleExpiration` TEXT, `pillCountEntered` INTEGER, `isException` INTEGER NOT NULL, " +
                "`qrCode` TEXT NOT NULL, `requireQrScan` INTEGER NOT NULL, `isGroupAdministered` INTEGER NOT NULL, " +
                "`prepackagedGroupId` TEXT, `purpose` TEXT, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
        )
        db.execSQL(
            "INSERT INTO `medications_new` (`id`, `patientId`, `ndc`, `name`, `strength`, `form`, " +
                "`dosageInstructions`, `scheduleTimes`, `administrationTimeIds`, `startDate`, `endDate`, " +
                "`bottleExpiration`, `pillCountEntered`, `isException`, `qrCode`, `requireQrScan`, " +
                "`isGroupAdministered`, `prepackagedGroupId`, `purpose`, `isArchived`) " +
                "SELECT `id`, `patientId`, `ndc`, `name`, `strength`, `form`, `dosageInstructions`, " +
                "`scheduleTimes`, '', `startDate`, `endDate`, `bottleExpiration`, `pillCountEntered`, " +
                "`isException`, `qrCode`, 1, 0, NULL, `purpose`, `isArchived` FROM `medications`"
        )
        db.execSQL("DROP TABLE `medications`")
        db.execSQL("ALTER TABLE `medications_new` RENAME TO `medications`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_medications_patientId` ON `medications` (`patientId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_medications_qrCode` ON `medications` (`qrCode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_medications_prepackagedGroupId` ON `medications` (`prepackagedGroupId`)")

        // ---------- administration_times ----------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `administration_times` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`time` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_administration_times_name` ON `administration_times` (`name`)"
        )
    }
}

/**
 * Adds the optional group name to medications. Nothing is rebuilt and no row is changed: existing groups
 * simply start unnamed (null) and keep working exactly as before.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `medications` ADD COLUMN `prepackagedGroupName` TEXT")
    }
}

/**
 * v4 -> v5. Adds the spreadsheet import history tables. Purely additive: no existing table, row or key is
 * touched. The SQL matches what Room generates from ImportRecords.kt exactly (Room validates the schema on open).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_history` (`id` TEXT NOT NULL, `createdAt` TEXT NOT NULL, " +
                "`sourceFileName` TEXT NOT NULL, `mode` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                "`backupFileName` TEXT, `newPatients` INTEGER NOT NULL, `newMedications` INTEGER NOT NULL, " +
                "`patientsUpdated` INTEGER NOT NULL, `medicationsUpdated` INTEGER NOT NULL, " +
                "`fieldsChanged` INTEGER NOT NULL, `rowsSkipped` INTEGER NOT NULL, `message` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `change_history` (`id` TEXT NOT NULL, `importId` TEXT NOT NULL, " +
                "`changedAt` TEXT NOT NULL, `actorUserId` TEXT, `recordType` TEXT NOT NULL, " +
                "`recordId` TEXT NOT NULL, `field` TEXT NOT NULL, `oldValue` TEXT, `newValue` TEXT, " +
                "`significant` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_history_recordId` ON `change_history` (`recordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_history_importId` ON `change_history` (`importId`)")
    }
}

/** Additive migration: no history rows or original schedule/end dates are changed. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("startDate", "endDate", "bunk", "counselor", "division").forEach {
            db.execSQL("ALTER TABLE patients ADD COLUMN $it TEXT")
        }
        db.execSQL("ALTER TABLE medications ADD COLUMN isPrn INTEGER NOT NULL DEFAULT 0")
        listOf("createdByUserId", "createdByName", "createdAt", "sheetName", "discontinuedOn").forEach {
            db.execSQL("ALTER TABLE medications ADD COLUMN $it TEXT")
        }
        db.execSQL("ALTER TABLE dose_logs ADD COLUMN heldReason TEXT")
        listOf("recoveryQuestionIds", "recoveryHashes", "recoverySalts").forEach {
            db.execSQL("ALTER TABLE users ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("ALTER TABLE users ADD COLUMN recoveryFailures INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE users ADD COLUMN recoveryLockedUntil INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS camp_settings (id INTEGER NOT NULL PRIMARY KEY, startDates TEXT NOT NULL, endDates TEXT NOT NULL, bunkLabel TEXT NOT NULL, counselorLabel TEXT NOT NULL, divisionLabel TEXT NOT NULL)")

        // Match the previous export ordering, including collision suffixes, and freeze those sheet names.
        data class LegacySheet(val id: String, val patient: String, val med: String, val start: String)
        val rows = mutableListOf<LegacySheet>()
        db.query("SELECT m.id, p.name, m.name, m.startDate FROM medications m LEFT JOIN patients p ON p.id=m.patientId ORDER BY m.name ASC").use { c ->
            while (c.moveToNext()) rows += LegacySheet(c.getString(0), c.getString(1).orEmpty(), c.getString(2), c.getString(3))
        }
        val used = hashSetOf<String>()
        rows.sortedWith(compareBy({ it.patient.lowercase() }, { it.med.lowercase() }, { it.start })).forEach {
            val name = com.campmeds.app.export.SheetNames.unique(com.campmeds.app.export.SheetNames.baseName(it.patient, it.med), used)
            db.execSQL("UPDATE medications SET sheetName=? WHERE id=?", arrayOf(name, it.id))
        }
        // Creator and recovery answers cannot be reconstructed. Leave them unknown rather than inventing them.
    }
}
