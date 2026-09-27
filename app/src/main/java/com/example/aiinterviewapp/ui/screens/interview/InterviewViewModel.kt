package com.example.aiinterviewapp.ui.screens.interview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.data.service.VoiceResult
import com.example.aiinterviewapp.data.service.VoiceService
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.InterviewResumeState
import com.example.aiinterviewapp.domain.model.InterviewStatus
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.model.normalized
import com.example.aiinterviewapp.domain.model.overallScorePercent
import com.example.aiinterviewapp.domain.model.resumeState
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import com.example.aiinterviewapp.domain.usecase.EvaluateAnswerUseCase
import com.example.aiinterviewapp.domain.usecase.GenerateQuestionUseCase
import com.example.aiinterviewapp.ui.common.FeedbackMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class InterviewViewModel @Inject constructor(
    private val generateQuestionUseCase: GenerateQuestionUseCase,
    private val evaluateAnswerUseCase: EvaluateAnswerUseCase,
    private val repository: InterviewRepository,
    private val voiceService: VoiceService,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<InterviewUiState>(InterviewUiState.Loading)
    val uiState: StateFlow<InterviewUiState> = _uiState.asStateFlow()

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _feedback = MutableStateFlow<FeedbackMessage?>(null)
    val feedback: StateFlow<FeedbackMessage?> = _feedback.asStateFlow()

    private var currentInterview: Interview? = null
    private var resumeContext: String? = null
    private var isBusy = false
    private var pendingCompletion = false
    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val MAX_QUESTION_GENERATION_ATTEMPTS = 3
    }

    override fun onCleared() {
        voiceService.stopTts()
        voiceService.stopListening()
    }

    fun showFeedback(message: String) {
        _feedback.value = FeedbackMessage(message)
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    fun toggleVoiceMode() {
        _isVoiceMode.value = !_isVoiceMode.value
        if (!_isVoiceMode.value) {
            voiceService.stopTts()
        }
    }

    fun startVoiceInput() {
        if (_isListening.value) return
        viewModelScope.launch {
            _isListening.value = true
            try {
                voiceService.startListening().collect { result ->
                    when (result) {
                        is VoiceResult.Success -> submitAnswer(result.text)
                        is VoiceResult.Error -> {
                            _feedback.value = FeedbackMessage(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                _feedback.value = FeedbackMessage("Voice input isn't available right now. Please try again.")
            } finally {
                _isListening.value = false
            }
        }
    }

    fun startInterview(
        role: String,
        type: String,
        difficulty: String,
        experience: String,
        questionCount: Int = 5
    ) {
        if (currentInterview != null) return

        viewModelScope.launch {
            _uiState.value = InterviewUiState.Loading
            try {
                resumeContext = authPreferences.resumeText.first()
                val resumable = repository.getResumableInterview()
                if (resumable != null &&
                    resumable.role == role &&
                    resumable.type == type &&
                    resumable.difficulty == difficulty &&
                    resumable.experience == experience &&
                    resumable.questionCount == questionCount
                ) {
                    restoreInterview(resumable)
                } else {
                    startNewInterview(role, type, difficulty, experience, questionCount)
                }
            } catch (e: Exception) {
                _uiState.value = InterviewUiState.Error(
                    "Couldn't load your interview data. Please try again."
                )
            }
        }
    }

    fun resumeInterview(id: String) {
        if (currentInterview != null) return
        viewModelScope.launch {
            _uiState.value = InterviewUiState.Loading
            try {
                resumeContext = authPreferences.resumeText.first()
                val saved = repository.getInterviewById(id)
                if (saved == null) {
                    _uiState.value = InterviewUiState.Error("This interview is no longer available.")
                    return@launch
                }
                if (saved.status == InterviewStatus.COMPLETED) {
                    _uiState.value = InterviewUiState.Error("This interview is already completed.")
                    return@launch
                }
                restoreInterview(saved)
            } catch (e: Exception) {
                _uiState.value = InterviewUiState.Error(
                    "Couldn't load this interview. Please try again."
                )
            }
        }
    }

    private fun startNewInterview(
        role: String,
        type: String,
        difficulty: String,
        experience: String,
        questionCount: Int
    ) {
        isBusy = false
        pendingCompletion = false
        messages.clear()
        currentInterview = Interview(
            id = UUID.randomUUID().toString(),
            role = role,
            type = type,
            difficulty = difficulty,
            questionCount = questionCount.coerceIn(1, 30),
            experience = experience
        )
        loadNextQuestion()
    }

    private fun restoreInterview(saved: Interview) {
        currentInterview = saved
        isBusy = false
        pendingCompletion = false
        messages.clear()
        saved.questions.forEach { question ->
            messages.add(ChatMessage(question.question, isAi = true))
            question.answer?.takeIf { it.isNotBlank() }?.let {
                messages.add(ChatMessage(it, isAi = false))
            }
            question.evaluation?.let { evaluation ->
                messages.add(ChatMessage(evaluationFeedbackMessage(evaluation.normalized()), isAi = true))
            }
        }
        when (saved.resumeState()) {
            InterviewResumeState.LOAD_FIRST_QUESTION,
            InterviewResumeState.LOAD_NEXT_QUESTION -> loadNextQuestion()

            InterviewResumeState.AWAITING_ANSWER -> {
                _uiState.value = InterviewUiState.Success(messages.toList())
                if (_isVoiceMode.value) {
                    saved.questions.lastOrNull()?.question?.let {
                        runCatching { voiceService.speak(it) }
                    }
                }
            }

            InterviewResumeState.NEEDS_EVALUATION -> {
                _uiState.value = InterviewUiState.Success(messages.toList())
                saved.questions.lastOrNull()?.let { evaluateQuestion(it) }
            }

            InterviewResumeState.READY_TO_FINISH -> finishInterview()

            InterviewResumeState.INVALID -> {
                _uiState.value = InterviewUiState.Error("This interview could not be restored.")
            }
        }
    }

    private fun loadNextQuestion() {
        val interview = currentInterview ?: return
        if (isBusy) return
        isBusy = true
        viewModelScope.launch {
            _uiState.value = InterviewUiState.Loading
            // Defensive deduplication: Gemini occasionally repeats a question
            // despite the prompt. Reject any question that already exists and
            // ask again (up to a few attempts) before surfacing an error.
            val existingQuestions = interview.questions.map { it.question }.toSet()
            var questionText: String? = null
            repeat(MAX_QUESTION_GENERATION_ATTEMPTS) {
                if (questionText != null) return@repeat
                generateQuestionUseCase(
                    role = interview.role,
                    experience = interview.experience,
                    difficulty = interview.difficulty,
                    type = interview.type,
                    previousQuestions = interview.questions.map { it.question },
                    resumeContext = resumeContext,
                    lastAnswer = interview.questions.lastOrNull()?.answer,
                    questionIndex = interview.questions.size,
                    totalQuestions = interview.questionCount
                ).fold(
                    onSuccess = { text ->
                        if (text !in existingQuestions) {
                            questionText = text
                        }
                    },
                    onFailure = {
                        isBusy = false
                        _uiState.value = InterviewUiState.Error(it.message ?: "Failed to load question")
                        return@launch
                    }
                )
            }

            val newQuestionText = questionText
            if (newQuestionText == null) {
                isBusy = false
                _uiState.value = InterviewUiState.Error(
                    "Couldn't generate a new question. Please try again."
                )
                return@launch
            }

            isBusy = false
            val newQuestion = InterviewQuestion(
                id = UUID.randomUUID().toString(),
                question = newQuestionText
            )
            currentInterview = interview.copy(
                questions = interview.questions + newQuestion
            )
            messages.add(ChatMessage(newQuestionText, isAi = true))
            _uiState.value = InterviewUiState.Success(messages.toList())
            persistProgress()

            if (_isVoiceMode.value) {
                runCatching { voiceService.speak(newQuestionText) }
            }
        }
    }

    fun submitAnswer(answer: String) {
        val interview = currentInterview ?: return
        val currentQuestion = interview.questions.lastOrNull() ?: return
        val trimmed = answer.trim()
        if (trimmed.isEmpty() || isBusy) return

        messages.add(ChatMessage(trimmed, isAi = false))
        _uiState.value = InterviewUiState.Success(messages.toList())

        val questionWithAnswer = currentQuestion.copy(answer = trimmed)
        currentInterview = interview.copy(
            questions = interview.questions.map { if (it.id == currentQuestion.id) questionWithAnswer else it }
        )
        viewModelScope.launch {
            persistProgress()
            evaluateQuestion(questionWithAnswer)
        }
    }

    fun retry() {
        if (isBusy) return
        if (pendingCompletion) {
            pendingCompletion = false
            finishInterview()
            return
        }
        val interview = currentInterview ?: return
        val lastQuestion = interview.questions.lastOrNull()
        if (lastQuestion != null &&
            !lastQuestion.answer.isNullOrBlank() &&
            lastQuestion.evaluation == null
        ) {
            evaluateQuestion(lastQuestion)
        } else {
            loadNextQuestion()
        }
    }

    private fun evaluateQuestion(question: InterviewQuestion) {
        val answer = question.answer?.trim().orEmpty()
        if (answer.isEmpty() || isBusy) return
        isBusy = true
        viewModelScope.launch {
            _uiState.value = InterviewUiState.Evaluating
            evaluateAnswerUseCase(question.question, answer)
                .onSuccess { evaluation ->
                    isBusy = false
                    val baseInterview = currentInterview ?: return@onSuccess
                    val updatedInterview = baseInterview.copy(
                        questions = baseInterview.questions.map {
                            if (it.id == question.id) question.copy(evaluation = evaluation.normalized()) else it
                        }
                    )
                    currentInterview = updatedInterview
                    persistProgress()
                    messages.add(ChatMessage(evaluationFeedbackMessage(evaluation.normalized()), isAi = true))

                    if (updatedInterview.questions.size < updatedInterview.questionCount) {
                        loadNextQuestion()
                    } else {
                        finishInterview()
                    }
                }.onFailure {
                    isBusy = false
                    _uiState.value = InterviewUiState.Error(
                        "Couldn't evaluate your answer. Your answer has been saved."
                    )
                }
        }
    }

    private fun finishInterview() {
        val interview = currentInterview ?: return
        viewModelScope.launch {
            val finalInterview = interview.copy(
                status = InterviewStatus.COMPLETED,
                score = interview.overallScorePercent()
            )
            currentInterview = finalInterview
            voiceService.stopTts()
            if (persistProgress()) {
                _uiState.value = InterviewUiState.Completed(finalInterview.id)
            } else {
                pendingCompletion = true
                _uiState.value = InterviewUiState.Error("Couldn't save your completed interview. Tap Try Again to retry.")
            }
        }
    }

    /**
     * Persists the current interview (as STARTED) so it can be resumed later.
     * Suspends until the write completes, returning whether it succeeded.
     * Used by the Save & Pause flow so the app never navigates away claiming
     * the interview was saved when it was not.
     */
    suspend fun saveAndPause(): Boolean {
        voiceService.stopTts()
        val interview = currentInterview ?: return true
        if (interview.questions.isEmpty()) {
            return runCatching { repository.deleteInterview(interview.id) }.isSuccess
        }
        return runCatching {
            repository.saveInterview(interview.copy(status = InterviewStatus.STARTED))
        }.isSuccess
    }

    fun cancelInterview() {
        voiceService.stopTts()
        val interview = currentInterview ?: return
        viewModelScope.launch {
            // NonCancellable so the save still completes if the ViewModel is
            // cleared while navigation away is happening.
            withContext(NonCancellable) {
                if (interview.questions.isEmpty()) {
                    runCatching { repository.deleteInterview(interview.id) }
                } else {
                    runCatching {
                        repository.saveInterview(interview.copy(status = InterviewStatus.STARTED))
                    }
                }
            }
        }
    }

    suspend fun discardInterview(): Boolean {
        val id = currentInterview?.id ?: return true
        voiceService.stopTts()
        currentInterview = null
        return runCatching { repository.deleteInterview(id) }.isSuccess
    }

    private suspend fun persistProgress(): Boolean {
        val interview = currentInterview ?: return false
        return runCatching { repository.saveInterview(interview) }.isSuccess
    }

    private fun evaluationFeedbackMessage(evaluation: QuestionEvaluation): String {
        val strengths = evaluation.strengths.trim()
        val suggestions = evaluation.suggestions.trim()
        val weaknesses = evaluation.weaknesses.trim()
        return buildString {
            append("Here's my feedback on your answer.")
            if (strengths.isNotEmpty()) {
                append("\n\nStrengths: ").append(strengths)
            }
            if (suggestions.isNotEmpty()) {
                append("\n\nSuggestion: ").append(suggestions)
            }
            if (weaknesses.isNotEmpty() && weaknesses != strengths) {
                append("\n\nTo work on: ").append(weaknesses)
            }
        }
    }
}

sealed class InterviewUiState {
    object Loading : InterviewUiState()
    object Evaluating : InterviewUiState()
    data class Success(val messages: List<ChatMessage>) : InterviewUiState()
    data class Error(val message: String) : InterviewUiState()
    data class Completed(val interviewId: String) : InterviewUiState()
}

data class ChatMessage(
    val text: String,
    val isAi: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)