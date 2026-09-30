package com.example.aiinterviewapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write here is scoped by `userId`.
 *
 * The scoping is in the SQL rather than in Kotlin filtering afterwards,
 * because post-filtering still pulls another user's rows into memory: a
 * `SELECT *` followed by `.filter { it.userId == me }` is a correct-looking
 * query that reads all of it. Pushing the predicate into SQL means an unscoped
 * query has to be written deliberately, and the argument names make the intent
 * obvious at the call site.
 */
@Dao
interface InterviewDao {

    @Query("SELECT * FROM interviews WHERE userId = :userId AND status = 'COMPLETED' ORDER BY date DESC")
    fun getCompletedInterviews(userId: String): Flow<List<InterviewEntity>>

    /** A row is readable only by its owner, even with the exact id. */
    @Query("SELECT * FROM interviews WHERE id = :id AND userId = :userId")
    suspend fun getInterviewById(id: String, userId: String): InterviewEntity?

    @Query("SELECT * FROM interviews WHERE userId = :userId AND status = 'STARTED' ORDER BY date DESC LIMIT 1")
    suspend fun getResumableInterview(userId: String): InterviewEntity?

    @Query("SELECT * FROM interviews WHERE userId = :userId AND status = 'STARTED' ORDER BY date DESC LIMIT 1")
    fun getResumableInterviewFlow(userId: String): Flow<InterviewEntity?>

    /**
     * The row is written with its owner's id in the same statement.
     *
     * If the entity's `userId` did not match the signed-in user the write would
     * still succeed under the entity's value, so callers must take the id from
     * the session and never from a domain object that a caller could influence.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterview(interview: InterviewEntity)

    @Query("DELETE FROM interviews WHERE id = :id AND userId = :userId")
    suspend fun deleteInterview(id: String, userId: String)
}
