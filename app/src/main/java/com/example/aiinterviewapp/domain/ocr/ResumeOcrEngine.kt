package com.example.aiinterviewapp.domain.ocr

import android.net.Uri

/**
 * A unit of work handed to the OCR engine.
 *
 * Kept as a sealed type so the engine can choose the cheapest correct input:
 * a content URI is handed to ML Kit directly, while bytes and PDF pages are
 * decoded under an explicit size bound.
 */
sealed interface OcrRequest {

    /** A picked image file. Decoded under a size bound before recognition. */
    data class ImageFile(val uri: Uri) : OcrRequest

    /** Raw image bytes already read into memory, e.g. a rendered PDF page. */
    data class ImageBytes(val bytes: ByteArray) : OcrRequest {

        override fun equals(other: Any?): Boolean =
            this === other || (other is ImageBytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A document whose pages must be rendered to images before recognition. */
    data class PdfPages(val uri: Uri) : OcrRequest
}

/**
 * On-device text recognition.
 *
 * This is the single seam between the resume pipeline and ML Kit. Production
 * binds the real engine; unit tests bind a fake, so the suite never depends on
 * a native OCR model being present.
 *
 * Implementations must recognise text locally. Nothing in this interface may
 * send a resume image to a remote service.
 */
interface ResumeOcrEngine {

    /**
     * Recognises text in [request].
     *
     * Returns a failure when the image cannot be decoded or the recogniser
     * fails. An empty or unreadable result is returned as a failure rather than
     * as empty text, so callers cannot mistake "nothing recognised" for a
     * successful extraction.
     */
    suspend fun recognize(request: OcrRequest): Result<String>
}
