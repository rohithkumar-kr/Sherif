package com.example.aiinterviewapp.data.local.db

import androidx.room.TypeConverter
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    @JvmStatic
    fun fromQuestionsList(value: List<InterviewQuestion>): String {
        return json.encodeToString(value)
    }

    @TypeConverter
    @JvmStatic
    fun toQuestionsList(value: String): List<InterviewQuestion> {
        return runCatching { json.decodeFromString<List<InterviewQuestion>>(value) }.getOrDefault(emptyList())
    }
}
