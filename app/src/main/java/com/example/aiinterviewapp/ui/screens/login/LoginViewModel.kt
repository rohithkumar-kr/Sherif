package com.example.aiinterviewapp.ui.screens.login

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinterviewapp.domain.repository.AuthRepository
import com.example.aiinterviewapp.domain.repository.AuthResult
import com.example.aiinterviewapp.utils.DevAuthPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the login screen.
 *
 * The Phase 0-2 version had two buttons -- "Continue with Google" and "Continue
 * as Guest" -- where the first set `is_logged_in = true` and the string
 * "Google User" without contacting anyone, and the second did the same with
 * "Guest User". Neither established an identity, so there was no user to scope
 * data to. Both are gone: there is one path, and it ends in a token the backend
 * verified (RULE 5).
 *
 * The [Activity] is passed in rather than held, because Credential Manager's
 * Google ID flow needs a live activity at the moment the chooser appears.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val devAuthPolicy: DevAuthPolicy = DevAuthPolicy.fromBuildConfig()
) : ViewModel() {

    private val _loginEvent = MutableSharedFlow<Unit>()
    val loginEvent: SharedFlow<Unit> = _loginEvent

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Whether to draw the development entry.
     *
     * False in every release build, where it is a constant the compiler folds
     * away, so the button is not merely invisible -- it does not exist.
     */
    val isDevelopmentEntryAvailable: Boolean = devAuthPolicy.isAvailable

    /**
     * Signs in as the development identity.
     *
     * Ends in the same [AuthResult] handling as [loginWithGoogle] and emits the
     * same event, so the app navigates to Home by the existing route with no
     * development-specific navigation to keep in step.
     */
    fun signInForDevelopment() {
        if (_uiState.value.isLoading) return
        if (!isDevelopmentEntryAvailable) return

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = authRepository.signInForDevelopment()) {
                is AuthResult.Success -> {
                    _uiState.value = LoginUiState()
                    _loginEvent.emit(Unit)
                }
                is AuthResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    /**
     * Signs in with Google, then exchanges the ID token for a SHERIF session.
     *
     * Failure is a first-class outcome. Showing an error and staying on the
     * screen is the honest response to "Google sign-in was cancelled" or "the
     * backend rejected this token"; proceeding as if signed in is how a user
     * ends up with an app full of someone else's data.
     */
    fun loginWithGoogle(activity: Activity) {
        if (_uiState.value.isLoading) return

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(activity)) {
                is AuthResult.Success -> {
                    _uiState.value = LoginUiState()
                    _loginEvent.emit(Unit)
                }
                is AuthResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
