package com.example.aiinterviewapp.domain.model

/**
 * The document formats SHERIF can read a resume from.
 *
 * Detection is based on the provider-reported MIME type with the file extension
 * as a fallback, because some document providers report a generic or empty
 * type for picked files.
 */
enum class ResumeDocumentType(val mimeTypes: Set<String>, val extensions: Set<String>) {

    PDF(mimeTypes = setOf("application/pdf"), extensions = setOf("pdf")),

    JPEG(mimeTypes = setOf("image/jpeg", "image/jpg"), extensions = setOf("jpg", "jpeg")),

    PNG(mimeTypes = setOf("image/png"), extensions = setOf("png")),

    /**
     * Anything SHERIF deliberately does not read, including DOC/DOCX, archives,
     * executables and media. Detection must reject these rather than guessing,
     * so an unsupported file never reaches OCR or Gemini.
     */
    UNSUPPORTED(mimeTypes = emptySet(), extensions = emptySet());

    val isImage: Boolean get() = this == JPEG || this == PNG

    companion object {

        /**
         * Resolves a MIME type to a supported type, or [UNSUPPORTED].
         *
         * The comparison ignores case and any MIME parameters (for example
         * `image/jpeg; charset=binary`) so a slightly decorated type from a
         * content provider is still recognised.
         */
        fun fromMimeType(mimeType: String?): ResumeDocumentType {
            val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase()
                ?: return UNSUPPORTED
            return entries.firstOrNull { normalized in it.mimeTypes } ?: UNSUPPORTED
        }

        /** Resolves a file name or extension such as `resume.png`. */
        fun fromFileName(fileName: String?): ResumeDocumentType {
            val extension = fileName?.substringAfterLast('.', "")
                ?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return UNSUPPORTED
            return entries.firstOrNull { extension in it.extensions } ?: UNSUPPORTED
        }

        /**
         * Resolves a document from both signals, preferring the MIME type and
         * falling back to the extension when the provider reports nothing.
         */
        fun resolve(mimeType: String?, fileName: String?): ResumeDocumentType {
            val fromMime = fromMimeType(mimeType)
            if (fromMime != UNSUPPORTED) return fromMime
            return fromFileName(fileName)
        }
    }
}

/** How the resume text was obtained, so the UI can describe the real work done. */
enum class ResumeTextOrigin {

    /** Text selected directly out of a PDF by PDFBox. */
    PDF_TEXT,

    /** Text recognised locally from a rendered image by on-device OCR. */
    OCR
}

/**
 * The normalized resume text plus a record of how it was produced.
 *
 * Both origins converge here, which is what allows a single
 * `analyzeResume()` implementation and a single persisted representation to
 * serve every supported upload.
 */
data class ResumeExtraction(
    val text: String,
    val origin: ResumeTextOrigin,
    val documentType: ResumeDocumentType
)

/**
 * Why a resume could not be read.
 *
 * Each case carries the exact user-facing wording required by the Phase 2
 * error contract. Failures are always typed rather than collapsed into a
 * generic message, and no case ever produces a partial or fabricated profile.
 */
sealed class ResumeExtractionException(message: String) : Exception(message) {

    class UnsupportedFormat :
        ResumeExtractionException(
            "Unsupported resume format. Please upload a PDF, JPG, JPEG, or PNG."
        )

    class CorruptDocument :
        ResumeExtractionException("Unable to read this PDF.")

    class NoReadableContent :
        ResumeExtractionException("No readable resume content was found.")

    class OcrFailed :
        ResumeExtractionException(
            "Unable to read the resume image. Please upload a clearer image."
        )

    class PoorOcrQuality :
        ResumeExtractionException(
            "The resume text could not be read reliably. " +
                "Please upload a clearer image or PDF."
        )
}
