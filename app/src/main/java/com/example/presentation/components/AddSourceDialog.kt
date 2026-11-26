package com.example.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.NewsSource
import com.example.domain.model.UrlNormalizer

data class FeedPreset(val name: String, val url: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, url: String) -> Unit,
    modifier: Modifier = Modifier,
    existingSources: List<NewsSource> = emptyList(),
    externalError: String? = null,
    isSubmitting: Boolean = false
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalError) {
        if (!externalError.isNullOrBlank()) {
            localError = externalError
        }
    }

    val displayedError = localError ?: externalError

    val presets = listOf(
        FeedPreset("Winlator Releases", "https://github.com/brunodev85/winlator/releases.atom"),
        FeedPreset("RetroArch Blog", "https://www.libretro.com/index.php/feed/"),
        FeedPreset("Dolphin Blog", "https://dolphin-emu.org/blog/feeds/"),
        FeedPreset("Reddit r/emulation", "https://www.reddit.com/r/emulation/.rss"),
        FeedPreset("PPSSPP Feed", "https://www.ppsspp.org/feed.xml"),
        FeedPreset("RPCS3 Blog", "https://rpcs3.net/blog/feed/")
    )

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Добавить RSS/Atom источник")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название (необязательно)") },
                    placeholder = { Text("например: RetroArch Blog") },
                    leadingIcon = { Icon(Icons.Default.Title, contentDescription = null) },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("source_title_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        localError = null
                    },
                    label = { Text("URL RSS/Atom ленты *") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = true,
                    enabled = !isSubmitting,
                    isError = !displayedError.isNullOrBlank(),
                    supportingText = if (!displayedError.isNullOrBlank()) {
                        { Text(displayedError) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("source_url_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Готовые пресеты (нажмите для вставки):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        SuggestionChip(
                            onClick = {
                                if (!isSubmitting) {
                                    title = preset.name
                                    url = preset.url
                                    localError = null
                                }
                            },
                            label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val cleanUrl = url.trim()
                    if (cleanUrl.isBlank() ||
                        (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                            !cleanUrl.startsWith("https://", ignoreCase = true))
                    ) {
                        localError = "Введите корректный URL (начиная с http:// или https://)"
                        return@Button
                    }
                    val normalizedCandidate = UrlNormalizer.normalizeForComparison(cleanUrl)
                    val duplicate = existingSources.firstOrNull {
                        UrlNormalizer.normalizeForComparison(it.url) == normalizedCandidate
                    }
                    if (duplicate != null) {
                        localError = "Этот источник уже добавлен («${duplicate.title}»)"
                        return@Button
                    }
                    onAdd(title, cleanUrl)
                },
                modifier = Modifier.testTag("submit_add_source_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = onDismiss
            ) {
                Text("Отмена")
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    )
}
