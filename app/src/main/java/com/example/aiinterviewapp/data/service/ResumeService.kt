package com.example.aiinterviewapp.data.service

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam that lets the resume pipeline be driven by an already-opened document,
 * so extraction can be exercised without an Android [Context].
 */
fun interface ResumeTextSource {
    suspend fun extractText(uri: Uri): String
}

@Singleton
class ResumeService @Inject constructor(
    @ApplicationContext private val context: Context
) : ResumeTextSource {
    /**
     * Extracts the text content of a PDF selected via the system file picker.
     * Throws a descriptive exception when the file cannot be read, so callers
     * can surface a real error to the user instead of a placeholder string.
     */
    override suspend fun extractText(uri: Uri): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected file")
        input.use { stream -> extractText(stream) }
    }

    /** Backwards-compatible alias retained for existing callers. */
    suspend fun extractTextFromPdf(uri: Uri): String = extractText(uri)

    companion object {

        /**
         * Pure PDF text extraction over an already-opened document.
         *
         * Deliberately free of any Android dependency so the extraction
         * behaviour is directly testable. Returns the extracted text, which is
         * legitimately empty for a scanned or otherwise text-less PDF; deciding
         * what an empty result means is left to the caller. Throws only for a
         * document that cannot be read at all.
         */
        fun extractText(input: InputStream): String {
            val document = PDDocument.load(input)
            document.use {
                if (it.isEncrypted) {
                    throw IllegalStateException("Encrypted PDFs are not supported")
                }
                return PDFTextStripper().getText(it).trim()
            }
        }
    }
}
