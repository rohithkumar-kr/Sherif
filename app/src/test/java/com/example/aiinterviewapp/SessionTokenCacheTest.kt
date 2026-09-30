package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.local.datastore.SessionTokenCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The token mirror that removed `runBlocking` from the OkHttp thread.
 *
 * The behaviour that matters is not "it returns a token" -- it is that the
 * mirror follows the store in both directions, including back to null on
 * sign-out. A cache that only ever gains tokens would keep presenting a
 * credential the server has already revoked, which is the failure the whole
 * change was made to avoid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTokenCacheTest {

    @Test
    fun `a token present in the store is readable synchronously`() = runTest {
        val cache = SessionTokenCache(TestScope(testScheduler), flowOf("stored-token"))

        advanceUntilIdle()

        assertEquals("stored-token", cache.current)
    }

    @Test
    fun `nothing is presented before the store has been read`() = runTest {
        val cache = SessionTokenCache(TestScope(testScheduler), MutableStateFlow<String?>(null))

        // The cold-start window. It is safe only because the splash screen gates
        // every authenticated destination behind the same DataStore read.
        assertNull(cache.current)
    }

    @Test
    fun `signing out clears the mirror, so no dead credential is replayed`() = runTest {
        val store = MutableStateFlow<String?>("stored-token")
        val cache = SessionTokenCache(TestScope(testScheduler), store)
        advanceUntilIdle()
        assertEquals("stored-token", cache.current)

        store.value = null
        advanceUntilIdle()

        assertNull(cache.current)
    }

    @Test
    fun `signing in again replaces the mirror`() = runTest {
        val store = MutableStateFlow<String?>(null)
        val cache = SessionTokenCache(TestScope(testScheduler), store)
        advanceUntilIdle()

        store.value = "fresh-token"
        advanceUntilIdle()

        assertEquals("fresh-token", cache.current)
    }

    @Test
    fun `the mirror follows the store without a second write path`() = runTest {
        val store = MutableStateFlow<String?>(null)
        val cache = SessionTokenCache(TestScope(testScheduler), store)

        listOf("a", "b", "c").forEach { token ->
            store.value = token
            advanceUntilIdle()
            assertEquals(token, cache.current)
        }
    }

    @Test
    fun `reading the mirror needs no dispatcher of its own`() = runTest {
        val cache = SessionTokenCache(TestScope(testScheduler), flowOf("token"))
        advanceUntilIdle()

        // The interceptor calls this on an OkHttp worker thread. Nothing here
        // dispatches, suspends or throws -- a read that needed a coroutine would
        // deadlock that thread rather than fail a test.
        repeat(100) { assertEquals("token", cache.current) }
    }
}
