package com.example.aiinterviewapp

import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format acceptance is a security-adjacent boundary: an unrecognised file must
 * be refused before it can reach OCR or cost a Gemini call.
 */
class ResumeDocumentTypeTest {

    // --- Rule 2: supported types -------------------------------------------

    @Test
    fun `pdf is recognized from its mime type`() {
        assertEquals(ResumeDocumentType.PDF, ResumeDocumentType.fromMimeType("application/pdf"))
    }

    @Test
    fun `jpeg is recognized from its mime type`() {
        assertEquals(ResumeDocumentType.JPEG, ResumeDocumentType.fromMimeType("image/jpeg"))
    }

    @Test
    fun `the legacy jpg mime type is recognized`() {
        assertEquals(ResumeDocumentType.JPEG, ResumeDocumentType.fromMimeType("image/jpg"))
    }

    @Test
    fun `png is recognized from its mime type`() {
        assertEquals(ResumeDocumentType.PNG, ResumeDocumentType.fromMimeType("image/png"))
    }

    @Test
    fun `mime type matching ignores case`() {
        assertEquals(ResumeDocumentType.PDF, ResumeDocumentType.fromMimeType("APPLICATION/PDF"))
    }

    @Test
    fun `mime type parameters are ignored`() {
        assertEquals(ResumeDocumentType.JPEG, ResumeDocumentType.fromMimeType("image/jpeg; charset=binary"))
    }

    // --- Extension fallback, for providers that report a generic type -------

    @Test
    fun `a generic mime type falls back to the file extension`() {
        assertEquals(
            ResumeDocumentType.PNG,
            ResumeDocumentType.resolve("application/octet-stream", "resume.png")
        )
    }

    @Test
    fun `a missing mime type falls back to the file extension`() {
        assertEquals(
            ResumeDocumentType.PDF,
            ResumeDocumentType.resolve(null, "My Resume v2.pdf")
        )
    }

    @Test
    fun `jpg and jpeg extensions are both recognized`() {
        assertEquals(ResumeDocumentType.JPEG, ResumeDocumentType.fromFileName("resume.jpg"))
        assertEquals(ResumeDocumentType.JPEG, ResumeDocumentType.fromFileName("resume.jpeg"))
    }

    @Test
    fun `extension matching ignores case`() {
        assertEquals(ResumeDocumentType.PNG, ResumeDocumentType.fromFileName("RESUME.PNG"))
    }

    @Test
    fun `a known mime type wins over a conflicting extension`() {
        assertEquals(
            ResumeDocumentType.PDF,
            ResumeDocumentType.resolve("application/pdf", "resume.png")
        )
    }

    // --- Rule 2: unsupported types are refused ------------------------------

    @Test
    fun `doc and docx are unsupported`() {
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromFileName("resume.doc"))
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromFileName("resume.docx"))
        assertEquals(
            ResumeDocumentType.UNSUPPORTED,
            ResumeDocumentType.fromMimeType("application/msword")
        )
    }

    @Test
    fun `archives executables and media are unsupported`() {
        listOf("archive.zip", "setup.exe", "app.apk", "clip.mp4", "photo.heic", "sheet.xlsx")
            .forEach { name ->
                assertEquals(
                    "expected $name to be unsupported",
                    ResumeDocumentType.UNSUPPORTED,
                    ResumeDocumentType.fromFileName(name)
                )
            }
    }

    @Test
    fun `an absent or uninformative type is unsupported`() {
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromMimeType(null))
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromMimeType(""))
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromFileName(null))
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromFileName("noextension"))
    }

    @Test
    fun `a file with no extension is unsupported`() {
        assertEquals(ResumeDocumentType.UNSUPPORTED, ResumeDocumentType.fromFileName("README"))
    }

    // --- Classification helpers --------------------------------------------

    @Test
    fun `only images are classified as images`() {
        assertTrue(ResumeDocumentType.JPEG.isImage)
        assertTrue(ResumeDocumentType.PNG.isImage)
        assertFalse(ResumeDocumentType.PDF.isImage)
        assertFalse(ResumeDocumentType.UNSUPPORTED.isImage)
    }
}
