package com.example.aiinterviewapp.ui.screens.report

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.ui.common.DimensionBar
import com.example.aiinterviewapp.ui.common.FeedbackSnackbar
import com.example.aiinterviewapp.ui.common.ScoreChip
import com.example.aiinterviewapp.ui.common.ScoreRing
import com.example.aiinterviewapp.ui.common.SherifButton
import com.example.aiinterviewapp.ui.common.SherifCard
import com.example.aiinterviewapp.ui.common.SherifOutlinedButton
import com.example.aiinterviewapp.ui.common.SherifSectionHeader
import com.example.aiinterviewapp.ui.common.formatInterviewDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    interviewId: String,
    onBack: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(interviewId) {
        viewModel.loadReport(interviewId)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Report", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
            when (uiState) {
                is ReportUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ReportUiState.Success -> {
                    val interview = (uiState as ReportUiState.Success).interview
                    ReportContent(
                        interview = interview,
                        onShare = { viewModel.exportAndShare(interview) },
                        onDone = onBack,
                        isExporting = isExporting
                    )
                }
                is ReportUiState.Error -> {
                    Text(
                        text = (uiState as ReportUiState.Error).message,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ReportContent(
    interview: Interview,
    onShare: () -> Unit,
    onDone: () -> Unit,
    isExporting: Boolean = false
) {
    val evaluations = interview.questions.mapNotNull { it.evaluation }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ScoreRing(score = interview.score)
        }

        item {
            SherifCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = interview.role,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${interview.type} · ${interview.difficulty} · ${interview.experience}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatInterviewDate(interview.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    ScoreChip(value = interview.score, max = 100, label = "Overall")
                }
            }
        }

        item {
            SherifCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Performance Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Average ratings across all questions, evaluated by Gemini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    DimensionBar("Confidence", dimOf(evaluations) { it.confidence })
                    Spacer(Modifier.height(12.dp))
                    DimensionBar("Technical", dimOf(evaluations) { it.technical })
                    Spacer(Modifier.height(12.dp))
                    DimensionBar("Communication", dimOf(evaluations) { it.communication })
                    Spacer(Modifier.height(12.dp))
                    DimensionBar("Grammar", dimOf(evaluations) { it.grammar })
                }
            }
        }

        item {
            SherifSectionHeader(title = "Question Breakdown", modifier = Modifier.fillMaxWidth())
        }

        items(interview.questions) { question ->
            EvaluationCard(question)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SherifOutlinedButton(
                    text = if (isExporting) "Preparing..." else "Share PDF",
                    onClick = onShare,
                    enabled = !isExporting,
                    icon = Icons.Default.Share,
                    modifier = Modifier.weight(1f).height(56.dp)
                )
                SherifButton(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier.weight(1f).height(56.dp)
                )
            }
        }
    }
}

private fun dimOf(
    evaluations: List<QuestionEvaluation>,
    selector: (QuestionEvaluation) -> Int
): Int {
    if (evaluations.isEmpty()) return 0
    return (evaluations.map(selector).average()).roundToInt()
}

@Composable
fun EvaluationCard(question: InterviewQuestion) {
    val evaluation = question.evaluation
    SherifCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Q",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (evaluation != null) {
                    ScoreChip(value = evaluation.score)
                }
            }
            if (question.answer != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Your answer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = question.answer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (evaluation != null) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Spacer(Modifier.height(14.dp))

                RatingRow("Confidence", evaluation.confidence)
                RatingRow("Technical", evaluation.technical)
                RatingRow("Communication", evaluation.communication)
                RatingRow("Grammar", evaluation.grammar)

                if (evaluation.strengths.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(text = "Strengths", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = evaluation.strengths,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (evaluation.weaknesses.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "Weaknesses", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        text = evaluation.weaknesses,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (evaluation.suggestions.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "Suggestions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = evaluation.suggestions,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RatingRow(label: String, score: Int) {
    DimensionBar(label = label, value = score, max = 10, modifier = Modifier.padding(vertical = 2.dp))
}