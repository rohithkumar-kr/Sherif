package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.auth.GoogleSignInManager
import com.example.aiinterviewapp.data.local.datastore.SessionStore
import com.example.aiinterviewapp.data.remote.model.SherifBackendException
import com.example.aiinterviewapp.data.remote.model.SherifErrorCode
import com.example.aiinterviewapp.data.repository.AuthRepositoryImpl
import com.example.aiinterviewapp.domain.repository.AuthResult
import com.example.aiinterviewapp.utils.DevAuthPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The development entry, and the properties that keep it out of production.
 *
 * [AuthRepositoryImpl] takes its [DevAuthPolicy] as a constructor argument
 * precisely so these can be asserted without building a release APK: "release
 * cannot enable this" is the claim most worth testing and the hardest to test
 * if the flag is read from `BuildConfig` deep inside the logic.
 */
class DevAuthPolicyTest {

    // --- 1. Disabled by default ----------------------------------------------

    @Test
    fun `dev auth is unavailable when the build does not enable it`() {
        val policy = DevAuthPolicy(buildEnabled = false, apiConfigured = true)

        assertFalse(policy.isAvailable)
    }

    @Test
    fun `dev auth is unavailable with no backend configured`() {
        // A debug build pointed at the reserved .invalid placeholder could only
        // fail, so the entry is withheld rather than offered and broken.
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = false)

        assertFalse(policy.isAvailable)
    }

    @Test
    fun `the disabled policy is unavailable`() {
        assertFalse(DevAuthPolicy.DISABLED.isAvailable)
    }

    // --- 2. Explicitly enabled -----------------------------------------------

    @Test
    fun `dev auth is available when a debug build has a backend`() {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        assertTrue(policy.isAvailable)
    }

    // --- 3. Production configuration cannot enable it ------------------------

    @Test
    fun `a production-shaped configuration cannot enable dev auth`() {
        // What release builds compile against: DEV_AUTH_ENABLED=false, however
        // the backend happens to be addressed.
        listOf(true, false).forEach { apiConfigured ->
            assertFalse(
                "a release build must not offer dev auth (apiConfigured=$apiConfigured)",
                DevAuthPolicy(buildEnabled = false, apiConfigured = apiConfigured).isAvailable
            )
        }
    }

    @Test
    fun `the development identity is namespaced`() {
        assertTrue(DevAuthPolicy.DEV_USER_ID.startsWith(DevAuthPolicy.DEV_USER_ID_PREFIX))
        assertEquals("dev:local", DevAuthPolicy.DEV_USER_ID)
    }

    // --- 4. The development session is recognised ----------------------------

    @Test
    fun `a development user id is recognised`() {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        assertTrue(policy.isDevelopmentUser(DevAuthPolicy.DEV_USER_ID))
        assertTrue(policy.isDevelopmentUser("dev:anything-else"))
    }

    @Test
    fun `a real user id is not treated as development`() {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        assertFalse(policy.isDevelopmentUser("1234567890"))
        assertFalse(policy.isDevelopmentUser("dev"))
        assertFalse(policy.isDevelopmentUser(null))
    }
}

/**
 * The repository half: that a development session is stored and cleared through
 * the ordinary [SessionStore], and that the whole thing refuses to run in a
 * build which has not opted in.
 */
class DevSignInRepositoryTest {

    private val googleSignInManager: GoogleSignInManager = mock()
    private val sessionStore: SessionStore = mock()

    private fun repository(policy: DevAuthPolicy, api: FakeSherifBackendApi = FakeSherifBackendApi()) =
        AuthRepositoryImpl(
            googleSignInManager = googleSignInManager,
            backendApi = api,
            sessionStore = sessionStore,
            devAuthPolicy = policy
        )

    // --- The session is real, and stored normally ----------------------------

    @Test
    fun `development sign-in stores the session the backend issued`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        val result = repository(policy).signInForDevelopment()

        assertTrue(result is AuthResult.Success)
        // Stored through the same call Google sign-in uses, with the id the
        // backend returned. Nothing here invents an identity.
        verify(sessionStore).saveSession(
            accessToken = "test-dev-access-token",
            userId = DevAuthPolicy.DEV_USER_ID,
            expiresAt = Long.MAX_VALUE,
            email = null,
            name = null
        )
    }

    @Test
    fun `the development identity is recognised after sign-in`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        val result = repository(policy).signInForDevelopment()

        assertEquals(DevAuthPolicy.DEV_USER_ID, (result as AuthResult.Success).userId)
        assertTrue(policy.isDevelopmentUser(result.userId))
    }

    // --- 3. Refused in a build that has not opted in -------------------------

    @Test
    fun `development sign-in is refused when the build disables it`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = false, apiConfigured = true)
        val api = FakeSherifBackendApi()

        val result = repository(policy, api).signInForDevelopment()

        assertTrue(result is AuthResult.Failure)
        // The decisive assertions: the network was never touched and nothing
        // was written. A refused entry that still called the server would be a
        // bypass with extra steps.
        assertEquals("no dev session may be requested", 0, api.devSessionRequests)
        verify(sessionStore, never()).saveSession(
            accessToken = any(),
            userId = any(),
            expiresAt = any(),
            email = anyOrNull(),
            name = anyOrNull()
        )
    }

    @Test
    fun `development sign-in is refused with no backend configured`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = false)

        val result = repository(policy).signInForDevelopment()

        assertTrue(result is AuthResult.Failure)
    }

    // --- 5. Logout clears it, like any other session -------------------------

    @Test
    fun `signing out clears a development session`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)
        val repository = repository(policy)
        repository.signInForDevelopment()

        repository.signOut()

        // The same clearSession a real sign-out performs, so a development
        // session cannot outlive a sign-out or need its own cleanup path.
        verify(sessionStore).clearSession()
        verify(googleSignInManager).clearCredentialState()
    }

    @Test
    fun `a development session is a normal session to the store`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        repository(policy).signInForDevelopment()

        // Scoped like any other user id, which is what keeps development data
        // out of a real user's scope.
        verify(sessionStore).saveSession(
            accessToken = any(),
            userId = org.mockito.kotlin.eq(DevAuthPolicy.DEV_USER_ID),
            expiresAt = any(),
            email = anyOrNull(),
            name = anyOrNull()
        )
    }

    @Test
    fun `guest sign-in stores guest session`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)

        val result = repository(policy).signInAsGuest()

        assertTrue(result is AuthResult.Success)
        verify(sessionStore).saveSession(
            accessToken = any(),
            userId = any(),
            expiresAt = any(),
            email = org.mockito.kotlin.eq("guest@example.com"),
            name = org.mockito.kotlin.eq("Guest User")
        )
    }

    // --- 6. An unreachable or refused backend is a reported failure -----------

    @Test
    fun `a backend without the dev route reports how to fix it`() = runTest {
        val policy = DevAuthPolicy(buildEnabled = true, apiConfigured = true)
        val api = FakeSherifBackendApi(
            error = SherifBackendException(SherifErrorCode.MALFORMED_REQUEST, "The request could not be processed.")
        )

        val result = repository(policy, api).signInForDevelopment()

        assertTrue(result is AuthResult.Failure)
        assertTrue(
            "the message must name the environment variable the operator needs to set",
            (result as AuthResult.Failure).message.contains("SHERIF_DEV_AUTH_ENABLED")
        )
    }
}
