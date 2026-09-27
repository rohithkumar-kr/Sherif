package com.example.aiinterviewapp.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.BuildConfig
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _loginEvent = MutableSharedFlow<Unit>()
    val loginEvent: SharedFlow<Unit> = _loginEvent

    fun loginAsGuest() {
        viewModelScope.launch {
            authPreferences.setLoggedIn(true, "Guest User", "")
            _loginEvent.emit(Unit)
        }
    }

    fun loginWithGoogle() {
        viewModelScope.launch {
            if (BuildConfig.GOOGLE_CLIENT_ID.isBlank()) {
                // No OAuth client is configured for this build. The real
                // Google Sign-In integration cannot run without a client ID
                // from the Google Cloud Console, so fall back to a local
                // guest session instead of fabricating an account.
                authPreferences.setLoggedIn(true, "Guest User", "")
                _loginEvent.emit(Unit)
                return@launch
            }
            // TODO(real Google Sign-In): wire Credential Manager /
            // GoogleSignInClient here using BuildConfig.GOOGLE_CLIENT_ID and
            // persist the real profile via authPreferences.setLoggedIn.
            // Required before enabling: an OAuth 2.0 Web + Android client ID
            // in Google Cloud Console and the app's SHA-1 fingerprint.
            authPreferences.setLoggedIn(true, "Google User", "")
            _loginEvent.emit(Unit)
        }
    }
}