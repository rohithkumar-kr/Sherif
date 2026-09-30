package com.example.aiinterviewapp.data.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.core.content.FileProvider
import com.example.aiinterviewapp.domain.model.Interview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfExportService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * The only directory the app ever shares from.
     *
     * Matches the single `<cache-path>` in `res/xml/file_paths.xml`. The
     * directory is deliberately separate from the rest of the cache because the
     * cache also holds the temporary files that PDF and OCR import create from
     * the user's own documents.
     */
    private val exportDir: File
        get() = File(context.cacheDir, EXPORT_DIR_NAME)

    /**
     * Renders [interview] to a PDF and returns the written file.
     *
     * The failure is a `Result`, not a null, and it is logged before it is
     * returned. A bare `null` used to be indistinguishable from "the user
     * pressed cancel", so a report that failed to render because of storage,
     * an unopenable file or a font problem was indistinguishable from one that
     * never started.
     *
     * The document is closed in a `finally` rather than on the success path.
     * On the earlier shape an exception thrown by `writeTo` left it to the
     * catch block to close a half-written document, and one thrown by
     * `close()` itself meant it was never closed -- in both cases a native
     * resource outlived the failed export.
     */
    fun exportReportToPdf(interview: Interview): Result<File> {
        val document = PdfDocument()
        return try {
            val file = writeReport(document, interview)
            Result.success(file)
        } catch (e: Exception) {
            Log.e(TAG, "PDF export failed for interview ${interview.id}", e)
            Result.failure(e)
        } finally {
            runCatching { document.close() }
        }
    }

    private fun writeReport(document: PdfDocument, interview: Interview): File {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 50f
        val contentWidth = pageWidth - margin * 2

        val titlePaint = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val sectionPaint = Paint().apply { textSize = 13f }
        val bodyPaint = Paint().apply { textSize = 11f }
        val labelPaint = Paint().apply { textSize = 12f; isFakeBoldText = true }

        val blocks = mutableListOf<Pair<Paint, String>>()
        blocks.add(titlePaint to "SHERIF Interview Report: ${interview.role}")
        blocks.add(
            sectionPaint to "Overall Score: ${interview.score}% | " +
                "Type: ${interview.type} | Difficulty: ${interview.difficulty} | Experience: ${interview.experience}"
        )
        blocks.add(sectionPaint to "")
        interview.questions.forEachIndexed { index, question ->
            blocks.add(labelPaint to "Q${index + 1}: ${question.question}")
            blocks.add(sectionPaint to "A: ${question.answer ?: "No answer"}")
            question.evaluation?.let { evaluation ->
                blocks.add(
                    sectionPaint to "Score: ${evaluation.score}/10 | Confidence: ${evaluation.confidence}/10 | " +
                        "Technical: ${evaluation.technical}/10 | Communication: ${evaluation.communication}/10 | Grammar: ${evaluation.grammar}/10"
                )
                blocks.add(bodyPaint to "Strengths: ${evaluation.strengths}")
                blocks.add(bodyPaint to "Weaknesses: ${evaluation.weaknesses}")
                blocks.add(bodyPaint to "Suggestions: ${evaluation.suggestions}")
            }
            blocks.add(sectionPaint to "")
        }

        val lines = mutableListOf<Pair<Paint, String>>()
        for ((paint, text) in blocks) {
            if (text.isBlank()) {
                lines.add(paint to "")
            } else {
                wrapText(text, paint, contentWidth).forEach { lines.add(paint to it) }
            }
        }

        // `finishPage` is required before the document is written, and a document
        // with no finished page is silently invalid. The loop always runs at
        // least once, so this holds even for a report with no questions.
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        )
        var y = margin + 30f
        for ((paint, line) in lines) {
            if (y > pageHeight - margin) {
                document.finishPage(page)
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, document.pages.size + 1).create()
                )
                y = margin + 30f
            }
            if (line.isNotEmpty()) {
                page.canvas.drawText(line, margin, y, paint)
            }
            y += paint.textSize * 1.7f
        }
        document.finishPage(page)

        val directory = exportDir
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the export directory")
        }

        val file = File(directory, "report_${interview.id.sanitisedForFileName()}.pdf")
        // Written to a temporary name and moved into place. A process death or a
        // full disk mid-write otherwise leaves a truncated PDF under the final
        // name, which is then shared as if it were complete.
        val temporary = File(directory, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { document.writeTo(it) }
            if (!temporary.renameTo(file)) {
                // `renameTo` can fail across some filesystems; copy-then-delete
                // is the portable fallback.
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }

        pruneOldExports(keep = file)
        return file
    }

    /**
     * Keeps the newest few exports and removes the rest.
     *
     * Every export is a full copy of an interview, and the cache is not
     * something the user manages, so without this a user who shares twenty
     * reports accumulates twenty PDFs until the system clears the cache at an
     * arbitrary moment -- possibly mid-share.
     *
     * A few are kept rather than one because a share is asynchronous: the
     * receiving app opens the URI after `startActivity` returns, so deleting
     * the file it was just handed would break it. Pruning by age alone, newest
     * first, is what makes that window survivable.
     */
    private fun pruneOldExports(keep: File) {
        try {
            val exports = exportDir.listFiles { file ->
                file.isFile && file.name.startsWith(EXPORT_PREFIX) && file != keep
            } ?: return

            exports
                .sortedByDescending { it.lastModified() }
                .drop(MAX_RETAINED_EXPORTS)
                .forEach { stale ->
                    if (stale.delete()) {
                        Log.d(TAG, "Removed an old export: ${stale.name}")
                    } else {
                        Log.w(TAG, "Could not remove an old export: ${stale.name}")
                    }
                }
        } catch (e: SecurityException) {
            // Pruning is housekeeping. Failing to do it must not fail an export
            // that has already been written.
            Log.w(TAG, "Could not prune old exports", e)
        }
    }

    /**
     * Reduces an id to something safe to use as a file name.
     *
     * The id is generated by the app, so this is not a security control in the
     * usual sense -- but a value from the database is still not something to
     * interpolate into a path. A separator here would place the file outside the
     * export directory, where the narrowed `file_paths.xml` would refuse to
     * share it, and a blank id would produce `report_.pdf`, with every export
     * of every interview colliding on one name.
     */
    private fun String.sanitisedForFileName(): String {
        val cleaned = map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("")

        return cleaned.take(MAX_FILE_NAME_LENGTH).ifBlank { "unknown" }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        var line = words.first()
        for (word in words.drop(1)) {
            val candidate = "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line)
                line = word
            }
        }
        lines.add(line)
        return lines
    }

    /**
     * Hands [file] to whatever the user wants to open it with.
     *
     * Every failure here used to be a bare `false`, so a missing FileProvider
     * authority and "no app can open a PDF" looked the same to whoever was
     * debugging. The cause is logged for the same reason as the export above.
     *
     * The grant is scoped to the single URI rather than to the app's whole
     * storage: `ClipData` carries the URI alongside the intent extra, and on
     * some launchers the extra alone is not enough to convey a read grant.
     */
    fun sharePdf(file: File): Result<Unit> = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Sharing ${file.name} failed", e)
        Result.failure(e)
    }

    private companion object {
        const val TAG = "SherifPdfExport"

        /** Must match the `<cache-path name="exports">` in `res/xml/file_paths.xml`. */
        const val EXPORT_DIR_NAME = "exports"
        const val EXPORT_PREFIX = "report_"
        const val MAX_RETAINED_EXPORTS = 4
        const val MAX_FILE_NAME_LENGTH = 64
        const val MIME_PDF = "application/pdf"
    }
}