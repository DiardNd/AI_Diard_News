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
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    val sections = listOf(
        HelpSection(
            title = "1. Источники новостей (RSS / Atom)",
            icon = Icons.Default.RssFeed,
            summary = "Как добавлять свои ленты, отключать ненужные и использовать пресеты",
            details = listOf(
                "• Добавление: Нажмите вкладку «Источники» и кнопку «+» внизу экрана. Введите название и URL RSS-ленты (например: https://example.com/feed).",
                "• Готовые пресеты: В окне добавления доступны быстрые ссылки на блоги RetroArch, Dolphin, Reddit r/emulation, Winlator, PPSSPP и RPCS3.",
                "• Переключатель (Switch): Отключите источник, если не хотите видеть его новости. Отключённые источники не обновляются и не тратят мобильный трафик.",
                "• Удаление: Нажмите на значок корзины рядом с источником для его полного удаления."
            )
        ),
        HelpSection(
            title = "2. Фильтры и теги новостей",
            icon = Icons.Default.FilterList,
            summary = "Гибкий показ только интересных платформ и систем эмуляции",
            details = listOf(
                "• Быстрые чипы: В верхней части ленты можно в один клик выбрать тег («RetroArch», «Winlator», «PS2», «Switch») или нажать «Все».",
                "• Мультивыбор: На вкладке «Фильтры» выберите несколько тегов галочками. Лента покажет статьи, у которых есть хотя бы один из выбранных тегов.",
                "• Ручные теги: Открыв любую новость, вы можете добавить к ней новый тег или удалить существующий (клик по крестику).",
                "• Сброс: Кнопка «Сбросить» быстро возвращает показ полной ленты."
            )
        ),
        HelpSection(
            title = "3. Настройка правил авто-тегирования",
            icon = Icons.Default.AutoAwesome,
            summary = "Управляйте ключевыми словами для автоматического назначения тегов",
            details = listOf(
                "• Как это работает: При скачивании новостей DiardNews сканирует заголовок и текст статьи на наличие ключевых слов каждого тега.",
                "• Редактирование правил: Нажмите кнопку «Настройка авто-тегов» (значок волшебной палочки в «Фильтрах»). Выберите любой тег и укажите ключевые слова через запятую (например: «winlator, wine, mobox»).",
                "• Создание своего тега: Вы можете добавить свой тег (например: «Vita3K») со своими словами («vita, psvita, vita3k»).",
                "• Пересчёт базы: Кнопка «Применить авто-теги ко всей ленте» пересканирует уже загруженные новости по новым правилам."
            )
        ),
        HelpSection(
            title = "4. Экранный переводчик на русский язык",
            icon = Icons.Default.Translate,
            summary = "Удобное чтение зарубежных новостей и гайдов без перехода в другие приложения",
            details = listOf(
                "• Перевод новости: Откройте любую новость и нажмите синюю кнопку «🌐 Перевести на русский».",
                "• Что переводится: Заголовок, краткое описание и весь основной текст статьи с сохранением структуры.",
                "• Оригинал: Повторное нажатие кнопки возвращает оригинальный текст на английском.",
                "• Кэш перевода: Переведённая новость сохраняется в памяти, поэтому переключение происходит моментально."
            )
        ),
        HelpSection(
            title = "5. Поиск, обновление и чтение",
            icon = Icons.Default.Search,
            summary = "Полезные жесты и функции для удобной навигации",
            details = listOf(
                "• Pull-to-Refresh: Потяните ленту новостей сверху вниз для обновления всех активных источников.",
                "• Поиск: Введите любое слово в строке поиска — фильтрация сработает по заголовку, аннотации и тегам одновременно.",
                "• Читать в браузере: Кнопка «Читать в первоисточнике» на экране новости откроет официальный пост на сайте разработчиков.",
                "• Поделиться: Кнопка со стрелочкой позволяет отправить ссылку на новость в Telegram, Discord или другие мессенджеры."
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
                .padding(innerPadding),
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

            items(sections) { section ->
                HelpSectionCard(section = section)
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
