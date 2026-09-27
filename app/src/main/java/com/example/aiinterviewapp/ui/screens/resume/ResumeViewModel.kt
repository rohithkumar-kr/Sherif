package com.example.aiinterviewapp.ui.screens.resume

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import com.example.aiinterviewapp.domain.usecase.AnalyzeResumeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which part of the resume pipeline is currently running.
 *
 * [EXTRACTING] and [ANALYZING] are deliberately distinct: extraction is local
 * PDF text extraction and has not involved Gemini at all, so the UI must not
 * claim the document is being analyzed while it is still being read.
 */
enum class ResumeStage {
    IDLE,
    EXTRACTING,
    ANALYZING
}

data class ResumeUiState(
    val extractedText: String? = null,
    val hasResume: Boolean = false,
    val profile: ResumeProfile? = null,
    /** Claims Gemini produced that the document does not support, and that were dropped. */
    val unsupportedClaims: List<String> = emptyList(),
    val stage: ResumeStage = ResumeStage.IDLE,
    val error: String? = null,
    /** Resume text currently awaiting analysis, used by retry. */
    val pendingAnalysisText: String? = null,
    /** Resume text the displayed profile was actually derived from. */
    val profileSourceText: String? = null
) {
    val isExtracting: Boolean get() = stage == ResumeStage.EXTRACTING
    val isAnalyzing: Boolean get() = stage == ResumeStage.ANALYZING
    val isLoading: Boolean get() = stage != ResumeStage.IDLE
    val canRetry: Boolean get() = !isLoading && !pendingAnalysisText.isNullOrBlank()
    val hasProfile: Boolean get() = profile != null

    /** True when the stored profile no longer matches the resume on screen. */
    val isProfileStale: Boolean
        get() = profile != null && extractedText != null && extractedText != profileSourceText
}

@HiltViewModel
class ResumeViewModel @Inject constructor(
    private val resumeTextSource: ResumeTextSource,
    private val authPreferences: AuthPreferences,
    private val analyzeResume: AnalyzeResumeUseCase,
    private val profileStore: ResumeProfileStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResumeUiState())
    val uiState: StateFlow<ResumeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authPreferences.resumeText.collect { text ->
                _uiState.value = _uiState.value.copy(
                    extractedText = text,
                    hasResume = !text.isNullOrBlank()
                )
            }
        }
        viewModelScope.launch {
            profileStore.resumeProfile().collect { profile ->
                if (profile != null && _uiState.value.profile == null) {
                    val text = authPreferences.resumeText.first()
                    _uiState.value = _uiState.value.copy(
                        profile = profile,
                        profileSourceText = text
                    )
                }
            }
        }
    }

    /**
     * Extracts text from the selected PDF, then analyses it with Gemini.
     *
     * A failure at either step surfaces an error and leaves any previously
     * successful profile untouched, so a bad upload can never destroy good
     * state.
     */
    fun uploadResume(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.EXTRACTING,
                error = null
            )
            val text = try {
                resumeTextSource.extractText(uri)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    stage = ResumeStage.IDLE,
                    error = "Could not read this PDF: ${e.message ?: "the file appears to be damaged or unsupported."}"
                )
                return@launch
            }

            if (text.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    stage = ResumeStage.IDLE,
                    error = "No readable text was found in this PDF. Scanned or image-only resumes are not supported yet."
                )
                return@launch
            }

            // Persist the text immediately so interview question generation
            // keeps working even when Gemini is unavailable.
            authPreferences.setResumeText(text)
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.ANALYZING,
                hasResume = true,
                extractedText = text,
                error = null
            )
            runAnalysis(text)
        }
    }

    /** Re-runs analysis on the resume currently on screen (AC: re-analysis). */
    fun reanalyze() {
        val text = _uiState.value.extractedText ?: return
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.ANALYZING,
                error = null
            )
            runAnalysis(text)
        }
    }

    /** Retries the last failed analysis (AC: error state offers retry). */
    fun retry() {
        val text = _uiState.value.pendingAnalysisText ?: return
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.ANALYZING,
                error = null
            )
            runAnalysis(text)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun deleteResume() {
        viewModelScope.launch {
            authPreferences.setResumeText(null)
            profileStore.clearResumeProfile()
            _uiState.value = ResumeUiState()
        }
    }

    /**
     * Calls Gemini and only replaces visible state on success. A failure
     * records the error and keeps the previous profile, so a failed attempt can
     * never overwrite a successful analysis.
     */
    private suspend fun runAnalysis(text: String) {
        val result = analyzeResume(text)
        result
            .onSuccess { analysis ->
                profileStore.saveResumeProfile(analysis.profile)
                _uiState.value = _uiState.value.copy(
                    stage = ResumeStage.IDLE,
                    profile = analysis.profile,
                    unsupportedClaims = analysis.unsupportedClaims,
                    profileSourceText = text,
                    pendingAnalysisText = null,
                    error = null
                )
            }
            .onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    stage = ResumeStage.IDLE,
                    error = throwable.message ?: "Resume analysis failed. Please try again.",
                    pendingAnalysisText = text
                )
            }
    }
}
