package com.example.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TagItem

@Composable
fun EditTagDialog(
    tag: TagItem? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, keywords: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    var keywords by remember { mutableStateOf(tag?.keywords ?: "") }
    var selectedColor by remember { mutableStateOf(tag?.colorHex ?: "#00D2D3") }
    var isError by remember { mutableStateOf(false) }

    val presetColors = listOf(
        "#00D2D3", "#FF9F43", "#54A0FF", "#FF6B6B",
        "#48DBFB", "#1DD1A1", "#A29BFE", "#F368E0",
        "#0ABDE3", "#EE5A24", "#10AC84", "#5F27CD"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (tag == null) "Новый тег и правила авто-поиска" else "Редактировать тег «${tag.name}»")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        isError = false
                    },
                    label = { Text("Название тега *") },
                    placeholder = { Text("например: Cassia") },
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Введите название тега") }
                    } else null,
                    modifier = Modifier.fillMaxWidth().testTag("tag_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Ключевые слова для авто-тега") },
                    placeholder = { Text("cassia, windows on android, fex") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    supportingText = {
                        Text("Через запятую. Если в новости встретится любое из слов — тег присвоится автоматически.")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tag_keywords_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Цвет тега:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    presetColors.take(6).forEach { hex ->
                        ColorCircle(
                            hex = hex,
                            isSelected = selectedColor.equals(hex, ignoreCase = true),
                            onClick = { selectedColor = hex }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    presetColors.drop(6).take(6).forEach { hex ->
                        ColorCircle(
                            hex = hex,
                            isSelected = selectedColor.equals(hex, ignoreCase = true),
                            onClick = { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isBlank()) {
                        isError = true
                        return@Button
                    }
                    onSave(clean, selectedColor, keywords.trim())
                },
                modifier = Modifier.testTag("save_tag_button")
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    )
}

@Composable
private fun ColorCircle(
    hex: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = parseColor(hex)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .clickable { onClick() }
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
