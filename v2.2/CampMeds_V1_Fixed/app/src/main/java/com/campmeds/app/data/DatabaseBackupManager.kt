package com.campmeds.app.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recovery copy taken before every bulk import. The database file is copied as-is, so the copy stays encrypted
 * with the same SQLCipher key (it is useless without the device's Keystore-protected key, exactly like the live
 * database). The WAL is checkpointed first so the single file contains every committed change, and the copy is
 * verified byte-for-byte (length and SHA-256) before the import is allowed to start. The newest [KEEP] copies are kept.
 *
 * Restoring is a manual, administrator-level action (replace campmeds_encrypted.db with a verified copy while the
 * app is closed); there is no in-app restore.
 */
class DatabaseBackupManager(private val context: Context) {

    companion object {
        const val KEEP = 5
        private const val PREFIX = "before-import-"
    }

    private fun backupDir(): File = File(context.filesDir, "import_backups").apply { mkdirs() }

    fun createVerifiedBackup(db: AppDatabase): File {
        val support = db.openHelper.writableDatabase
        support.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst()) { "Could not checkpoint the database before the import." }
            check(cursor.getInt(0) == 0) { "The database is busy. Try the import again in a moment." }
        }
        val source = File(requireNotNull(support.path) { "Database location is unknown." })
        check(source.isFile) { "Database file not found." }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val target = File(backupDir(), "$PREFIX$stamp.db")
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }

        check(target.length() == source.length() && sha256(target) == sha256(source)) {
            "The recovery copy could not be verified, so the import was not started."
        }
        prune()
        return target
    }

    private fun prune() {
        backupDir().listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

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
