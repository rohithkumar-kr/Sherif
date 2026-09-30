package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import com.example.aiinterviewapp.data.repository.ResumeAnalysisRepositoryImpl
import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.model.factualClaims
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds two controlled resumes through the same analysis pipeline and proves
 * the resulting profiles are materially different rather than a fixed answer.
 */
class ResumeGroundingAcrossResumesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Answers with whichever scripted response matches the resume text that
     * actually arrived, so a test that swaps resumes cannot accidentally be
     * answered from the other script.
     */
    private class ScriptedBackendApi(
        private val responses: Map<String, String>
    ) : com.example.aiinterviewapp.data.remote.api.SherifBackendApi {
        val requests = mutableListOf<GeminiRequest>()

        private fun scriptFor(request: GeminiRequest): GeminiResponse {
            requests.add(request)
            val text = request.contents.single().parts.single().text
            val body = responses.entries.first { text.contains(it.key) }.value
            return GeminiResponse(
                candidates = listOf(
                    GeminiResponse.Candidate(
                        content = GeminiResponse.Content(parts = listOf(GeminiResponse.Part(text = body)))
                    )
                )
            )
        }

        override suspend fun analyzeResume(request: GeminiRequest): GeminiResponse = scriptFor(request)
        override suspend fun generateQuestion(request: GeminiRequest): GeminiResponse = scriptFor(request)
        override suspend fun evaluateAnswer(request: GeminiRequest): GeminiResponse = scriptFor(request)
        override suspend fun createSession(
            request: com.example.aiinterviewapp.data.remote.model.BackendSessionRequest
        ) = com.example.aiinterviewapp.data.remote.model.BackendSessionResponse(
            accessToken = "test-access-token",
            userId = "test-user",
            expiresAt = Long.MAX_VALUE
        )

        override suspend fun createDevelopmentSession() =
            com.example.aiinterviewapp.data.remote.model.BackendSessionResponse(
                accessToken = "test-dev-access-token",
                userId = "dev:local",
                expiresAt = Long.MAX_VALUE
            )
    }

    private val resumeA = """
        Aarav Sharma
        Android Developer
        Skills: Kotlin, Jetpack Compose, Room, Android Studio
        Project: Japanese Vocabulary App built with Kotlin and Room
    """.trimIndent()

    private val resumeB = """
        Meera Iyer
        Data Scientist
        Skills: Python, Pandas, TensorFlow, Scikit-learn
        Project: Time Series Forecasting using TensorFlow
    """.trimIndent()

    /** Mirrors what Gemini returns for each resume, including one invented skill. */
    private val analysisA = """
        {
          "candidateName": "Aarav Sharma",
          "targetRole": "Android Developer",
          "summary": "Android developer.",
          "technicalSkills": ["Kotlin", "Jetpack Compose", "Room", "Android Studio"],
          "softSkills": [],
          "education": [],
          "workExperience": [],
          "projects": [{"name": "Japanese Vocabulary App", "technologies": ["Kotlin", "Room"]}],
          "certifications": [],
          "achievements": [],
          "languages": [],
          "strengths": ["Shipped an app"],
          "areasForImprovement": [],
          "missingInformation": ["No education listed"],
          "resumeQuality": {"overallRating": "Adequate"}
        }
    """.trimIndent()

    private val analysisB = """
        {
          "candidateName": "Meera Iyer",
          "targetRole": "Data Scientist",
          "summary": "Data scientist.",
          "technicalSkills": ["Python", "Pandas", "TensorFlow", "Scikit-learn", "Kubernetes"],
          "softSkills": [],
          "education": [],
          "workExperience": [],
          "projects": [{"name": "Time Series Forecasting", "technologies": ["TensorFlow"]}],
          "certifications": [],
          "achievements": [],
          "languages": [],
          "strengths": ["Modelling experience"],
          "areasForImprovement": [],
          "missingInformation": ["No education listed"],
          "resumeQuality": {"overallRating": "Adequate"}
        }
    """.trimIndent()

    @Test
    fun `two different resumes produce materially different profiles`() = runTest {
        val api = ScriptedBackendApi(
            mapOf(
                "Japanese Vocabulary App" to analysisA,
                "Time Series Forecasting" to analysisB
            )
        )
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val a: ResumeAnalysis = repository.analyzeResume(resumeA).getOrThrow()
        val b: ResumeAnalysis = repository.analyzeResume(resumeB).getOrThrow()

        // 1. Candidate identity differs
        assertEquals("Aarav Sharma", a.profile.candidateName)
        assertEquals("Meera Iyer", b.profile.candidateName)
        assertNotEquals(a.profile.candidateName, b.profile.candidateName)

        // 2. Role differs
        assertEquals("Android Developer", a.profile.targetRole)
        assertEquals("Data Scientist", b.profile.targetRole)
        assertNotEquals(a.profile.targetRole, b.profile.targetRole)

        // 3. Skill sets differ, with no overlap
        assertTrue(a.profile.technicalSkills.containsAll(listOf("Kotlin", "Jetpack Compose", "Room")))
        assertTrue(b.profile.technicalSkills.containsAll(listOf("Python", "Pandas", "TensorFlow")))
        assertTrue("skill sets must not overlap", a.profile.technicalSkills.intersect(b.profile.technicalSkills.toSet()).isEmpty())

        // 4. Projects differ and neither leaks into the other
        assertTrue(a.profile.projects.single().name!!.contains("Japanese Vocabulary"))
        assertTrue(b.profile.projects.single().name!!.contains("Time Series"))
        assertTrue(a.profile.projects.none { it.name!!.contains("Time Series") })
        assertTrue(b.profile.projects.none { it.name!!.contains("Japanese Vocabulary") })

        // 5. Whole profiles are not identical
        assertNotEquals(a.profile.factualClaims, b.profile.factualClaims)
        assertNotEquals(a.profile, b.profile)
    }

    @Test
    fun `a skill invented for resume B is not kept as fact`() = runTest {
        val api = ScriptedBackendApi(mapOf("Time Series Forecasting" to analysisB))
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        val b = repository.analyzeResume(resumeB).getOrThrow()

        assertFalse(
            "Kubernetes is not in resume B and must not be recorded as a skill",
            b.profile.technicalSkills.contains("Kubernetes")
        )
        assertTrue(b.unsupportedClaims.contains("Kubernetes"))
    }

    @Test
    fun `each analysis request carries its own resume text`() = runTest {
        val api = ScriptedBackendApi(
            mapOf(
                "Japanese Vocabulary App" to analysisA,
                "Time Series Forecasting" to analysisB
            )
        )
        val repository = ResumeAnalysisRepositoryImpl(api, json)

        repository.analyzeResume(resumeA)
        repository.analyzeResume(resumeB)

        val first = api.requests[0].contents.single().parts.single().text
        val second = api.requests[1].contents.single().parts.single().text
        assertTrue(first.contains("Jetpack Compose"))
        assertFalse(first.contains("TensorFlow"))
        assertTrue(second.contains("TensorFlow"))
        assertFalse(second.contains("Jetpack Compose"))
    }
}
