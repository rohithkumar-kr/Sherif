package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import com.example.aiinterviewapp.data.repository.ResumeAnalysisRepositoryImpl
import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody

class ResumeAnalysisRepositoryImplTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }


    private fun responseWith(text: String) = GeminiResponse(
        candidates = listOf(
            GeminiResponse.Candidate(
                content = GeminiResponse.Content(parts = listOf(GeminiResponse.Part(text = text)))
            )
        )
    )

    private val androidResume = """
        Aarav Sharma
        Android Developer
        Skills: Kotlin, Jetpack Compose, Room, Android Studio
        Project: Japanese Vocabulary App built with Kotlin and Room
        Education: B.Tech Computer Science, Anna University, 2024
    """.trimIndent()

    private val validAndroidAnalysis = """
        {
          "candidateName": "Aarav Sharma",
          "targetRole": "Android Developer",
          "summary": "Android developer who has shipped a Kotlin and Room application.",
          "technicalSkills": ["Kotlin", "Jetpack Compose", "Room", "Android Studio"],
          "softSkills": [],
          "education": [
            {"institution": "Anna University", "degree": "B.Tech", "field": "Computer Science", "graduationYear": "2024"}
          ],
          "workExperience": [],
          "projects": [
            {"name": "Japanese Vocabulary App", "technologies": ["Kotlin", "Room"]}
          ],
          "certifications": [],
          "achievements": [],
          "languages": [],
          "strengths": ["Shipped a complete app"],
          "areasForImprovement": ["No measurable metrics"],
          "missingInformation": ["No work experience listed"],
          "resumeQuality": {"overallRating": "Adequate", "notes": "Add measurable impact."}
        }
    """.trimIndent()

    @Test
    fun `actual resume text reaches the backend in the analysis request`() = runTest {
        val token = "UNIQUE_RESUME_TEST_TOKEN_847291"
        val api = FakeSherifBackendApi { responseWith(validAndroidAnalysis) }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        repository.analyzeResume("$androidResume\nReference: $token")

        val sentText = api.requests.single().contents.single().parts.single().text
        assertTrue(
            "Captured request did not contain the resume token",
            sentText.contains(token)
        )
        assertTrue(sentText.contains("Japanese Vocabulary App"))
        assertTrue(sentText.contains("Kotlin"))
    }

    @Test
    fun `analysis uses its own dedicated schema and not the question schema`() = runTest {
        val api = FakeSherifBackendApi { responseWith(validAndroidAnalysis) }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        repository.analyzeResume(androidResume)

        val schema = api.requests.single().generationConfig?.responseSchema
        assertNotNull(schema)
        assertTrue(schema!!.properties!!.containsKey("technicalSkills"))
        assertTrue(schema.properties!!.containsKey("resumeQuality"))
        assertTrue("analysis must not be a question-shaped request", !schema.properties!!.containsKey("question"))
    }

    @Test
    fun `analysis goes to the resume endpoint, never the interview ones`() = runTest {
        val api = FakeSherifBackendApi { responseWith(validAndroidAnalysis) }

        ResumeAnalysisRepositoryImpl(api, json).analyzeResume(androidResume)

        assertEquals(listOf("resume/analyze"), api.endpointsUsed)
    }

    @Test
    fun `valid analysis response produces a grounded profile`() = runTest {
        val api = FakeSherifBackendApi { responseWith(validAndroidAnalysis) }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue(result.isSuccess)
        val analysis: ResumeAnalysis = result.getOrThrow()
        assertEquals("Aarav Sharma", analysis.profile.candidateName)
        assertEquals("Japanese Vocabulary App", analysis.profile.projects.single().name)
        assertTrue(analysis.profile.technicalSkills.containsAll(listOf("Kotlin", "Jetpack Compose", "Room")))
    }

    @Test
    fun `malformed analysis response fails without fabricating a profile`() = runTest {
        val api = FakeSherifBackendApi { responseWith("I'm sorry, I cannot help with that.") }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue("malformed response must be a failure", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun `truncated json fails without fabricating a profile`() = runTest {
        val api = FakeSherifBackendApi { responseWith("""{"candidateName": "Aarav", "technicalSkills": ["Ko""") }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue(result.isFailure)
    }

    @Test
    fun `empty analysis response fails without fabricating a profile`() = runTest {
        val api = FakeSherifBackendApi { GeminiResponse(candidates = emptyList()) }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue("empty response must be a failure", result.isFailure)
    }

    @Test
    fun `blank resume text fails before any backend call`() = runTest {
        val api = FakeSherifBackendApi { responseWith(validAndroidAnalysis) }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume("   ")

        assertTrue(result.isFailure)
        assertTrue("no request should be sent for blank input", api.requests.isEmpty())
    }

    @Test
    fun `an analysis with nothing verifiable fails rather than returning an empty profile`() = runTest {
        val api = FakeSherifBackendApi {
            responseWith("""{"candidateName": "", "summary": "", "technicalSkills": [], "strengths": []}""")
        }
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue(result.isFailure)
    }

    @Test
    fun `backend HTTP failure produces a mapped error and no profile`() = runTest {
        val api = FakeSherifBackendApi(error = HttpException(Response.error<Any>(429, "{}".toResponseBody())))
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Too many requests"))
    }

    @Test
    fun `network failure produces a mapped error and no profile`() = runTest {
        val api = FakeSherifBackendApi(error = IOException("socket timeout"))
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val result = repository.analyzeResume(androidResume)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("temporarily unavailable"))
    }
}
