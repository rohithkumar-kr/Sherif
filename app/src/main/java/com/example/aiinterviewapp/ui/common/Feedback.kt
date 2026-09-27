package com.example.aiinterviewapp.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

data class FeedbackMessage(
    val message: String,
    val isError: Boolean = true
)

/**
 * Shows a snackbar whenever a new feedback message arrives and invokes
 * [onShown] afterwards so the producer can reset its pending message.
 */
@Composable
fun FeedbackSnackbar(
    snackbarHostState: SnackbarHostState,
    feedback: FeedbackMessage?,
    onShown: () -> Unit
) {
    LaunchedEffect(feedback) {
        val current = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.message)
        onShown()
    }
}