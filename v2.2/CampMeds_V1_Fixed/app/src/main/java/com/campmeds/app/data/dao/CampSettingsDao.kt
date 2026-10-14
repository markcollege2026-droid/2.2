package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.CampSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface CampSettingsDao {
    @Query("SELECT * FROM camp_settings WHERE id = 1")
    fun observe(): Flow<CampSettings?>
    @Query("SELECT * FROM camp_settings WHERE id = 1") suspend fun get(): CampSettings?
    @Upsert suspend fun save(settings: CampSettings)
}
