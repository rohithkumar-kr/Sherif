package com.example.aiinterviewapp.data.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.example.aiinterviewapp.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Obtains a Google-signed identity assertion using Credential Manager.
 *
 * What comes back is an **ID token**, not a password and not an API key: a
 * short-lived JWT that Google signed and that names an account. SHERIF never
 * sees or stores a Google password, and the token is exchanged immediately for
 * a SHERIF session.
 *
 * There is no client secret here and there must not be one. A secret shipped
 * inside an APK is public (RULE 2), which is exactly why the client id below is
 * safe to compile in: it identifies the *app* to Google, and the proof of
 * identity travels in the signed token.
 */
@Singleton
class GoogleSignInManager @Inject constructor(
    // The application context, not an activity: CredentialManager outlives any
    // single screen, and holding a scoped context here would leak it.
    @ApplicationContext private val context: Context
) {
    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    /**
     * The Web client id from the Google Cloud console.
     *
     * Credential Manager's Google ID flow requires this to be the **Web** OAuth
     * client id, not the Android one. Blank means Google sign-in is not
     * configured, which surfaces as a configuration error rather than a silent
     * failure.
     */
    private val clientId: String = BuildConfig.GOOGLE_CLIENT_ID

    val isConfigured: Boolean get() = clientId.isNotBlank()

    /**
     * Requests a Google ID token and returns it, or throws.
     *
     * [filterByAuthorizedAccount] false is deliberate: the picker is the user's
     * account chooser. A first run has no authorized account yet, and asking
     * for one would produce a confusing empty state rather than an account list.
     */
    suspend fun requestIdToken(
        activity: Activity,
        filterByAuthorizedAccount: Boolean = false
    ): GoogleIdTokenCredential {
        if (!isConfigured) {
            throw SignInException.ConfigurationMissing
        }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccount)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(filterByAuthorizedAccount)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val result = credentialManager.getCredential(activity, request)
            when (val credential = result.credential) {
                is GoogleIdTokenCredential -> credential
                else -> throw SignInException.NoGoogleCredential
            }
        } catch (exception: GetCredentialException) {
            throw SignInException.GoogleSignInFailed(exception.message)
        }
    }

    /**
     * Forgets the cached Google account choice.
     *
     * Called on sign-out so a later sign-in shows the picker again instead of
     * silently reusing the previous account, which matters when the next person
     * to use the device is not that user.
     */
    suspend fun clearCredentialState() {
        runCatching {
            credentialManager.clearCredentialState(
                androidx.credentials.ClearCredentialStateRequest()
            )
        }
    }
}

/** What can go wrong signing in, in terms the UI can act on. */
sealed class SignInException(message: String? = null) : Exception(message) {
    /** No OAuth client id compiled in, so the flow cannot even start. */
    data object ConfigurationMissing : SignInException("Google sign-in is not configured")

    /** The user cancelled, or no Google account was available. */
    data object NoGoogleCredential : SignInException("No Google account was selected")

    /** Google's own failure, e.g. network. */
    data class GoogleSignInFailed(val detail: String?) :
        SignInException(detail ?: "Google sign-in failed")
}
