package com.campmeds.app.data.dao

import androidx.room.*
import com.campmeds.app.data.entity.Medication
import kotlinx.coroutines.flow.Flow

/** No @Delete: medications are archived, never hard-deleted (history keeps pointing at them). */
@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE patientId = :patientId AND isArchived = 0 ORDER BY name ASC")
    fun observeForPatient(patientId: String): Flow<List<Medication>>

    @Query("SELECT * FROM medications WHERE patientId = :patientId AND isArchived = 1 ORDER BY name ASC")
    fun observeArchivedForPatient(patientId: String): Flow<List<Medication>>

    @Query(
        """
        SELECT * FROM medications
        WHERE isArchived = 0 AND startDate <= :today AND (endDate IS NULL OR endDate >= :today)
        """
    )
    suspend fun getActiveOn(today: String): List<Medication>

    /** Live version, so a medication added/edited while the dashboard is open shows up without a restart. */
    @Query(
        """
        SELECT * FROM medications
        WHERE isArchived = 0 AND startDate <= :today AND (endDate IS NULL OR endDate >= :today)
        """
    )
    fun observeActiveOn(today: String): Flow<List<Medication>>

    /** Every medication including archived ones: used by the export (one sheet per medication). */
    @Query("SELECT * FROM medications ORDER BY name ASC")
    suspend fun getAllIncludingArchived(): List<Medication>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: String): Medication?

    /** Archived medications are returned too, so scanning an old label is recognised (and flagged). */
    @Query("SELECT * FROM medications WHERE qrCode = :qrCode LIMIT 1")
    suspend fun getByQrCode(qrCode: String): Medication?

    // V1 bug 9: only medications that came from a real openFDA lookup may act as the local NDC cache.
    // An empty NDC (manual entry without one) must never match anything.
    @Query("SELECT * FROM medications WHERE ndc = :ndc AND ndc != '' AND isException = 0 LIMIT 1")
    suspend fun getCachedByNdc(ndc: String): Medication?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(medication: Medication)

    @Query("UPDATE medications SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE medications SET isArchived = 1 WHERE patientId = :patientId")
    suspend fun archiveAllForPatient(patientId: String)
}
