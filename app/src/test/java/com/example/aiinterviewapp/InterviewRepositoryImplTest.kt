package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.local.dao.InterviewDao
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import com.example.aiinterviewapp.data.remote.api.GeminiApi
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import com.example.aiinterviewapp.data.repository.InterviewRepositoryImpl
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun geminiResponseOf(text: String) = GeminiResponse(
    candidates = listOf(
        GeminiResponse.Candidate(
            content = GeminiResponse.Content(
                parts = listOf(GeminiResponse.Part(text = text))
            )
        )
    )
)

private fun response(text: String) = geminiResponseOf(text)

class InterviewRepositoryImplTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private class FakeGeminiApi(
        private val responseProvider: () -> GeminiResponse = { geminiResponseOf("{\"question\": \"Default?\"}") }
    ) : GeminiApi {
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generateContent(request: GeminiRequest): GeminiResponse {
            requests.add(request)
            return responseProvider()
        }
    }

    private class ThrowingGeminiApi(
        private val error: Throwable
    ) : GeminiApi {
        override suspend fun generateContent(request: GeminiRequest): GeminiResponse {
            throw error
        }
    }

    private class FakeInterviewDao(
        private val initial: List<InterviewEntity> = emptyList()
    ) : InterviewDao {
        val state = MutableStateFlow(initial)

        override fun getCompletedInterviews(): Flow<List<InterviewEntity>> = state.map { it }

        override suspend fun getInterviewById(id: String): InterviewEntity? =
            state.value.firstOrNull { it.id == id }

        override suspend fun getResumableInterview(): InterviewEntity? =
            state.value.firstOrNull { it.status == "STARTED" }

        override fun getResumableInterviewFlow(): Flow<InterviewEntity?> =
            state.map { it.firstOrNull { e -> e.status == "STARTED" } }

        override suspend fun insertInterview(interview: InterviewEntity) {
            state.value = state.value.filterNot { it.id == interview.id } + interview
        }

        override suspend fun deleteInterview(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    @Test
    fun `generateQuestion parses structured JSON question`() = runTest {
        val api = FakeGeminiApi { response("""{"question": "Explain how LiveData works."}""") }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val result = repo.generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isSuccess)
        assertEquals("Explain how LiveData works.", result.getOrNull())
    }

    @Test
    fun `generateQuestion falls back to plain text response`() = runTest {
        val api = FakeGeminiApi { response("What is a ViewModel?") }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val result = repo.generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isSuccess)
        assertEquals("What is a ViewModel?", result.getOrNull())
    }

    @Test
    fun `generateQuestion requests JSON mime type and schema`() = runTest {
        val api = FakeGeminiApi { response("""{"question": "Q?"}""") }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        repo.generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        val config = api.requests.single().generationConfig
        assertEquals("application/json", config?.responseMimeType)
        assertEquals("OBJECT", config?.responseSchema?.type)
        assertTrue(config?.responseSchema?.required?.contains("question") == true)
    }

    @Test
    fun `evaluateAnswer parses evaluation from structured output`() = runTest {
        val raw = """{"score":8,"confidence":7,"communication":6,"technical":9,"grammar":7,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val api = FakeGeminiApi { response(raw) }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val result = repo.evaluateAnswer("Question?", "Answer")

        assertTrue(result.isSuccess)
        val evaluation: QuestionEvaluation = result.getOrThrow()
        assertEquals(8, evaluation.score)
        assertEquals("s", evaluation.suggestions)
    }

    @Test
    fun `evaluateAnswer clamps out-of-range scores`() = runTest {
        val raw = """{"score":99,"confidence":-1,"communication":5,"technical":5,"grammar":5,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val api = FakeGeminiApi { response(raw) }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val evaluation = repo.evaluateAnswer("Q", "A").getOrThrow()

        assertEquals(10, evaluation.score)
        assertEquals(0, evaluation.confidence)
    }

    @Test
    fun `evaluateAnswer fails on malformed response`() = runTest {
        val api = FakeGeminiApi { response("no json here") }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val result = repo.evaluateAnswer("Q", "A")

        assertTrue(result.isFailure)
    }

    @Test
    fun `generateQuestion maps http 429 to rate limit message`() = runTest {
        val api = ThrowingGeminiApi(
            HttpException(Response.error<Any>(429, "{}".toResponseBody()))
        )
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val failure = repo.generateQuestion("R", "Fresher", "Medium", "Technical", emptyList()).exceptionOrNull()

        assertTrue(failure?.message?.contains("rate limit") == true)
    }

    @Test
    fun `generateQuestion maps io exception to network message`() = runTest {
        val api = ThrowingGeminiApi(IOException("socket timeout"))
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val failure = repo.generateQuestion("R", "Fresher", "Medium", "Technical", emptyList()).exceptionOrNull()

        assertTrue(failure?.message?.contains("No internet connection") == true)
    }

    @Test
    fun `generateQuestion fails on empty candidates response`() = runTest {
        val api = FakeGeminiApi { GeminiResponse(candidates = emptyList()) }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        val result = repo.generateQuestion("R", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isFailure)
    }

    @Test
    fun `evaluateAnswer sends evaluation schema with required fields`() = runTest {
        val api = FakeGeminiApi { response("""{"score":7,"confidence":6,"communication":6,"technical":7,"grammar":8,"suggestions":"s","strengths":"st","weaknesses":"w"}""") }
        val repo = InterviewRepositoryImpl(api, FakeInterviewDao(), json)

        repo.evaluateAnswer("Q", "A")

        val schema = api.requests.single().generationConfig?.responseSchema
        val required = schema?.required ?: emptyList()
        assertEquals(
            listOf("score", "confidence", "communication", "technical", "grammar", "suggestions", "strengths", "weaknesses"),
            required
        )
    }

    @Test
    fun `saveInterview and resumable query round-trip`() = runTest {
        val dao = FakeInterviewDao()
        val repo = InterviewRepositoryImpl(FakeGeminiApi(), dao, json)
        val interview = com.example.aiinterviewapp.domain.model.Interview(
            id = "interview-1",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = 5,
            experience = "Fresher",
            score = 0,
            status = com.example.aiinterviewapp.domain.model.InterviewStatus.STARTED,
            questions = listOf(
                com.example.aiinterviewapp.domain.model.InterviewQuestion(
                    id = "q1",
                    question = "Explain LiveData?",
                    answer = "It's a lifecycle-aware observable."
                )
            )
        )

        repo.saveInterview(interview)
        val resumable = repo.getResumableInterview()

        assertEquals(interview.id, resumable?.id)
        assertEquals(1, resumable?.questions?.size)
        assertEquals("It's a lifecycle-aware observable.", resumable?.questions?.first()?.answer)
    }

    @Test
    fun `completed interview is not returned as resumable`() = runTest {
        val dao = FakeInterviewDao()
        val repo = InterviewRepositoryImpl(FakeGeminiApi(), dao, json)
        val completed = com.example.aiinterviewapp.domain.model.Interview(
            id = "done-1",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = 1,
            experience = "Fresher",
            score = 80,
            status = com.example.aiinterviewapp.domain.model.InterviewStatus.COMPLETED
        )

        repo.saveInterview(completed)

        assertEquals(null, repo.getResumableInterview())
    }
}