package com.example.aiinterviewapp.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the most recent successful [ResumeProfile] alongside the existing
 * resume text, in the same DataStore file.
 *
 * Takes the shared [DataStore] instance rather than creating its own delegate:
 * Android DataStore throws if two delegates target the same file, so exactly
 * one instance of "auth_prefs" may exist in the process.
 */
@Singleton
class DataStoreResumeProfileStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) : ResumeProfileStore {

    private val resumeProfile = stringPreferencesKey("resume_profile")

    override fun resumeProfile(): Flow<ResumeProfile?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw throwable
        }
        .map { prefs -> decode(prefs[resumeProfile]) }

    override suspend fun saveResumeProfile(profile: ResumeProfile) {
        val encoded = json.encodeToString(ResumeProfile.serializer(), profile)
        dataStore.edit { prefs -> prefs[resumeProfile] = encoded }
    }

    override suspend fun clearResumeProfile() {
        dataStore.edit { prefs -> prefs.remove(resumeProfile) }
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
}
