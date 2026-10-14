package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "users")
data class User(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: Role,
    val pinHash: String,   // salted hash — see auth/PinHasher.kt; never store raw PIN
    val pinSalt: String,
    @androidx.room.ColumnInfo(defaultValue = "''") val recoveryQuestionIds: List<String> = emptyList(),
    @androidx.room.ColumnInfo(defaultValue = "''") val recoveryHashes: List<String> = emptyList(),
    @androidx.room.ColumnInfo(defaultValue = "''") val recoverySalts: List<String> = emptyList(),
    @androidx.room.ColumnInfo(defaultValue = "0") val recoveryFailures: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val recoveryLockedUntil: Long = 0
)
