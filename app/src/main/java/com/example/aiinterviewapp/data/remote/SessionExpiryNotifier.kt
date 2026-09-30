package com.example.aiinterviewapp.data.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single "this session is over" signal.
 *
 * [com.example.aiinterviewapp.data.remote.model.SherifBackendException.requiresSignIn]
 * already knows when a failure means the session is no longer valid, but until
 * nothing consumed it the app dead-ended: the interview screen showed "Your
 * session expired. Please sign in again." and offered only Try Again, which
 * would fail identically forever. A credential with an expiry needs somewhere to
 * go when it expires, and this is that place.
 *
 * Two properties make it safe to drive navigation from:
 *
 *  * **Latched.** [report] is a no-op once an expiry has been announced until
 *    [reset] runs. Without the latch, several in-flight requests failing
 *    together would each fire the event and the sign-out would be attempted
 *    repeatedly against an already-cleared session.
 *  * **No replay.** The flow has `replay = 0`, so an expiry that happens while
 *    no screen is collecting is dropped rather than replayed on the next
 *    composition. Replaying is what turns a one-time event into a navigation
 *    loop.
 *
 * It is an event, not state. [reset] is called when a new session is
 * established, which is the only correct moment to arm it again.
 */
@Singleton
class SessionExpiryNotifier @Inject constructor() {

    private val announced = AtomicBoolean(false)

    private val _expiries = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits once per expired session. Collected once, at the navigation root. */
    val expiries: SharedFlow<Unit> = _expiries.asSharedFlow()

    /** True while an announced expiry is still waiting to be handled. */
    val isAnnounced: Boolean get() = announced.get()

    /** Announces an expiry. Ignored if one is already outstanding. */
    fun report() {
        if (announced.compareAndSet(false, true)) {
            _expiries.tryEmit(Unit)
        }
    }

    /** Re-arms the notifier once a new, valid session exists. */
    fun reset() {
        announced.set(false)
    }
}
