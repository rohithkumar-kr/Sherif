package com.example.aiinterviewapp.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.example.aiinterviewapp.domain.ocr.ResumeOcrEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device OCR backed by ML Kit's bundled Latin text recogniser.
 *
 * Resource safety is enforced here rather than left to chance:
 *
 *  * **Bounded decoding.** Image dimensions are read with `inJustDecodeBounds`
 *    first, then the bitmap is decoded with a power-of-two subsample, so a
 *    50 MP phone photo never allocates a full-resolution bitmap.
 *  * **Bounded rendering.** Scanned PDF pages are rendered through the
 *    platform `PdfRenderer` at a scale that keeps the longest edge within
 *    [MAX_DIMENSION], rather than at the page's full point size.
 *  * **One bitmap at a time, always released.** Each page is rendered,
 *    recognised and recycled in a `finally` block, so a long document never
 *    accumulates bitmaps. Renderers and descriptors are closed too.
 *
 * ML Kit's API is callback-based; [recognizeBitmap] bridges it to a coroutine
 * and propagates cancellation.
 */
@Singleton
class MlKitResumeOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : ResumeOcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(request: OcrRequest): Result<String> = runCatching {
        when (request) {
            is OcrRequest.ImageFile -> recognizeUri(request.uri)
            is OcrRequest.ImageBytes -> recognizeBytes(request.bytes)
            is OcrRequest.PdfPages -> recognizePdfPages(request.uri)
        }
    }

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw ResumeExtractionException.OcrFailed()

    /**
     * Decodes straight from the content stream.
     *
     * The bytes are deliberately never buffered into a single array: a photo
     * from a modern camera is tens of megabytes, and materialising it would
     * defeat the point of bounding the decode. Only the subsampled bitmap is
     * ever held in memory.
     */
    private suspend fun recognizeUri(uri: Uri): String {
        val bitmap = decodeBounded { openStream(uri) }
        return try {
            recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw ResumeExtractionException.OcrFailed()
        val bitmap = decodeBounded { ByteArrayInputStream(bytes) }
        return try {
            recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Renders each page of a scanned PDF with the platform renderer and
     * recognises the pages in order.
     *
     * This is a purely local path: no page image is ever sent to a remote
     * service, and ML Kit's bundled recogniser runs on device.
     *
     * Both the descriptor and the renderer are closed. `PdfRenderer.close()`
     * releases the renderer only, so the descriptor is owned here explicitly.
     */
    private suspend fun recognizePdfPages(uri: Uri): String =
        openDescriptor(uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                if (pageCount <= 0) throw ResumeExtractionException.NoReadableContent()

                val builder = StringBuilder()
                for (index in 0 until pageCount) {
                    val text = renderAndRecognizePage(renderer, index)
                    if (text.isNotBlank()) {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(text)
                    }
                }
                if (builder.isBlank()) throw ResumeExtractionException.NoReadableContent()
                builder.toString()
            }
        }

    private fun openDescriptor(uri: Uri): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw ResumeExtractionException.OcrFailed()

    private suspend fun renderAndRecognizePage(renderer: PdfRenderer, index: Int): String {
        renderer.openPage(index).use { page ->
            val scale = renderScale(page.width, page.height)
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)

            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // A white backdrop avoids transparent-black page areas, which
                // the recogniser reads as noise.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return recognizeBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun recognizeBitmap(bitmap: Bitmap): String {
        if (bitmap.isRecycled) throw ResumeExtractionException.OcrFailed()
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it.text) }
                .addOnFailureListener { continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }
    }

    /**
     * Decodes an image under an explicit size bound.
     *
     * The stream is opened twice, once to read the dimensions and once to
     * decode, because `BitmapFactory` consumes what it reads. Only the
     * subsampled bitmap is retained.
     *
     * The bound is generous because ML Kit needs a reasonably large image to
     * read small text; the subsample only engages for images far beyond it.
     */
    private fun decodeBounded(openStream: () -> InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ResumeExtractionException.OcrFailed()
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return openStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ResumeExtractionException.OcrFailed()
    }

    companion object {

        /**
         * Longest edge kept when decoding or rendering. Larger inputs are
         * scaled down, bounding peak memory while staying well above the
         * resolution ML Kit needs for legible text.
         */
        const val MAX_DIMENSION = 2048

        /** Guard against pathological documents; a resume is not 25+ pages. */
        const val MAX_PDF_PAGES = 25

        /**
         * Power-of-two sample size keeping the longest edge at or below
         * [MAX_DIMENSION]. Pure arithmetic, so it is directly testable.
         *
         * Doubling continues while the *decoded* edge would still exceed the
         * bound, so an image exactly at the bound is left at full resolution
         * while a 12 MP photo is halved rather than kept whole.
         */
        fun calculateSampleSize(width: Int, height: Int): Int {
            val longest = max(width, height)
            var sample = 1
            while (longest / sample > MAX_DIMENSION) {
                sample *= 2
            }
            return sample
        }

        /**
         * Scale factor for rendering a PDF page, capped so the longest edge
         * never exceeds [MAX_DIMENSION]. Never enlarges a small page.
         */
        fun renderScale(width: Int, height: Int): Float {
            val longest = max(width, height)
            if (longest <= 0) return 1f
            return if (longest <= MAX_DIMENSION) 1f else MAX_DIMENSION.toFloat() / longest
        }
    }
}
