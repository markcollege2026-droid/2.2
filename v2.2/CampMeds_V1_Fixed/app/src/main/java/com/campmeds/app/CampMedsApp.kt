package com.campmeds.app

import android.app.Application
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.DatabaseBackupManager
import com.campmeds.app.data.DatabaseRestoreManager
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.repository.SpreadsheetImportRepository

class CampMedsApp : Application() {

    /**
     * Resolved on every access instead of being copied once into a field: a restore closes the Room
     * instance and replaces the database file, and every part of the app has to see the new one.
     */
    val database: AppDatabase get() = AppDatabase.getInstance(this)

    val backups: DatabaseBackupManager by lazy { DatabaseBackupManager(this) }

    val restoreManager: DatabaseRestoreManager by lazy { DatabaseRestoreManager(this, backups) }

    val repository: CampMedsRepository by lazy { CampMedsRepository.getInstance { database } }

    val importRepository: SpreadsheetImportRepository by lazy {
        SpreadsheetImportRepository({ database }, backups)
    }

    override fun onCreate() {
        super.onCreate()
        // Repair a restore that was interrupted between the two renames. Doing it here, before anything
        // queries the database, means the app either opens the restored file or the original one.
        DatabaseRestoreManager.recoverInterruptedRestore(this)
    }
}
