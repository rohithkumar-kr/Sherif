package com.example.aiinterviewapp.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: InterviewRepository
) : ViewModel() {

    private val _history = MutableStateFlow<List<Interview>>(emptyList())
    val history: StateFlow<List<Interview>> = _history.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getInterviewHistory().collectLatest {
                _history.value = it
            }
        }
    }

    fun deleteInterview(id: String) {
        viewModelScope.launch {
            repository.deleteInterview(id)
        }
    }
}
