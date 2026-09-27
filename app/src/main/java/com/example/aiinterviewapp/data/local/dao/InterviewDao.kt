package com.example.aiinterviewapp.data.local.dao

import androidx.room.*
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InterviewDao {
    @Query("SELECT * FROM interviews WHERE status = 'COMPLETED' ORDER BY date DESC")
    fun getCompletedInterviews(): Flow<List<InterviewEntity>>

    @Query("SELECT * FROM interviews WHERE id = :id")
    suspend fun getInterviewById(id: String): InterviewEntity?

    @Query("SELECT * FROM interviews WHERE status = 'STARTED' ORDER BY date DESC LIMIT 1")
    suspend fun getResumableInterview(): InterviewEntity?

    @Query("SELECT * FROM interviews WHERE status = 'STARTED' ORDER BY date DESC LIMIT 1")
    fun getResumableInterviewFlow(): Flow<InterviewEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterview(interview: InterviewEntity)

    @Query("DELETE FROM interviews WHERE id = :id")
    suspend fun deleteInterview(id: String)
}
