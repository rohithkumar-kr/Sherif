package com.example.aiinterviewapp.ui.screens.resume

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.local.datastore.ScopedResumeText
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import com.example.aiinterviewapp.domain.repository.ResumeTextExtractor
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
 * The stages are deliberately distinct so the UI can describe the real work:
 * [EXTRACTING] is local document reading and involves no Gemini call at all,
 * [RUNNING_OCR] is a local OCR pass, and only [ANALYZING] sends a request.
 * A resumable document that already has usable text must never display an OCR
 * message, which is why OCR is its own stage rather than a flavour of
 * extraction.
 */
enum class ResumeStage {
    IDLE,
    EXTRACTING,
    RUNNING_OCR,
    READING_IMAGE,
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
    val profileSourceText: String? = null,
    /** How the current resume text was obtained, shown as a small provenance note. */
    val textOrigin: ResumeTextOrigin? = null
) {
    val isExtracting: Boolean get() = stage == ResumeStage.EXTRACTING
    val isRunningOcr: Boolean get() = stage == ResumeStage.RUNNING_OCR
    val isReadingImage: Boolean get() = stage == ResumeStage.READING_IMAGE
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
    private val resumeTextExtractor: ResumeTextExtractor,
    private val scopedResumeText: ScopedResumeText,
    private val analyzeResume: AnalyzeResumeUseCase,
    private val profileStore: ResumeProfileStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResumeUiState())
    val uiState: StateFlow<ResumeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Both flows follow the session, so a sign-out clears the resume
            // and the analysis together. Watching a single global value would
            // leave the previous user's profile on screen.
            scopedResumeText.currentUserResumeText.collect { text ->
                _uiState.value = _uiState.value.copy(
                    extractedText = text,
                    hasResume = !text.isNullOrBlank()
                )
            }
        }
        viewModelScope.launch {
            profileStore.resumeProfile().collect { profile ->
                if (profile != null && _uiState.value.profile == null) {
                    val text = scopedResumeText.currentUserResumeText.first()
                    _uiState.value = _uiState.value.copy(
                        profile = profile,
                        profileSourceText = text
                    )
                }
            }
        }
    }

    /**
     * Extracts text from the selected resume file, then analyses it with Gemini.
     *
     * The extractor decides whether the document needs OCR, and reports the
     * stage so the UI can stay accurate. A failure at either step surfaces an
     * error and leaves any previously successful profile untouched, so a bad
     * upload can never destroy good state.
     */
    fun uploadResume(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.EXTRACTING,
                error = null
            )
            val extraction = resumeTextExtractor.extract(uri) { stage ->
                _uiState.value = _uiState.value.copy(stage = stage.toResumeStage())
            }.getOrElse { throwable ->
                _uiState.value = _uiState.value.copy(
                    stage = ResumeStage.IDLE,
                    error = throwable.toUserMessage()
                )
                return@launch
            }

            val text = extraction.text

            // Persist the normalized text immediately, whatever its origin, so
            // interview question generation keeps working even when the analysis
            // service is unavailable. OCR-derived text is stored identically,
            // under the signed-in user.
            scopedResumeText.setResumeText(text)
            _uiState.value = _uiState.value.copy(
                stage = ResumeStage.ANALYZING,
                hasResume = true,
                extractedText = text,
                textOrigin = extraction.origin,
                error = null
            )
            runAnalysis(text)
        }
    }

    /**
     * Maps a typed extraction failure to user-facing wording.
     *
     * Typed failures already carry the required copy. Anything unexpected falls
     * back to a generic message rather than leaking an exception string that
     * might contain file paths.
     */
    private fun Throwable.toUserMessage(): String = when (this) {
        is ResumeExtractionException -> message
            ?: "Resume processing failed. Please upload a clearer resume."
        else -> "Resume processing failed. Please upload a clearer resume."
    }

    private fun ResumeTextExtractor.ExtractionStage.toResumeStage(): ResumeStage =
        when (this) {
            ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT -> ResumeStage.EXTRACTING
            ResumeTextExtractor.ExtractionStage.RUNNING_OCR -> ResumeStage.RUNNING_OCR
            ResumeTextExtractor.ExtractionStage.READING_IMAGE -> ResumeStage.READING_IMAGE
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
            scopedResumeText.setResumeText(null)
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
