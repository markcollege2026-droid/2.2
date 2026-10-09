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
    val pinSalt: String
)
