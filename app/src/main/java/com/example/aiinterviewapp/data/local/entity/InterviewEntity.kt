package com.example.aiinterviewapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewStatus
import com.example.aiinterviewapp.domain.model.InterviewQuestion

/**
 * An interview, owned by exactly one user.
 *
 * [userId] is the stable identifier the backend assigned at sign-in. It is part
 * of the row rather than a session-time filter alone, because the row is the
 * only place ownership can be enforced: a DAO query that forgets its `WHERE`
 * clause then leaks a *row* rather than merely displaying another user's data.
 *
 * Not `nullable`. A row with no owner cannot be filtered for, so making the
 * column non-null forces every query to answer "whose?" -- there is no way to
 * write an unscoped `SELECT *` that returns anything.
 */
@Entity(
    tableName = "interviews",
    indices = [Index(value = ["date"]), Index(value = ["userId", "date"])]
)
data class InterviewEntity(
    @PrimaryKey val id: String,
    val userId: String,
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

/**
 * The owner recorded for interviews that predate per-user data.
 *
 * Phase 2 rows have no owner and no way to recover one, so they are assigned
 * here rather than left null or, far worse, handed to whoever signs in next.
 * A Google subject id is never this value, so these rows stay unreachable to
 * real users: unreachable beats misattributed (RULE 9).
 */
const val LEGACY_LOCAL_OWNER = "legacy-local-owner"

/**
 * True when [userId] is the placeholder used for pre-Phase-3 rows.
 *
 * The UI can use this to tell the user their old interviews need claiming,
 * instead of silently showing an empty history.
 */
fun String.isLegacyLocalOwner(): Boolean = this == LEGACY_LOCAL_OWNER

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
