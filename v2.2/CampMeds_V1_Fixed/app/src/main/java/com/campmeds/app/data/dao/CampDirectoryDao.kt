package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CampDirectoryDao {
    @Query("SELECT * FROM camp_sessions ORDER BY startDate, name") fun sessions(): Flow<List<CampSession>>
    @Query("SELECT * FROM camp_sessions ORDER BY startDate, name") suspend fun allSessions(): List<CampSession>
    @Upsert suspend fun save(session: CampSession)
    @Query("DELETE FROM camp_sessions WHERE id=:id") suspend fun removeSession(id: String)
    @Query("SELECT * FROM divisions ORDER BY name") fun divisions(): Flow<List<Division>>
    @Upsert suspend fun save(division: Division)
    @Query("DELETE FROM divisions WHERE name=:name") suspend fun removeDivision(name: String)
    @Query("SELECT * FROM bunk_options ORDER BY name") fun bunks(): Flow<List<BunkOption>>
    @Query("SELECT * FROM bunk_options ORDER BY name") suspend fun allBunks(): List<BunkOption>
    @Upsert suspend fun save(bunk: BunkOption)
    @Query("DELETE FROM bunk_options WHERE name=:name") suspend fun removeBunk(name: String)
}
