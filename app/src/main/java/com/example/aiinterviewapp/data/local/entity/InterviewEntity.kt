package com.example.aiinterviewapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewStatus
import com.example.aiinterviewapp.domain.model.InterviewQuestion

@Entity(
    tableName = "interviews",
    indices = [Index(value = ["date"])]
)
data class InterviewEntity(
    @PrimaryKey val id: String,
    val role: String,
    val type: String,
    val difficulty: String,
    val questionCount: Int,
    val experience: String,
    val score: Int,
    val date: Long,
    val status: String,
    val questionsJson: List<InterviewQuestion>
)

fun InterviewEntity.toDomain(): Interview {
    return Interview(
        id = id,
        role = role,
        type = type,
        difficulty = difficulty,
        questionCount = questionCount,
        experience = experience,
        score = score,
        date = date,
        status = runCatching { InterviewStatus.valueOf(status) }.getOrDefault(InterviewStatus.STARTED),
        questions = questionsJson
    )
}
