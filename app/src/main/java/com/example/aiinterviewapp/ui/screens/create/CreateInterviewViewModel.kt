package com.example.aiinterviewapp.ui.screens.create

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.local.datastore.ScopedResumeText
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import com.example.aiinterviewapp.domain.usecase.AnalyzeResumeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateInterviewUiState(
    val hasResume: Boolean = false,
    val resumeProfile: ResumeProfile? = null,
    val isUploading: Boolean = false,
    val uploadError: String? = null
)

@HiltViewModel
class CreateInterviewViewModel @Inject constructor(
    private val scopedResumeText: ScopedResumeText,
    private val profileStore: ResumeProfileStore,
    private val resumeTextSource: ResumeTextSource,
    private val analyzeResume: AnalyzeResumeUseCase
) : ViewModel() {

    private val _isUploading = MutableStateFlow(false)
    private val _uploadError = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(CreateInterviewUiState())
    val uiState: StateFlow<CreateInterviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                scopedResumeText.currentUserResumeText,
                profileStore.resumeProfile(),
                _isUploading,
                _uploadError
            ) { text: String?, profile: ResumeProfile?, uploading: Boolean, error: String? ->
                CreateInterviewUiState(
                    hasResume = !text.isNullOrBlank(),
                    resumeProfile = profile,
                    isUploading = uploading,
                    uploadError = error
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun uploadResume(uri: Uri) {
        viewModelScope.launch {
            _isUploading.value = true
            _uploadError.value = null
            try {
                val text = resumeTextSource.extractText(uri)
                if (text.isBlank()) {
                    _uploadError.value = "No readable text found in PDF."
                    _isUploading.value = false
                    return@launch
                }
                scopedResumeText.setResumeText(text)
                val analysis = analyzeResume(text)
                analysis.onSuccess {
                    profileStore.saveResumeProfile(it.profile)
                }.onFailure {
                    _uploadError.value = "Resume analysis failed: ${it.message}"
                }
            } catch (e: Exception) {
                _uploadError.value = "Failed to read PDF: ${e.message}"
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun clearError() {
        _uploadError.value = null
    }
}
