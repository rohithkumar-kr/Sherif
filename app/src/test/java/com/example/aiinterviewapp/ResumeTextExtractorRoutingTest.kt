package com.example.aiinterviewapp

import android.net.Uri
import com.example.aiinterviewapp.data.ocr.ResumeDocumentResolver
import com.example.aiinterviewapp.data.repository.DefaultResumeTextExtractor
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import com.example.aiinterviewapp.domain.model.ResumeExtraction
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.example.aiinterviewapp.domain.repository.ResumeTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A stand-in for the real PDFBox-backed service, which needs font resources
 * and an Android `AssetManager` that only exist on a device. Only the two
 * behaviours that matter for routing are modelled: what a text PDF yields, and
 * that a document cannot be read at all.
 */
private class FakePdfTextSource(
    var text: String = "",
    var failure: Throwable? = null
) : ResumeTextSource {

    var calls: Int = 0
        private set

    override suspend fun extractText(uri: Uri): String {
        calls++
        failure?.let { throw it }
        return text
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeTextExtractorRoutingTest {

    private lateinit var pdf: FakePdfTextSource
    private lateinit var ocr: OcrTestDoubles.FakeOcrEngine
    private lateinit var extractor: DefaultResumeTextExtractor

    private val uri = OcrTestDoubles.uri()

    private fun build(
        type: ResumeDocumentType,
        pdfText: String = "",
        pdfFailure: Throwable? = null,
        ocrResult: () -> Result<String> = { Result.success("") }
    ) {
        pdf = FakePdfTextSource(pdfText, pdfFailure)
        ocr = OcrTestDoubles.FakeOcrEngine(ocrResult)

        val resolver = mock<ResumeDocumentResolver>()
        whenever(resolver.resolve(uri)).thenReturn(type)

        extractor = DefaultResumeTextExtractor(
            documentResolver = resolver,
            ocrEngine = ocr,
            pdfTextSource = pdf,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // --- AC-03 / AC-10: a text PDF must never invoke OCR --------------------

    @Test
    fun `a text pdf uses pdfbox and never invokes ocr`() = runTest {
        build(
            type = ResumeDocumentType.PDF,
            pdfText = "Aarav Sharma\nAndroid Developer\nKotlin Jetpack Compose Room"
        )

        val result = extractor.extract(uri).getOrThrow()

        assertEquals("OCR must not run for a usable text PDF", 0, ocr.invocations)
        assertEquals(1, pdf.calls)
        assertEquals(ResumeTextOrigin.PDF_TEXT, result.origin)
        assertTrue(result.text.contains("Aarav Sharma"))
    }

    @Test
    fun `a text pdf reports the extraction stage and never the ocr stage`() = runTest {
        build(type = ResumeDocumentType.PDF, pdfText = "Aarav Sharma Android Developer Kotlin")

        val stages = mutableListOf<ResumeTextExtractor.ExtractionStage>()
        extractor.extract(uri) { stages += it }.getOrThrow()

        assertEquals(
            listOf(ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT),
            stages
        )
    }

    // --- AC-04 / AC-10: a scanned PDF must fall back to OCR -----------------

    @Test
    fun `a scanned pdf falls back to ocr and uses the recognised text`() = runTest {
        build(
            type = ResumeDocumentType.PDF,
            pdfText = "",
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val result = extractor.extract(uri).getOrThrow()

        assertEquals("OCR must run for a scanned PDF", 1, ocr.invocations)
        assertEquals(1, pdf.calls)
        assertEquals(ResumeTextOrigin.OCR, result.origin)
        assertTrue(result.text.contains("Kotlin"))
    }

    @Test
    fun `a scanned pdf reports extraction before ocr`() = runTest {
        build(
            type = ResumeDocumentType.PDF,
            pdfText = "   \n  ",
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val stages = mutableListOf<ResumeTextExtractor.ExtractionStage>()
        extractor.extract(uri) { stages += it }.getOrThrow()

        assertEquals(
            listOf(
                ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT,
                ResumeTextExtractor.ExtractionStage.RUNNING_OCR
            ),
            stages
        )
    }

    @Test
    fun `a scanned pdf requests ocr over rendered pages`() = runTest {
        build(
            type = ResumeDocumentType.PDF,
            pdfText = "",
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        extractor.extract(uri).getOrThrow()

        assertTrue(
            "a PDF must be sent for page rendering, not as a plain image",
            ocr.requests.single() is OcrRequest.PdfPages
        )
    }

    @Test
    fun `a pdf whose text layer is only whitespace is treated as scanned`() = runTest {
        build(
            type = ResumeDocumentType.PDF,
            pdfText = "\n\n   \n\t\n",
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val result = extractor.extract(uri).getOrThrow()

        assertEquals(1, ocr.invocations)
        assertEquals(ResumeTextOrigin.OCR, result.origin)
    }

    // --- AC-05 / AC-06 / AC-07 / AC-10: images go straight to OCR ------------

    @Test
    fun `a jpg is routed to ocr`() = runTest {
        build(
            type = ResumeDocumentType.JPEG,
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val result = extractor.extract(uri).getOrThrow()

        assertEquals(1, ocr.invocations)
        assertEquals(0, pdf.calls)
        assertEquals(ResumeTextOrigin.OCR, result.origin)
        assertEquals(ResumeDocumentType.JPEG, result.documentType)
    }

    @Test
    fun `a png is routed to ocr`() = runTest {
        build(
            type = ResumeDocumentType.PNG,
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val result = extractor.extract(uri).getOrThrow()

        assertEquals(1, ocr.invocations)
        assertEquals(0, pdf.calls)
        assertEquals(ResumeDocumentType.PNG, result.documentType)
    }

    @Test
    fun `an image is sent to ocr as a file rather than a pdf`() = runTest {
        build(
            type = ResumeDocumentType.PNG,
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        extractor.extract(uri).getOrThrow()

        assertTrue(ocr.requests.single() is OcrRequest.ImageFile)
    }

    @Test
    fun `an image reports the image stage without a preceding extraction stage`() = runTest {
        build(
            type = ResumeDocumentType.JPEG,
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val stages = mutableListOf<ResumeTextExtractor.ExtractionStage>()
        extractor.extract(uri) { stages += it }.getOrThrow()

        assertEquals(listOf(ResumeTextExtractor.ExtractionStage.READING_IMAGE), stages)
    }

    @Test
    fun `an image never claims a text layer was missing`() = runTest {
        build(
            type = ResumeDocumentType.PNG,
            ocrResult = { Result.success("Aarav Sharma Android Developer Kotlin Room") }
        )

        val stages = mutableListOf<ResumeTextExtractor.ExtractionStage>()
        extractor.extract(uri) { stages += it }.getOrThrow()

        assertTrue(
            "a picked image must not report the scanned-document stage",
            ResumeTextExtractor.ExtractionStage.RUNNING_OCR !in stages
        )
        assertTrue(
            "a picked image must not report a text-layer attempt",
            ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT !in stages
        )
    }

    // --- AC-08: unsupported formats are rejected before any work ------------

    @Test
    fun `an unsupported format is rejected`() = runTest {
        build(type = ResumeDocumentType.UNSUPPORTED)

        val failure = extractor.extract(uri).exceptionOrNull()

        assertTrue(failure is ResumeExtractionException.UnsupportedFormat)
        assertEquals(
            "Unsupported resume format. Please upload a PDF, JPG, JPEG, or PNG.",
            failure?.message
        )
    }

    @Test
    fun `an unsupported format triggers neither ocr nor pdf reading`() = runTest {
        build(type = ResumeDocumentType.UNSUPPORTED)

        extractor.extract(uri)

        assertEquals(0, ocr.invocations)
        assertEquals(0, pdf.calls)
    }

    // --- RULE 5: failures must not masquerade as success ---------------------

    @Test
    fun `a corrupt pdf reports a corrupt document failure`() = runTest {
        build(type = ResumeDocumentType.PDF, pdfFailure = IllegalStateException("boom"))

        val failure = extractor.extract(uri).exceptionOrNull()

        assertTrue(failure is ResumeExtractionException.CorruptDocument)
        assertEquals("Unable to read this PDF.", failure?.message)
    }

    @Test
    fun `a corrupt pdf is not sent to ocr`() = runTest {
        build(type = ResumeDocumentType.PDF, pdfFailure = IllegalStateException("boom"))

        extractor.extract(uri)

        assertEquals("a document we cannot open must not be OCR-ed", 0, ocr.invocations)
    }

    @Test
    fun `empty ocr output reports no readable content`() = runTest {
        build(type = ResumeDocumentType.PNG, ocrResult = { Result.success("   \n  ") })

        val failure = extractor.extract(uri).exceptionOrNull()

        assertTrue(failure is ResumeExtractionException.NoReadableContent)
        assertEquals("No readable resume content was found.", failure?.message)
    }

    @Test
    fun `a failing ocr engine reports an ocr failure`() = runTest {
        build(
            type = ResumeDocumentType.PNG,
            ocrResult = { Result.failure(IllegalStateException("decoder died")) }
        )

        val failure = extractor.extract(uri).exceptionOrNull()

        assertTrue(failure is ResumeExtractionException.OcrFailed)
        assertEquals(
            "Unable to read the resume image. Please upload a clearer image.",
            failure?.message
        )
    }

    @Test
    fun `garbage ocr output is rejected by the quality gate`() = runTest {
        build(type = ResumeDocumentType.PNG, ocrResult = { Result.success("...///---|||===") })

        val failure = extractor.extract(uri).exceptionOrNull()

        assertTrue(failure is ResumeExtractionException.PoorOcrQuality)
    }

    @Test
    fun `extraction never returns a profile`() = runTest {
        build(type = ResumeDocumentType.UNSUPPORTED)

        // Extraction yields text or a failure; it never invents a profile.
        val value: Result<ResumeExtraction> = extractor.extract(uri)

        assertTrue(value.isFailure)
    }
}
