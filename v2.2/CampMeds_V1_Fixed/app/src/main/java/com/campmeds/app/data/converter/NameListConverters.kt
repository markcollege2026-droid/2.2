package com.campmeds.app.data.converter

import androidx.room.TypeConverter
import org.json.JSONArray

/** Field-scoped JSON codec: names may contain commas. Existing schedule/id codecs remain unchanged. */
class NameListConverters {
    @TypeConverter fun encode(names: List<String>): String = JSONArray(names).toString()
    @TypeConverter fun decode(value: String): List<String> = JSONArray(value).let { a -> (0 until a.length()).map { a.getString(it) } }
}
