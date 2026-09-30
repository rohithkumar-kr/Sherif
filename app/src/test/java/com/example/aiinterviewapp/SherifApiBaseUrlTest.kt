package com.example.aiinterviewapp

import com.example.aiinterviewapp.utils.SherifApiBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend address decides whether any AI feature works at all, and the two
 * rules here protect the app from the two ways a hand-written value goes wrong:
 * a missing trailing slash, which crashes Retrofit at startup, and the
 * `.invalid` placeholder, which fails later and far less legibly.
 */
class SherifApiBaseUrlTest {

    @Test
    fun `a missing trailing slash is added`() {
        assertEquals("http://10.0.2.2:8080/", SherifApiBaseUrl.normalize("http://10.0.2.2:8080"))
    }

    @Test
    fun `an existing trailing slash is left alone`() {
        assertEquals("https://api.example.com/", SherifApiBaseUrl.normalize("https://api.example.com/"))
    }

    @Test
    fun `surrounding whitespace from a properties file is trimmed`() {
        assertEquals("https://api.example.com/", SherifApiBaseUrl.normalize("  https://api.example.com  "))
    }

    @Test
    fun `a path prefix keeps its own trailing slash`() {
        assertEquals("https://api.example.com/sherif/", SherifApiBaseUrl.normalize("https://api.example.com/sherif"))
    }

    @Test
    fun `the reserved placeholder counts as unconfigured`() {
        assertFalse(SherifApiBaseUrl.isConfigured("https://api.sherif.invalid/"))
    }

    @Test
    fun `a real address counts as configured`() {
        assertTrue(SherifApiBaseUrl.isConfigured("http://10.0.2.2:8080/"))
    }
}
