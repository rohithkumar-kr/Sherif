package com.example.aiinterviewapp.ui.screens.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.service.PdfExportService
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import com.example.aiinterviewapp.ui.common.FeedbackMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: InterviewRepository,
    private val pdfExportService: PdfExportService
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReportUiState>(ReportUiState.Loading)
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _feedback = MutableStateFlow<FeedbackMessage?>(null)
    val feedback: StateFlow<FeedbackMessage?> = _feedback.asStateFlow()

    fun loadReport(interviewId: String) {
        viewModelScope.launch {
            _uiState.value = ReportUiState.Loading
            try {
                val interview = repository.getInterviewById(interviewId)
                _uiState.value = if (interview != null) {
                    ReportUiState.Success(interview)
                } else {
                    ReportUiState.Error("Report not found")
                }
            } catch (e: Exception) {
                _uiState.value = ReportUiState.Error("Couldn't load this report. Please try again.")
            }
        }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    fun exportAndShare(interview: Interview) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    pdfExportService.exportReportToPdf(interview)
                }
                file.fold(
                    onSuccess = { generated ->
                        // The service has already logged why, so the message
                        // here only has to be the user-facing half.
                        pdfExportService.sharePdf(generated).onFailure {
                            _feedback.value = FeedbackMessage("Couldn't share the PDF report. Please try again.")
                        }
                    },
                    onFailure = {
                        _feedback.value = FeedbackMessage("Couldn't generate the PDF report. Please try again.")
                    }
                )
            } finally {
                _isExporting.value = false
            }
        }
    }
}

sealed class ReportUiState {
    object Loading : ReportUiState()
    data class Success(val interview: Interview) : ReportUiState()
    data class Error(val message: String) : ReportUiState()
}
