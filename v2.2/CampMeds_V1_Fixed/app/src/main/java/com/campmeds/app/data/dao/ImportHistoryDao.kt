package com.campmeds.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.campmeds.app.data.entity.ChangeHistoryEntry
import com.campmeds.app.data.entity.ImportHistoryEntry

@Dao
interface ImportHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImport(entry: ImportHistoryEntry)

    @Insert
    suspend fun insertChanges(entries: List<ChangeHistoryEntry>)

    @Query("SELECT * FROM import_history ORDER BY createdAt DESC")
    suspend fun getImports(): List<ImportHistoryEntry>

    @Query("SELECT * FROM change_history WHERE importId = :importId ORDER BY changedAt ASC, id ASC")
    suspend fun changesForImport(importId: String): List<ChangeHistoryEntry>

    @Query("SELECT * FROM change_history WHERE recordId = :recordId ORDER BY changedAt ASC, id ASC")
    suspend fun changesForRecord(recordId: String): List<ChangeHistoryEntry>
}
