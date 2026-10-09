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
