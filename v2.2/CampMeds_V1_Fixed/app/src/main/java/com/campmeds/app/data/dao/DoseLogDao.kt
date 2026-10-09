package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.DoseLog
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseLogDao {

    @Query(
        """
        SELECT * FROM dose_logs
        WHERE scheduledFor BETWEEN :startOfDay AND :endOfDay
        ORDER BY scheduledFor ASC
        """
    )
    fun observeForDay(startOfDay: String, endOfDay: String): Flow<List<DoseLog>>

    @Query(
        """
        SELECT * FROM dose_logs
        WHERE medicationId IN (SELECT id FROM medications WHERE patientId = :patientId)
        ORDER BY scheduledFor DESC
        """
    )
    fun observeHistoryForPatient(patientId: String): Flow<List<DoseLog>>

    @Query(
        """
        SELECT dl.* FROM dose_logs dl
        JOIN medications m ON dl.medicationId = m.id
        WHERE m.patientId = :patientId
        ORDER BY dl.scheduledFor DESC
        """
    )
    suspend fun getHistoryForPatientOnce(patientId: String): List<DoseLog>

    /** Every administration record ever written, regardless of whether its medication/patient is archived. */
    @Query("SELECT * FROM dose_logs ORDER BY scheduledFor ASC")
    suspend fun getAll(): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medicationId AND scheduledFor = :scheduledFor LIMIT 1")
    suspend fun findExisting(medicationId: String, scheduledFor: String): DoseLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doseLog: DoseLog)
}
