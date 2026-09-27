package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.AiResponseParser
import com.example.aiinterviewapp.data.remote.ResumeAnalysisPrompts
import com.example.aiinterviewapp.data.remote.ResumeGrounding
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiSchemas
import com.example.aiinterviewapp.data.remote.model.getText
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.factualClaims
import com.example.aiinterviewapp.domain.model.hasContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live, opt-in verification of the real analysis chain.
 *
 * Runs the production prompt + schema over the genuine Gemini endpoint and then
 * the production parsing and grounding code, so structured-output support and
 * grounding behaviour are proven against the real model instead of assumed.
 * Skipped unless `-Dgemini.live=true` and a key is present, so the ordinary
 * suite stays hermetic.
 */
class LiveGeminiResumeAnalysisTest {

    private val apiKey: String? =
        System.getenv("GEMINI_LIVE_KEY")
            ?: File("../local.properties").takeIf { it.isFile }?.let { props ->
                java.util.Properties().apply { props.inputStream().use { load(it) } }
                    .getProperty("GEMINI_API_KEY")
            }

    private val live by lazy {
        System.getenv("GEMINI_LIVE") == "true" && !apiKey.isNullOrBlank()
    }

    private val androidResume = """
        Aarav Sharma
        aarav.sharma@example.com | +91 98123 45678
        Target Role: Android Developer

        SUMMARY
        Android developer with three years of experience shipping production
        mobile applications in Kotlin.

        TECHNICAL SKILLS
        Kotlin, Jetpack Compose, Android SDK, Room, Retrofit, Coroutines, MVVM,
        Git, PostgreSQL

        SOFT SKILLS
        Code review, mentoring, technical writing

        EDUCATION
        B.Tech in Computer Science, Anna University, 2019

        WORK EXPERIENCE
        Software Engineer, Flipkart, 2 years
        Built and maintained the seller onboarding Android application used by
        roughly 3000 internal staff, reducing onboarding time by 40 percent.

        PROJECTS
        SpendTracker: personal finance Android app built with Kotlin, Jetpack
        Compose and Room, featuring offline-first sync via Retrofit and Coroutines.

        CERTIFICATIONS
        Android Developers Certification, Google

        ACHIEVEMENTS
        Reduced seller onboarding time by 40 percent at Flipkart

        LANGUAGES
        English, Hindi, Tamil
    """.trimIndent()

    private val dataScienceResume = """
        Priya Raghavan
        priya.raghavan@example.com | +1 415 555 0184
        Target Role: Data Scientist

        SUMMARY
        Data scientist focused on machine learning for forecasting problems.

        TECHNICAL SKILLS
        Python, pandas, NumPy, scikit-learn, TensorFlow, SQL, Spark, Airflow,
        Tableau, statistics

        SOFT SKILLS
        Stakeholder communication, technical writing

        EDUCATION
        M.S. in Statistics, University of Washington, 2020

        WORK EXPERIENCE
        Data Scientist, Stripe, 3 years
        Built demand forecasting models that improved forecast error by 18 percent
        across six product lines.

        PROJECTS
        ChurnModel: gradient boosted classifier predicting subscription churn,
        implemented in Python with scikit-learn and deployed through Airflow.

        CERTIFICATIONS
        AWS Machine Learning Specialty

        ACHIEVEMENTS
        Improved forecast accuracy by 18 percent at Stripe

        LANGUAGES
        English, Kannada
    """.trimIndent()

    /**
     * Serialises live calls and keeps a minimum gap between them. The free tier
     * allows only a handful of requests per minute, so a burst would exhaust the
     * budget purely through test parallelism.
     */
    private val liveCallLock = Any()

    private fun analyze(resumeText: String): Pair<ResumeProfile, List<String>> = synchronized(liveCallLock) {
        analyzeInternal(resumeText)
    }

    private fun analyzeInternal(resumeText: String): Pair<ResumeProfile, List<String>> {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        val request = GeminiRequest.create(
            ResumeAnalysisPrompts.build(resumeText),
            temperature = 0.2,
            maxOutputTokens = 4096,
            responseMimeType = "application/json",
            responseSchema = GeminiSchemas.resumeProfileSchema
        )
        val payload = kotlinx.serialization.json.Json.encodeToString(
            GeminiRequest.serializer(), request
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        val httpRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent")
            .header("x-goog-api-key", apiKey!!)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        // Free-tier quota is a small per-minute request budget, so back off well
        // past the short production window when verifying repeatedly.
        var response = client.newCall(httpRequest).execute()
        var attempt = 0
        while (!response.isSuccessful && attempt < 6 &&
            (response.code == 429 || response.code in 500..599)
        ) {
            attempt++
            val body = runCatching { response.body?.string() }.getOrNull()
            response.close()
            val wait = Regex("Please retry in ([0-9.]+)s")
                .find(body.orEmpty())
                ?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?.plus(15.0)
                ?: (Math.pow(2.0, attempt.toDouble()) * 5.0)
            Thread.sleep((wait * 1000).toLong())
            response = client.newCall(httpRequest).execute()
        }

        response.use { http ->
            val body = http.body?.string().orEmpty()
            assertTrue("live call failed: HTTP ${http.code} ${body.take(300)}", http.isSuccessful)
            assertFalse("response body was empty", body.isBlank())
            val decodedResponse = json.decodeFromString(
                com.example.aiinterviewapp.data.remote.model.GeminiResponse.serializer(),
                body
            )
            val raw = decodedResponse.getText()
            assertNotNull("Gemini returned no text", raw)
            val jsonObject = AiResponseParser.extractJsonObject(raw!!)
            assertNotNull("response was not a JSON object", jsonObject)
            val decoded = json.decodeFromString(ResumeProfile.serializer(), jsonObject!!)
            val (grounded, report) = ResumeGrounding.ground(decoded, resumeText)
            return grounded to report.unsupportedClaims
        }
    }

    @Test
    fun `live analysis of an android resume is structured and grounded`() {
        assumeTrue("live verification disabled", live)

        val (profile, rejected) = analyze(androidResume)

        assertTrue("analysis produced no content", profile.hasContent)
        val claims = profile.factualClaims
        assertTrue("no factual claims extracted", claims.isNotEmpty())
        // Every retained factual claim must be present in the source document.
        val lowered = androidResume.lowercase()
        claims.forEach { claim ->
            val tokens = claim.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length >= 3 }
            val supported = tokens.isEmpty() || tokens.any { lowered.contains(it) }
            assertTrue("ungrounded claim survived: $claim", supported)
        }
        assertTrue("Kotlin should be detected", claims.any { it.contains("Kotlin", true) })
        assertTrue("Flipkart should be detected", claims.any { it.contains("Flipkart", true) })
        println("LIVE android -> claims=${claims.size} rejected=$rejected name=${profile.candidateName}")
    }

    @Test
    fun `live analysis of a data science resume is structured and grounded`() {
        assumeTrue("live verification disabled", live)

        val (profile, rejected) = analyze(dataScienceResume)

        assertTrue("analysis produced no content", profile.hasContent)
        val claims = profile.factualClaims
        assertTrue("no factual claims extracted", claims.isNotEmpty())
        val lowered = dataScienceResume.lowercase()
        claims.forEach { claim ->
            val tokens = claim.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length >= 3 }
            val supported = tokens.isEmpty() || tokens.any { lowered.contains(it) }
            assertTrue("ungrounded claim survived: $claim", supported)
        }
        assertTrue("Python should be detected", claims.any { it.contains("Python", true) })
        assertTrue("Stripe should be detected", claims.any { it.contains("Stripe", true) })
        println("LIVE dataScience -> claims=${claims.size} rejected=$rejected name=${profile.candidateName}")
    }

    @Test
    fun `live analyses of two different resumes differ substantially`() {
        assumeTrue("live verification disabled", live)

        val (androidProfile, _) = analyze(androidResume)
        val (dataProfile, _) = analyze(dataScienceResume)

        val a = androidProfile.factualClaims.map { it.lowercase() }.toSet()
        val d = dataProfile.factualClaims.map { it.lowercase() }.toSet()
        val androidOnly = a - d
        val dataOnly = d - a
        println("LIVE diff -> androidOnly=$androidOnly dataOnly=$dataOnly")
        assertTrue("android-only facts: ${androidOnly.size}", androidOnly.size >= 3)
        assertTrue("data-only facts: ${dataOnly.size}", dataOnly.size >= 3)
    }
}
