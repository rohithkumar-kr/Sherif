package com.example.aiinterviewapp.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Preferences that belong to the app rather than to a user.
 *
 * Phase 3 split the old `AuthPreferences` in two. This half holds only
 * genuinely device-wide settings -- currently the theme -- plus the *keys* of
 * the user-scoped values. Everything that identifies a person lives behind a
 * [ScopedResumeText] key derived from a user id, so it cannot be read without
 * one.
 *
 * `is_logged_in` and the unscoped `user_name`/`user_email` keys are gone:
 * a boolean cannot expire, and a global name is a cross-user leak waiting to
 * happen. Session truth is [SessionStore].
 */
@Singleton
class AuthPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val DARK_MODE = booleanPreferencesKey("dark_mode")

    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { it[DARK_MODE] ?: false }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[DARK_MODE] = enabled }
    }
}

/**
 * Resume text, scoped to the signed-in user.
 *
 * Phase 2 stored one global `resume_text`, which meant User B saw User A's
 * resume. The value is now keyed by user id, so a read without a user id is not
 * merely empty but *impossible*: there is no unscoped key left to fall back to.
 *
 * The pre-Phase-3 global entry is intentionally not adopted by anyone. Its
 * owner cannot be determined, and quietly handing it to whoever signs in next
 * would be exactly the misattribution RULE 9 forbids. It stays where it is,
 * unread, until an explicit claim flow exists.
 */
@Singleton
class ScopedResumeText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore
) {
    /**
     * The signed-in user's resume text, switching with the session.
     *
     * When the session ends this emits null, which is why it is built on
     * `flatMapLatest`: a logout has to *stop* delivering the previous user's
     * resume, not merely stop writing to it. A flow that cached its last value
     * would leave one person's resume on screen after another person signed in.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentUserResumeText: Flow<String?> =
        sessionStore.userId.flatMapLatest { userId ->
            if (userId == null) flowOf(null) else resumeText(userId)
        }

    fun resumeText(userId: String): Flow<String?> =
        context.dataStore.data.map { it[keyFor(userId)] }

    /**
     * Stores resume text for the signed-in user.
     *
     * Refuses to write when there is no session. Returning silently rather than
     * throwing keeps the caller's flow simple, and the alternative -- writing
     * to some fallback key -- is exactly the global-state bug this class
     * exists to remove.
     */
    suspend fun setResumeText(text: String?) {
        val userId = sessionStore.currentUserId() ?: return
        context.dataStore.edit { prefs ->
            if (text == null) prefs.remove(keyFor(userId)) else prefs[keyFor(userId)] = text
        }
    }

    /** Wipes one user's resume text, e.g. for a "delete my data" action. */
    suspend fun clearFor(userId: String) {
        context.dataStore.edit { it.remove(keyFor(userId)) }
    }

    companion object {
        private const val PREFIX = "resume_text_for_"

        fun keyFor(userId: String): Preferences.Key<String> =
            stringPreferencesKey(PREFIX + userId)
    }
}
