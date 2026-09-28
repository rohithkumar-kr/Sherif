package com.example.aiinterviewapp

import android.net.Uri
import com.example.aiinterviewapp.data.ocr.OcrTextNormalizer
import com.example.aiinterviewapp.data.ocr.ResumeDocumentResolver
import com.example.aiinterviewapp.data.ocr.ResumeTextQualityValidator
import com.example.aiinterviewapp.data.repository.DefaultResumeTextExtractor
import com.example.aiinterviewapp.data.service.ResumeService
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import com.example.aiinterviewapp.domain.model.ResumeExtraction
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Controlled sample documents driven through the *real* extractor and the *real*
 * PDFBox path, with only the OCR recogniser substituted.
 *
 * The routing tests elsewhere prove the decisions from mocked inputs. These
 * prove the decisions from genuine PDF bytes: a real selectable text layer, a
 * real page with no text layer at all, and genuinely corrupt bytes. That is the
 * difference between asserting the router calls OCR and asserting a scanned
 * document is actually recognised as scanned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlledResumeSampleTest {

    @Before
    fun setUp() {
        PdfBoxTestResources.install()
    }

    // --- Sample A: a clean, text-based PDF ----------------------------------

    private fun textPdf(vararg lines: String): ByteArray = pdfDocument {
        val page = PDPage()
        it.addPage(page)
        PDPageContentStream(it, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(40f, 700f)
            lines.forEach { line ->
                stream.showText(line)
                stream.newLineAtOffset(0f, -16f)
            }
            stream.endText()
        }
    }

    /** A page with real geometry but genuinely no text layer, like a scan. */
    private fun imageOnlyPdf(): ByteArray = pdfDocument { it.addPage(PDPage()) }

    private fun pdfDocument(build: (PDDocument) -> Unit): ByteArray {
        val document = PDDocument()
        build(document)
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun extractorFor(
        bytes: ByteArray,
        type: ResumeDocumentType = ResumeDocumentType.PDF,
        ocrText: String? = "Aarav Sharma\nAndroid Developer\nKotlin Jetpack Compose Room"
    ): Pair<DefaultResumeTextExtractor, OcrTestDoubles.FakeOcrEngine> {
        val ocr = OcrTestDoubles.FakeOcrEngine(
            ocrText?.let { text -> { Result.success(text) } }
                ?: { Result.failure(ResumeExtractionException.OcrFailed()) }
        )
        val uri = OcrTestDoubles.uri()
        val resolver = mock<ResumeDocumentResolver>()
        whenever(resolver.resolve(uri)).thenReturn(type)

        val realPdfBox = object : ResumeTextSource {
            override suspend fun extractText(uri: Uri): String =
                ResumeService.extractText(ByteArrayInputStream(bytes))
        }

        val extractor = DefaultResumeTextExtractor(
            documentResolver = resolver,
            ocrEngine = ocr,
            pdfTextSource = realPdfBox,
            ioDispatcher = Dispatchers.Unconfined
        )
        // The URI is the same mock the resolver is stubbed for.
        extractorUri = uri
        return extractor to ocr
    }

    private lateinit var extractorUri: Uri

    private suspend fun extract(
        bytes: ByteArray,
        type: ResumeDocumentType = ResumeDocumentType.PDF,
        ocrText: String? = "Aarav Sharma\nAndroid Developer\nKotlin Jetpack Compose Room"
    ): Pair<Result<ResumeExtraction>, OcrTestDoubles.FakeOcrEngine> {
        val (extractor, ocr) = extractorFor(bytes, type, ocrText)
        return extractor.extract(extractorUri) to ocr
    }

    @Test
    fun `sample A a clean text pdf never reaches ocr and keeps its facts`() = runTest {
        val bytes = textPdf(
            "Aarav Sharma",
            "Android Developer",
            "Skills: Kotlin, Jetpack Compose, Room, C++"
        )

        val (result, ocr) = extract(bytes)

        val extraction = result.getOrThrow()
        assertEquals("a clean text PDF must not be OCR-ed", 0, ocr.invocations)
        assertEquals(ResumeTextOrigin.PDF_TEXT, extraction.origin)
        assertTrue(extraction.text.contains("Aarav Sharma"))
        assertTrue("facts must survive extraction", extraction.text.contains("C++"))
        assertTrue(extraction.text.contains("Kotlin"))
    }

    @Test
    fun `sample A output passes the same quality gate as an OCR result`() = runTest {
        val bytes = textPdf("Aarav Sharma", "Android Developer", "Kotlin Jetpack Compose Room")

        val (result, _) = extract(bytes)

        assertTrue(ResumeTextQualityValidator.isUsable(result.getOrThrow().text))
    }

    // --- Sample B: a scanned, image-only PDF --------------------------------

    @Test
    fun `sample B a real page with no text layer is detected as scanned`() = runTest {
        val bytes = imageOnlyPdf()

        val (result, ocr) = extract(bytes)

        val extraction = result.getOrThrow()
        assertEquals("a page with no text layer must be OCR-ed", 1, ocr.invocations)
        assertEquals(ResumeTextOrigin.OCR, extraction.origin)
        assertTrue(ocr.requests.single() is OcrRequest.PdfPages)
    }

    @Test
    fun `sample B a scanned pdf is normalised and accepted`() = runTest {
        val noisy = "  Aarav   Sharma  \r\n\r\n\r\nAndroid Developer  \nKotlin   Room  "

        val (result, _) = extract(imageOnlyPdf(), ocrText = noisy)

        val extraction = result.getOrThrow()
        assertEquals(OcrTextNormalizer.normalize(noisy), extraction.text)
        assertTrue(extraction.text.contains("Aarav Sharma"))
        assertTrue(ResumeTextQualityValidator.isUsable(extraction.text))
    }

    @Test
    fun `sample B an empty scanned pdf reports no readable content`() = runTest {
        // Real empty page, and OCR that finds nothing on it.
        val (result, _) = extract(imageOnlyPdf(), ocrText = "   \n  ")

        val failure = result.exceptionOrNull()
        assertTrue(failure is ResumeExtractionException.NoReadableContent)
        assertEquals("No readable resume content was found.", failure?.message)
    }

    // --- Corrupt and unsupported inputs -------------------------------------

    @Test
    fun `genuinely corrupt pdf bytes are rejected as corrupt, not OCR-ed`() = runTest {
        val garbage = "PK not really a pdf".toByteArray()

        val (result, ocr) = extract(garbage)

        val failure = result.exceptionOrNull()
        assertTrue("corrupt bytes must fail", failure is ResumeExtractionException.CorruptDocument)
        assertEquals("a file we cannot open must not be OCR-ed", 0, ocr.invocations)
    }

    @Test
    fun `an unsupported document type is rejected before any pdf work`() = runTest {
        val (result, ocr) = extract(textPdf("Aarav Sharma"), type = ResumeDocumentType.UNSUPPORTED)

        assertTrue(result.exceptionOrNull() is ResumeExtractionException.UnsupportedFormat)
        assertEquals(0, ocr.invocations)
    }
}
