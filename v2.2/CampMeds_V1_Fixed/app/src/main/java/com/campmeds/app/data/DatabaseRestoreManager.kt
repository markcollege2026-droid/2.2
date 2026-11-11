package com.campmeds.app.data

import android.content.Context
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app restore of a verified backup, safe by construction.
 *
 * Order of operations:
 *  1. **Validate** the chosen backup without touching anything (checksum, SQLCipher open, integrity check,
 *     schema compatibility, required tables, recorded row counts).
 *  2. **Stage** a fresh copy inside the databases directory, so the rename below is atomic (same filesystem)
 *     and the install never points at a file that is still being written.
 *  3. **Safety copy** the live database first, so the current data exists twice before anything is replaced.
 *  4. **Swap** with renames only: live -> `.restore-rollback`, staging -> live.
 *  5. **Verify** the restored database (integrity check, foreign keys, row counts against the manifest).
 *  6. On any failure after step 4, put the rollback file back and reopen it. The administrator is told the
 *     original data is unchanged, and it genuinely is — the swap is a rename, not a merge.
 *
 * A crash between the two renames is repaired on the next launch by [recoverInterruptedRestore], which
 * [AppDatabase] runs before Room opens the file.
 *
 * **Encryption key and portability.** Backups are byte copies of the SQLCipher file, so they are encrypted
 * with the passphrase that [DatabaseKeyProvider] stores in this install's Android Keystore-backed
 * EncryptedSharedPreferences. The passphrase is never exported, so a backup can only be opened and restored on
 * the install that created it: on another device (or after an uninstall, which removes the Keystore entry) the
 * file cannot be decrypted and [DatabaseBackupManager.validate] refuses it. That is deliberate — there is no
 * key export in this version, and none is implied here.
 *
 * **Schema versions.** A backup from an older schema (for example schema 5, which the older backup/restore
 * build wrote) is accepted when validation passes; the app's own data-preserving migrations then upgrade the
 * restored file the first time Room opens it. A backup from a *newer* schema is refused outright, never
 * "migrated down". Nothing here ever uses a destructive fallback.
 */
class DatabaseRestoreManager(
    private val context: Context,
    private val backups: DatabaseBackupManager,
    /** The live database file. Overridable so tests can use their own file. */
    private val databaseFile: File = AppDatabase.databaseFile(context),
    /** How to close/open the database around the swap. Overridable for tests. */
    private val closeDatabase: () -> Unit = { AppDatabase.closeInstance() },
    private val openDatabase: () -> AppDatabase = { AppDatabase.getInstance(context) }
) {

    sealed class RestoreResult {
        /** The backup was restored and verified. */
        data class Success(
            val restoredFrom: String,
            val safetyBackupName: String?,
            val schemaVersion: Int,
            val tableCounts: Map<String, Int>,
            /** True when the restored file came from an older schema and will be upgraded on next open. */
            val migratedFrom: Int? = null
        ) : RestoreResult()

        /** Nothing was changed: the backup was refused before any swap. */
        data class Rejected(val message: String) : RestoreResult()

        /** The swap started and was rolled back. The original data is intact. */
        data class Failed(val message: String) : RestoreResult()
    }

    companion object {
        const val STAGING_SUFFIX = ".restore-staging"
        const val ROLLBACK_SUFFIX = ".restore-rollback"

        fun stagingFileFor(databaseFile: File) = File(databaseFile.parentFile, databaseFile.name + STAGING_SUFFIX)
        fun rollbackFileFor(databaseFile: File) = File(databaseFile.parentFile, databaseFile.name + ROLLBACK_SUFFIX)

        /** Removes the sidecar files that belong to a database file we are replacing. */
        fun deleteSidecars(databaseFile: File) {
            File(databaseFile.absolutePath + "-wal").delete()
            File(databaseFile.absolutePath + "-shm").delete()
            File(databaseFile.absolutePath + "-journal").delete()
        }

        /**
         * Repairs a restore that was interrupted between "move the live file aside" and "move the new file
         * in". Returns true when the rollback copy had to be put back.
         *
         * Safe to call on every start: it only acts when the live file is missing.
         */
        fun recoverInterruptedRestore(context: Context): Boolean =
            recoverInterruptedRestore(AppDatabase.databaseFile(context))

        /** Same repair, for an explicitly named database file (used by the tests). */
        fun recoverInterruptedRestore(live: File): Boolean {
            val parent = live.parentFile ?: return false
            if (!parent.isDirectory) return false
            val staging = stagingFileFor(live)
            val rollback = rollbackFileFor(live)

            var recovered = false
            if (!live.isFile && rollback.isFile) {
                deleteSidecars(live)
                recovered = rollback.renameTo(live)
            }
            // The live database exists again: neither a rollback copy nor a staging file may linger,
            // because a later restore must never start from a stale one.
            if (live.isFile) {
                rollback.delete()
                staging.delete()
            }
            return recovered
        }
    }

    /** Creates a manual backup on demand (administrator action in Settings -> Backups). */
    suspend fun createManualBackup(): File = withContext(Dispatchers.IO) {
        backups.createVerifiedBackup(openDatabase(), BackupReason.MANUAL)
    }

    /**
     * Restores [backup] over the live database. Administrator only.
     *
     * Blocks the calling coroutine on the IO dispatcher on purpose: the database is closed and replaced
     * while this runs, so nothing else may read it concurrently.
     */
    suspend fun restore(backup: File, actor: User?): RestoreResult = withContext(Dispatchers.IO) {
        if (actor == null || actor.role != Role.ADMIN) {
            return@withContext RestoreResult.Rejected("Only an Administrator can restore a backup.")
        }

        val parent = databaseFile.parentFile
            ?: return@withContext RestoreResult.Rejected("The database folder could not be found.")
        parent.mkdirs()

        val staging = stagingFileFor(databaseFile)
        val rollback = rollbackFileFor(databaseFile)
        staging.delete()

        // 1. Validate the source, before anything is copied or closed.
        val source = backups.validate(backup)
        if (source is BackupValidation.Invalid) {
            return@withContext RestoreResult.Rejected(source.message)
        }
        val valid = source as BackupValidation.Valid

        // 2. Staged copy inside the databases directory: the later rename must be atomic.
        val staged = runCatching {
            backup.inputStream().use { input ->
                java.io.FileOutputStream(staging).use { out ->
                    input.copyTo(out)
                    out.flush()
                    out.fd.sync()
                }
            }
        }
        if (staged.isFailure) {
            staging.delete()
            return@withContext RestoreResult.Rejected(
                "The backup could not be copied, so nothing was changed. " +
                    staged.exceptionOrNull()?.message.orEmpty()
            )
        }

        // The copy must pass exactly the same validation as the original: a truncated copy is caught here,
        // while the live database is still untouched.
        val stagedValidation = backups.validate(staging)
        if (stagedValidation is BackupValidation.Invalid) {
            staging.delete()
            return@withContext RestoreResult.Rejected(
                "The backup could not be copied completely (${stagedValidation.message}). Nothing was changed."
            )
        }

        // 3. Safety copy of what is live right now, so a bad restore is always reversible.
        val safety = runCatching {
            // createVerifiedBackup checkpoints the WAL, copies, verifies and publishes the copy itself.
            backups.createVerifiedBackup(openDatabase(), BackupReason.PRE_RESTORE)
        }.getOrElse { error ->
            staging.delete()
            return@withContext RestoreResult.Rejected(
                "A safety copy of the current data could not be made, so the restore was not started. " +
                    (error.message ?: "")
            )
        }

        // 4. Swap by rename only.
        val hadLiveDatabase = databaseFile.isFile
        val closed = runCatching { closeDatabase() }
        if (closed.isFailure) {
            staging.delete()
            runCatching { openDatabase() }
            return@withContext RestoreResult.Failed(
                "The database could not be closed, so nothing was changed. " +
                    (closed.exceptionOrNull()?.message ?: "")
            )
        }

        deleteSidecars(databaseFile)
        rollback.delete()
        if (hadLiveDatabase && !databaseFile.renameTo(rollback)) {
            staging.delete()
            runCatching { openDatabase() }
            return@withContext RestoreResult.Failed("The current database could not be set aside. Nothing was changed.")
        }
        if (!staging.renameTo(databaseFile)) {
            if (hadLiveDatabase) rollback.renameTo(databaseFile)
            staging.delete()
            runCatching { openDatabase() }
            return@withContext RestoreResult.Failed("The backup could not be put in place. Nothing was changed.")
        }

        // 5. Verify, and roll back if anything is off.
        val verification = runCatching {
            val db = openDatabase()
            val integrity = backups.integrityReport(db)
            check(integrity.equals("ok", ignoreCase = true)) { "integrity check reported: $integrity" }
            val broken = backups.foreignKeyProblems(db)
            check(broken.isEmpty()) { "broken references: ${broken.take(3).joinToString(", ")}" }
            val counts = backups.tableCounts(db)
            // Only compare tables the backup actually recorded row counts for (an older schema has fewer).
            val mismatches = valid.tableCounts.filter { (table, expected) -> counts[table] != expected }
            check(mismatches.isEmpty()) { "row counts differ after restore: ${mismatches.keys.joinToString(", ")}" }
            counts
        }

        if (verification.isFailure) {
            val reason = verification.exceptionOrNull()?.message.orEmpty()
            runCatching { closeDatabase() }
            databaseFile.delete()
            deleteSidecars(databaseFile)
            val restored = if (hadLiveDatabase) rollback.renameTo(databaseFile) else true
            runCatching { openDatabase() }
            val suffix = if (restored) {
                "Your original data is unchanged."
            } else {
                "The original file is kept as ${rollback.name}; it was not possible to put it back automatically."
            }
            return@withContext RestoreResult.Failed("The restored database failed verification ($reason). $suffix")
        }

        // 6. Success: the rollback copy is no longer needed (the pre-restore backup remains as an undo point).
        rollback.delete()
        staging.delete()
        RestoreResult.Success(
            restoredFrom = backup.name,
            safetyBackupName = safety.name,
            schemaVersion = valid.schemaVersion,
            tableCounts = verification.getOrThrow(),
            migratedFrom = if (valid.needsMigration) valid.schemaVersion else null
        )
    }
}
