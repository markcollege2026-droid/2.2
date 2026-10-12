package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.AdministrationTime
import kotlinx.coroutines.flow.Flow

/**
 * Named administration times are shared by everyone on the device and only administrators may change them
 * (enforced in the UI/repository, not here). Ordered by time so the dropdown reads like the camp's day.
 */
@Dao
interface AdministrationTimeDao {

    @Query("SELECT * FROM administration_times ORDER BY time ASC, name ASC")
    fun observeAll(): Flow<List<AdministrationTime>>

    @Query("SELECT * FROM administration_times ORDER BY time ASC, name ASC")
    suspend fun getAll(): List<AdministrationTime>

    @Query("SELECT * FROM administration_times WHERE id = :id")
    suspend fun getById(id: String): AdministrationTime?

    /** Case-insensitive duplicate check: "Breakfast" and "breakfast" may not both exist. */
    @Query("SELECT * FROM administration_times WHERE name = :name COLLATE NOCASE AND id != :exceptId LIMIT 1")
    suspend fun findByName(name: String, exceptId: String): AdministrationTime?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AdministrationTime)

    @Delete
    suspend fun delete(entry: AdministrationTime)
}
