package com.campmeds.app

import android.app.Application
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.DatabaseBackupManager
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.repository.SpreadsheetImportRepository

class CampMedsApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: CampMedsRepository
        private set

    lateinit var importRepository: SpreadsheetImportRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = CampMedsRepository.getInstance(database)
        importRepository = SpreadsheetImportRepository(database, DatabaseBackupManager(this))
    }
}
