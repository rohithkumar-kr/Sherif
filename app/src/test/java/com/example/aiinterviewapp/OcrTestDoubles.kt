package com.example.aiinterviewapp

import android.net.Uri
import com.example.aiinterviewapp.domain.ocr.OcrRequest
import com.example.aiinterviewapp.domain.ocr.ResumeOcrEngine
import org.mockito.Mockito

/**
 * Test doubles for the OCR seam.
 *
 * Production binds the real ML Kit engine; these stand in for it so the suite
 * never depends on a native recognition model being installed. The fake also
 * counts invocations, which is how the routing rules (OCR must not run for a
 * text PDF, and must run for a scan or an image) are actually proven rather
 * than assumed.
 */
object OcrTestDoubles {

    fun uri(): Uri = Mockito.mock(Uri::class.java)

    class FakeOcrEngine(
        var result: () -> Result<String> = { Result.failure(IllegalStateException("not configured")) }
    ) : ResumeOcrEngine {

        /** Total number of times OCR was invoked. */
        var invocations: Int = 0
            private set

        val requests = mutableListOf<OcrRequest>()

        override suspend fun recognize(request: OcrRequest): Result<String> {
            invocations++
            requests += request
            return result()
        }
    }

    /** A representative OCR transcription, deliberately noisy like real output. */
    val NOISY_OCR_TEXT = """
          Aarav   Sharma\r
        aarav.sharma@example.com


        Android   Developer


        SKILLS: Kotlin, Jetpack Compose, Room, Retrofit, C++
        EXPERIENCE: 3.5 years at Flipkart (2021 - 2024)
        EDUCATION: B.Tech, Anna University, 2019
    """.trimIndent()
}
