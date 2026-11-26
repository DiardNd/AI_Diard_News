package com.example.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.DiagnosticStep
import com.example.domain.model.DiagnosticStepStatus
import com.example.domain.model.SourceDiagnosticReport
import com.example.presentation.viewmodel.SourceDiagnosticsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourceDiagnosticsDialog(
    state: SourceDiagnosticsUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isDialogVisible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 700.dp)
                .testTag("source_diagnostics_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Диагностика источника",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.sourceTitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("diagnostics_close_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть диагностику"
                        )
                    }
                }

                if (state.sourceUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = state.sourceUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    when {
                        state.isLoading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp)
                                    .testTag("diagnostics_loading"),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Проверяем сервер, HTTP-ответ, формат RSS/Atom и локальную БД...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        state.errorMessage != null -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("diagnostics_error_box")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = state.errorMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        state.report != null -> {
                            DiagnosticsReportContent(report = state.report)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Footer buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !state.isLoading,
                        modifier = Modifier.testTag("diagnostics_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Повторить")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("diagnostics_close_button")
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsReportContent(report: SourceDiagnosticReport) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Overall status banner
        val (overallBg, overallAccent, overallIcon, overallHeadline) = when (report.overallStatus) {
            DiagnosticStepStatus.SUCCESS -> Quad(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.primary,
                Icons.Default.CheckCircle,
                "Источник полностью исправен"
            )
            DiagnosticStepStatus.WARNING -> Quad(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.tertiary,
                Icons.Default.Warning,
                "Обнаружены замечания: ${report.failedStageTitle ?: "проверьте детали"}"
            )
            DiagnosticStepStatus.ERROR -> Quad(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                MaterialTheme.colorScheme.error,
                Icons.Default.Error,
                "Сбой на этапе: ${report.failedStageTitle ?: "проверка не пройдена"}"
            )
            DiagnosticStepStatus.SKIPPED -> Quad(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.outline,
                Icons.Default.RemoveCircleOutline,
                "Проверка не завершена"
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = overallBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("diagnostics_overall_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = overallIcon,
                    contentDescription = null,
                    tint = overallAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = overallHeadline,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Summary metrics card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("diagnostics_metrics_card")
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Сводка параметров",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                MetricRow(
                    label = "Записей из источника:",
                    value = "${report.fetchedItemsCount} (в лимит обновления: ${report.processableItemsCount})"
                )

                val latestDateText = when {
                    report.latestFeedPubDate != null -> formatDiagnosticDate(report.latestFeedPubDate)
                    !report.latestFeedRawPubDate.isNullOrBlank() ->
                        "Не распознана («${report.latestFeedRawPubDate}»)"
                    report.fetchedItemsCount > 0 -> "Отсутствует в XML"
                    else -> "—"
                }
                MetricRow(
                    label = "Свежая публикация:",
                    value = latestDateText
                )

                MetricRow(
                    label = "HTTP / Формат:",
                    value = buildString {
                        append(report.httpCode?.let { "HTTP $it" } ?: "Нет ответа")
                        if (report.responseTimeMs != null) {
                            append(" • ${report.responseTimeMs} мс")
                        }
                        if (report.fetchedItemsCount > 0 || report.feedFormat.name != "UNKNOWN") {
                            append(" • ${report.feedFormat.label}")
                        }
                    }
                )

                MetricRow(
                    label = "Локальная БД и лента:",
                    value = "в БД ${report.storedInDbCount} • видно в ленте ${report.visibleInFeedCount} • новых ${report.wouldInsertNewCount}"
                )
            }
        }

        // Step-by-step details
        report.steps.forEach { step ->
            DiagnosticStepCard(step = step)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun DiagnosticStepCard(step: DiagnosticStep) {
    val (icon, tint, statusLabel) = when (step.status) {
        DiagnosticStepStatus.SUCCESS -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "ОК")
        DiagnosticStepStatus.WARNING -> Triple(Icons.Default.Warning, MaterialTheme.colorScheme.tertiary, "Внимание")
        DiagnosticStepStatus.ERROR -> Triple(Icons.Default.Error, MaterialTheme.colorScheme.error, "Ошибка")
        DiagnosticStepStatus.SKIPPED -> Triple(Icons.Default.RemoveCircleOutline, MaterialTheme.colorScheme.outline, "Пропущено")
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostics_step_${step.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = tint
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = step.summary,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!step.technicalDetails.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = step.technicalDetails,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private fun formatDiagnosticDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
