package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.service.ResumeService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Exercises the real PDFBox extraction path on the JVM, so the guarantee that
 * a text-based PDF still yields text is verified rather than assumed.
 */
class ResumeServiceExtractionTest {

    @Before
    fun setUp() {
        PdfBoxTestResources.install()
    }

    private fun pdfWithText(vararg lines: String): ByteArray {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(40f, 700f)
            lines.forEach { line ->
                stream.showText(line)
                stream.newLineAtOffset(0f, -16f)
            }
            stream.endText()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun emptyPdf(): ByteArray {
        val document = PDDocument()
        document.addPage(PDPage())
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun `a text based pdf produces non blank extracted text`() {
        val bytes = pdfWithText(
            "Aarav Sharma",
            "Android Developer",
            "Skills: Kotlin, Jetpack Compose, Room"
        )

        val text = ResumeService.extractText(ByteArrayInputStream(bytes))

        assertTrue("extracted text must not be blank", text.isNotBlank())
        assertTrue(text.contains("Aarav Sharma"))
        assertTrue(text.contains("Kotlin"))
        assertTrue(text.contains("Jetpack Compose"))
        assertTrue(text.contains("Room"))
    }

    @Test
    fun `a text less pdf returns empty text rather than throwing`() {
        val text = ResumeService.extractText(ByteArrayInputStream(emptyPdf()))

        assertTrue("a text-less pdf yields no text", text.isBlank())
    }

    @Test
    fun `a corrupt pdf throws a descriptive error instead of crashing`() {
        val garbage = "this is not a pdf at all".toByteArray()

        val error = runCatching { ResumeService.extractText(ByteArrayInputStream(garbage)) }.exceptionOrNull()

        assertTrue("unreadable input must surface an error", error != null)
    }

    @Test
    fun `extraction trims surrounding whitespace`() {
        val text = ResumeService.extractText(ByteArrayInputStream(pdfWithText("Solo Line")))

        assertEquals(text.trim(), text)
    }

    @Test
    fun `text based extraction never invents content`() {
        val text = ResumeService.extractText(ByteArrayInputStream(pdfWithText("Only Kotlin Here")))

        assertFalse(text.contains("Spring Boot"))
        assertFalse(text.contains("Kubernetes"))
    }
}
