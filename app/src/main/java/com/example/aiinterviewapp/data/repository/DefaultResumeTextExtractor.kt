package com.example.aiinterviewapp.data.repository

import android.net.Uri
import com.example.aiinterviewapp.data.ocr.OcrTextNormalizer
import com.example.aiinterviewapp.data.ocr.ResumeDocumentResolver
import com.example.aiinterviewapp.data.ocr.ResumeTextQualityValidator
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import com.example.aiinterviewapp.domain.model.ResumeExtraction
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.model.ResumeTextOrigin
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.example.aiinterviewapp.domain.ocr.ResumeOcrEngine
import com.example.aiinterviewapp.domain.repository.ResumeTextExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a picked resume to the cheapest method that can read it.
 *
 * ```
 * PDF
 *  ├── usable text layer  -> PDFBox, OCR never runs
 *  └── no usable text     -> on-device OCR
 * JPG / JPEG / PNG       -> on-device OCR
 * anything else          -> rejected before any work or network call
 * ```
 *
 * Every path produces the same normalized, validated text, so the single
 * `analyzeResume()` pipeline serves all of them. All file and bitmap work is
 * confined to the IO dispatcher.
 */
@Singleton
class DefaultResumeTextExtractor @Inject constructor(
    private val documentResolver: ResumeDocumentResolver,
    private val ocrEngine: ResumeOcrEngine,
    private val pdfTextSource: ResumeTextSource,
    private val ioDispatcher: CoroutineDispatcher
) : ResumeTextExtractor {

    override suspend fun extract(
        uri: Uri,
        onStage: (ResumeTextExtractor.ExtractionStage) -> Unit
    ): Result<ResumeExtraction> = withContext(ioDispatcher) {
        runCatching {
            val documentType = documentResolver.resolve(uri)
            if (documentType == ResumeDocumentType.UNSUPPORTED) {
                throw ResumeExtractionException.UnsupportedFormat()
            }
            when (documentType) {
                ResumeDocumentType.PDF -> extractFromPdf(uri, onStage)
                ResumeDocumentType.JPEG, ResumeDocumentType.PNG -> extractViaOcr(
                    documentType = documentType,
                    request = OcrRequest.ImageFile(uri),
                    stage = ResumeTextExtractor.ExtractionStage.READING_IMAGE,
                    onStage = onStage
                )
                ResumeDocumentType.UNSUPPORTED -> throw ResumeExtractionException.UnsupportedFormat()
            }
        }
    }

    /**
     * Reads a PDF's text layer, falling back to OCR only when that layer is
     * unusable. A PDF that already yields readable text must never trigger an
     * OCR pass, because OCR is slower and less accurate than the text layer.
     */
    private suspend fun extractFromPdf(
        uri: Uri,
        onStage: (ResumeTextExtractor.ExtractionStage) -> Unit
    ): ResumeExtraction {
        onStage(ResumeTextExtractor.ExtractionStage.EXTRACTING_TEXT)

        val embeddedText = runCatching { pdfTextSource.extractText(uri) }.getOrElse { throwable ->
            // An unreadable or encrypted document is a real failure, not an
            // invitation to spend time OCR-ing a file we cannot open.
            if (throwable is ResumeExtractionException) throw throwable
            throw ResumeExtractionException.CorruptDocument()
        }

        val normalizedEmbedded = OcrTextNormalizer.normalize(embeddedText)
        if (ResumeTextQualityValidator.isUsable(normalizedEmbedded)) {
            return ResumeExtraction(
                text = normalizedEmbedded,
                origin = ResumeTextOrigin.PDF_TEXT,
                documentType = ResumeDocumentType.PDF
            )
        }

        // Scanned or image-only PDF: the text layer is empty by design.
        return extractViaOcr(
            documentType = ResumeDocumentType.PDF,
            request = OcrRequest.PdfPages(uri),
            stage = ResumeTextExtractor.ExtractionStage.RUNNING_OCR,
            onStage = onStage
        )
    }

    private suspend fun extractViaOcr(
        documentType: ResumeDocumentType,
        request: OcrRequest,
        stage: ResumeTextExtractor.ExtractionStage,
        onStage: (ResumeTextExtractor.ExtractionStage) -> Unit
    ): ResumeExtraction {
        onStage(stage)

        val recognized = ocrEngine.recognize(request).getOrElse { throwable ->
            if (throwable is ResumeExtractionException) throw throwable
            throw ResumeExtractionException.OcrFailed()
        }

        val normalized = OcrTextNormalizer.normalize(recognized)
        if (normalized.isBlank()) {
            throw ResumeExtractionException.NoReadableContent()
        }

        // Quality gate: unusable OCR output must never reach Gemini.
        ResumeTextQualityValidator.requireUsable(normalized)

        return ResumeExtraction(
            text = normalized,
            origin = ResumeTextOrigin.OCR,
            documentType = documentType
        )
    }
}
