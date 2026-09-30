package com.example.aiinterviewapp.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single DataStore instance for "auth_prefs".
 *
 * Exposed to the rest of the module so the resume profile store shares this
 * exact instance. Creating a second `preferencesDataStore` delegate for the
 * same file makes DataStore throw at runtime.
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

/**
 * Durable session state: the bearer token, who it belongs to, and when it ends.
 *
 * This is the app's answer to "is anyone signed in", replacing the Phase 0-2
 * boolean. A session is a *credential with an expiry*, which is what makes
 * expiry detection possible at all -- a boolean cannot expire.
 *
 * Nothing here is a long-lived secret of the backend: the token authorises this
 * device to act as this user and nothing else, and revoking it is a server-side
 * key rotation or simply waiting it out. The Gemini credential is not in this
 * file, or anywhere else on the device.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ACCESS_TOKEN = stringPreferencesKey("session_access_token")
    private val USER_ID = stringPreferencesKey("session_user_id")
    private val USER_EMAIL = stringPreferencesKey("session_user_email")
    private val USER_NAME = stringPreferencesKey("session_user_name")
    private val EXPIRES_AT = longPreferencesKey("session_expires_at")

    /** True while a non-expired session is stored (RULE 5). */
    val isSignedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val token = prefs[ACCESS_TOKEN]
        val expiresAt = prefs[EXPIRES_AT]
        !token.isNullOrBlank() && expiresAt != null && expiresAt > System.currentTimeMillis()
    }

    /** The authenticated user's id, or null when signed out. */
    val userId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_ID]?.takeIf { prefs[ACCESS_TOKEN] != null }
    }

    val userEmail: Flow<String?> = context.dataStore.data.map { it[USER_EMAIL] }
    val userName: Flow<String?> = context.dataStore.data.map { it[USER_NAME] }

    /** The bearer token for the current session, if it has not expired. */
    val accessToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN]?.takeIf {
            val expiresAt = prefs[EXPIRES_AT]
            expiresAt != null && expiresAt > System.currentTimeMillis()
        }
    }

    suspend fun saveSession(
        accessToken: String,
        userId: String,
        expiresAt: Long,
        email: String? = null,
        name: String? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            prefs[USER_ID] = userId
            prefs[EXPIRES_AT] = expiresAt
            email?.let { prefs[USER_EMAIL] = it }
            name?.let { prefs[USER_NAME] = it }
        }
    }

    /**
     * Drops the credential but leaves user-owned content alone (RULE 6).
     *
     * Signing out must not delete the signed-in user's resume or interviews:
     * isolation comes from scoping, not from destruction, so signing back in
     * restores their own data and nobody else's.
     */
    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(USER_ID)
            prefs.remove(EXPIRES_AT)
            prefs.remove(USER_EMAIL)
            prefs.remove(USER_NAME)
        }
    }

    /** The currently stored user id, or null. Used to scope local queries. */
    suspend fun currentUserId(): String? = context.dataStore.data.first().let { prefs ->
        prefs[USER_ID]?.takeIf { !prefs[ACCESS_TOKEN].isNullOrBlank() }
    }

    /** Emits the stored expiry in epoch millis, or null when signed out. */
    suspend fun currentExpiryMillis(): Long? =
        context.dataStore.data.first()[EXPIRES_AT]

    /**
     * The bearer token to send right now, or null when signed out or expired.
     *
     * Expiry is checked on *every* read rather than cached, so a session that
     * lapsed in the background is never presented as valid (RULE 5).
     */
    suspend fun currentAccessToken(): String? = context.dataStore.data.first().let { prefs ->
        val token = prefs[ACCESS_TOKEN]
        val expiresAt = prefs[EXPIRES_AT]
        if (token.isNullOrBlank() || expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            null
        } else {
            token
        }
    }
}
