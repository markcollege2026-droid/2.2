package com.campmeds.app.data

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Why a backup was taken. Each reason keeps its own retention list, so a burst of imports can never
 * push out the manual backup an administrator made on purpose.
 */
enum class BackupReason(val prefix: String, val keep: Int, val label: String) {
    IMPORT("before-import-", 5, "Taken automatically before an import"),
    MANUAL("manual-", 10, "Manual backup"),
    PRE_RESTORE("pre-restore-", 2, "Taken automatically before a restore");

    companion object {
        /** Longest prefix first, so "pre-restore-" can never be mistaken for another kind. */
        fun ofFile(name: String): BackupReason? =
            BackupReason.values().sortedByDescending { it.prefix.length }.firstOrNull { name.startsWith(it.prefix) }
    }
}

/**
 * Metadata written next to a backup, so a file that looks complete can be proven complete.
 *
 * The manifest records the schema version the backup was taken from, the app version that took it, its size
 * and SHA-256, and the row count of every table this build knows about (see
 * [DatabaseBackupManager.ALL_TABLES]). It is always written *after* the database copy has been verified and
 * published. A .db file without a matching manifest is treated as a legacy copy (created by an older build of
 * the app): it can still be inspected and restored, but it must pass [DatabaseBackupManager.validate] first.
 */
data class BackupManifest(
    val schemaVersion: Int,
    val appVersionName: String,
    val createdAt: String,
    val sha256: String,
    val sizeBytes: Long,
    val tableCounts: Map<String, Int>
) {
    fun toJson(): String = JSONObject().apply {
        put("format", FORMAT)
        put("schemaVersion", schemaVersion)
        put("appVersionName", appVersionName)
        put("createdAt", createdAt)
        put("sha256", sha256)
        put("sizeBytes", sizeBytes)
        put("tableCounts", JSONObject().apply { tableCounts.forEach { (k, v) -> put(k, v) } })
    }.toString(2)

    fun createdLocal(): LocalDateTime =
        runCatching { LocalDateTime.ofInstant(Instant.parse(createdAt), ZoneId.systemDefault()) }
            .getOrElse { LocalDateTime.now() }

    companion object {
        const val FORMAT = 1

        fun parse(text: String): BackupManifest? = runCatching {
            val json = JSONObject(text)
            if (json.optInt("format", 0) != FORMAT) return null
            val countsJson = json.optJSONObject("tableCounts") ?: JSONObject()
            val counts = buildMap {
                countsJson.keys().forEach { key -> put(key, countsJson.getInt(key)) }
            }
            BackupManifest(
                schemaVersion = json.getInt("schemaVersion"),
                appVersionName = json.optString("appVersionName", ""),
                createdAt = json.getString("createdAt"),
                sha256 = json.getString("sha256"),
                sizeBytes = json.getLong("sizeBytes"),
                tableCounts = counts
            )
        }.getOrNull()
    }
}

/** One backup file on disk, with its manifest when it has one. */
data class BackupFile(val file: File, val reason: BackupReason, val manifest: BackupManifest?) {
    val name: String get() = file.name
    val legacy: Boolean get() = manifest == null
    val sizeBytes: Long get() = manifest?.sizeBytes ?: file.length()
    val schemaVersion: Int? get() = manifest?.schemaVersion
    val createdAt: LocalDateTime
        get() = manifest?.createdLocal()
            ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault())
}

/** Result of inspecting a backup file. A backup is only usable when this is [Valid]. */
sealed class BackupValidation {
    /**
     * [schemaVersion] is the version recorded by the backup itself. [needsMigration] is true when it is older
     * than [SCHEMA_VERSION]; the restore path then relies on the app's existing data-preserving migrations,
     * which run the first time Room opens the restored file.
     */
    data class Valid(
        val schemaVersion: Int,
        val tableCounts: Map<String, Int>,
        val legacy: Boolean
    ) : BackupValidation() {
        val needsMigration: Boolean get() = schemaVersion < SCHEMA_VERSION
    }

    data class Invalid(val message: String) : BackupValidation()
}

/**
 * Creates, inspects and prunes recovery copies of the encrypted database.
 *
 * Guarantees:
 *  - A copy is written to a `.part` file, verified (byte length, SHA-256, and that it actually opens with the
 *    device key and returns valid row counts), and only then renamed to its final name. A half-written copy
 *    can therefore never be mistaken for a good one.
 *  - The manifest is written last and atomically. Until it exists, the .db file is "legacy", which [validate]
 *    treats as *not* manifest-verified but still inspects fully before any restore.
 *  - The database file is copied as-is, so it stays SQLCipher-encrypted with the same key as the live
 *    database. That is what keeps the backup unreadable without the device's Keystore-backed key — and also
 *    means a backup is only restorable on the install that created it (see [DatabaseRestoreManager]).
 *  - Nothing outside `filesDir/import_backups` is touched, and temporary files are removed on every path
 *    (success and failure).
 */
class DatabaseBackupManager(
    private val context: Context,
    /** Test hook: use a private directory so backup tests cannot disturb real recovery copies. */
    private val directory: File? = null
) {

    companion object {
        const val DIR_NAME = "import_backups"
        const val DB_SUFFIX = ".db"
        const val PART_SUFFIX = ".part"
        const val MANIFEST_SUFFIX = ".json"

        /** Tables that have existed since v1: a backup without these is incomplete, whatever its version. */
        val CORE_TABLES = listOf("patients", "medications", "dose_logs", "users")

        /**
         * Tables that were added by migration 1 -> 5: every backup made by a schema-5 build or later has them.
         */
        val V5_TABLES = CORE_TABLES + listOf("administration_times", "import_history", "change_history")

        /** Tables added by migration 6 -> 7 (camp sessions, divisions, bunk directory). */
        val V7_TABLES = listOf("camp_settings", "camp_sessions", "divisions", "bunk_options")

        /**
         * Every table this build knows about (schema 7). New backups record a row count for each of them, and a
         * backup must contain all of them before it is restored into this build.
         */
        val ALL_TABLES = V5_TABLES + V7_TABLES

        /**
         * The tables a backup of the given schema version must contain. Older backups are held to the tables
         * that existed when they were made, never to tables their schema predates.
         */
        fun requiredTablesFor(schemaVersion: Int): List<String> =
            if (schemaVersion >= 7) ALL_TABLES else V5_TABLES

        /** Kept for compatibility with the previous manager's public constant. */
        const val KEEP = 5
    }

    /**
     * The same passphrase Room opens the live database with. SQLCipher's char[] entry point feeds exactly the
     * same passphrase bytes into the key derivation as `SupportFactory(SQLiteDatabase.getBytes(passphrase))`,
     * which is what AppDatabase uses, so a copy opened here really is decryptable with the device key.
     *
     * A fresh array is read for every open: SQLCipher is allowed to wipe the array it is handed, and a cached,
     * wiped key would silently make every later backup look unreadable.
     */
    private fun databasePassphrase(): CharArray {
        SQLiteDatabase.loadLibs(context)
        return DatabaseKeyProvider.getOrCreatePassphrase(context)
    }

    fun backupDir(): File = (directory ?: File(context.filesDir, DIR_NAME)).apply { mkdirs() }

    fun manifestFile(backup: File): File = File(backup.parentFile, backup.name + MANIFEST_SUFFIX)

    // ------------------------------------------------------------------ create

    /**
     * Copies the live database, verifies the copy, publishes it and writes its manifest.
     *
     * Throws if the copy cannot be proven good; in that case no backup is published and the import or
     * restore that asked for it must not proceed.
     */
    fun createVerifiedBackup(
        db: AppDatabase,
        reason: BackupReason = BackupReason.IMPORT,
        appVersionName: String = appVersionName()
    ): File {
        checkpoint(db)
        val support = db.openHelper.writableDatabase
        val source = File(requireNotNull(support.path) { "Database location is unknown." })
        check(source.isFile) { "The database file was not found, so no backup could be made." }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        var target = File(backupDir(), "${reason.prefix}$stamp$DB_SUFFIX")
        var collision = 2
        while (target.exists() || manifestFile(target).exists()) {
            target = File(backupDir(), "${reason.prefix}$stamp-$collision$DB_SUFFIX")
            collision++
        }
        val partial = File(backupDir(), target.name + PART_SUFFIX)
        partial.delete()

        try {
            source.inputStream().use { input ->
                FileOutputStream(partial).use { out ->
                    input.copyTo(out)
                    out.flush()
                    out.fd.sync() // the bytes must be on disk before the file is renamed/published
                }
            }
            check(partial.length() == source.length() && partial.length() > 0) {
                "The backup copy is incomplete, so it was discarded."
            }
            val digest = sha256(partial)

            // Byte equality is not enough: the copy has to open with the device key and contain its rows.
            val copy = readBackup(partial)
                ?: error("The backup copy could not be opened, so it was discarded.")
            check(copy.schemaVersion > 0) { "The backup copy is not a CampMeds database, so it was discarded." }
            check(copy.schemaVersion <= SCHEMA_VERSION) {
                "The backup copy reports schema ${copy.schemaVersion}, which this build cannot restore."
            }
            val missing = requiredTablesFor(copy.schemaVersion).filter { it !in copy.presentTables }
            check(missing.isEmpty()) { "The backup copy is missing ${missing.joinToString(", ")}." }

            check(partial.renameTo(target)) { "The backup copy could not be published." }

            val manifest = BackupManifest(
                // Recorded from the copy itself, so the manifest always states the schema the bytes hold.
                schemaVersion = copy.schemaVersion,
                appVersionName = appVersionName,
                createdAt = Instant.now().toString(),
                sha256 = digest,
                sizeBytes = target.length(),
                tableCounts = copy.counts
            )
            writeManifest(target, manifest)
            prune(reason)
            cleanupTemporaryFiles()
            return target
        } catch (t: Throwable) {
            partial.delete()
            target.delete()
            manifestFile(target).delete()
            throw t
        }
    }

    /**
     * Flushes the write-ahead log so the single .db file contains every committed change.
     *
     * When the database is not in WAL mode the pragma reports nothing to flush, which is fine: there is no
     * separate log to lose. A non-zero result means another connection is still writing, and copying the file
     * then would risk a torn backup, so that is turned into an error the caller must show.
     */
    fun checkpoint(db: AppDatabase) {
        val support = db.openHelper.writableDatabase
        val busy = support.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        check(busy == 0) { "The database is busy, so no backup was made. Try again in a moment." }
    }

    // ------------------------------------------------------------------ inspect

    /** Every backup in the folder, newest first. Unreadable manifests simply leave the entry legacy. */
    fun listBackups(): List<BackupFile> =
        backupDir().listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(DB_SUFFIX) }
            .mapNotNull { file ->
                val reason = BackupReason.ofFile(file.name) ?: return@mapNotNull null
                BackupFile(file, reason, readManifest(file))
            }
            .sortedByDescending { it.file.name }

    /**
     * Full validation of a backup file. This never modifies the file and never touches the live database.
     *
     * The checks are deliberately cumulative: identity and integrity first, then compatibility, then
     * completeness (including the recorded row counts), so the message the administrator sees is the most
     * specific one that applies.
     */
    fun validate(file: File): BackupValidation {
        if (!file.isFile) return BackupValidation.Invalid("That backup file is no longer there.")
        if (file.length() <= 0L) return BackupValidation.Invalid("That backup file is empty and cannot be restored.")

        val contents = readBackup(file)
            ?: return BackupValidation.Invalid(
                "This file is not a readable CampMeds backup. It may be damaged, or it was created on " +
                    "another device (a backup is encrypted with the key of the install that made it)."
            )

        if (!contents.integrityOk) {
            return BackupValidation.Invalid("The backup failed its own integrity check, so it was not used.")
        }
        if (contents.schemaVersion <= 0) {
            return BackupValidation.Invalid("This file is not a CampMeds database.")
        }
        if (contents.schemaVersion > SCHEMA_VERSION) {
            return BackupValidation.Invalid(
                "This backup was created by a newer version of CampMeds (schema ${contents.schemaVersion}). " +
                    "Update the app before restoring it. Nothing was changed."
            )
        }
        val missingCore = CORE_TABLES.filter { it !in contents.presentTables }
        if (missingCore.isNotEmpty()) {
            return BackupValidation.Invalid(
                "The backup is incomplete: it has no ${missingCore.joinToString(", ")} table. Nothing was changed."
            )
        }

        val manifest = readManifest(file)

        // A backup from an older schema is restorable only when it still carries every table that version
        // had. Missing tables would mean rows silently disappearing, so the file is refused instead.
        val expected = requiredTablesFor(
            // Trust whichever version is lower: a manifest can never make an old file look newer.
            minOf(contents.schemaVersion, manifest?.schemaVersion ?: contents.schemaVersion)
        )
        val missing = expected.filter { it !in contents.presentTables }
        if (missing.isNotEmpty()) {
            return BackupValidation.Invalid(
                "The backup is incomplete for schema ${contents.schemaVersion}: it has no " +
                    "${missing.joinToString(", ")} table. Nothing was changed."
            )
        }

        if (manifest != null) {
            if (manifest.schemaVersion > SCHEMA_VERSION) {
                return BackupValidation.Invalid(
                    "The backup manifest says it was created by a newer version of CampMeds " +
                        "(schema ${manifest.schemaVersion}). Update the app before restoring it. Nothing was changed."
                )
            }
            if (!manifest.sha256.equals(sha256(file), ignoreCase = true)) {
                return BackupValidation.Invalid(
                    "The backup does not match the checksum recorded when it was made, so it has changed or is " +
                        "damaged. Nothing was changed."
                )
            }
            val mismatches = manifest.tableCounts.filter { (table, count) -> contents.counts[table] != count }
            if (mismatches.isNotEmpty()) {
                return BackupValidation.Invalid(
                    "The backup is incomplete: its record counts do not match the manifest " +
                        "(${mismatches.keys.joinToString(", ")}). Nothing was changed."
                )
            }
        }

        return BackupValidation.Valid(contents.schemaVersion, contents.counts, legacy = manifest == null)
    }

    /** Row counts of a live (already opened) database, used to verify a restore afterwards. */
    fun tableCounts(db: AppDatabase): Map<String, Int> {
        val support = db.openHelper.writableDatabase
        return ALL_TABLES.mapNotNull { table ->
            runCatching {
                support.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    if (cursor.moveToFirst()) table to cursor.getInt(0) else table to 0
                }
            }.getOrNull()
        }.toMap()
    }

    /** "ok" when SQLite is happy with the file; otherwise the first problem it reports. */
    fun integrityReport(db: AppDatabase): String =
        db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
        }

    /** Broken foreign keys (should be empty); returned as readable table pairs, never row contents. */
    fun foreignKeyProblems(db: AppDatabase): List<String> =
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            val problems = mutableListOf<String>()
            while (cursor.moveToNext()) {
                problems += "${cursor.getString(0)} -> ${cursor.getString(2)}"
            }
            problems
        }

    /** Deletes a backup and its manifest. */
    fun delete(file: File) {
        manifestFile(file).delete()
        file.delete()
    }

    // ------------------------------------------------------------------ internals

    private class BackupContents(
        val schemaVersion: Int,
        val integrityOk: Boolean,
        val presentTables: Set<String>,
        val counts: Map<String, Int>
    )

    /**
     * Opens a database file read-only with the device key and reads what a restore would rely on.
     * Returns null when the file cannot be opened at all (wrong key, truncated, not SQLite).
     */
    private fun readBackup(file: File): BackupContents? {
        val handle = openForReading(file) ?: return null
        return try {
            val integrity = handle.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
            val version = handle.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val tables = mutableSetOf<String>()
            handle.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { tables += it }
            }
            val counts = ALL_TABLES.mapNotNull { table ->
                if (table !in tables) null
                else handle.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    if (cursor.moveToFirst()) table to cursor.getInt(0) else table to 0
                }
            }.toMap()
            BackupContents(version, integrity.equals("ok", ignoreCase = true), tables, counts)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { handle.close() }
            // Opening a WAL-mode file creates -wal/-shm siblings, also for read-only connections. They are
            // empty and belong to the inspection, not to the backup, so they must not be left behind.
            removeEmptySidecars(file)
        }
    }

    /**
     * Opens a backup for inspection. Read-only first, because inspecting a backup must not modify it; a
     * read-write open is only a fallback for the case where SQLite cannot create the sidecar files read-only.
     * No statement that writes is ever executed against the handle.
     */
    private fun openForReading(file: File): SQLiteDatabase? {
        val readOnly = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, databasePassphrase(), null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()
        if (readOnly != null) return readOnly
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, databasePassphrase(), null, SQLiteDatabase.OPEN_READWRITE)
        }.getOrNull()
    }

    private fun removeEmptySidecars(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(file.absolutePath + suffix)
            if (sidecar.isFile && sidecar.length() == 0L) sidecar.delete()
        }
    }

    private fun readManifest(backup: File): BackupManifest? {
        val file = manifestFile(backup)
        if (!file.isFile) return null
        return runCatching { BackupManifest.parse(file.readText()) }.getOrNull()
    }

    private fun writeManifest(backup: File, manifest: BackupManifest) {
        val target = manifestFile(backup)
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        FileOutputStream(partial).use { out ->
            out.write(manifest.toJson().toByteArray())
            out.flush()
            out.fd.sync()
        }
        check(partial.renameTo(target)) { "The backup manifest could not be written." }
    }

    /** Keeps the newest [BackupReason.keep] copies for this reason; older ones are deleted with their manifest. */
    private fun prune(reason: BackupReason) {
        backupDir().listFiles { f -> f.isFile && f.name.startsWith(reason.prefix) && f.name.endsWith(DB_SUFFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(reason.keep)
            ?.forEach { delete(it) }
    }

    /** Removes half-written copies and manifests whose database file is gone. */
    private fun cleanupTemporaryFiles() {
        backupDir().listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(PART_SUFFIX) -> file.delete()
                file.length() == 0L && (file.name.endsWith("-wal") || file.name.endsWith("-shm") ||
                    file.name.endsWith("-journal")) -> file.delete()
                file.name.endsWith(MANIFEST_SUFFIX) -> {
                    val db = File(file.parentFile, file.name.removeSuffix(MANIFEST_SUFFIX))
                    if (!db.isFile) file.delete()
                }
            }
        }
    }

    private fun appVersionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
