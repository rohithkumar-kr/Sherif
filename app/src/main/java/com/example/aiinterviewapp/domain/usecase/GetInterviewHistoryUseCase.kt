package com.example.aiinterviewapp.domain.usecase

import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetInterviewHistoryUseCase @Inject constructor(
    private val repository: InterviewRepository
) {
    operator fun invoke(): Flow<List<Interview>> {
        return repository.getInterviewHistory()
    }
}
