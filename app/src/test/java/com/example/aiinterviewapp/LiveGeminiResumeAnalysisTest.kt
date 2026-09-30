package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.AiResponseParser
import com.example.aiinterviewapp.data.remote.ResumeAnalysisPrompts
import com.example.aiinterviewapp.data.remote.ResumeGrounding
import com.example.aiinterviewapp.data.remote.model.BackendSessionRequest
import com.example.aiinterviewapp.data.remote.model.BackendSessionResponse
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import com.example.aiinterviewapp.data.remote.model.GeminiSchemas
import com.example.aiinterviewapp.data.remote.model.getText
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.factualClaims
import com.example.aiinterviewapp.domain.model.hasContent
import java.io.File
import java.util.concurrent.TimeUnit
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

/**
 * Live, opt-in verification of the real analysis chain.
 *
 * Phase 2 ran the production prompt and schema straight at
 * `generativelanguage.googleapis.com` with a key read from `local.properties`.
 * That is no longer a path the app can take: the Gemini credential is backend
 * only, and shipping it in a test would recreate the very leak Phase 3 removed.
 *
 * So this test now speaks the app's real protocol -- authenticate, then call the
 * analysis endpoint -- and asserts the same grounding properties it always did.
 * It exercises Android -> authenticated backend -> Gemini end to end, which is
 * strictly more useful than the old direct call, because a broken auth path or
 * a missing endpoint now fails here.
 *
 * Skipped unless the environment is configured:
 *   SHERIF_LIVE=true                      opt in
 *   SHERIF_API_BASE_URL                   e.g. http://127.0.0.1:8080/
 *   SHERIF_LIVE_ID_TOKEN                  a Google ID token, or
 *   GEMINI_ID_TOKEN + GOOGLE_CLIENT_ID    for the local backend test identity path
 */
class LiveGeminiResumeAnalysisTest {

    private val properties = java.util.Properties().apply {
        val file = File("../local.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

    private fun setting(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: properties.getProperty(name)?.takeIf { it.isNotBlank() }

    private val baseUrl: String? = setting("SHERIF_API_BASE_URL")

    /**
     * The identity token presented to the backend.
     *
     * A real Google ID token is the intended input. When testing a locally
     * started backend, the backend's test identity verifier accepts an opaque
     * `id:*` token instead, which is how the loop closes without needing a live
     * Google account.
     */
    private val idToken: String? = setting("SHERIF_LIVE_ID_TOKEN")
        ?: setting("GEMINI_ID_TOKEN")?.let { token ->
            setting("GOOGLE_CLIENT_ID")?.let { "$token $it" }
        }
        ?: setting("SHERIF_LIVE_TEST_ID_TOKEN")

    private val live: Boolean =
        setting("SHERIF_LIVE") == "true" &&
            !baseUrl.isNullOrBlank() &&
            !idToken.isNullOrBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

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
     * Serialises live calls and keeps a minimum gap between them, so test
     * parallelism cannot exhaust the request budget on its own.
     */
    private val liveCallLock = Any()

    /**
     * Exchanges the identity token for a SHERIF session.
     *
     * Done once per JVM and reused, so the three tests cost one session exchange
     * rather than three.
     */
    private val accessToken: String by lazy { openSession() }

    private fun openSession(): String {
        val payload = json.encodeToString(
            BackendSessionRequest.serializer(),
            BackendSessionRequest(idToken = idToken!!)
        )
        val request = Request.Builder()
            .url(baseUrl!!.trimEnd('/') + "/v1/auth/session")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(
                "session exchange failed: HTTP ${response.code} ${body.take(300)}",
                response.isSuccessful
            )
            // The backend, not the client, decides who this is. The app scopes
            // local data by whatever the response says, so a successful exchange
            // must always carry a non-blank identity.
            val session = json.decodeFromString(BackendSessionResponse.serializer(), body)
            assertTrue("backend returned a blank userId", session.userId.isNotBlank())
            assertTrue("backend returned a blank access token", session.accessToken.isNotBlank())
            assertTrue("session must have a finite expiry", session.expiresAt > 0)
            return session.accessToken
        }
    }

    private fun analyze(resumeText: String): Pair<ResumeProfile, List<String>> =
        synchronized(liveCallLock) { analyzeInternal(resumeText) }

    private fun analyzeInternal(resumeText: String): Pair<ResumeProfile, List<String>> {
        val request = GeminiRequest.create(
            ResumeAnalysisPrompts.build(resumeText),
            temperature = 0.2,
            maxOutputTokens = 4096,
            responseMimeType = "application/json",
            responseSchema = GeminiSchemas.resumeProfileSchema
        )
        val payload = json.encodeToString(GeminiRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url(baseUrl!!.trimEnd('/') + "/v1/resume/analyze")
            // The credential is a SHERIF session issued by the backend, not a
            // Google key. This is the whole Phase 3 arrangement in one header.
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        // A per-user rate limit exists now, so a failure here is as likely to be
        // throttling as an upstream fault. Back off on both.
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
            // The backend must never relay its own Gemini credential, whatever
            // happens upstream.
            assertFalse("response leaked a credential", body.contains("x-goog-api-key"))
            val decodedResponse = json.decodeFromString(GeminiResponse.serializer(), body)
            val raw = decodedResponse.getText()
            assertNotNull("backend returned no text", raw)
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
