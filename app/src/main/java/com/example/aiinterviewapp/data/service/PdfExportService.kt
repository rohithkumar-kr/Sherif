package com.example.aiinterviewapp.data.service

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.aiinterviewapp.domain.model.Interview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfExportService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun exportReportToPdf(interview: Interview): File? {
        val document = PdfDocument()
        return try {
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

            val file = File(context.cacheDir, "report_${interview.id}.pdf")
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
            file
        } catch (e: Exception) {
            document.close()
            null
        }
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

    fun sharePdf(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}