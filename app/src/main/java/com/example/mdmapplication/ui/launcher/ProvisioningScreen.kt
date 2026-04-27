package com.example.mdmapplication.ui.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val ProvisioningBgTop = Color(0xFF09111D)
private val ProvisioningBgMid = Color(0xFF101827)
private val ProvisioningBgBottom = Color(0xFF05070C)
private val ProvisioningPanel = Color(0xDD111A27)
private val ProvisioningRow = Color(0x66192535)
private val ProvisioningBorder = Color.White.copy(alpha = 0.12f)
private val ProvisioningText = Color(0xFFF3F7FF)
private val ProvisioningMuted = Color(0xFFAEB9C8)
private val ProvisioningAccent = Color(0xFF7CCBFF)
private val ProvisioningPass = Color(0xFF7EE0A1)
private val ProvisioningFail = Color(0xFFFF8A96)
private val ProvisioningManual = Color(0xFFFFC66D)
private val ProvisioningRebootGreen = Color(0xFF2DBE72)

@Composable
fun ProvisioningScreen(
    setupState: SetupState,
    steps: List<ProvisioningStep>,
    loading: Boolean,
    error: String?,
    rebootError: String?,
    rebootRequested: Boolean,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onRetry: () -> Unit,
    onReboot: () -> Unit
) {
    val strings = provisioningStrings(language)
    val scrollState = rememberScrollState()
    val allPassed = steps.isNotEmpty() && steps.all { it.status == ProvisioningStepStatus.PASSED }
    val showReboot = allPassed || setupState == SetupState.REBOOT_RECOMMENDED
    val retryEnabled = !loading && !rebootRequested
    val rebootEnabled = showReboot && !rebootRequested
    val highlightedStep = steps.firstOrNull {
        it.status == ProvisioningStepStatus.MANUAL_REQUIRED || it.status == ProvisioningStepStatus.FAILED
    }
    val userMessage = userFacingProvisioningMessage(
        step = highlightedStep?.key,
        status = highlightedStep?.status,
        rawDetail = highlightedStep?.detail,
        language = language
    ) ?: userFacingProvisioningError(error, language)
    val technicalDetail = listOfNotNull(
        highlightedStep?.detail?.takeIf { it.isNotBlank() },
        error?.takeIf { it.isNotBlank() },
        rebootError?.takeIf { it.isNotBlank() }
    ).distinct().joinToString("\n")

    val revealedPassedKeys = remember { mutableStateListOf<ProvisioningStepKey>() }
    LaunchedEffect(steps.map { it.key to it.status }) {
        revealedPassedKeys.retainAll(steps.map { it.key }.toSet())
        steps.forEach { step ->
            if (
                step.status == ProvisioningStepStatus.PASSED &&
                step.key !in revealedPassedKeys
            ) {
                delay(140L)
                revealedPassedKeys += step.key
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(ProvisioningBgTop, ProvisioningBgMid, ProvisioningBgBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = ProvisioningPanel,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .border(1.dp, ProvisioningBorder, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ProvisioningHeader(
                        title = strings.title,
                        stateLabel = setupStateLabel(setupState, language),
                        changeLanguageLabel = strings.changeLanguage,
                        onLanguageChange = {
                            onLanguageChange(
                                if (language == AppLanguage.VI) AppLanguage.EN else AppLanguage.VI
                            )
                        }
                    )

                    Text(
                        text = strings.checklistTitle,
                        color = ProvisioningText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEach { step ->
                            val displayStatus = if (
                                step.status == ProvisioningStepStatus.PASSED &&
                                step.key !in revealedPassedKeys
                            ) {
                                ProvisioningStepStatus.PENDING
                            } else {
                                step.status
                            }
                            ProvisioningStepRow(
                                step = step,
                                displayStatus = displayStatus,
                                language = language
                            )
                        }
                    }

                    GuidancePanel(
                        title = strings.nextStepsTitle,
                        message = userMessage ?: strings.noActionNeeded,
                        technicalDetailsTitle = strings.technicalDetails,
                        technicalDetail = technicalDetail
                    )

                    ProvisioningPrimaryAction(
                        showReboot = showReboot,
                        loading = loading,
                        retryEnabled = retryEnabled,
                        rebootEnabled = rebootEnabled,
                        rebootRequested = rebootRequested,
                        strings = strings,
                        onRetry = onRetry,
                        onReboot = onReboot
                    )
                }
            }
        }
    }
}

@Composable
private fun ProvisioningHeader(
    title: String,
    stateLabel: String,
    changeLanguageLabel: String,
    onLanguageChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = ProvisioningText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stateLabel,
                color = ProvisioningMuted,
                style = MaterialTheme.typography.labelLarge
            )
        }

        OutlinedButton(
            onClick = onLanguageChange,
            modifier = Modifier.widthIn(min = 58.dp)
        ) {
            Text(changeLanguageLabel, color = ProvisioningText)
        }
    }
}

@Composable
private fun ProvisioningStepRow(
    step: ProvisioningStep,
    displayStatus: ProvisioningStepStatus,
    language: AppLanguage
) {
    val statusColor = when (displayStatus) {
        ProvisioningStepStatus.PENDING -> ProvisioningMuted
        ProvisioningStepStatus.RUNNING -> ProvisioningAccent
        ProvisioningStepStatus.PASSED -> ProvisioningPass
        ProvisioningStepStatus.FAILED -> ProvisioningFail
        ProvisioningStepStatus.MANUAL_REQUIRED -> ProvisioningManual
    }

    Surface(
        color = ProvisioningRow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProvisioningStatusIcon(status = displayStatus, statusColor = statusColor)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stepTitle(step.key, language),
                    color = ProvisioningText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stepSubtitle(step.copy(status = displayStatus), language),
                    color = ProvisioningMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            StatusBadge(
                label = statusLabel(displayStatus, language),
                color = statusColor
            )
        }
    }
}

@Composable
private fun ProvisioningStatusIcon(
    status: ProvisioningStepStatus,
    statusColor: Color
) {
    val targetAlpha = if (status == ProvisioningStepStatus.PASSED) 1f else 0.9f
    val targetScale = if (status == ProvisioningStepStatus.PASSED) 1.06f else 1f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 180),
        label = "provisioningStepAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "provisioningStepScale"
    )

    Surface(
        color = statusColor.copy(alpha = 0.16f),
        shape = CircleShape,
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .alpha(alpha)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (status == ProvisioningStepStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = statusColor
                )
            } else {
                Text(
                    text = statusGlyph(status),
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.widthIn(min = 72.dp, max = 118.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun GuidancePanel(
    title: String,
    message: String,
    technicalDetailsTitle: String,
    technicalDetail: String
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = Color(0x66192535),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ProvisioningBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = ProvisioningText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                color = ProvisioningMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            if (technicalDetail.isNotBlank()) {
                Text(
                    text = if (expanded) "$technicalDetailsTitle ▲" else "$technicalDetailsTitle ▼",
                    color = ProvisioningAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
                if (expanded) {
                    Text(
                        text = technicalDetail,
                        color = ProvisioningMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ProvisioningPrimaryAction(
    showReboot: Boolean,
    loading: Boolean,
    retryEnabled: Boolean,
    rebootEnabled: Boolean,
    rebootRequested: Boolean,
    strings: ProvisioningStrings,
    onRetry: () -> Unit,
    onReboot: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (showReboot) {
            Button(
                onClick = onReboot,
                enabled = rebootEnabled,
                modifier = Modifier.fillMaxWidth(0.72f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ProvisioningRebootGreen,
                    contentColor = Color.White,
                    disabledContainerColor = ProvisioningRebootGreen.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.72f)
                )
            ) {
                Text(if (rebootRequested) strings.rebootRequested else strings.reboot)
            }
        } else {
            OutlinedButton(
                onClick = onRetry,
                enabled = retryEnabled,
                modifier = Modifier.fillMaxWidth(0.72f)
            ) {
                Text(
                    text = if (loading) strings.checking else strings.retry,
                    color = if (retryEnabled) ProvisioningText else ProvisioningMuted
                )
            }
        }
    }
}

private fun statusGlyph(status: ProvisioningStepStatus): String =
    when (status) {
        ProvisioningStepStatus.PENDING -> "-"
        ProvisioningStepStatus.RUNNING -> ""
        ProvisioningStepStatus.PASSED -> "OK"
        ProvisioningStepStatus.FAILED -> "!"
        ProvisioningStepStatus.MANUAL_REQUIRED -> "!"
    }

