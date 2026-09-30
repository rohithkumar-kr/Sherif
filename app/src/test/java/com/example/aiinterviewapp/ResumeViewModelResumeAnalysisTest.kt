package com.example.aiinterviewapp

import android.net.Uri
import com.example.aiinterviewapp.data.local.datastore.ScopedResumeText
import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import com.example.aiinterviewapp.domain.model.ResumeExtraction
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import com.example.aiinterviewapp.domain.repository.ResumeTextExtractor
import com.example.aiinterviewapp.domain.usecase.AnalyzeResumeUseCase
import com.example.aiinterviewapp.ui.screens.resume.ResumeStage
import com.example.aiinterviewapp.ui.screens.resume.ResumeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeViewModelResumeAnalysisTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var viewModelRef: ResumeViewModel
    private lateinit var textSource: RecordingTextExtractor
    private lateinit var store: RecordingProfileStore
    private lateinit var analysisRepository: ScriptedAnalysisRepository
    private lateinit var scopedResumeText: ScopedResumeText
    private lateinit var resumeText: MutableStateFlow<String?>

    private val androidProfile = ResumeProfile(
        candidateName = "Aarav Sharma",
        targetRole = "Android Developer",
        technicalSkills = listOf("Kotlin", "Jetpack Compose", "Room"),
        summary = "Android developer."
    )

    private val dataScienceProfile = ResumeProfile(
        candidateName = "Meera Iyer",
        targetRole = "Data Scientist",
        technicalSkills = listOf("Python", "Pandas", "TensorFlow"),
        summary = "Data scientist."
    )

    private val androidText = "Aarav Sharma Android Developer Kotlin Jetpack Compose Room"
    private val dataScienceText = "Meera Iyer Data Scientist Python Pandas TensorFlow"

    private fun uri(): Uri = Mockito.mock(Uri::class.java)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun build(text: String = androidText) {
        textSource = RecordingTextExtractor(text) { viewModelRef.uiState.value.stage }
        store = RecordingProfileStore()
        analysisRepository = ScriptedAnalysisRepository { viewModelRef.uiState.value.stage }
        scopedResumeText = mock()
        resumeText = MutableStateFlow(null)
        whenever(scopedResumeText.currentUserResumeText).thenReturn(resumeText)

        viewModelRef = ResumeViewModel(
            resumeTextExtractor = textSource,
            scopedResumeText = scopedResumeText,
            analyzeResume = AnalyzeResumeUseCase(analysisRepository),
            profileStore = store
        )
    }

    // --- AC-21 / AC-22: distinct extraction and AI analysis states -----------

    @Test
    fun `extraction state is reported as extraction and not as AI analysis`() = runTest(dispatcher) {
        build()
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(ResumeStage.EXTRACTING, textSource.stageSeenInsideExtract)
        assertTrue("extraction must not claim AI analysis", textSource.stageSeenInsideExtract != ResumeStage.ANALYZING)
    }

    @Test
    fun `the AI analysis state corresponds to an actual Gemini call`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(ResumeStage.ANALYZING, analysisRepository.stageSeenInsideAnalysis)
        assertEquals(1, analysisRepository.calls)
    }

    @Test
    fun `extraction and analysis are two distinct steps`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(1, textSource.calls)
        assertEquals(1, analysisRepository.calls)
    }

    // --- AC-23 / AC-24: success and error states ----------------------------

    @Test
    fun `a successful analysis exposes the profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(androidProfile, viewModelRef.uiState.value.profile)
        assertTrue(viewModelRef.uiState.value.hasProfile)
        assertNull(viewModelRef.uiState.value.error)
        assertEquals(ResumeStage.IDLE, viewModelRef.uiState.value.stage)
    }

    @Test
    fun `a failed analysis shows an error, no profile, and offers retry`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.failure(IllegalStateException("Gemini is temporarily unavailable. Please try again.")) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertNotNull(viewModelRef.uiState.value.error)
        assertNull("a failed analysis must not show a profile", viewModelRef.uiState.value.profile)
        assertTrue("retry must be offered", viewModelRef.uiState.value.canRetry)
    }

    @Test
    fun `an unreadable pdf surfaces an error and never reaches Gemini`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()

        val failing = object : ResumeTextExtractor {
            override suspend fun extract(
                uri: Uri,
                onStage: (ResumeTextExtractor.ExtractionStage) -> Unit
            ): Result<ResumeExtraction> = Result.failure(ResumeExtractionException.CorruptDocument())
        }
        val failingViewModel = ResumeViewModel(
            resumeTextExtractor = failing,
            scopedResumeText = scopedResumeText,
            analyzeResume = AnalyzeResumeUseCase(analysisRepository),
            profileStore = store
        )
        viewModelRef = failingViewModel

        failingViewModel.uploadResume(uri())
        advanceUntilIdle()

        assertEquals("Unable to read this PDF.", failingViewModel.uiState.value.error)
        assertEquals(0, analysisRepository.calls)
    }

    @Test
    fun `a text-less pdf reports missing text and does not analyse`() = runTest(dispatcher) {
        build(text = "   ")
        // With OCR in place, a text-less PDF is routed to OCR rather than
        // rejected outright; the extractor owns that decision. This covers the
        // case where OCR also produced nothing usable.
        textSource.failure = ResumeExtractionException.NoReadableContent()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(
            "No readable resume content was found.",
            viewModelRef.uiState.value.error
        )
        assertEquals(0, analysisRepository.calls)
        assertNull(viewModelRef.uiState.value.profile)
    }

    // --- AC-24 / AC-29: retry -----------------------------------------------

    @Test
    fun `retry issues a fresh Gemini request and can then succeed`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.failure(IllegalStateException("Gemini is temporarily unavailable. Please try again.")) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()
        assertEquals(1, analysisRepository.calls)

        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        viewModelRef.retry()
        advanceUntilIdle()

        assertEquals(2, analysisRepository.calls)
        assertEquals(androidProfile, viewModelRef.uiState.value.profile)
        assertNull(viewModelRef.uiState.value.error)
    }

    @Test
    fun `repeated retries never produce duplicate profiles`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        viewModelRef.reanalyze()
        advanceUntilIdle()
        viewModelRef.reanalyze()
        advanceUntilIdle()

        assertEquals(3, analysisRepository.calls)
        assertEquals("only one profile is active at a time", androidProfile, store.active)
        assertEquals("a retry overwrites rather than accumulates", 1, store.currentCount)
        assertEquals(androidProfile, viewModelRef.uiState.value.profile)
    }

    // --- AC-25: re-analysis -------------------------------------------------

    @Test
    fun `re-analyze makes a new request and shows the loading state`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        // Hold the Gemini call open so the in-flight state can be observed.
        val gate = CompletableDeferred<Unit>()
        analysisRepository.gate = gate
        viewModelRef.reanalyze()
        testScheduler.runCurrent()

        assertEquals(ResumeStage.ANALYZING, viewModelRef.uiState.value.stage)
        assertTrue(viewModelRef.uiState.value.isAnalyzing)
        assertEquals("the old profile stays visible while re-analysing", androidProfile, viewModelRef.uiState.value.profile)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, analysisRepository.calls)
        assertEquals(ResumeStage.IDLE, viewModelRef.uiState.value.stage)
    }

    @Test
    fun `a failed re-analysis keeps the previous successful profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        analysisRepository.result = { Result.failure(IllegalStateException("Gemini is temporarily unavailable. Please try again.")) }
        viewModelRef.reanalyze()
        advanceUntilIdle()

        assertEquals("the old profile must survive a failed retry", androidProfile, viewModelRef.uiState.value.profile)
        assertNotNull(viewModelRef.uiState.value.error)
    }

    // --- AC-19 / AC-20: replacement ----------------------------------------

    @Test
    fun `uploading a second resume replaces the first profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        textSource.nextText = dataScienceText
        analysisRepository.result = { Result.success(ResumeAnalysis(dataScienceProfile)) }
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        val state = viewModelRef.uiState.value
        assertEquals(dataScienceProfile, state.profile)
        assertTrue(state.profile!!.technicalSkills.contains("TensorFlow"))
        assertFalse("no Android skill from resume A may remain", state.profile!!.technicalSkills.contains("Room"))
        assertFalse(state.isProfileStale)
    }

    @Test
    fun `a failed replacement preserves the previous successful profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        textSource.nextText = dataScienceText
        analysisRepository.result = { Result.failure(IllegalStateException("Gemini is temporarily unavailable. Please try again.")) }
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        val state = viewModelRef.uiState.value
        assertEquals("resume A's analysis must survive", androidProfile, state.profile)
        assertNotNull(state.error)
        assertTrue("the user must be told the profile is from the old resume", state.isProfileStale)
    }

    @Test
    fun `a failed extraction preserves the previous successful profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        textSource.failure = IllegalStateException("Could not read this PDF")
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        assertEquals(androidProfile, viewModelRef.uiState.value.profile)
        assertNotNull(viewModelRef.uiState.value.error)
    }

    // --- AC-18: the existing resume text write is preserved ------------------

    @Test
    fun `extracted resume text is still persisted for interview generation`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.failure(IllegalStateException("Gemini is temporarily unavailable. Please try again.")) }
        advanceUntilIdle()

        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        verify(scopedResumeText).setResumeText(androidText)
        assertTrue(viewModelRef.uiState.value.hasResume)
    }

    @Test
    fun `deleting the resume clears both the text and the profile`() = runTest(dispatcher) {
        build()
        analysisRepository.result = { Result.success(ResumeAnalysis(androidProfile)) }
        advanceUntilIdle()
        viewModelRef.uploadResume(uri())
        advanceUntilIdle()

        viewModelRef.deleteResume()
        advanceUntilIdle()

        assertNull(viewModelRef.uiState.value.profile)
        assertFalse(viewModelRef.uiState.value.hasResume)
        assertNull(viewModelRef.uiState.value.resumeProfileNow())
    }

    // --- AC-17: profile restored from storage --------------------------------

    @Test
    fun `a stored profile is restored when the screen is recreated`() = runTest(dispatcher) {
        build()
        store.emit(androidProfile)
        advanceUntilIdle()

        assertEquals(androidProfile, viewModelRef.uiState.value.profile)
    }

    // --- helpers -------------------------------------------------------------

    private fun com.example.aiinterviewapp.ui.screens.resume.ResumeUiState.resumeProfileNow() =
        profile

    private class RecordingTextExtractor(
        var nextText: String,
        private val stage: () -> ResumeStage
    ) : ResumeTextExtractor {
        var calls = 0
        var failure: Throwable? = null
        var stageSeenInsideExtract: ResumeStage? = null

        override suspend fun extract(
            uri: Uri,
            onStage: (ResumeTextExtractor.ExtractionStage) -> Unit
        ): Result<ResumeExtraction> {
            calls++
            stageSeenInsideExtract = stage()
            onStage(ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT)
            failure?.let { return Result.failure(it) }
            return Result.success(
                ResumeExtraction(
                    text = nextText,
                    origin = ResumeTextOrigin.PDF_TEXT,
                    documentType = ResumeDocumentType.PDF
                )
            )
        }
    }

    private class ScriptedAnalysisRepository(
        private val stage: () -> ResumeStage
    ) : ResumeAnalysisRepository {
        var calls = 0
        var result: () -> Result<ResumeAnalysis> = {
            Result.failure(IllegalStateException("not configured"))
        }
        var stageSeenInsideAnalysis: ResumeStage? = null
        var lastText: String? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun analyzeResume(resumeText: String): Result<ResumeAnalysis> {
            calls++
            lastText = resumeText
            stageSeenInsideAnalysis = stage()
            gate?.await()
            return result()
        }
    }

    private class RecordingProfileStore : ResumeProfileStore {
        private val state = MutableStateFlow<ResumeProfile?>(null)

        /** Number of profiles currently reachable, i.e. not overwritten. */
        val currentCount: Int get() = if (state.value == null) 0 else 1
        val active: ResumeProfile? get() = state.value

        fun emit(profile: ResumeProfile?) {
            state.value = profile
        }

        override fun resumeProfile(): Flow<ResumeProfile?> = state

        override suspend fun saveResumeProfile(profile: ResumeProfile) {
            state.value = profile
        }

        override suspend fun clearResumeProfile() {
            state.value = null
        }
    }
}
