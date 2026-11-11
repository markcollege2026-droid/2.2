package com.campmeds.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.BackupReason
import com.campmeds.app.data.BackupValidation
import com.campmeds.app.data.DatabaseBackupManager
import com.campmeds.app.data.DatabaseRestoreManager
import com.campmeds.app.data.SCHEMA_VERSION
import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.BunkOption
import com.campmeds.app.data.entity.CampSession
import com.campmeds.app.data.entity.Division
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Backup and restore against a real SQLCipher-encrypted database file (not in-memory: a backup is a file copy,
 * so it has to be tested on a file).
 *
 * The test database is a separate file opened through [AppDatabase.buildForTesting]; the restore manager is
 * pointed at that file, so the live app database is never touched.
 *
 * The data seeded here is deliberately schema-7 data: a PRN medication, a scheduled medication, an
 * administration history with a held PRN record, and the camp directory/session rows added by migration 6 -> 7.
 * A backup and its restore must preserve all of it.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "backup-restore-test"
    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var backups: DatabaseBackupManager
    private lateinit var backupDir: File
    private lateinit var admin: User
    private lateinit var patient: Patient
    private lateinit var scheduled: Medication
    private lateinit var prn: Medication

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        deleteDatabaseFiles()
        backupDir = File(context.cacheDir, "backup-test-${System.nanoTime()}").apply { mkdirs() }
        backups = DatabaseBackupManager(context, backupDir)
        db = openDb()
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        deleteDatabaseFiles()
        backupDir.deleteRecursively()
    }

    private fun openDb(): AppDatabase = AppDatabase.buildForTesting(context, dbName)

    private fun deleteDatabaseFiles() {
        dbFile.delete()
        DatabaseRestoreManager.deleteSidecars(dbFile)
        DatabaseRestoreManager.stagingFileFor(dbFile).delete()
        DatabaseRestoreManager.rollbackFileFor(dbFile).delete()
    }

    private suspend fun seed() {
        admin = User(name = "Admin", role = Role.ADMIN, pinHash = "h", pinSalt = "s")
        patient = Patient(
            name = "Ann Lee",
            firstName = "Ann",
            lastName = "Lee",
            notes = "penicillin",
            bunk = "Bunk 4",
            counselor = "Dana",
            division = "Machaneh Aleph",
            bunks = listOf("Bunk 4", "Bunk 5"),
            counselors = listOf("Dana", "Eli")
        )
        scheduled = Medication(
            patientId = patient.id,
            ndc = "0069-2587",
            name = "Metformin",
            strength = "500 mg",
            form = "TABLET",
            dosageInstructions = "1 tablet",
            scheduleTimes = listOf(LocalTime.of(8, 0)),
            startDate = LocalDate.of(2026, 7, 1),
            qrCode = "qr-1"
        )
        prn = Medication(
            patientId = patient.id,
            ndc = "0000-0001",
            name = "Ibuprofen",
            strength = "200 mg",
            form = "TABLET",
            dosageInstructions = "As needed",
            scheduleTimes = emptyList(),
            startDate = LocalDate.of(2026, 7, 1),
            isPrn = true,
            qrCode = "qr-prn"
        )
        db.userDao().upsert(admin)
        db.patientDao().upsert(patient)
        db.medicationDao().upsert(scheduled)
        db.medicationDao().upsert(prn)
        db.administrationTimeDao().upsert(AdministrationTime(name = "Breakfast", time = LocalTime.of(8, 0)))
        db.campDirectoryDao().saveSession(
            CampSession(name = "First session", startDate = LocalDate.of(2026, 7, 1), endDate = LocalDate.of(2026, 7, 20))
        )
        db.campDirectoryDao().saveDivision(Division(name = "Machaneh Aleph", colorHex = "#2E7D32"))
        db.campDirectoryDao().saveBunk(BunkOption(name = "Bunk 4", counselors = listOf("Dana", "Eli"), division = "Machaneh Aleph"))
        db.doseLogDao().upsert(
            DoseLog(
                medicationId = scheduled.id,
                scheduledFor = LocalDateTime.of(2026, 7, 2, 8, 0),
                status = DoseStatus.GIVEN,
                loggedAt = LocalDateTime.of(2026, 7, 2, 8, 5),
                loggedByUserId = admin.id
            )
        )
        // A PRN administration: recorded against the moment it was given, with a held follow-up.
        db.doseLogDao().upsert(
            DoseLog(
                medicationId = prn.id,
                scheduledFor = LocalDateTime.of(2026, 7, 2, 13, 20),
                status = DoseStatus.GIVEN,
                loggedAt = LocalDateTime.of(2026, 7, 2, 13, 20),
                loggedByUserId = admin.id,
                isPrn = true
            )
        )
        db.doseLogDao().upsert(
            DoseLog(
                medicationId = prn.id,
                scheduledFor = LocalDateTime.of(2026, 7, 2, 17, 45),
                status = DoseStatus.HELD,
                loggedAt = LocalDateTime.of(2026, 7, 2, 17, 45),
                loggedByUserId = admin.id,
                heldReason = "Camper asleep",
                isPrn = true
            )
        )
    }

    /** The restore manager wired to the test database file. */
    private fun restoreManager(): DatabaseRestoreManager = DatabaseRestoreManager(
        context = context,
        backups = backups,
        databaseFile = dbFile,
        closeDatabase = { db.close() },
        openDatabase = {
            // Production has a single Room instance, so the manager gets that instance back while it is open
            // and a fresh one only after a swap closed it.
            if (!db.isOpen) db = openDb()
            db
        }
    )

    // ------------------------------------------------------------------ backup

    @Test
    fun backupIsCompleteManifestedAndStillEncrypted() {
        val file = backups.createVerifiedBackup(db, BackupReason.MANUAL)

        assertTrue("the backup file must exist", file.isFile)
        assertTrue("no temporary file may be left behind", backupDir.listFiles().orEmpty().none { it.name.endsWith(".part") })

        val entry = backups.listBackups().single()
        assertEquals(BackupReason.MANUAL, entry.reason)
        assertNotNull(entry.manifest)
        val manifest = entry.manifest!!
        assertEquals("the manifest must record the schema version it was taken from", SCHEMA_VERSION, manifest.schemaVersion)
        assertTrue(
            "every schema-7 table must be recorded",
            manifest.tableCounts.keys.containsAll(DatabaseBackupManager.ALL_TABLES)
        )
        assertEquals(1, manifest.tableCounts["patients"])
        assertEquals(2, manifest.tableCounts["medications"])
        assertEquals(3, manifest.tableCounts["dose_logs"])
        assertEquals(1, manifest.tableCounts["administration_times"])
        assertEquals(1, manifest.tableCounts["camp_sessions"])
        assertEquals(1, manifest.tableCounts["divisions"])
        assertEquals(1, manifest.tableCounts["bunk_options"])
        assertEquals(file.length(), manifest.sizeBytes)

        // The copy is a byte copy of the SQLCipher file, so it must NOT start with a plaintext SQLite header.
        val head = ByteArray(15)
        file.inputStream().use { it.read(head) }
        assertNotEquals("SQLite format 3", String(head))

        assertTrue(
            "a freshly written backup must validate",
            backups.validate(file) is BackupValidation.Valid
        )
    }

    @Test
    fun backupReasonsKeepSeparateRetention() {
        repeat(7) { backups.createVerifiedBackup(db, BackupReason.IMPORT) }
        val manual = backups.createVerifiedBackup(db, BackupReason.MANUAL)

        val all = backups.listBackups()
        assertEquals(
            "import copies are capped at ${BackupReason.IMPORT.keep}",
            BackupReason.IMPORT.keep,
            all.count { it.reason == BackupReason.IMPORT }
        )
        assertTrue("the manual backup must survive an import burst", all.any { it.file == manual })
    }

    // ------------------------------------------------------------------ restore

    @Test
    fun restoreReplacesAllDataAndPassesItsOwnChecks() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)

        // Changes made after the backup must be gone after the restore.
        db.patientDao().upsert(Patient(name = "Later Patient", firstName = "Later", lastName = "Patient"))
        db.medicationDao().upsert(
            Medication(
                patientId = patient.id,
                ndc = "",
                name = "Later Med",
                strength = "",
                form = "TABLET",
                dosageInstructions = "1",
                scheduleTimes = listOf(LocalTime.of(9, 0)),
                startDate = LocalDate.of(2026, 7, 1)
            )
        )
        assertEquals(2, db.patientDao().getAllIncludingArchived().size)

        val result = restoreManager().restore(backup, admin)

        assertTrue("restore should succeed, was: $result", result is DatabaseRestoreManager.RestoreResult.Success)
        result as DatabaseRestoreManager.RestoreResult.Success

        assertEquals("the restored data must be what the backup contained", 1,
            db.patientDao().getAllIncludingArchived().size)
        assertEquals(2, db.medicationDao().getAllIncludingArchived().size)
        assertEquals(3, db.doseLogDao().getAll().size)
        assertEquals("Ann Lee", db.patientDao().getAllIncludingArchived().single().name)
        assertEquals(1, result.tableCounts["patients"])
        assertEquals(SCHEMA_VERSION, result.schemaVersion)
        assertNull("a current-schema backup is not reported as migrated", result.migratedFrom)

        assertEquals("ok", backups.integrityReport(db))
        assertTrue("restored data must not have broken references", backups.foreignKeyProblems(db).isEmpty())

        // The undo point stays available; the working files do not.
        assertTrue(backups.listBackups().any { it.reason == BackupReason.PRE_RESTORE })
        assertFalse(DatabaseRestoreManager.stagingFileFor(dbFile).exists())
        assertFalse(DatabaseRestoreManager.rollbackFileFor(dbFile).exists())
    }

    /**
     * The core data-safety promise: administration history (including PRN doses and held reasons) and the
     * schema-7 camp directory must come back exactly as they were.
     */
    @Test
    fun medicationHistoryAndCampDirectorySurviveABackupAndRestore() = runBlocking {
        val before = run {
            val meds = db.medicationDao().getAllIncludingArchived()
            val logs = db.doseLogDao().getAll().sortedBy { it.scheduledFor }
            Triple(meds, logs, db.patientDao().getAllIncludingArchived().single())
        }
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)

        // Wipe the live data as a second "later change", then restore.
        db.doseLogDao().getAll().forEach { log ->
            db.openHelper.writableDatabase.execSQL("DELETE FROM dose_logs WHERE id = ?", arrayOf(log.id))
        }
        assertEquals(0, db.doseLogDao().getAll().size)

        val result = restoreManager().restore(backup, admin)
        assertTrue("restore should succeed, was: $result", result is DatabaseRestoreManager.RestoreResult.Success)

        val medsAfter = db.medicationDao().getAllIncludingArchived()
        val logsAfter = db.doseLogDao().getAll().sortedBy { it.scheduledFor }
        val patientAfter = db.patientDao().getAllIncludingArchived().single()

        assertEquals("both medications must survive", before.first.map { it.id }.sorted(), medsAfter.map { it.id }.sorted())
        assertEquals(2, medsAfter.size)
        assertTrue("the PRN flag must survive", medsAfter.single { it.id == prn.id }.isPrn)
        assertFalse("the scheduled medication must stay scheduled", medsAfter.single { it.id == scheduled.id }.isPrn)

        assertEquals("every administration record must come back", before.second.size, logsAfter.size)
        assertEquals(before.second.map { it.id }.sorted(), logsAfter.map { it.id }.sorted())
        val heldPrn = logsAfter.single { it.id == before.second.single { log -> log.status == DoseStatus.HELD }.id }
        assertEquals(DoseStatus.HELD, heldPrn.status)
        assertEquals("the held reason is part of history and must survive", "Camper asleep", heldPrn.heldReason)
        assertTrue("PRN records must stay marked as PRN", heldPrn.isPrn)
        assertEquals(
            "administration timestamps must not move",
            before.second.map { it.scheduledFor.toString() },
            logsAfter.map { it.scheduledFor.toString() }
        )

        assertEquals("multiple bunks must survive", listOf("Bunk 4", "Bunk 5"), patientAfter.bunks)
        assertEquals("multiple counselors must survive", listOf("Dana", "Eli"), patientAfter.counselors)
        assertEquals("the division must survive", "Machaneh Aleph", patientAfter.division)

        assertEquals(1, db.campDirectoryDao().allSessions().size)
        assertEquals("First session", db.campDirectoryDao().allSessions().single().name)
        assertEquals("#2E7D32", db.campDirectoryDao().divisions().first().single().colorHex)
        assertEquals(1, db.campDirectoryDao().allBunks().size)
    }

    @Test
    fun damagedBackupIsRejectedAndChangesNothing() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)
        // Flip one byte inside the copy: the file still looks like a backup but is no longer the one recorded.
        RandomAccessFile(backup, "rw").use { raf ->
            val position = backup.length() / 2
            raf.seek(position)
            val original = raf.read()
            raf.seek(position)
            raf.write(original xor 0xFF)
        }
        assertTrue("a damaged backup must not validate", backups.validate(backup) is BackupValidation.Invalid)

        val result = restoreManager().restore(backup, admin)

        assertTrue("a damaged backup must be refused, was: $result", result is DatabaseRestoreManager.RestoreResult.Rejected)
        assertEquals("the live data must be untouched", 1, db.patientDao().getAllIncludingArchived().size)
        assertEquals(3, db.doseLogDao().getAll().size)
        assertTrue(backups.listBackups().none { it.reason == BackupReason.PRE_RESTORE })
    }

    /** Deleting (or truncating) the manifest must be detected by the recorded checksum/row counts. */
    @Test
    fun incompleteBackupWithMissingTablesIsRejected() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)
        // Remove the manifest so the file is treated as a legacy copy and inspected fully, then truncate the
        // copy so it is no longer a complete database.
        backups.manifestFile(backup).delete()
        assertTrue("the entry must fall back to the legacy format", backups.listBackups().single().legacy)
        RandomAccessFile(backup, "rw").use { raf -> raf.setLength(backup.length() / 2) }

        assertTrue(backups.validate(backup) is BackupValidation.Invalid)
        val result = restoreManager().restore(backup, admin)
        assertTrue(result is DatabaseRestoreManager.RestoreResult.Rejected)
        assertEquals(1, db.patientDao().getAllIncludingArchived().size)
    }

    /** A manifest whose row counts no longer match the file must be caught before any swap. */
    @Test
    fun manifestRowCountMismatchIsRejected() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)
        val manifest = backups.manifestFile(backup)
        val tampered = manifest.readText().replace("\"dose_logs\": 3", "\"dose_logs\": 4")
        assertNotEquals("the test must actually change the manifest", manifest.readText(), tampered)
        manifest.writeText(tampered)

        assertTrue(backups.validate(backup) is BackupValidation.Invalid)
        assertTrue(restoreManager().restore(backup, admin) is DatabaseRestoreManager.RestoreResult.Rejected)
        assertEquals(3, db.doseLogDao().getAll().size)
    }

    @Test
    fun fileThatIsNotABackupIsRejected() = runBlocking {
        val junk = File(backupDir, "manual-20260101-010101-000.db")
        junk.writeBytes(ByteArray(4096) { 7 })

        val result = restoreManager().restore(junk, admin)

        assertTrue(result is DatabaseRestoreManager.RestoreResult.Rejected)
        assertEquals(1, db.patientDao().getAllIncludingArchived().size)
        assertEquals("ok", backups.integrityReport(db))
    }

    @Test
    fun emptyFileIsRejected() = runBlocking {
        val empty = File(backupDir, "manual-20260101-010102-000.db")
        empty.writeBytes(ByteArray(0))

        assertTrue(restoreManager().restore(empty, admin) is DatabaseRestoreManager.RestoreResult.Rejected)
        assertEquals(1, db.patientDao().getAllIncludingArchived().size)
    }

    @Test
    fun nonAdministratorCannotRestore() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)
        val provider = User(name = "Provider", role = Role.PROVIDER, pinHash = "h", pinSalt = "s")

        val result = restoreManager().restore(backup, provider)

        assertTrue(result is DatabaseRestoreManager.RestoreResult.Rejected)
        assertTrue((result as DatabaseRestoreManager.RestoreResult.Rejected).message.contains("Administrator"))
        assertEquals(1, db.patientDao().getAllIncludingArchived().size)
    }

    @Test
    fun failedVerificationPutsTheOriginalDatabaseBack() = runBlocking {
        val backup = backups.createVerifiedBackup(db, BackupReason.MANUAL)
        db.patientDao().upsert(Patient(name = "Second", firstName = "Second", lastName = "Patient"))
        assertEquals(2, db.patientDao().getAllIncludingArchived().size)

        // Let the safety copy be made, then fail the reopen that verifies the restored file.
        var opens = 0
        val manager = DatabaseRestoreManager(
            context = context,
            backups = backups,
            databaseFile = dbFile,
            closeDatabase = { db.close() },
            openDatabase = {
                opens++
                if (opens == 2) throw IllegalStateException("simulated verification failure")
                if (!db.isOpen) db = openDb()
                db
            }
        )

        val result = manager.restore(backup, admin)

        assertTrue("a failed restore must be reported, was: $result", result is DatabaseRestoreManager.RestoreResult.Failed)
        val failure = result as DatabaseRestoreManager.RestoreResult.Failed
        assertTrue(
            "the message must say the original data survived: ${failure.message}",
            failure.message.contains("unchanged")
        )
        assertEquals(
            "the data that existed before the restore must be back",
            2,
            db.patientDao().getAllIncludingArchived().size
        )
        assertEquals("ok", backups.integrityReport(db))
        assertFalse(DatabaseRestoreManager.rollbackFileFor(dbFile).exists())
    }

    // ------------------------------------------------------------------ interrupted restore

    @Test
    fun interruptedRestoreIsRepairedOnNextStart() {
        val live = File(backupDir, "interrupted.db")
        val rollback = DatabaseRestoreManager.rollbackFileFor(live)
        val staging = DatabaseRestoreManager.stagingFileFor(live)

        // A restore that died between its two renames: the live file was moved aside (the rollback copy holds
        // the original data) and the staged backup was never put in place.
        rollback.writeText("original-data")
        staging.writeText("backup-data")
        live.delete()
        assertTrue("the original file must be put back", DatabaseRestoreManager.recoverInterruptedRestore(live))
        assertEquals("original-data", live.readText())
        assertFalse("the rollback copy must be consumed", rollback.exists())
        assertFalse("the staged copy must not be left behind", staging.exists())

        // A stale staging file next to a healthy database must be removed and nothing else touched.
        staging.writeText("half-written")
        assertFalse(DatabaseRestoreManager.recoverInterruptedRestore(live))
        assertFalse(staging.exists())
        assertEquals("original-data", live.readText())
    }

    /** A real interrupted restore: the live database is moved aside, then the app "restarts". */
    @Test
    fun interruptedRestoreOfARealDatabaseLeavesTheOriginalDataUsable() = runBlocking {
        backups.createVerifiedBackup(db, BackupReason.MANUAL)
        db.close()

        // Simulate the crash window: live -> rollback, staging written, no swap yet.
        val rollback = DatabaseRestoreManager.rollbackFileFor(dbFile)
        val staging = DatabaseRestoreManager.stagingFileFor(dbFile)
        DatabaseRestoreManager.deleteSidecars(dbFile)
        assertTrue(dbFile.renameTo(rollback))
        staging.writeText("half-written staging copy")

        assertTrue(DatabaseRestoreManager.recoverInterruptedRestore(dbFile))

        db = openDb()
        assertEquals("the original database must open again with all its data", 1,
            db.patientDao().getAllIncludingArchived().size)
        assertEquals(3, db.doseLogDao().getAll().size)
        assertFalse(rollback.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun backupsOfTheSameReasonArePrunedButTheNewestKept() {
        repeat(BackupReason.MANUAL.keep + 3) { backups.createVerifiedBackup(db, BackupReason.MANUAL) }
        val manual = backups.listBackups().filter { it.reason == BackupReason.MANUAL }
        assertEquals(BackupReason.MANUAL.keep, manual.size)
        assertNotNull(manual.first().manifest)
        assertNull(backupDir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".part") })
    }
}
