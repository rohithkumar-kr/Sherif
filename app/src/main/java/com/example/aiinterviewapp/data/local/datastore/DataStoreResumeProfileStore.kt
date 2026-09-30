package com.example.aiinterviewapp.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The session user's [ResumeProfile], in the same DataStore file as everything
 * else.
 *
 * Two changes from Phase 2. The key is derived from the user id, so User B can
 * never read User A's profile (RULE 10). And the store is driven by the
 * *session* rather than by a global "who is logged in" flag, so signing out
 * stops emission instead of leaving the previous user's profile on screen.
 *
 * A pre-Phase-3 unscoped `resume_profile` entry is never adopted: its owner is
 * unknown and handing it to the next sign-in would be misattribution.
 */
@Singleton
class DataStoreResumeProfileStore @Inject constructor(
    private val sessionStore: SessionStore,
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) : ResumeProfileStore {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun resumeProfile(): Flow<ResumeProfile?> =
        sessionStore.userId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(null)
            } else {
                dataStore.data
                    .catch { throwable ->
                        if (throwable is IOException) {
                            emit(emptyPreferences())
                        } else {
                            throw throwable
                        }
                    }
                    .map { prefs -> decode(prefs[keyFor(userId)]) }
            }
        }

    override suspend fun saveResumeProfile(profile: ResumeProfile) {
        val userId = sessionStore.currentUserId() ?: return
        val encoded = json.encodeToString(ResumeProfile.serializer(), profile)
        dataStore.edit { prefs -> prefs[keyFor(userId)] = encoded }
    }

    override suspend fun clearResumeProfile() {
        val userId = sessionStore.currentUserId() ?: return
        dataStore.edit { prefs -> prefs.remove(keyFor(userId)) }
    }

    /**
     * A stored payload that cannot be decoded yields null rather than throwing,
     * so a corrupted entry degrades to "no analysis yet" instead of taking down
     * the screen that reads it.
     */
    private fun decode(raw: String?): ResumeProfile? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ResumeProfile.serializer(), raw) }.getOrNull()
    }

    companion object {
        private const val PREFIX = "resume_profile_for_"

        fun keyFor(userId: String): Preferences.Key<String> =
            stringPreferencesKey(PREFIX + userId)
    }
}
