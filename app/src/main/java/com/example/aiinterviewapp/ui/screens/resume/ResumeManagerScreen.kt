package com.example.aiinterviewapp.ui.screens.resume

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aiinterviewapp.domain.model.ResumeProfile

/**
 * Resume formats offered by the picker.
 *
 * The picker filters, but it does not enforce: a provider can still hand back
 * something else, which `ResumeDocumentType` then rejects before any OCR or
 * Gemini work happens.
 */
private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/pdf",
    "image/jpeg",
    "image/png"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeManagerScreen(
    onBack: () -> Unit,
    onStartResumeInterview: () -> Unit = {},
    viewModel: ResumeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Hold a read grant so the document stays readable for the duration of
        // extraction, including any OCR pass over rendered pages.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.uploadResume(uri)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resume Manager", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                LoadingState(stage = uiState.stage)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    InfoCard()

                    uiState.error?.let { message ->
                        ErrorCard(
                            message = message,
                            canRetry = uiState.canRetry,
                            onRetry = { viewModel.retry() },
                            onDismiss = { viewModel.clearError() }
                        )
                    }

                    if (uiState.isProfileStale) {
                        StaleProfileNotice(onReanalyze = { viewModel.reanalyze() })
                    }

                    uiState.profile?.let { profile ->
                        ResumeProfileCard(profile = profile)
                    }

                    uiState.unsupportedClaims.takeIf { it.isNotEmpty() }?.let { claims ->
                        UnsupportedClaimsCard(claims)
                    }

                    if (uiState.hasResume) {
                        Button(
                            onClick = onStartResumeInterview,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Resume Interview", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        ResumeDetailCard(
                            text = uiState.extractedText ?: "",
                            onDelete = { viewModel.deleteResume() }
                        )

                        OutlinedButton(
                            onClick = { launcher.launch(SUPPORTED_MIME_TYPES) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Replace Resume")
                        }

                        OutlinedButton(
                            onClick = { viewModel.reanalyze() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Re-analyze with AI")
                        }
                    } else {
                        EmptyResumeState { launcher.launch(SUPPORTED_MIME_TYPES) }
                    }
                }
            }
        }
    }
}

/**
 * Reports the operation that is genuinely running.
 *
 * OCR gets its own message rather than being folded into extraction, so a
 * normal text PDF never shows a "reading scanned resume" message it did not
 * earn, and Gemini is never mentioned before the request is actually sent.
 */
@Composable
private fun LoadingState(stage: ResumeStage) {
    val title: String
    val detail: String
    when (stage) {
        ResumeStage.EXTRACTING -> {
            title = "Extracting resume text..."
            detail = "Reading text from your document"
        }
        ResumeStage.RUNNING_OCR -> {
            title = "Reading scanned resume..."
            detail = "No selectable text was found, so this is being read on your device"
        }
        ResumeStage.READING_IMAGE -> {
            title = "Reading resume image..."
            detail = "This is being read on your device"
        }
        ResumeStage.ANALYZING -> {
            title = "Analyzing resume with AI..."
            detail = "Asking Gemini to build your resume profile"
        }
        ResumeStage.IDLE -> {
            title = "Working..."
            detail = "Preparing your resume"
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
fun InfoCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Uploading your resume lets Gemini analyse it into a structured profile, " +
                    "then generate interview questions grounded in your actual experience.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (canRetry) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun StaleProfileNotice(onReanalyze: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "The profile below is from your previous resume.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReanalyze) { Text("Analyze") }
        }
    }
}

@Composable
private fun ResumeProfileCard(profile: ResumeProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI Resume Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))

            profile.candidateName?.let { ProfileField("Candidate", it) }
            profile.targetRole?.let { ProfileField("Target role", it) }
            profile.summary?.let { ProfileField("Summary", it) }

            if (profile.technicalSkills.isNotEmpty()) {
                ProfileChips("Technical skills", profile.technicalSkills)
            }
            if (profile.softSkills.isNotEmpty()) {
                ProfileChips("Soft skills", profile.softSkills)
            }
            if (profile.projects.isNotEmpty()) {
                ProfileSection("Projects") {
                    profile.projects.forEach { project ->
                        Text(
                            text = listOfNotNull(project.name, project.technologies.joinToString(", ").ifBlank { null })
                                .joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (profile.education.isNotEmpty()) {
                ProfileSection("Education") {
                    profile.education.forEach { entry ->
                        Text(
                            text = listOfNotNull(
                                entry.degree,
                                entry.field,
                                entry.institution,
                                entry.graduationYear
                            ).joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (profile.workExperience.isNotEmpty()) {
                ProfileSection("Experience") {
                    profile.workExperience.forEach { entry ->
                        Text(
                            text = listOfNotNull(entry.role, entry.company, entry.duration)
                                .joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (profile.certifications.isNotEmpty()) {
                ProfileChips("Certifications", profile.certifications)
            }
            if (profile.languages.isNotEmpty()) {
                ProfileChips("Languages", profile.languages)
            }
            if (profile.achievements.isNotEmpty()) {
                ProfileSection("Achievements") {
                    profile.achievements.forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (profile.strengths.isNotEmpty()) {
                ProfileSection("Strengths") {
                    profile.strengths.forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (profile.areasForImprovement.isNotEmpty()) {
                ProfileSection("Areas for improvement") {
                    profile.areasForImprovement.forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val quality = profile.resumeQuality
            if (quality.overallRating != null || quality.notes != null) {
                ProfileSection("Resume quality") {
                    Text(
                        text = listOfNotNull(
                            quality.overallRating?.let { "Overall: $it" },
                            quality.clarityRating?.let { "Clarity: $it" },
                            quality.impactRating?.let { "Impact: $it" },
                            quality.notes
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (profile.missingInformation.isNotEmpty()) {
                ProfileSection("Missing information") {
                    profile.missingInformation.forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileChips(title: String, values: List<String>) {
    ProfileSection(title) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedClaimsCard(claims: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Some details Gemini mentioned are not in your resume, so they were not " +
                    "added to your profile:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(6.dp))
            claims.forEach {
                Text(
                    text = "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
fun ResumeDetailCard(text: String, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Extracted Content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun EmptyResumeState(onUpload: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Ready to boost your prep?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Upload your resume as a PDF, JPG, JPEG or PNG to get an AI profile and personalized interview questions. Scanned documents are read on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onUpload,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select Resume File")
        }
    }
}
