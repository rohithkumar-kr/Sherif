package com.example.aiinterviewapp.ui.screens.interview

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.aiinterviewapp.ui.common.FeedbackSnackbar
import com.example.aiinterviewapp.ui.common.SherifButton
import com.example.aiinterviewapp.ui.common.SherifOutlinedButton
import com.example.aiinterviewapp.ui.common.SherifTypingIndicator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewScreen(
    viewModel: InterviewViewModel,
    onNavigateToReport: (String) -> Unit,
    onQuit: () -> Unit,
    totalQuestions: Int? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isVoiceMode by viewModel.isVoiceMode.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showQuitDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var isDiscarding by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(uiState) {
        (uiState as? InterviewUiState.Success)?.let { chatMessages = it.messages }
        if (uiState is InterviewUiState.Completed) {
            onNavigateToReport((uiState as InterviewUiState.Completed).interviewId)
        }
    }

    val answeredCount = chatMessages.count { !it.isAi }
    val isThinking = uiState is InterviewUiState.Evaluating ||
        (uiState is InterviewUiState.Loading && chatMessages.isNotEmpty())
    val showErrorBubble = uiState is InterviewUiState.Error && chatMessages.isNotEmpty()
    val hasFooter = isThinking || showErrorBubble

    LaunchedEffect(chatMessages.size, hasFooter) {
        if (chatMessages.isNotEmpty()) {
            val target = if (hasFooter) chatMessages.size else chatMessages.size - 1
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput()
        } else {
            viewModel.showFeedback("Microphone permission is needed for voice input.")
        }
    }

    BackHandler(enabled = true) { showQuitDialog = true }

    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { if (!isPausing) showQuitDialog = false },
            title = { Text("Pause this interview?") },
            text = { Text("Your progress will be saved. You can resume this session later from the home screen.") },
            confirmButton = {
                TextButton(
                    enabled = !isPausing,
                    onClick = {
                        isPausing = true
                        scope.launch {
                            val saved = viewModel.saveAndPause()
                            isPausing = false
                            if (saved) {
                                showQuitDialog = false
                                onQuit()
                            } else {
                                viewModel.showFeedback("Could not save progress. Please try again.")
                            }
                        }
                    }
                ) {
                    Text(if (isPausing) "Saving…" else "Save & Pause")
                }
            },
            dismissButton = {
                TextButton(enabled = !isPausing, onClick = { showQuitDialog = false }) {
                    Text("Keep Going")
                }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDiscarding) showDiscardDialog = false },
            title = { Text("Discard this interview?") },
            text = { Text("All answers so far will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    enabled = !isDiscarding,
                    onClick = {
                        isDiscarding = true
                        scope.launch {
                            val deleted = viewModel.discardInterview()
                            isDiscarding = false
                            if (deleted) {
                                showDiscardDialog = false
                                onQuit()
                            } else {
                                viewModel.showFeedback("Could not discard the interview. Please try again.")
                            }
                        }
                    }
                ) {
                    Text(if (isDiscarding) "Discarding…" else "Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !isDiscarding, onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val onContinueLater: () -> Unit = {
        scope.launch {
            val saved = viewModel.saveAndPause()
            if (saved) {
                onQuit()
            } else {
                viewModel.showFeedback("Could not save progress. Please try again.")
            }
        }
    }

    val isResumeGrounded by viewModel.isResumeGrounded.collectAsState()

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "SHERIF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Session Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (isResumeGrounded) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                "Resume-Grounded",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { showQuitDialog = true }) {
                            Icon(Icons.Default.Close, contentDescription = "Quit")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleVoiceMode() }) {
                            Icon(
                                imageVector = if (isVoiceMode) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Toggle Voice Mode",
                                tint = if (isVoiceMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)
                    )
                )
                if (totalQuestions != null && totalQuestions > 0) {
                    InterviewProgress(answered = answeredCount.coerceAtMost(totalQuestions), total = totalQuestions)
                }
            }
        },
        bottomBar = {
            PremiumChatInput(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.submitAnswer(inputText)
                        inputText = ""
                    }
                },
                onVoiceClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.startVoiceInput()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                isLoading = uiState is InterviewUiState.Loading || isThinking || showErrorBubble
            )
        }
    ) { padding ->
        FeedbackSnackbar(
            snackbarHostState = snackbarHostState,
            feedback = feedback,
            onShown = { viewModel.consumeFeedback() }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState is InterviewUiState.Loading && chatMessages.isEmpty() -> {
                    InitialLoadingState()
                }
                uiState is InterviewUiState.Error && chatMessages.isEmpty() -> {
                    ErrorDisplay(
                        message = (uiState as InterviewUiState.Error).message,
                        onRetry = { viewModel.retry() },
                        onContinueLater = onContinueLater
                    )
                }
                else -> {
                    ChatList(
                        messages = chatMessages,
                        listState = listState,
                        thinking = isThinking,
                        errorMessage = (uiState as? InterviewUiState.Error)?.message
                            ?.takeIf { showErrorBubble },
                        onRetry = { viewModel.retry() },
                        onContinueLater = onContinueLater
                    )
                }
            }
        }
    }
}

@Composable
fun ChatList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    thinking: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onContinueLater: () -> Unit = {}
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(messages) { message ->
            PremiumChatBubble(message)
        }
        if (thinking) {
            item(key = "typing_footer") {
                AiTypingBubble()
            }
        } else if (errorMessage != null) {
            item(key = "error_footer") {
                AiErrorBubble(
                    message = errorMessage,
                    onRetry = onRetry,
                    onContinueLater = onContinueLater
                )
            }
        }
    }
}

@Composable
fun PremiumChatBubble(message: ChatMessage) {
    if (message.isAi) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.Start
        ) {
            AiAvatar()
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) {
                Text(
                    text = "SHERIF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    ),
                    tonalElevation = 1.dp
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 480.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "YOU",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 4.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    )
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiAvatar() {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AiTypingBubble() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AI is thinking" },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) {
            Text(
                text = "SHERIF",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp
            ) {
                SherifTypingIndicator(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun AiErrorBubble(
    message: String,
    onRetry: () -> Unit,
    onContinueLater: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) {
            Text(
                text = "SHERIF",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "I couldn't evaluate that answer.",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SherifButton(
                            text = "Try Again",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        )
                        SherifOutlinedButton(
                            text = "Continue Later",
                            onClick = onContinueLater,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onVoiceClick,
                enabled = !isLoading,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = MaterialTheme.colorScheme.secondary)
            }

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Share your thoughts...", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                maxLines = 4,
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.width(12.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = !isLoading && text.isNotBlank(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun InitialLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Preparing your interview\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        SherifTypingIndicator(dotColor = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun InterviewProgress(answered: Int, total: Int) {
    val fraction = (answered.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "Question ${(answered + 1).coerceAtMost(total)} of $total",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ErrorDisplay(message: String, onRetry: () -> Unit, onContinueLater: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        SherifButton(text = "Try Again", onClick = onRetry)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onContinueLater) {
            Text("Continue Later")
        }
    }
}