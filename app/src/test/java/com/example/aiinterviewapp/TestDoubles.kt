package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.local.datastore.SessionStore
import com.example.aiinterviewapp.data.remote.api.SherifBackendApi
import com.example.aiinterviewapp.data.remote.model.BackendSessionRequest
import com.example.aiinterviewapp.data.remote.model.BackendSessionResponse
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Test doubles for the Phase 3 networking and session layers.
 *
 * Centralised so a change to [SherifBackendApi]'s shape is one edit here rather
 * than four edits across the suite. Nothing in this file talks to a network or
 * needs a device.
 */

fun geminiResponseOf(text: String): GeminiResponse = GeminiResponse(
    candidates = listOf(
        GeminiResponse.Candidate(
            content = GeminiResponse.Content(
                parts = listOf(GeminiResponse.Part(text = text))
            )
        )
    )
)

/**
 * A fake that answers every AI endpoint from one provider.
 *
 * [error] is declared before [responseProvider] so a trailing lambda lands on
 * the provider, which is what every test actually wants to supply.
 */
class FakeSherifBackendApi(
    private val error: Throwable? = null,
    private val responseProvider: () -> GeminiResponse = { geminiResponseOf("{\"question\": \"Default?\"}") }
) : SherifBackendApi {

    /** Every request seen, in order, across all three endpoints. */
    val requests = mutableListOf<GeminiRequest>()

    /** The endpoint paths exercised, so a test can prove they are distinct. */
    val endpointsUsed = mutableListOf<String>()

    override suspend fun createSession(request: BackendSessionRequest): BackendSessionResponse =
        BackendSessionResponse(
            accessToken = "test-access-token",
            userId = "test-user",
            expiresAt = Long.MAX_VALUE
        )

    override suspend fun createDevelopmentSession(): BackendSessionResponse {
        devSessionRequests++
        error?.let { throw it }
        return BackendSessionResponse(
            accessToken = "test-dev-access-token",
            userId = "dev:local",
            expiresAt = Long.MAX_VALUE
        )
    }

    /**
     * How many times the development session route was called.
     *
     * Counted so a test can assert a *refused* development sign-in never
     * reached the network, which is the property that distinguishes a disabled
     * entry from a merely hidden one.
     */
    var devSessionRequests: Int = 0
        private set

    override suspend fun analyzeResume(request: GeminiRequest): GeminiResponse =
        record("resume/analyze", request)

    override suspend fun generateQuestion(request: GeminiRequest): GeminiResponse =
        record("interview/question", request)

    override suspend fun evaluateAnswer(request: GeminiRequest): GeminiResponse =
        record("interview/evaluate", request)

    private suspend fun record(endpoint: String, request: GeminiRequest): GeminiResponse {
        requests.add(request)
        endpointsUsed.add(endpoint)
        error?.let { throw it }
        return responseProvider()
    }
}

/**
 * A [SessionStore] stub for the single user id tests care about.
 *
 * `SessionStore` needs a `Context`, which a JVM test has no way to provide, so
 * the session is mocked instead. Mockito 5's inline mock maker handles the
 * final class.
 */
fun fakeSessionStore(userId: String?): SessionStore {
    val store = mock<SessionStore>()
    // `currentUserId()` is suspend, so the call that registers the stub has to
    // happen inside a coroutine. `runBlocking` supplies one; the stubbed
    // behaviour itself stays synchronous for the test.
    runBlocking { whenever(store.currentUserId()).thenReturn(userId) }
    whenever(store.userId).thenReturn(flowOf(userId))
    return store
}

/** A session store that reports nobody signed in. */
fun signedOutSessionStore(): SessionStore = fakeSessionStore(null)

/**
 * A [SessionStore] whose user id changes mid-test, to prove that flows
 * re-scope rather than serving a cached value.
 */
class SwitchableSessionStore(initialUserId: String?) {
    private val state = MutableStateFlow(initialUserId)

    val store: SessionStore = mock<SessionStore>().also { mockStore ->
        whenever(mockStore.userId).thenReturn(state)
        runBlocking {
            whenever(mockStore.currentUserId()).thenAnswer { state.value }
        }
    }

    fun signInAs(userId: String) {
        state.value = userId
    }

    fun signOut() {
        state.value = null
    }
}
