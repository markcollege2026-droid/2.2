package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Small shared configuration; patient values remain independent of customizable labels. */
@Entity(tableName = "camp_settings")
data class CampSetting(@PrimaryKey val key: String, val value: String)
