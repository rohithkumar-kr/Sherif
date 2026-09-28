package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.ocr.OcrTextNormalizer
import com.example.aiinterviewapp.data.ocr.ResumeDocumentResolver
import com.example.aiinterviewapp.data.ocr.ResumeTextQualityValidator
import com.example.aiinterviewapp.data.repository.DefaultResumeTextExtractor
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import com.example.aiinterviewapp.domain.usecase.AnalyzeResumeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Captures the text handed to the single Gemini entry point, so it can be
 * asserted directly. This is what proves OCR text and PDFBox text converge on
 * one analysis implementation rather than two.
 */
private class CapturingAnalysisRepository(
    private val profile: ResumeProfile = ResumeProfile(candidateName = "Aarav Sharma")
) : ResumeAnalysisRepository {

    var calls: Int = 0
        private set
    var lastText: String? = null
        private set

    override suspend fun analyzeResume(resumeText: String): Result<ResumeAnalysis> {
        calls++
        lastText = resumeText
        return Result.success(ResumeAnalysis(profile))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeOcrToGeminiPipelineTest {

    private lateinit var ocr: OcrTestDoubles.FakeOcrEngine
    private lateinit var analysis: CapturingAnalysisRepository

    private val uri = OcrTestDoubles.uri()

    private fun extractor(
        type: ResumeDocumentType,
        pdfText: String = "",
        ocrText: String? = null
    ): DefaultResumeTextExtractor {
        ocr = OcrTestDoubles.FakeOcrEngine(
            ocrText?.let { text -> { Result.success(text) } }
                ?: { Result.failure(ResumeExtractionException.OcrFailed()) }
        )
        val resolver = mock<ResumeDocumentResolver>()
        whenever(resolver.resolve(uri)).thenReturn(type)
        return DefaultResumeTextExtractor(
            documentResolver = resolver,
            ocrEngine = ocr,
            pdfTextSource = object : ResumeTextSource {
                override suspend fun extractText(uri: android.net.Uri): String = pdfText
            },
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // --- AC-12: a controlled OCR token must reach analyzeResume -------------

    @Test
    fun `ocr derived text reaches analyzeResume with the controlled token intact`() = runTest {
        val token = "UNIQUE_OCR_TEST_TOKEN_92741"
        val subject = extractor(
            type = ResumeDocumentType.PNG,
            ocrText = "Aarav Sharma\nAndroid Developer\n$token\nKotlin Room"
        )
        analysis = CapturingAnalysisRepository()

        val text = subject.extract(uri).getOrThrow().text
        AnalyzeResumeUseCase(analysis).invoke(text)

        assertEquals(1, analysis.calls)
        assertTrue(
            "the OCR token must survive into the Gemini request",
            analysis.lastText!!.contains(token)
        )
    }

    @Test
    fun `ocr text is normalized before it reaches analyzeResume`() = runTest {
        val subject = extractor(
            type = ResumeDocumentType.PNG,
            ocrText = OcrTestDoubles.NOISY_OCR_TEXT
        )
        analysis = CapturingAnalysisRepository()

        val extracted = subject.extract(uri).getOrThrow()
        AnalyzeResumeUseCase(analysis).invoke(extracted.text)

        assertEquals(OcrTextNormalizer.normalize(OcrTestDoubles.NOISY_OCR_TEXT), extracted.text)
        assertEquals("no carriage returns may survive", -1, analysis.lastText!!.indexOf('\r'))
        assertTrue("runs of spaces must be collapsed", !analysis.lastText!!.contains("   "))
        assertTrue("C++ must survive normalization", analysis.lastText!!.contains("C++"))
    }

    // --- AC-13: one shared pipeline for both origins ------------------------

    @Test
    fun `pdf text and ocr text both flow through the same analyzeResume`() = runTest {
        val pdfSubject = extractor(
            type = ResumeDocumentType.PDF,
            pdfText = "Aarav Sharma Android Developer Kotlin Room Retrofit"
        )
        val pdfText = pdfSubject.extract(uri).getOrThrow().text

        val ocrSubject = extractor(
            type = ResumeDocumentType.PNG,
            ocrText = "Aarav Sharma Android Developer Kotlin Room Retrofit"
        )
        val ocrText = ocrSubject.extract(uri).getOrThrow().text

        analysis = CapturingAnalysisRepository()
        val useCase = AnalyzeResumeUseCase(analysis)
        useCase(pdfText)
        val afterPdf = analysis.lastText
        useCase(ocrText)

        assertEquals("one implementation serves both origins", 2, analysis.calls)
        assertEquals("both origins must produce the same text", afterPdf, analysis.lastText)
    }

    // --- RULE 11 / AC-09: invalid input must not cost an API call -----------

    @Test
    fun `empty ocr output never reaches analyzeResume`() = runTest {
        val subject = extractor(type = ResumeDocumentType.PNG, ocrText = "   \n\n  ")
        analysis = CapturingAnalysisRepository()

        val result = subject.extract(uri)
        if (result.isSuccess) {
            AnalyzeResumeUseCase(analysis).invoke(result.getOrThrow().text)
        }

        assertTrue(result.isFailure)
        assertEquals("invalid extraction must not call Gemini", 0, analysis.calls)
    }

    @Test
    fun `garbage ocr output never reaches analyzeResume`() = runTest {
        val subject = extractor(type = ResumeDocumentType.PNG, ocrText = "...///---|||===")
        analysis = CapturingAnalysisRepository()

        val result = subject.extract(uri)
        if (result.isSuccess) {
            AnalyzeResumeUseCase(analysis).invoke(result.getOrThrow().text)
        }

        assertTrue(result.isFailure)
        assertEquals(0, analysis.calls)
    }

    @Test
    fun `an unsupported file never reaches analyzeResume`() = runTest {
        val subject = extractor(type = ResumeDocumentType.UNSUPPORTED)
        analysis = CapturingAnalysisRepository()

        val result = subject.extract(uri)
        if (result.isSuccess) {
            AnalyzeResumeUseCase(analysis).invoke(result.getOrThrow().text)
        }

        assertTrue(result.isFailure)
        assertEquals("AC-08: unsupported input must cost no Gemini call", 0, analysis.calls)
        assertEquals(0, ocr.invocations)
    }

    @Test
    fun `a scanned pdf whose ocr also fails never reaches analyzeResume`() = runTest {
        val subject = extractor(type = ResumeDocumentType.PDF, pdfText = "")
        analysis = CapturingAnalysisRepository()

        val result = subject.extract(uri)
        if (result.isSuccess) {
            AnalyzeResumeUseCase(analysis).invoke(result.getOrThrow().text)
        }

        assertTrue(result.isFailure)
        assertEquals(0, analysis.calls)
    }

    // --- RULE 12: normalized text is what gets persisted ---------------------

    @Test
    fun `ocr text is produced in the same shape a pdf would produce`() = runTest {
        val subject = extractor(
            type = ResumeDocumentType.PNG,
            ocrText = "  Aarav   Sharma  \r\n\r\n\r\nAndroid Developer  "
        )

        val extracted = subject.extract(uri).getOrThrow()

        assertEquals("Aarav Sharma\n\nAndroid Developer", extracted.text)
        assertEquals(
            ResumeTextQualityValidator.requireUsable(extracted.text),
            extracted.text
        )
    }

    @Test
    fun `a scanned pdf is reported as ocr origin so the ui can say so`() = runTest {
        val subject = extractor(
            type = ResumeDocumentType.PDF,
            pdfText = "",
            ocrText = "Aarav Sharma Android Developer Kotlin Room"
        )

        val extracted = subject.extract(uri).getOrThrow()

        assertTrue(ocr.requests.single() is OcrRequest.PdfPages)
        assertEquals(ResumeTextOrigin.OCR, extracted.origin)
        assertEquals(ResumeDocumentType.PDF, extracted.documentType)
    }
}
