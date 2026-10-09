package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.Patient
import kotlinx.coroutines.flow.Flow

/**
 * No @Delete here on purpose: patients are archived (isArchived = 1), never hard-deleted, so dose
 * history and exports keep their patient name.
 */
@Dao
interface PatientDao {
    @Query("SELECT * FROM patients WHERE isArchived = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE isArchived = 0 AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun search(query: String): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE id = :id")
    suspend fun getById(id: String): Patient?

    @Query("SELECT * FROM patients WHERE id = :id")
    fun observeById(id: String): Flow<Patient?>

    /** Includes archived patients (needed by the export so history of deleted patients stays exportable). */
    @Query("SELECT * FROM patients ORDER BY name ASC")
    suspend fun getAllIncludingArchived(): List<Patient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(patient: Patient)

    @Query("UPDATE patients SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)
}
