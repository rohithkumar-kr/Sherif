package com.example.aiinterviewapp.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import com.example.aiinterviewapp.domain.usecase.GetInterviewHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authPreferences: AuthPreferences,
    private val getInterviewHistoryUseCase: GetInterviewHistoryUseCase,
    private val interviewRepository: InterviewRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false) }
            var hasLoaded = false
            try {
                combine(
                    authPreferences.userName,
                    getInterviewHistoryUseCase(),
                    interviewRepository.getResumableInterviewFlow()
                ) { name, history, resumable ->
                    HomeUiState(
                        userName = name ?: "",
                        recentInterviews = history.take(5),
                        totalInterviews = history.size,
                        avgScore = if (history.isNotEmpty()) history.map { it.score }.average() else 0.0,
                        bestScore = history.map { it.score }.maxOrNull() ?: 0,
                        resumableInterview = resumable,
                        isLoading = false,
                        isError = false
                    )
                }.collect { state ->
                    hasLoaded = true
                    _uiState.value = state
                }
            } catch (e: Exception) {
                if (!hasLoaded) {
                    _uiState.update { it.copy(isLoading = false, isError = true) }
                }
            }
        }
    }

    fun discardResumable(id: String) {
        viewModelScope.launch {
            interviewRepository.deleteInterview(id)
        }
    }
}

data class HomeUiState(
    val userName: String = "",
    val recentInterviews: List<Interview> = emptyList(),
    val totalInterviews: Int = 0,
    val avgScore: Double = 0.0,
    val bestScore: Int = 0,
    val resumableInterview: Interview? = null,
    val isLoading: Boolean = true,
    val isError: Boolean = false
)