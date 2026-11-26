package com.example.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun EditSourceDialog(
    source: NewsSource,
    existingSources: List<NewsSource> = emptyList(),
    externalError: String? = null,
    isSubmitting: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember(source.id) { mutableStateOf(source.title) }
    var url by remember(source.id) { mutableStateOf(source.url) }
    var localError by remember(source.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(externalError) {
        if (!externalError.isNullOrBlank()) {
            localError = externalError
        }
    }

    val displayedError = localError ?: externalError

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Редактировать источник")
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
                    onValueChange = {
                        title = it
                        localError = null
                    },
                    label = { Text("Название *") },
                    leadingIcon = { Icon(Icons.Default.Title, contentDescription = null) },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_source_title_input")
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
                        .testTag("edit_source_url_input")
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "При изменении URL уже сохранённые новости останутся в базе без дублирования.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val cleanTitle = title.trim()
                    val cleanUrl = url.trim()
                    if (cleanTitle.isBlank()) {
                        localError = "Введите название источника"
                        return@Button
                    }
                    if (cleanUrl.isBlank() ||
                        (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                            !cleanUrl.startsWith("https://", ignoreCase = true))
                    ) {
                        localError = "Введите корректный URL (начиная с http:// или https://)"
                        return@Button
                    }
                    val normalizedNew = UrlNormalizer.normalizeForComparison(cleanUrl)
                    val duplicate = existingSources.firstOrNull {
                        it.id != source.id && UrlNormalizer.normalizeForComparison(it.url) == normalizedNew
                    }
                    if (duplicate != null) {
                        localError = "Этот источник уже добавлен («${duplicate.title}»)"
                        return@Button
                    }
                    onSave(cleanTitle, cleanUrl)
                },
                modifier = Modifier.testTag("submit_edit_source_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_edit_source_button")
            ) {
                Text("Отмена")
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.testTag("edit_source_dialog")
    )
}
