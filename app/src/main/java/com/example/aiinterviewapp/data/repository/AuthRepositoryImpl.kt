package com.example.aiinterviewapp.data.repository

import android.app.Activity
import com.example.aiinterviewapp.data.auth.GoogleSignInManager
import com.example.aiinterviewapp.data.auth.SignInException
import com.example.aiinterviewapp.data.local.datastore.SessionStore
import com.example.aiinterviewapp.data.remote.api.SherifBackendApi
import com.example.aiinterviewapp.data.remote.model.BackendSessionRequest
import com.example.aiinterviewapp.data.remote.model.SherifBackendException
import com.example.aiinterviewapp.domain.repository.AuthRepository
import com.example.aiinterviewapp.domain.repository.AuthResult
import com.example.aiinterviewapp.utils.DevAuthPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a Google identity assertion into a SHERIF session.
 *
 * The order matters and is the whole point: Google token first, SHERIF session
 * second. Only after the backend has verified the token and told us who the user
 * is do we write a session locally. `userId` is therefore never a value this
 * app invented -- it is echoed from a verified identity, which is what makes
 * local data scoping trustworthy (RULE 9).
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val googleSignInManager: GoogleSignInManager,
    private val backendApi: SherifBackendApi,
    private val sessionStore: SessionStore,
    private val devAuthPolicy: DevAuthPolicy = DevAuthPolicy.fromBuildConfig()
) : AuthRepository {

    override suspend fun signInWithGoogle(activity: Activity): AuthResult =
        try {
            val idToken = googleSignInManager.requestIdToken(activity)
            val session = backendApi.createSession(BackendSessionRequest(idToken = idToken.idToken!!))
            sessionStore.saveSession(
                accessToken = session.accessToken,
                userId = session.userId,
                expiresAt = session.expiresAt,
                email = null,
                name = null
            )
            AuthResult.Success(session.userId)
        } catch (exception: SignInException) {
            AuthResult.Failure(exception.message ?: "Sign-in failed")
        } catch (exception: SherifBackendException) {
            // A rejected ID token is a sign-in failure, not an app error. The
            // message comes from the backend's closed error contract, so it is
            // already safe to show.
            AuthResult.Failure(exception.message)
        } catch (exception: Exception) {
            AuthResult.Failure(exception.message ?: "Sign-in failed")
        }

    /**
     * Asks a development backend for a development session.
     *
     * Refuses outright unless [devAuthPolicy] says this build may, which in
     * practice means a debug build. The check is a compile-time constant in
     * release, so this method is dead code there rather than a runtime branch.
     *
     * Everything after the check is identical to [signInWithGoogle]: the same
     * [SessionStore.saveSession], the same token, the same expiry. That is why
     * a development session is not a second authentication system. The
     * resulting session expires, is cached, is signed out by [signOut] and
     * passes through the same interceptors, so nothing downstream needs to
     * know how it was obtained.
     *
     * Note what is *not* here: no fabricated Google identity and no local token
     * minted on the device. The session comes from the backend, signed with the
     * backend's key, exactly as a real one is.
     */
    override suspend fun signInForDevelopment(): AuthResult {
        if (!devAuthPolicy.isAvailable) {
            return AuthResult.Failure("Development sign-in is not available in this build")
        }

        return try {
            val session = backendApi.createDevelopmentSession()
            sessionStore.saveSession(
                accessToken = session.accessToken,
                userId = session.userId,
                expiresAt = session.expiresAt,
                email = null,
                name = null
            )
            AuthResult.Success(session.userId)
        } catch (exception: SherifBackendException) {
            // Usually the backend was not started with SHERIF_DEV_AUTH_ENABLED,
            // which answers 404 because the route does not exist there. Saying
            // so is the difference between a five-second fix and an afternoon.
            AuthResult.Failure(
                "Development sign-in needs a backend started with SHERIF_DEV_AUTH_ENABLED=true"
            )
        } catch (exception: Exception) {
            AuthResult.Failure(exception.message ?: "Development sign-in failed")
        }
    }

    /**
     * Ends the session.
     *
     * Only local state is destroyed. The user's resume and interviews stay on
     * the device under their own user id, so signing back in restores exactly
     * their data and no one else's (RULE 6, RULE 10).
     *
     * A development session is cleared by this same call. It is not a special
     * state, so there is nothing extra to clean up and no way for one to
     * outlive a sign-out.
     */
    override suspend fun signOut() {
        googleSignInManager.clearCredentialState()
        sessionStore.clearSession()
    }
}
