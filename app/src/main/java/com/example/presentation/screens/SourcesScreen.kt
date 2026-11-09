package com.example.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.NewsSource
import com.example.presentation.components.AddSourceDialog
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.SourceItemCard

@Composable
fun SourcesScreen(
    sources: List<NewsSource>,
    onToggleSource: (Long, Boolean) -> Unit,
    onAddSource: (title: String, url: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onDeleteSource: (Long) -> Unit,
    onRestorePresets: () -> Unit,
    onExportToUri: (Uri) -> Unit,
    onImportFromUri: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }

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
                onClick = { showAddDialog = true },
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
            // Header Card with SAF Export/Import buttons
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Управление RSS/Atom источниками",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Отключите переключатель, чтобы скрыть новости источника из ленты. Новости загружаются автоматически при обновлении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Export / Import buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("diardnews_backup.json") },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_config_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Экспорт JSON", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_config_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Импорт JSON", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Sources list or Empty State
            if (sources.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.RssFeed,
                    title = "Пока нет источников — добавьте первый",
                    description = "Добавьте RSS/Atom ленту любимого эмулятора вручную или восстановите стартовые пресеты (RetroArch, Dolphin, Winlator, Reddit, PPSSPP).",
                    actionText = "Добавить источник",
                    onActionClick = { showAddDialog = true },
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
                    items(
                        items = sources,
                        key = { it.id }
                    ) { source ->
                        SourceItemCard(
                            source = source,
                            onToggle = { isEnabled -> onToggleSource(source.id, isEnabled) },
                            onDelete = { onDeleteSource(source.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title, url ->
                isAdding = true
                onAddSource(title, url) { success, _ ->
                    isAdding = false
                    if (success) {
                        showAddDialog = false
                    }
                }
            }
        )
    }
}
