package com.example.aiinterviewapp.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.ui.common.SherifBrandMark
import com.example.aiinterviewapp.ui.common.SherifButton
import com.example.aiinterviewapp.ui.common.SherifCard
import com.example.aiinterviewapp.ui.common.SherifEmptyState
import com.example.aiinterviewapp.ui.common.SherifHistoryItem
import com.example.aiinterviewapp.ui.common.SherifOutlinedButton
import com.example.aiinterviewapp.ui.common.SherifSectionHeader
import java.util.Calendar

@Composable
fun HomeScreen(
    onNavigateToCreate: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToResume: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    onResumeInterview: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        DashboardTopBar(userName = uiState.userName, onProfile = onNavigateToProfile)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                WelcomeSection(userName = uiState.userName)
            }

            item {
                PrimaryAction(onClick = onNavigateToCreate)
            }

            item {
                AnimatedVisibility(
                    visible = uiState.resumableInterview != null && !uiState.isError,
                    enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
                ) {
                    uiState.resumableInterview?.let { resumable ->
                        ResumeSection(
                            interview = resumable,
                            onResume = { onResumeInterview(resumable.id) },
                            onDiscard = { viewModel.discardResumable(resumable.id) }
                        )
                    }
                }
            }

            item {
                QuickActions(
                    onPractice = onNavigateToCreate,
                    onHistory = onNavigateToHistory,
                    onResume = onNavigateToResume,
                    onSettings = onNavigateToSettings
                )
            }

            if (uiState.isError) {
                item {
                    DashboardErrorState(onRetry = viewModel::retry)
                }
            } else {
                item {
                    SherifSectionHeader(title = "Performance")
                }

                when {
                    uiState.isLoading -> item { PerformanceSkeleton() }
                    uiState.totalInterviews == 0 -> item {
                        SherifEmptyState(
                            icon = Icons.Default.Analytics,
                            title = "No interview results yet",
                            subtitle = "Complete your first interview to see your performance here."
                        )
                    }
                    else -> item {
                        PerformanceSection(state = uiState)
                    }
                }

                item {
                    SherifSectionHeader(
                        title = "Recent Practice",
                        actionLabel = "See all",
                        onAction = onNavigateToHistory
                    )
                }

                when {
                    uiState.isLoading -> item { RecentListSkeleton() }
                    uiState.recentInterviews.isEmpty() -> item {
                        SherifEmptyState(
                            icon = Icons.Default.Analytics,
                            title = "No practice sessions yet",
                            subtitle = "Complete an interview and your results will appear here.",
                            actionLabel = "Start an Interview",
                            onAction = onNavigateToCreate
                        )
                    }
                    else -> items(uiState.recentInterviews) { interview ->
                        SherifHistoryItem(
                            interview = interview,
                            onClick = { onNavigateToReport(interview.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTopBar(userName: String, onProfile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SherifBrandMark(size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "SHERIF",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "AI Interview Coach",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        ProfileAvatar(userName = userName, onClick = onProfile)
    }
}

@Composable
private fun ProfileAvatar(userName: String, onClick: () -> Unit) {
    val initial = userName
        .trim()
        .takeIf { it.isNotBlank() && !isGenericName(it) }
        ?.first()
        ?.uppercaseChar()
        ?: 'S'
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WelcomeSection(userName: String) {
    val displayName = userName.trim().takeIf { it.isNotBlank() && !isGenericName(it) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = if (displayName == null) {
                "Welcome back"
            } else {
                "${greetingForHour()}, ${displayName.split(" ").first()}"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Ready for your next interview?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrimaryAction(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Start AI Interview",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Practice with an AI interviewer and get detailed feedback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResumeSection(
    interview: Interview,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val answered = interview.questions.size
    val total = interview.questionCount.coerceAtLeast(1)
    val currentQuestion = (answered + 1).coerceAtMost(total)
    val progress = (answered.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    SherifCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Continue Interview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${interview.role} · ${interview.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Question $currentQuestion of $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SherifButton(
                    text = "Resume Interview",
                    onClick = onResume,
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
                SherifOutlinedButton(
                    text = "Discard",
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QuickActions(
    onPractice: () -> Unit,
    onHistory: () -> Unit,
    onResume: () -> Unit,
    onSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QuickActionCard(
                title = "Practice",
                subtitle = "New AI interview",
                icon = Icons.Default.PlayArrow,
                onClick = onPractice,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                title = "History",
                subtitle = "Past results",
                icon = Icons.Default.History,
                onClick = onHistory,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QuickActionCard(
                title = "Resume Manager",
                subtitle = "Analyze your PDF",
                icon = Icons.Default.Description,
                onClick = onResume,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                title = "Settings",
                subtitle = "Account & settings",
                icon = Icons.Default.Settings,
                onClick = onSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SherifCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PerformanceSection(state: HomeUiState) {
    SherifCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCell("Interviews", state.totalInterviews.toString())
            StatCell("Avg Score", "%.0f%%".format(state.avgScore), animate = true)
            StatCell("Best Score", "${state.bestScore}%", animate = true)
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, animate: Boolean = false) {
    val animatedValue by animateFloatAsState(
        targetValue = value.removeSuffix("%").toFloatOrNull() ?: 0f,
        animationSpec = tween(500),
        label = "stat"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (animate) "${animatedValue.toInt()}%" else value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PerformanceSkeleton() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {}
}

@Composable
private fun RecentListSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {}
        }
    }
}

@Composable
private fun DashboardErrorState(onRetry: () -> Unit) {
    SherifCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Couldn't load your dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Please try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            SherifButton(text = "Retry", onClick = onRetry)
        }
    }
}

private fun isGenericName(name: String): Boolean {
    return name.equals("Guest User", ignoreCase = true) ||
        name.equals("Google User", ignoreCase = true) ||
        name.equals("Guest", ignoreCase = true)
}

private fun greetingForHour(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}