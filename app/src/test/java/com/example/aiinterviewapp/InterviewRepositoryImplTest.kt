package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.local.dao.InterviewDao
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import com.example.aiinterviewapp.data.repository.InterviewRepositoryImpl
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.InterviewStatus
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

/**
 * Behaviour of the interview repository against a faked SHERIF backend.
 *
 * The fake answers all three endpoints, so these tests stay focused on parsing
 * and prompt construction. Which endpoint each operation uses is asserted
 * separately in the isolation tests.
 */
class InterviewRepositoryImplTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * An in-memory DAO that honours the `userId` argument on every call.
     *
     * Filtering here as well as in the repository is deliberate: it means a
     * repository that passes the wrong user id produces a visibly wrong result
     * rather than silently returning everything.
     */
    private class FakeInterviewDao(
        private val initial: List<InterviewEntity> = emptyList()
    ) : InterviewDao {
        val state = MutableStateFlow(initial)

        override fun getCompletedInterviews(userId: String): Flow<List<InterviewEntity>> =
            state.map { rows -> rows.filter { it.userId == userId } }

        override suspend fun getInterviewById(id: String, userId: String): InterviewEntity? =
            state.value.firstOrNull { it.id == id && it.userId == userId }

        override suspend fun getResumableInterview(userId: String): InterviewEntity? =
            state.value.firstOrNull { it.userId == userId && it.status == "STARTED" }

        override fun getResumableInterviewFlow(userId: String): Flow<InterviewEntity?> =
            state.map { rows -> rows.firstOrNull { it.userId == userId && it.status == "STARTED" } }

        override suspend fun insertInterview(interview: InterviewEntity) {
            state.value = state.value.filterNot { it.id == interview.id } + interview
        }

        override suspend fun deleteInterview(id: String, userId: String) {
            state.value = state.value.filterNot { it.id == id && it.userId == userId }
        }
    }

    private fun repository(
        api: FakeSherifBackendApi = FakeSherifBackendApi(),
        dao: InterviewDao = FakeInterviewDao(),
        userId: String? = "user-a"
    ) = InterviewRepositoryImpl(api, dao, fakeSessionStore(userId), json)

    @Test
    fun `generateQuestion parses structured JSON question`() = runTest {
        val api = FakeSherifBackendApi { geminiResponseOf("""{"question": "Explain how LiveData works."}""") }

        val result = repository(api).generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isSuccess)
        assertEquals("Explain how LiveData works.", result.getOrNull())
    }

    @Test
    fun `generateQuestion falls back to plain text response`() = runTest {
        val api = FakeSherifBackendApi { geminiResponseOf("What is a ViewModel?") }

        val result = repository(api).generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isSuccess)
        assertEquals("What is a ViewModel?", result.getOrNull())
    }

    @Test
    fun `generateQuestion requests JSON mime type and schema`() = runTest {
        val api = FakeSherifBackendApi { geminiResponseOf("""{"question": "Q?"}""") }

        repository(api).generateQuestion("Android Developer", "Fresher", "Medium", "Technical", emptyList())

        val config = api.requests.single().generationConfig
        assertEquals("application/json", config?.responseMimeType)
        assertEquals("OBJECT", config?.responseSchema?.type)
        assertTrue(config?.responseSchema?.required?.contains("question") == true)
    }

    @Test
    fun `question generation uses the interview question endpoint`() = runTest {
        val api = FakeSherifBackendApi { geminiResponseOf("""{"question": "Q?"}""") }

        repository(api).generateQuestion("R", "Fresher", "Medium", "Technical", emptyList())

        assertEquals(listOf("interview/question"), api.endpointsUsed)
    }

    @Test
    fun `evaluateAnswer parses evaluation from structured output`() = runTest {
        val raw = """{"score":8,"confidence":7,"communication":6,"technical":9,"grammar":7,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val api = FakeSherifBackendApi { geminiResponseOf(raw) }

        val result = repository(api).evaluateAnswer("Question?", "Answer")

        assertTrue(result.isSuccess)
        val evaluation: QuestionEvaluation = result.getOrThrow()
        assertEquals(8, evaluation.score)
        assertEquals("s", evaluation.suggestions)
    }

    @Test
    fun `evaluateAnswer clamps out-of-range scores`() = runTest {
        val raw = """{"score":99,"confidence":-1,"communication":5,"technical":5,"grammar":5,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val api = FakeSherifBackendApi { geminiResponseOf(raw) }

        val evaluation = repository(api).evaluateAnswer("Q", "A").getOrThrow()

        assertEquals(10, evaluation.score)
        assertEquals(0, evaluation.confidence)
    }

    @Test
    fun `evaluateAnswer fails on malformed response`() = runTest {
        val api = FakeSherifBackendApi { geminiResponseOf("no json here") }

        val result = repository(api).evaluateAnswer("Q", "A")

        assertTrue(result.isFailure)
    }

    @Test
    fun `answer evaluation uses the interview evaluate endpoint`() = runTest {
        val api = FakeSherifBackendApi {
            geminiResponseOf("""{"score":7,"confidence":6,"communication":6,"technical":7,"grammar":8,"suggestions":"s","strengths":"st","weaknesses":"w"}""")
        }

        repository(api).evaluateAnswer("Q", "A")

        assertEquals(listOf("interview/evaluate"), api.endpointsUsed)
    }

    @Test
    fun `generateQuestion maps http 429 to rate limit message`() = runTest {
        val api = FakeSherifBackendApi(
            error = HttpException(Response.error<Any>(429, "{}".toResponseBody()))
        )

        val failure = repository(api)
            .generateQuestion("R", "Fresher", "Medium", "Technical", emptyList())
            .exceptionOrNull()

        assertTrue(failure?.message?.contains("Too many requests") == true)
    }

    @Test
    fun `generateQuestion maps io exception to network message`() = runTest {
        val api = FakeSherifBackendApi(error = IOException("socket timeout"))

        val failure = repository(api)
            .generateQuestion("R", "Fresher", "Medium", "Technical", emptyList())
            .exceptionOrNull()

        assertTrue(failure?.message?.contains("temporarily unavailable") == true)
    }

    @Test
    fun `generateQuestion fails on empty candidates response`() = runTest {
        val api = FakeSherifBackendApi { com.example.aiinterviewapp.data.remote.model.GeminiResponse(candidates = emptyList()) }

        val result = repository(api).generateQuestion("R", "Fresher", "Medium", "Technical", emptyList())

        assertTrue(result.isFailure)
    }

    @Test
    fun `evaluateAnswer sends evaluation schema with required fields`() = runTest {
        val api = FakeSherifBackendApi {
            geminiResponseOf("""{"score":7,"confidence":6,"communication":6,"technical":7,"grammar":8,"suggestions":"s","strengths":"st","weaknesses":"w"}""")
        }

        repository(api).evaluateAnswer("Q", "A")

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
        val interview = Interview(
            id = "interview-1",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = 5,
            experience = "Fresher",
            score = 0,
            status = InterviewStatus.STARTED,
            questions = listOf(
                InterviewQuestion(
                    id = "q1",
                    question = "Explain LiveData?",
                    answer = "It's a lifecycle-aware observable."
                )
            )
        )

        repository(dao = dao).saveInterview(interview)
        val resumable = repository(dao = dao).getResumableInterview()

        assertEquals(interview.id, resumable?.id)
        assertEquals(1, resumable?.questions?.size)
        assertEquals("It's a lifecycle-aware observable.", resumable?.questions?.first()?.answer)
    }

    @Test
    fun `completed interview is not returned as resumable`() = runTest {
        val dao = FakeInterviewDao()
        val completed = Interview(
            id = "done-1",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = 1,
            experience = "Fresher",
            score = 80,
            status = InterviewStatus.COMPLETED
        )

        val repo = repository(dao = dao)
        repo.saveInterview(completed)

        assertEquals(null, repo.getResumableInterview())
    }
}
