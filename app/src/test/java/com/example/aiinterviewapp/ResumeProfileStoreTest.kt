package com.example.aiinterviewapp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiinterviewapp.data.local.datastore.DataStoreResumeProfileStore
import com.example.aiinterviewapp.domain.model.ResumeEducation
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal in-memory DataStore so persistence can be tested on the JVM. */
private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }

    suspend fun raw(): Preferences = state.value
}

/**
 * The resume text key used by AuthPreferences. Duplicated here deliberately so
 * this test can prove the profile store never disturbs it.
 */
private val resumeTextKey = stringPreferencesKey("resume_text")

class ResumeProfileStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val profileA = ResumeProfile(
        candidateName = "Aarav Sharma",
        targetRole = "Android Developer",
        technicalSkills = listOf("Kotlin", "Jetpack Compose", "Room"),
        education = listOf(ResumeEducation(institution = "Anna University", degree = "B.Tech")),
        projects = listOf(ResumeProject(name = "Japanese Vocabulary App", technologies = listOf("Kotlin")))
    )

    private val profileB = ResumeProfile(
        candidateName = "Meera Iyer",
        targetRole = "Data Scientist",
        technicalSkills = listOf("Python", "Pandas", "TensorFlow", "Scikit-learn"),
        education = listOf(ResumeEducation(institution = "IIT Madras", degree = "B.Tech")),
        projects = listOf(ResumeProject(name = "Time Series Forecasting", technologies = listOf("TensorFlow")))
    )

    @Test
    fun `profile survives store and process recreation`() = runTest {
        val dataStore = FakePreferencesDataStore()
        DataStoreResumeProfileStore(dataStore, json).saveResumeProfile(profileA)

        // A brand new store instance stands in for a new process reading the
        // same on-disk DataStore.
        val afterRecreation = DataStoreResumeProfileStore(dataStore, json).resumeProfile().first()

        assertEquals(profileA, afterRecreation)
    }

    @Test
    fun `every persisted field is restored unchanged`() = runTest {
        val dataStore = FakePreferencesDataStore()
        DataStoreResumeProfileStore(dataStore, json).saveResumeProfile(profileA)

        val restored = DataStoreResumeProfileStore(dataStore, json).resumeProfile().first()!!

        assertEquals(profileA.candidateName, restored.candidateName)
        assertEquals(profileA.targetRole, restored.targetRole)
        assertEquals(profileA.technicalSkills, restored.technicalSkills)
        assertEquals(profileA.education, restored.education)
        assertEquals(profileA.projects, restored.projects)
        assertEquals(profileA, restored)
    }

    @Test
    fun `replacing the profile leaves only the new one`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val store = DataStoreResumeProfileStore(dataStore, json)
        store.saveResumeProfile(profileA)
        store.saveResumeProfile(profileB)

        val current = store.resumeProfile().first()!!

        assertEquals(profileB, current)
        assertTrue(current.technicalSkills.contains("TensorFlow"))
        assertTrue("no Android skill from profile A may remain", current.technicalSkills.none { it == "Room" })
        assertTrue(current.projects.none { it.name == "Japanese Vocabulary App" })
    }

    @Test
    fun `clear removes the stored profile`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val store = DataStoreResumeProfileStore(dataStore, json)
        store.saveResumeProfile(profileA)

        store.clearResumeProfile()

        assertNull(store.resumeProfile().first())
    }

    @Test
    fun `storing a profile does not disturb the existing resume text`() = runTest {
        val dataStore = FakePreferencesDataStore()
        dataStore.edit { it[resumeTextKey] = "Kotlin Jetpack Compose Room" }

        DataStoreResumeProfileStore(dataStore, json).saveResumeProfile(profileA)

        assertEquals("Kotlin Jetpack Compose Room", dataStore.raw()[resumeTextKey])
    }

    @Test
    fun `a corrupted stored payload degrades to null instead of throwing`() = runTest {
        val dataStore = FakePreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("resume_profile")] = "{not valid json" }

        val profile = DataStoreResumeProfileStore(dataStore, json).resumeProfile().first()

        assertNull(profile)
    }

    @Test
    fun `an empty store reports no profile`() = runTest {
        val profile = DataStoreResumeProfileStore(FakePreferencesDataStore(), json).resumeProfile().first()

        assertNull(profile)
    }
}
