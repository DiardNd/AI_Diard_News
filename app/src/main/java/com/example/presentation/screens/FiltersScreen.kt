package com.example.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TagItem
import com.example.presentation.components.EditTagDialog
import com.example.presentation.components.parseColor
import com.example.presentation.viewmodel.NewsFeedUiState

@Composable
fun FiltersScreen(
    uiState: NewsFeedUiState,
    onToggleTag: (Long) -> Unit,
    onClearAll: () -> Unit,
    onApply: () -> Unit,
    onSaveTag: (name: String, colorHex: String, keywords: String) -> Unit,
    onDeleteTag: (Long) -> Unit,
    onReapplyAutoTags: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingTag by remember { mutableStateOf<TagItem?>(null) }
    var isCreatingTag by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("filters_screen")
    ) {
        // Status & Guide Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Фильтры и правила авто-тегов",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.selectedTagIds.isEmpty()) {
                        "Отображаются ВСЕ новости. Отметьте теги галочками, чтобы фильтровать ленту. Вы также можете настраивать ключевые слова для авто-тегирования."
                    } else {
                        "Выбрано тегов: ${uiState.selectedTagIds.size} из ${uiState.tags.size}. В ленте будут новости с любым из отмеченных тегов."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Auto-tag control actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { isCreatingTag = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("add_new_tag_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Создать тег", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onReapplyAutoTags,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("reapply_auto_tags_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Пересчитать", fontSize = 12.sp)
                    }
                }
            }
        }

        // Action row: Clear filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Теги и ключевые слова (${uiState.tags.size})",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (uiState.selectedTagIds.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag("clear_all_filters_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сбросить")
                }
            }
        }

        // Tags List or Empty State
        if (uiState.tags.isEmpty()) {
            com.example.presentation.components.EmptyStateView(
                icon = Icons.Default.FilterAlt,
                title = "Нет доступных тегов",
                description = "В приложении пока нет созданных тегов. Добавьте свой первый тег и настройте для него ключевые слова авто-поиска.",
                actionText = "Создать тег",
                onActionClick = { isCreatingTag = true },
                modifier = Modifier.weight(1f),
                testTag = "empty_state_tags"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .testTag("tags_filter_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            items(
                items = uiState.tags,
                key = { it.id }
            ) { tag ->
                val isSelected = uiState.selectedTagIds.contains(tag.id)
                val tagColor = parseColor(tag.colorHex)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_tag_card_${tag.id}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleTag(tag.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("filter_checkbox_${tag.id}")
                        )

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(tagColor)
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleTag(tag.id) }
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (tag.keywords.isNotBlank()) {
                                Text(
                                    text = "Ключевые слова: ${tag.keywords}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = "Автопоиск по имени тега",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }

                        // Edit tag auto-keywords button
                        IconButton(
                            onClick = { editingTag = tag },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("edit_tag_button_${tag.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Редактировать ключевые слова",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Delete tag button
                        IconButton(
                            onClick = { onDeleteTag(tag.id) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("delete_tag_button_${tag.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Удалить тег",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

        // Bottom Apply Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apply_filters_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Применить фильтры (${uiState.articles.size} новостей)")
            }
        }
    }

    if (isCreatingTag) {
        EditTagDialog(
            tag = null,
            onDismiss = { isCreatingTag = false },
            onSave = { name, colorHex, keywords ->
                onSaveTag(name, colorHex, keywords)
                isCreatingTag = false
            }
        )
    }

    editingTag?.let { tagToEdit ->
        EditTagDialog(
            tag = tagToEdit,
            onDismiss = { editingTag = null },
            onSave = { name, colorHex, keywords ->
                onSaveTag(name, colorHex, keywords)
                editingTag = null
            }
        )
    }
}
