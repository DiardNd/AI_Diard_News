package com.example.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.NewsSource
import com.example.domain.model.SourceSortMode
import com.example.domain.model.SourceStatusInfo
import com.example.presentation.components.AddSourceDialog
import com.example.presentation.components.EditSourceDialog
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.SourceDiagnosticsDialog
import com.example.presentation.components.SourceItemCard
import com.example.presentation.viewmodel.SourceDiagnosticsUiState

@Composable
fun SourcesScreen(
    sources: List<NewsSource>,
    onToggleSource: (Long, Boolean) -> Unit,
    onAddSource: (title: String, url: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onDeleteSource: (Long) -> Unit,
    onMoveSourceUp: (Long) -> Unit,
    onMoveSourceDown: (Long) -> Unit,
    onRestorePresets: () -> Unit,
    onExportToUri: (Uri) -> Unit,
    onImportFromUri: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    sourceSortMode: SourceSortMode = SourceSortMode.MANUAL,
    onSelectSortMode: (SourceSortMode) -> Unit = {},
    onEditSource: (id: Long, title: String, url: String, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, _, _, cb -> cb(true, null) },
    sourceStatuses: Map<Long, SourceStatusInfo> = emptyMap(),
    onDiagnoseSource: (Long) -> Unit = {},
    diagnosticsState: SourceDiagnosticsUiState = SourceDiagnosticsUiState(),
    onDismissDiagnostics: () -> Unit = {},
    onRetryDiagnostics: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }
    var addSourceError by remember { mutableStateOf<String?>(null) }

    var editingSource by remember { mutableStateOf<NewsSource?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var editSourceError by remember { mutableStateOf<String?>(null) }

    var showHelpHint by rememberSaveable { mutableStateOf(false) }

    // SAF Document Launchers
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onExportToUri(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportFromUri(uri)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    addSourceError = null
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_source_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить источник")
            }
        },
        modifier = modifier.testTag("sources_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Compact Header Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RssFeed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Источники (${sources.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { exportLauncher.launch("diardnews_backup.json") },
                        modifier = Modifier.testTag("export_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = "Экспорт JSON",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.testTag("import_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Импорт JSON",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showHelpHint = !showHelpHint },
                        modifier = Modifier.testTag("sources_help_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "Подсказка по источникам",
                            tint = if (showHelpHint) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            // Collapsible Help Card (closed by default)
            AnimatedVisibility(visible = showHelpHint) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("sources_help_hint_card")
                ) {
                    Text(
                        text = "Переключатель включает или скрывает новости источника. В ручном режиме сортировки стрелки ↑ и ↓ меняют порядок источников в списке и в фильтре ленты. Кнопка редактирования позволяет изменить название и URL без потери сохранённых новостей.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Compact Sort Mode Selector Strip
            val sortScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(sortScrollState)
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SourceSortMode.entries.forEach { mode ->
                    val isSelected = sourceSortMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectSortMode(mode) },
                        label = { Text(mode.shortLabel, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("sort_mode_${mode.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sources list or Empty State
            if (sources.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.RssFeed,
                    title = "Пока нет источников — добавьте первый",
                    description = "Добавьте RSS/Atom ленту любимого эмулятора вручную или восстановите стартовые пресеты (RetroArch, Dolphin, Winlator, Reddit, PPSSPP).",
                    actionText = "Добавить источник",
                    onActionClick = {
                        addSourceError = null
                        showAddDialog = true
                    },
                    secondaryActionText = "Восстановить пресеты",
                    onSecondaryActionClick = onRestorePresets,
                    testTag = "empty_state_sources"
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("sources_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = sources,
                        key = { _, item -> item.id }
                    ) { index, source ->
                        SourceItemCard(
                            source = source,
                            onToggle = { isEnabled -> onToggleSource(source.id, isEnabled) },
                            onDelete = { onDeleteSource(source.id) },
                            canMoveUp = index > 0,
                            canMoveDown = index < sources.lastIndex,
                            showManualSortControls = sourceSortMode == SourceSortMode.MANUAL,
                            onMoveUp = { onMoveSourceUp(source.id) },
                            onMoveDown = { onMoveSourceDown(source.id) },
                            onEdit = {
                                editSourceError = null
                                editingSource = source
                            },
                            onDiagnose = { onDiagnoseSource(source.id) },
                            statusInfo = sourceStatuses[source.id],
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = {
                addSourceError = null
                showAddDialog = false
            },
            onAdd = { title, url ->
                if (!isAdding) {
                    isAdding = true
                    addSourceError = null
                    onAddSource(title, url) { success, errMsg ->
                        isAdding = false
                        if (success) {
                            addSourceError = null
                            showAddDialog = false
                        } else {
                            addSourceError = errMsg ?: "Не удалось добавить источник"
                        }
                    }
                }
            },
            existingSources = sources,
            externalError = addSourceError,
            isSubmitting = isAdding
        )
    }

    val currentEditTarget = editingSource
    if (currentEditTarget != null) {
        EditSourceDialog(
            source = currentEditTarget,
            existingSources = sources,
            externalError = editSourceError,
            isSubmitting = isEditing,
            onDismiss = {
                editSourceError = null
                editingSource = null
            },
            onSave = { newTitle, newUrl ->
                if (!isEditing) {
                    isEditing = true
                    editSourceError = null
                    onEditSource(currentEditTarget.id, newTitle, newUrl) { success, errMsg ->
                        isEditing = false
                        if (success) {
                            editSourceError = null
                            editingSource = null
                        } else {
                            editSourceError = errMsg ?: "Не удалось сохранить изменения"
                        }
                    }
                }
            }
        )
    }

    if (diagnosticsState.isDialogVisible) {
        SourceDiagnosticsDialog(
            state = diagnosticsState,
            onRetry = onRetryDiagnostics,
            onDismiss = onDismissDiagnostics
        )
    }
}
