package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.SessionExpiryNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal that ends the "Your session expired" dead-end.
 *
 * Both properties below were chosen to stop a specific failure, and both are
 * observable: several requests failing at once would each try to sign the user
 * out, and a replayed event turns a one-time signal into a navigation loop that
 * bounces the user back to the login screen on every recomposition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpiryNotifierTest {

    @Test
    fun `an expiry is announced once even when several requests fail together`() = runTest {
        val notifier = SessionExpiryNotifier()
        val received = mutableListOf<Unit>()
        val collector = backgroundScope.launch { notifier.expiries.collect { received += it } }
        // Let the collector subscribe first. The flow does not replay, so an
        // event published before anyone is listening is deliberately dropped.
        advanceUntilIdle()

        // Three in-flight requests all get a 401 from the same dead session.
        notifier.report()
        notifier.report()
        notifier.report()
        advanceUntilIdle()

        assertEquals(1, received.size)
        collector.cancel()
    }

    @Test
    fun `an announced expiry stays announced until it is reset`() {
        val notifier = SessionExpiryNotifier()

        notifier.report()

        assertTrue(notifier.isAnnounced)
    }

    @Test
    fun `a new session re-arms the signal`() {
        val notifier = SessionExpiryNotifier()
        notifier.report()

        // What MainActivity does on successful sign-in.
        notifier.reset()

        assertFalse(notifier.isAnnounced)
    }

    @Test
    fun `an expiry after a reset is announced again`() = runTest {
        val notifier = SessionExpiryNotifier()
        notifier.report()
        notifier.reset()
        val received = mutableListOf<Unit>()
        val collector = backgroundScope.launch { notifier.expiries.collect { received += it } }
        advanceUntilIdle()

        notifier.report()
        advanceUntilIdle()

        assertEquals(1, received.size)
        collector.cancel()
    }

    @Test
    fun `resetting twice is harmless`() {
        val notifier = SessionExpiryNotifier()

        notifier.reset()
        notifier.reset()

        assertFalse(notifier.isAnnounced)
    }
}
