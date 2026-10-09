package com.campmeds.app

import android.app.Application
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.repository.CampMedsRepository

class CampMedsApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: CampMedsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = CampMedsRepository.getInstance(database)
    }
}
