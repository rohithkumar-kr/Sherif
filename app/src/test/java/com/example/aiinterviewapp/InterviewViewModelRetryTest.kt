package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.data.service.VoiceService
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.InterviewStatus
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import com.example.aiinterviewapp.domain.usecase.EvaluateAnswerUseCase
import com.example.aiinterviewapp.domain.usecase.GenerateQuestionUseCase
import com.example.aiinterviewapp.ui.screens.interview.InterviewUiState
import com.example.aiinterviewapp.ui.screens.interview.InterviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewViewModelRetryTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: InterviewRepository
    private lateinit var voiceService: VoiceService
    private lateinit var authPreferences: AuthPreferences

    private var generateQuestionResults: ArrayDeque<Result<String>> = ArrayDeque()
    private var evaluateAnswerResults: ArrayDeque<Result<QuestionEvaluation>> = ArrayDeque()
    private var throwOnResumableRead = false
    private var throwOnInterviewRead = false
    private var failOnSave = false
    private val savedInterviews = mutableListOf<Interview>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = object : InterviewRepository {
            override suspend fun generateQuestion(
                role: String,
                experience: String,
                difficulty: String,
                type: String,
                previousQuestions: List<String>,
                resumeContext: String?,
                lastAnswer: String?,
                questionIndex: Int,
                totalQuestions: Int
            ): Result<String> {
                return if (generateQuestionResults.isEmpty()) {
                    Result.success("Default question?")
                } else {
                    generateQuestionResults.removeFirst()
                }
            }

            override suspend fun evaluateAnswer(
                question: String,
                answer: String
            ): Result<QuestionEvaluation> {
                return if (evaluateAnswerResults.isEmpty()) {
                    Result.success(defaultEvaluation())
                } else {
                    evaluateAnswerResults.removeFirst()
                }
            }

            override suspend fun saveInterview(interview: Interview) {
                if (failOnSave) throw IllegalStateException("save failed")
                savedInterviews.removeAll { it.id == interview.id }
                savedInterviews.add(interview)
            }

            override fun getInterviewHistory(): Flow<List<Interview>> = flowOf(savedInterviews)

            override suspend fun getInterviewById(id: String): Interview? {
                if (throwOnInterviewRead) throw IllegalStateException("db unavailable")
                return savedInterviews.firstOrNull { it.id == id }
            }

            override suspend fun getResumableInterview(): Interview? {
                if (throwOnResumableRead) throw IllegalStateException("db unavailable")
                return savedInterviews.firstOrNull { it.status == InterviewStatus.STARTED }
            }

            override fun getResumableInterviewFlow(): Flow<Interview?> = MutableStateFlow(
                savedInterviews.firstOrNull { it.status == InterviewStatus.STARTED }
            )

            override suspend fun deleteInterview(id: String) {
                savedInterviews.removeAll { it.id == id }
            }
        }
        voiceService = mock()
        authPreferences = mock()
        whenever(authPreferences.resumeText).thenReturn(flowOf(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = InterviewViewModel(
        generateQuestionUseCase = GenerateQuestionUseCase(repository),
        evaluateAnswerUseCase = EvaluateAnswerUseCase(repository),
        repository = repository,
        voiceService = voiceService,
        authPreferences = authPreferences
    )

    private fun defaultEvaluation() = QuestionEvaluation(
        score = 7,
        confidence = 6,
        communication = 6,
        technical = 7,
        grammar = 8,
        suggestions = "s",
        strengths = "st",
        weaknesses = "w"
    )

    @Test
    fun `retry after initial question generation failure retries question generation`() = runTest {
        generateQuestionResults.add(Result.failure(Exception("network down")))
        generateQuestionResults.add(Result.success("What is MVVM?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 5)
        advanceUntilIdle()

        assertTrue(vm.uiState.value is InterviewUiState.Error)

        vm.retry()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val success = state as InterviewUiState.Success
        assertEquals(1, success.messages.size)
        assertEquals("What is MVVM?", success.messages[0].text)
        assertEquals(true, success.messages[0].isAi)
    }

    @Test
    fun `retry after initial question generation failure persists a single interview without duplicates`() = runTest {
        generateQuestionResults.add(Result.failure(Exception("network down")))
        generateQuestionResults.add(Result.success("What is MVVM?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 5)
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        assertEquals(1, savedInterviews.size)
        val saved = savedInterviews.single()
        assertEquals(1, saved.questions.size)
        assertEquals("What is MVVM?", saved.questions[0].question)
        assertEquals(InterviewStatus.STARTED, saved.status)
    }

    @Test
    fun `retry after evaluation failure re-evaluates existing question instead of generating new one`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is dependency injection?"))
        evaluateAnswerResults.add(Result.failure(Exception("gemini overloaded")))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        assertTrue(vm.uiState.value is InterviewUiState.Error)

        vm.retry()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val success = state as InterviewUiState.Success
        // question1 + answer + feedback + question2: retry re-evaluated q1, feedback added, then loaded the next question
        assertEquals(4, success.messages.size)
        assertEquals(true, success.messages[2].isAi)
        assertEquals("What is dependency injection?", success.messages[3].text)

        val saved = savedInterviews.single()
        assertEquals(2, saved.questions.size)
        val first = saved.questions[0]
        assertEquals("What is MVVM?", first.question)
        assertEquals("MVVM separates UI from business logic.", first.answer)
        assertEquals(7, first.evaluation?.score)
    }

    @Test
    fun `retry after evaluation failure persists evaluation without duplicating question`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        evaluateAnswerResults.add(Result.failure(Exception("gemini overloaded")))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 1)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(1, saved.questions.size)
        val question = saved.questions.single()
        assertEquals("What is MVVM?", question.question)
        assertEquals("MVVM separates UI from business logic.", question.answer)
        assertEquals(7, question.evaluation?.score)
    }

    @Test
    fun `retry completes interview after successful evaluation retry`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        evaluateAnswerResults.add(Result.failure(Exception("gemini overloaded")))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 1)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Completed)
        val saved = savedInterviews.single()
        assertEquals(InterviewStatus.COMPLETED, saved.status)
        assertEquals(70, saved.score)
    }

    @Test
    fun `retry is ignored when an interview is not active`() = runTest {
        val vm = viewModel()
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is InterviewUiState.Loading)
        assertTrue(savedInterviews.isEmpty())
    }

    @Test
    fun `retry after second question generation failure regenerates next question without duplicating`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.failure(Exception("gemini overloaded")))
        generateQuestionResults.add(Result.success("What is dependency injection?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        assertTrue(vm.uiState.value is InterviewUiState.Error)

        vm.retry()
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(2, saved.questions.size)
        assertEquals("What is MVVM?", saved.questions[0].question)
        assertEquals("What is dependency injection?", saved.questions[1].question)
        val distinct = saved.questions.map { it.question }.distinct()
        assertEquals(saved.questions.size, distinct.size)
    }

    @Test
    fun `start interview surfaces error instead of staying stuck in loading when repository throws`() = runTest {
        throwOnResumableRead = true

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 5)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Error)
    }

    @Test
    fun `resume interview surfaces error instead of staying stuck in loading when repository throws`() = runTest {
        throwOnInterviewRead = true

        val vm = viewModel()
        vm.resumeInterview("missing-id")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Error)
    }

    @Test
    fun `retry after evaluation failure then continue later preserves progress and does not duplicate`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        evaluateAnswerResults.add(Result.failure(Exception("gemini overloaded")))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        vm.cancelInterview()
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(1, saved.questions.size)
        assertEquals("What is MVVM?", saved.questions[0].question)
        assertEquals("MVVM separates UI from business logic.", saved.questions[0].answer)
        assertEquals(InterviewStatus.STARTED, saved.status)
    }

    @Test
    fun `duplicate question from gemini is rejected and regenerated`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is dependency injection?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(2, saved.questions.size)
        assertEquals("What is MVVM?", saved.questions[0].question)
        assertEquals("What is dependency injection?", saved.questions[1].question)
        assertEquals(2, saved.questions.map { it.question }.distinct().size)
    }

    @Test
    fun `all generated questions duplicate surfaces error instead of staying stuck in loading`() = runTest {
        repeat(4) { generateQuestionResults.add(Result.success("What is MVVM?")) }

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        assertTrue(vm.uiState.value is InterviewUiState.Error)
        // First question is still persisted so the interview is not lost.
        val saved = savedInterviews.single()
        assertEquals(1, saved.questions.size)
    }

    @Test
    fun `submit answer while loading is ignored and does not persist partial state`() = runTest {
        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        // No advanceUntilIdle: state is still Loading and isBusy is true.

        vm.submitAnswer("ignored answer")
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(1, saved.questions.size)
        assertTrue(saved.questions[0].answer.isNullOrBlank())
        assertTrue(saved.questions[0].evaluation == null)
    }

    @Test
    fun `evaluation success adds AI feedback message to the conversation after the answer`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is dependency injection?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        // question1 + answer1 + feedback + question2
        assertEquals(4, messages.size)
        assertEquals(true, messages[0].isAi)
        assertEquals(false, messages[1].isAi)
        assertEquals(true, messages[2].isAi)
        assertTrue(messages[2].text.contains("Strengths"))
        assertTrue(messages[2].text.contains("Suggestion"))
        assertEquals("What is dependency injection?", messages[3].text)
    }

    @Test
    fun `resume keeps the user answer visible in the conversation`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 2,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(
                    InterviewQuestion(
                        id = "q1",
                        question = "What is MVVM?",
                        answer = "MVVM separates UI from business logic.",
                        evaluation = defaultEvaluation()
                    )
                )
            )
        )

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        // restored q1 + answer + feedback, then a new question was loaded
        assertEquals(4, messages.size)
        assertEquals("MVVM separates UI from business logic.", messages[1].text)
        assertEquals(false, messages[1].isAi)
        assertEquals(true, messages[2].isAi)
    }

    @Test
    fun `start interview with matching config restores resumable instead of creating duplicate`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 2,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(InterviewQuestion(id = "q1", question = "What is MVVM?"))
            )
        )

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val success = state as InterviewUiState.Success
        assertEquals(1, success.messages.size)
        assertEquals("What is MVVM?", success.messages[0].text)
        assertEquals(1, savedInterviews.size)
        assertEquals("resume-1", savedInterviews.single().id)
    }

    @Test
    fun `save and pause persists exact role type difficulty experience and question count`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 5)
        advanceUntilIdle()

        val paused = vm.saveAndPause()

        assertTrue(paused)
        val saved = savedInterviews.single()
        assertEquals("Android Developer", saved.role)
        assertEquals("Technical", saved.type)
        assertEquals("Medium", saved.difficulty)
        assertEquals("Fresher", saved.experience)
        assertEquals(5, saved.questionCount)
        assertEquals(InterviewStatus.STARTED, saved.status)
    }

    @Test
    fun `pause then resume restores the full conversation with the same interview id`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is dependency injection?"))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()
        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()

        val savedId = savedInterviews.single().id
        assertTrue(vm.saveAndPause())

        val resumed = viewModel()
        resumed.resumeInterview(savedId)
        advanceUntilIdle()

        val state = resumed.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        // q1 + answer + feedback + q2
        assertEquals(4, messages.size)
        assertEquals("What is MVVM?", messages[0].text)
        assertEquals("MVVM separates UI from business logic.", messages[1].text)
        assertEquals(false, messages[1].isAi)
        assertTrue(messages[2].text.contains("Strengths"))
        assertEquals("What is dependency injection?", messages[3].text)
        assertEquals(savedId, savedInterviews.single().id)
    }

    @Test
    fun `resume loads exactly the next unanswered question without duplicating previous ones`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 3,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(
                    InterviewQuestion(
                        id = "q1",
                        question = "What is MVVM?",
                        answer = "MVVM separates UI from business logic.",
                        evaluation = defaultEvaluation()
                    )
                )
            )
        )
        generateQuestionResults.add(Result.success("What is dependency injection?"))

        val vm = viewModel()
        vm.resumeInterview("resume-1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        // q1 + answer + feedback + new q2
        assertEquals(4, messages.size)
        assertEquals("What is dependency injection?", messages[3].text)
        assertEquals(
            listOf("What is MVVM?", "What is dependency injection?"),
            savedInterviews.single().questions.map { it.question }
        )
    }

    @Test
    fun `resume with a pending evaluation re-evaluates the last answer`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 2,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(
                    InterviewQuestion(id = "q1", question = "What is MVVM?", answer = "MVVM separates UI from business logic.")
                )
            )
        )
        generateQuestionResults.add(Result.success("What is dependency injection?"))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.resumeInterview("resume-1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        // q1 + answer + feedback + q2
        assertEquals(4, messages.size)
        assertTrue(messages[2].text.contains("Strengths"))
        assertEquals(7, savedInterviews.single().questions[0].evaluation?.score)
    }

    @Test
    fun `save and pause returns false when persistence fails and does not navigate away`() = runTest {
        failOnSave = true
        generateQuestionResults.add(Result.success("What is MVVM?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 1)
        advanceUntilIdle()

        val paused = vm.saveAndPause()

        assertFalse(paused)
        assertTrue(savedInterviews.isEmpty())
        assertTrue(vm.uiState.value is InterviewUiState.Success)
    }

    @Test
    fun `resume of a completed interview surfaces an error instead of resuming`() = runTest {
        savedInterviews.add(
            Interview(
                id = "done-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 1,
                experience = "Fresher",
                status = InterviewStatus.COMPLETED,
                score = 70,
                questions = listOf(
                    InterviewQuestion(
                        id = "q1",
                        question = "What is MVVM?",
                        answer = "MVVM separates UI from business logic.",
                        evaluation = defaultEvaluation()
                    )
                )
            )
        )

        val vm = viewModel()
        vm.resumeInterview("done-1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is InterviewUiState.Error)
        assertEquals(1, savedInterviews.size)
    }

    @Test
    fun `repeated pause and resume keeps the exact conversation across cycles`() = runTest {
        generateQuestionResults.add(Result.success("What is MVVM?"))
        generateQuestionResults.add(Result.success("What is dependency injection?"))
        evaluateAnswerResults.add(Result.success(defaultEvaluation()))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Medium", "Fresher", 2)
        advanceUntilIdle()
        vm.submitAnswer("MVVM separates UI from business logic.")
        advanceUntilIdle()
        val id = savedInterviews.single().id
        assertTrue(vm.saveAndPause())

        val resumed = viewModel()
        resumed.resumeInterview(id)
        advanceUntilIdle()
        val idAfterResume = savedInterviews.single().id
        assertTrue(resumed.saveAndPause())

        val resumedAgain = viewModel()
        resumedAgain.resumeInterview(idAfterResume)
        advanceUntilIdle()

        val state = resumedAgain.uiState.value
        assertTrue(state is InterviewUiState.Success)
        val messages = (state as InterviewUiState.Success).messages
        assertEquals(4, messages.size)
        assertEquals("What is MVVM?", messages[0].text)
        assertEquals("MVVM separates UI from business logic.", messages[1].text)
        assertEquals(false, messages[1].isAi)
        assertTrue(messages[2].isAi)
        assertEquals("What is dependency injection?", messages[3].text)
    }

    @Test
    fun `starting with a different config creates a new interview with correct metadata instead of reusing resumable`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 2,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(InterviewQuestion(id = "q1", question = "What is MVVM?"))
            )
        )
        generateQuestionResults.add(Result.success("What is Kotlin coroutines?"))

        val vm = viewModel()
        vm.startInterview("Android Developer", "Technical", "Hard", "Senior", 5)
        advanceUntilIdle()

        assertEquals(2, savedInterviews.size)
        val newInterview = savedInterviews.first { it.id != "resume-1" }
        assertEquals("Technical", newInterview.type)
        assertEquals("Senior", newInterview.experience)
        assertEquals("Hard", newInterview.difficulty)
        assertEquals(5, newInterview.questionCount)
    }

    @Test
    fun `resume preserves the original question count and remaining flow`() = runTest {
        savedInterviews.add(
            Interview(
                id = "resume-1",
                role = "Android Developer",
                type = "Technical",
                difficulty = "Medium",
                questionCount = 5,
                experience = "Fresher",
                status = InterviewStatus.STARTED,
                questions = listOf(
                    InterviewQuestion(
                        id = "q1",
                        question = "What is MVVM?",
                        answer = "MVVM separates UI from business logic.",
                        evaluation = defaultEvaluation()
                    )
                )
            )
        )
        generateQuestionResults.add(Result.success("What is dependency injection?"))

        val vm = viewModel()
        vm.resumeInterview("resume-1")
        advanceUntilIdle()

        val saved = savedInterviews.single()
        assertEquals(5, saved.questionCount)
        assertEquals(2, saved.questions.size)
    }
}