package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.ocr.MlKitResumeOcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing arithmetic is the only part of the OCR engine that can be
 * verified on a JVM, because it is pure. It is what stands between a
 * 50 MP camera photo and an out-of-memory crash, so it is tested directly
 * rather than trusted.
 */
class OcrImageBoundsTest {

    // --- Sample size: bound the decoded bitmap ------------------------------

    @Test
    fun `a small image is decoded at full resolution`() {
        assertEquals(1, MlKitResumeOcrEngine.calculateSampleSize(800, 600))
    }

    @Test
    fun `an image exactly at the bound is not subsampled`() {
        assertEquals(1, MlKitResumeOcrEngine.calculateSampleSize(2048, 2048))
    }

    @Test
    fun `a portrait image is bounded on its longest edge`() {
        // 4000 tall exceeds the bound even though the width does not, so the
        // sample size must follow the longest edge, not the first argument.
        assertEquals(2, MlKitResumeOcrEngine.calculateSampleSize(1000, 4000))
        assertEquals(2000, 4000 / MlKitResumeOcrEngine.calculateSampleSize(1000, 4000))
    }

    @Test
    fun `a 12 megapixel photo is subsampled down`() {
        assertTrue(MlKitResumeOcrEngine.calculateSampleSize(4032, 3024) > 1)
    }

    @Test
    fun `a very large image is subsampled aggressively`() {
        assertTrue(MlKitResumeOcrEngine.calculateSampleSize(8000, 6000) >= 4)
    }

    @Test
    fun `the sample size is always a power of two`() {
        listOf(2048, 3000, 4096, 5000, 8000, 12000).forEach { edge ->
            val sample = MlKitResumeOcrEngine.calculateSampleSize(edge, edge)
            assertEquals(
                "sample size for $edge must be a power of two, was $sample",
                0,
                sample and (sample - 1)
            )
        }
    }

    @Test
    fun `the decoded longest edge never exceeds the bound`() {
        listOf(
            4032 to 3024,
            8000 to 6000,
            12000 to 9000,
            1920 to 1080
        ).forEach { (width, height) ->
            val sample = MlKitResumeOcrEngine.calculateSampleSize(width, height)
            val longest = maxOf(width, height) / sample
            assertTrue(
                "decoded longest edge $longest exceeded ${MlKitResumeOcrEngine.MAX_DIMENSION}",
                longest <= MlKitResumeOcrEngine.MAX_DIMENSION
            )
        }
    }

    @Test
    fun `degenerate dimensions do not loop or overflow`() {
        assertEquals(1, MlKitResumeOcrEngine.calculateSampleSize(0, 0))
        assertEquals(1, MlKitResumeOcrEngine.calculateSampleSize(1, 1))
    }

    // --- Render scale: bound the rendered page bitmap -----------------------

    @Test
    fun `a small page renders at full size`() {
        assertEquals(1f, MlKitResumeOcrEngine.renderScale(612, 792), 0.0001f)
    }

    @Test
    fun `a page at the bound renders at full size`() {
        assertEquals(1f, MlKitResumeOcrEngine.renderScale(2048, 2048), 0.0001f)
    }

    @Test
    fun `an oversized page is scaled down`() {
        val scale = MlKitResumeOcrEngine.renderScale(612, 7920)

        assertTrue("an oversized page must be scaled down", scale < 1f)
        assertEquals(MlKitResumeOcrEngine.MAX_DIMENSION.toFloat(), 7920 * scale, 1f)
    }

    @Test
    fun `rendering never enlarges a page`() {
        assertEquals(1f, MlKitResumeOcrEngine.renderScale(100, 200), 0.0001f)
    }

    @Test
    fun `the rendered longest edge never exceeds the bound`() {
        listOf(612 to 792, 2480 to 3508, 612 to 7920, 100 to 100).forEach { (width, height) ->
            val scale = MlKitResumeOcrEngine.renderScale(width, height)
            val longest = maxOf(width, height) * scale
            assertTrue(
                "rendered longest edge $longest exceeded ${MlKitResumeOcrEngine.MAX_DIMENSION}",
                longest <= MlKitResumeOcrEngine.MAX_DIMENSION + 1f
            )
        }
    }

    @Test
    fun `a degenerate page size falls back to a usable scale`() {
        assertEquals(1f, MlKitResumeOcrEngine.renderScale(0, 0), 0.0001f)
    }

    // --- Page cap -----------------------------------------------------------

    @Test
    fun `the page cap is a documented constant`() {
        assertEquals(25, MlKitResumeOcrEngine.MAX_PDF_PAGES)
        assertEquals(2048, MlKitResumeOcrEngine.MAX_DIMENSION)
    }
}
