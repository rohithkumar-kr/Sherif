package com.sherif.backend.http

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import java.util.concurrent.ConcurrentHashMap

/**
 * A per-caller token bucket, plus a global bucket.
 *
 * Two budgets, because they defend against different things. The per-user
 * bucket stops one account from draining the shared Gemini key; the global
 * bucket stops many accounts from draining it together. Neither is a
 * sophisticated WAF -- the point is that an authenticated but abusive client
 * hits a wall before the paid upstream does.
 *
 * Buckets are created lazily and evicted once idle, so an attacker cycling
 * through user ids cannot grow the map without bound.
 */
class RateLimiter(
    private val perUserCapacity: Int = DEFAULT_PER_USER_CAPACITY,
    private val perUserRefillPerMinute: Int = DEFAULT_PER_USER_REFILL,
    private val globalCapacity: Int = DEFAULT_GLOBAL_CAPACITY,
    private val globalRefillPerMinute: Int = DEFAULT_GLOBAL_REFILL,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val global = Bucket(globalCapacity, globalRefillPerMinute)

    /**
     * Consumes one token for [userId].
     *
     * @throws BackendException with [BackendError.RATE_LIMITED] when either
     *   budget is exhausted, carrying the seconds to wait.
     */
    fun check(userId: String) {
        val now = clock()

        if (!global.tryConsume(now)) {
            throw BackendException(
                BackendError.RATE_LIMITED,
                retryAfterSeconds = global.retryAfterSeconds(now)
            )
        }

        val bucket = buckets.computeIfAbsent(userId) {
            Bucket(perUserCapacity, perUserRefillPerMinute)
        }
        if (!bucket.tryConsume(now)) {
            throw BackendException(
                BackendError.RATE_LIMITED,
                retryAfterSeconds = bucket.retryAfterSeconds(now)
            )
        }

        evictIdle(now)
    }

    /** Seconds until [userId] would have a token again. Used for Retry-After. */
    fun retryAfterSecondsFor(userId: String): Int =
        buckets[userId]?.retryAfterSeconds(clock()) ?: 1

    private fun evictIdle(now: Long) {
        if (buckets.size <= MAX_TRACKED_USERS) return
        buckets.entries.removeIf { it.value.idleFor(now) > IDLE_EVICTION_MILLIS }
    }

    /**
     * Lazily refilling counter.
     *
     * Not atomic by design: a lost update under extreme contention would hand
     * out a few extra tokens, which is an acceptable outcome here. Adding locks
     * would buy precision the abuse case does not need.
     */
    private class Bucket(private val capacity: Int, private val refillPerMinute: Int) {

        private var tokens: Double = capacity.toDouble()
        private var lastRefillMillis: Long = 0
        private var lastSeenMillis: Long = 0

        @Synchronized
        fun tryConsume(now: Long): Boolean {
            refill(now)
            lastSeenMillis = now
            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }
            return false
        }

        @Synchronized
        fun retryAfterSeconds(now: Long): Int {
            refill(now)
            val deficit = 1.0 - tokens
            val perSecond = refillPerMinute.toDouble() / 60.0
            if (perSecond <= 0.0) return 60
            return kotlin.math.ceil(deficit / perSecond).toInt().coerceIn(1, 60)
        }

        @Synchronized
        fun idleFor(now: Long): Long = now - lastSeenMillis

        private fun refill(now: Long) {
            if (lastRefillMillis == 0L) lastRefillMillis = now
            val elapsedMillis = now - lastRefillMillis
            if (elapsedMillis <= 0) return
            val refilled = elapsedMillis.toDouble() / 60_000.0 * refillPerMinute
            tokens = (tokens + refilled).coerceAtMost(capacity.toDouble())
            lastRefillMillis = now
        }
    }

    companion object {
        const val DEFAULT_PER_USER_CAPACITY = 20
        const val DEFAULT_PER_USER_REFILL = 20
        const val DEFAULT_GLOBAL_CAPACITY = 200
        const val DEFAULT_GLOBAL_REFILL = 200

        const val MAX_TRACKED_USERS = 10_000
        const val IDLE_EVICTION_MILLIS = 10L * 60L * 1000L
    }
}
