package com.example.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.config.ChangelogProvider
import com.example.data.config.VersionChangelogEntry

data class HelpSection(
    val title: String,
    val icon: ImageVector,
    val summary: String,
    val details: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val changelogEntries = remember(context) {
        ChangelogProvider.loadFromContext(context)
    }
    val latestVersion = changelogEntries.firstOrNull()
    val previousVersions = remember(changelogEntries) {
        if (changelogEntries.size > 1) changelogEntries.drop(1) else emptyList()
    }
    var showPreviousVersions by rememberSaveable { mutableStateOf(false) }

    val sections = listOf(
        HelpSection(
            title = "1. Источники новостей (RSS / Atom)",
            icon = Icons.Default.RssFeed,
            summary = "Добавление, редактирование, сортировка, диагностика и статусы лент",
            details = listOf(
                "• Добавление: Нажмите вкладку «Источники» и кнопку «+» внизу экрана. Дубликаты URL автоматически распознаются с нормализацией адреса.",
                "• Редактирование: Нажмите значок карандаша на карточке источника, чтобы изменить название или RSS/Atom URL без потери сохранённых новостей.",
                "• Сортировка: Выберите режим («Ручная», «А–Я», «Сначала включённые», «Сначала новые») — выбор сохраняется после перезапуска. В ручном режиме доступны стрелки ↑ и ↓.",
                "• Диагностика и статусы: Карточка источника показывает причину проблем (ошибка сети/HTTP, 0 записей, нет новых статей, устаревший фид или ошибка БД). Кнопка «Диагностика» запускает подробную проверку без изменения базы.",
                "• Переключатель (Switch): Отключите источник, если не хотите видеть его новости. Отключённые источники не обновляются и не тратят трафик.",
                "• Удаление: Нажмите на значок корзины на карточке источника для его полного удаления."
            )
        ),
        HelpSection(
            title = "2. Фильтрация по источникам",
            icon = Icons.Default.FilterList,
            summary = "Быстрый выбор одного или нескольких RSS-источников прямо в ленте",
            details = listOf(
                "• Чипы источников: В верхней части ленты отображаются все включённые RSS-источники в выбранном вами порядке. Нажмите на нужные источники (доступен мультивыбор), чтобы оставить только их новости.",
                "• Прокрутка вверх: Повторное нажатие на активный чип («Все» или выбранный источник при прокрученной ленте) плавно возвращает список новостей в начало.",
                "• Показать всё: Нажмите чип «Все» или «Сбросить фильтры», чтобы вернуться к общей ленте.",
                "• Поиск: Компактная строка поиска работает поверх выбранных источников и ищет по оригинальному и переведённому тексту, а также по тегам."
            )
        ),
        HelpSection(
            title = "3. Теги и правила авто-разметки",
            icon = Icons.Default.AutoAwesome,
            summary = "Визуальные метки статей и настройка ключевых слов во вкладке «Теги»",
            details = listOf(
                "• Визуальные метки: Теги помогают быстро ориентироваться в темах статей прямо в карточке новости.",
                "• Редактирование правил: Перейдите на вкладку «Теги», выберите любой тег или создайте новый и укажите ключевые слова через запятую (например: «winlator, wine, mobox»).",
                "• Ручные теги: Открыв новость, вы можете вручную добавить к ней тег или удалить лишний.",
                "• Пересчёт базы: Кнопка «Пересчитать» пересканирует сохранённые новости по обновлённым правилам."
            )
        ),
        HelpSection(
            title = "4. Автоперевод превью и переводчик статей",
            icon = Icons.Default.Translate,
            summary = "Заголовки и краткие описания переводятся автоматически, полный текст — по кнопке",
            details = listOf(
                "• Превью в ленте: Заголовки и краткие описания новых новостей автоматически переводятся на русский и сохраняются в памяти (оригиналы не перезаписываются).",
                "• Полный текст: Откройте любую новость и нажмите «Перевести на русский» для перевода полного текста статьи.",
                "• Оригинал: Повторное нажатие кнопки возвращает оригинальный текст статьи."
            )
        ),
        HelpSection(
            title = "5. Выделение, копирование, поиск и чтение",
            icon = Icons.Default.Search,
            summary = "Выделяйте фрагменты текста, копируйте статьи и обновляйте ленту жестом",
            details = listOf(
                "• Выделение и копирование: Удерживайте палец на заголовке или тексте в карточке или внутри статьи, чтобы выделить и скопировать нужный фрагмент. На экране новости также есть кнопка копирования всей статьи.",
                "• Pull-to-Refresh: Потяните ленту новостей сверху вниз для обновления всех активных источников (обрабатывается до 30 свежих записей, хранится до 50 на источник).",
                "• Читать в браузере и Поделиться: Кнопки в верхней и нижней части статьи позволяют открыть оригинал или отправить ссылку."
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Справка и обучение", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("help_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        modifier = modifier.testTag("help_screen")
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("help_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Добро пожаловать в DiardNews!",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Краткое руководство по всем возможностям приложения. Нажмите на нужный раздел для подробностей.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (latestVersion != null) {
                item {
                    LatestChangelogCard(entry = latestVersion)
                }
            }

            if (previousVersions.isNotEmpty()) {
                item {
                    PreviousVersionsHistoryCard(
                        entries = previousVersions,
                        isExpanded = showPreviousVersions,
                        onToggle = { showPreviousVersions = !showPreviousVersions }
                    )
                }
            }

            items(sections) { section ->
                HelpSectionCard(section = section)
            }
        }
    }
}

@Composable
private fun LatestChangelogCard(entry: VersionChangelogEntry) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("whats_new_latest_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Что нового в версии ${entry.version}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (entry.title.isNotBlank()) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            entry.changes.forEach { change ->
                Text(
                    text = "• $change",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviousVersionsHistoryCard(
    entries: List<VersionChangelogEntry>,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("changelog_history_card")
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "История изменений (${entries.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Список изменений предыдущих версий (${entries.first().version}–${entries.last().version})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Свернуть историю" else "Развернуть историю",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("changelog_history_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = buildString {
                                    append("Версия ${entry.version}")
                                    if (entry.title.isNotBlank()) {
                                        append(" — ${entry.title}")
                                    }
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            entry.changes.forEach { change ->
                                Text(
                                    text = "• $change",
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HelpSectionCard(section: HelpSection) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 4.dp, end = 4.dp)
                ) {
                    section.details.forEach { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
