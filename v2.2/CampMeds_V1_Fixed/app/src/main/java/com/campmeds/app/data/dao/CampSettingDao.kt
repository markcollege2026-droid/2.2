package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.CampSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface CampSettingDao {
    @Query("SELECT * FROM camp_settings") fun observeAll(): Flow<List<CampSetting>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(setting: CampSetting)
}
