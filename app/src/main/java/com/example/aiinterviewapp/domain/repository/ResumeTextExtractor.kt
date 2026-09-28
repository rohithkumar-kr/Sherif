package com.example.aiinterviewapp.domain.repository

import android.net.Uri
import com.example.aiinterviewapp.domain.model.ResumeExtraction

/**
 * Turns a picked resume file into normalized resume text.
 *
 * This is the single entry point for every supported input. The extractor owns
 * the decision of how to read the document, so no caller needs to know whether
 * a PDF is scanned, and no ViewModel contains extraction or OCR logic.
 *
 * The returned text is normalized and validated. On success it is ready to be
 * analysed and persisted; on failure it carries a typed
 * `ResumeExtractionException` and never partial or invented content.
 */
interface ResumeTextExtractor {

    /**
     * Extracts normalized resume text from [uri].
     *
     * @param onStage invoked as the extraction progresses so the UI can report
     *   the operation actually being performed. OCR is only reported when OCR
     *   really runs.
     */
    suspend fun extract(
        uri: Uri,
        onStage: (ExtractionStage) -> Unit = {}
    ): Result<ResumeExtraction>

    /** The observable phases of extraction, for accurate UI messaging. */
    enum class ExtractionStage {

        /** Reading a document's embedded text layer, i.e. PDFBox. */
        EXTRACTING_TEXT,

        /**
         * A PDF yielded no usable text, so its pages are being rendered and
         * recognised locally.
         */
        RUNNING_OCR,

        /**
         * A picked image is being recognised locally.
         *
         * Reported separately from [RUNNING_OCR] because the two mean different
         * things to the user: an image upload was never expected to have a text
         * layer, so the UI must not claim none was found.
         */
        READING_IMAGE
    }
}
