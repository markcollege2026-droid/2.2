package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY name ASC")
    fun observeAll(): Flow<List<User>>

    @Query("SELECT * FROM users")
    suspend fun getAll(): List<User>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: String): User?

    /**
     * @Upsert rather than @Insert(REPLACE): REPLACE deletes and re-inserts the row, and dose_logs.loggedByUserId
     * is ON DELETE SET NULL, so re-saving an existing user would quietly erase who administered past doses.
     */
    @Upsert
    suspend fun upsert(user: User)

    @Delete
    suspend fun delete(user: User)
}
