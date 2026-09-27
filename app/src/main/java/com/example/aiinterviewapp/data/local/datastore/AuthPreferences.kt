package com.example.aiinterviewapp.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single DataStore instance for "auth_prefs".
 *
 * Exposed to the rest of the module so the resume profile store shares this
 * exact instance. Creating a second `preferencesDataStore` delegate for the
 * same file makes DataStore throw at runtime.
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

@Singleton
class AuthPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val USER_EMAIL = stringPreferencesKey("user_email")
    private val DARK_MODE = booleanPreferencesKey("dark_mode")
    private val RESUME_TEXT = stringPreferencesKey("resume_text")

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[IS_LOGGED_IN] ?: false }
    val userName: Flow<String?> = context.dataStore.data.map { it[USER_NAME] }
    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { it[DARK_MODE] ?: false }
    val resumeText: Flow<String?> = context.dataStore.data.map { it[RESUME_TEXT] }

    suspend fun setLoggedIn(isLoggedIn: Boolean, name: String? = null, email: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[IS_LOGGED_IN] = isLoggedIn
            name?.let { prefs[USER_NAME] = it }
            email?.let { prefs[USER_EMAIL] = it }
        }
    }

    suspend fun setResumeText(text: String?) {
        context.dataStore.edit { prefs ->
            if (text == null) prefs.remove(RESUME_TEXT) else prefs[RESUME_TEXT] = text
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[DARK_MODE] = enabled }
    }

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
    }
}
