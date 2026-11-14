package com.omr.checker.data.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omr.checker.data.model.SheetLayoutConfig

class RoomConverters {

    private val gson = Gson()

    @TypeConverter
    fun fromSheetLayoutConfig(config: SheetLayoutConfig?): String {
        return if (config == null) "" else gson.toJson(config)
    }

    @TypeConverter
    fun toSheetLayoutConfig(json: String?): SheetLayoutConfig {
        if (json.isNullOrBlank()) return SheetLayoutConfig()
        return try {
            gson.fromJson(json, SheetLayoutConfig::class.java)
        } catch (_: Exception) {
            SheetLayoutConfig()
        }
    }

    @TypeConverter
    fun fromAnswerKeyMap(map: Map<Int, Char>?): String {
        return if (map == null) "" else gson.toJson(map)
    }

    @TypeConverter
    fun toAnswerKeyMap(json: String?): Map<Int, Char> {
        if (json.isNullOrBlank()) return emptyMap()
        val type = object : TypeToken<Map<Int, Char>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromRawAnswersMap(map: Map<Int, Char?>?): String {
        return if (map == null) "" else gson.toJson(map)
    }

    @TypeConverter
    fun toRawAnswersMap(json: String?): Map<Int, Char?> {
        if (json.isNullOrBlank()) return emptyMap()
        val type = object : TypeToken<Map<Int, Char?>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromIntList(list: List<Int>?): String {
        return if (list == null) "" else gson.toJson(list)
    }

    @TypeConverter
    fun toIntList(json: String?): List<Int> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<Int>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
