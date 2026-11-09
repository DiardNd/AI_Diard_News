package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.NewsArticle
import com.example.presentation.navigation.Screen
import com.example.presentation.screens.DetailScreen
import com.example.presentation.screens.FeedScreen
import com.example.presentation.screens.FiltersScreen
import com.example.presentation.screens.HelpScreen
import com.example.presentation.screens.SourcesScreen
import com.example.presentation.viewmodel.NewsViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: NewsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                DiardNewsApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun DiardNewsApp(viewModel: NewsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Feed) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    val selectedArticle = uiState.selectedArticle

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentScreen !is Screen.Detail) {
                NavigationBar(modifier = Modifier.testTag("bottom_navigation_bar")) {
                    NavigationBarItem(
                        selected = currentScreen is Screen.Feed,
                        onClick = { currentScreen = Screen.Feed },
                        icon = { Icon(Icons.Default.DynamicFeed, contentDescription = "Лента") },
                        label = { Text("Лента") },
                        modifier = Modifier.testTag("nav_tab_feed")
                    )

                    NavigationBarItem(
                        selected = currentScreen is Screen.Sources,
                        onClick = { currentScreen = Screen.Sources },
                        icon = { Icon(Icons.Default.RssFeed, contentDescription = "Источники") },
                        label = { Text("Источники") },
                        modifier = Modifier.testTag("nav_tab_sources")
                    )

                    NavigationBarItem(
                        selected = currentScreen is Screen.Filters,
                        onClick = { currentScreen = Screen.Filters },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (uiState.selectedTagIds.isNotEmpty()) {
                                        Badge { Text("${uiState.selectedTagIds.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "Фильтры")
                            }
                        },
                        label = { Text("Фильтры") },
                        modifier = Modifier.testTag("nav_tab_filters")
                    )

                    NavigationBarItem(
                        selected = currentScreen is Screen.Help,
                        onClick = { currentScreen = Screen.Help },
                        icon = { Icon(Icons.Default.HelpOutline, contentDescription = "Справка") },
                        label = { Text("Справка") },
                        modifier = Modifier.testTag("nav_tab_help")
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen_transition"
        ) { targetScreen ->
            when (targetScreen) {
                is Screen.Feed -> {
                    FeedScreen(
                        uiState = uiState,
                        onRefresh = { viewModel.refreshFeeds() },
                        onArticleClick = { article ->
                            viewModel.selectArticle(article.id)
                            currentScreen = Screen.Detail(article.id)
                        },
                        onOpenFilters = { currentScreen = Screen.Filters },
                        onOpenHelp = { currentScreen = Screen.Help },
                        onNavigateToSources = { currentScreen = Screen.Sources },
                        onToggleTag = { tagId -> viewModel.toggleTagFilter(tagId) },
                        onClearTagFilters = { viewModel.clearTagFilters() },
                        onSearchQueryChange = { q -> viewModel.setSearchQuery(q) },
                        onDismissNetworkError = { viewModel.clearNetworkError() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                is Screen.Sources -> {
                    SourcesScreen(
                        sources = uiState.sources,
                        onToggleSource = { id, enabled -> viewModel.toggleSource(id, enabled) },
                        onAddSource = { title, url, onComplete ->
                            viewModel.addSource(title, url, onComplete)
                        },
                        onDeleteSource = { id -> viewModel.deleteSource(id) },
                        onRestorePresets = { viewModel.resetDefaultSources() },
                        onExportToUri = { uri ->
                            coroutineScope.launch {
                                try {
                                    val json = viewModel.exportConfigJson()
                                    withContext(Dispatchers.IO) {
                                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                                            stream.write(json.toByteArray(Charsets.UTF_8))
                                        }
                                    }
                                    snackbarHostState.showSnackbar("Конфигурация успешно экспортирована")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Ошибка экспорта: ${e.message}")
                                }
                            }
                        },
                        onImportFromUri = { uri ->
                            coroutineScope.launch {
                                try {
                                    val json = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            stream.bufferedReader(Charsets.UTF_8).readText()
                                        }
                                    }
                                    if (json != null) {
                                        viewModel.importConfigJson(json)
                                    } else {
                                        snackbarHostState.showSnackbar("Не удалось прочитать выбранный файл")
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Ошибка импорта: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                is Screen.Filters -> {
                    FiltersScreen(
                        uiState = uiState,
                        onToggleTag = { tagId -> viewModel.toggleTagFilter(tagId) },
                        onClearAll = { viewModel.clearTagFilters() },
                        onApply = { currentScreen = Screen.Feed },
                        onSaveTag = { name, colorHex, keywords ->
                            viewModel.saveTag(name, colorHex, keywords)
                        },
                        onDeleteTag = { tagId -> viewModel.deleteTag(tagId) },
                        onReapplyAutoTags = { viewModel.reapplyAutoTags() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                is Screen.Help -> {
                    HelpScreen(
                        onBack = { currentScreen = Screen.Feed },
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                is Screen.Detail -> {
                    if (selectedArticle != null) {
                        DetailScreen(
                            article = selectedArticle,
                            translation = uiState.translations[selectedArticle.id],
                            isTranslating = uiState.isTranslating,
                            onBack = {
                                viewModel.selectArticle(null)
                                currentScreen = Screen.Feed
                            },
                            onToggleTranslate = {
                                viewModel.toggleArticleTranslation(selectedArticle)
                            },
                            onAddTag = { tagName ->
                                viewModel.addTagToArticle(selectedArticle.id, tagName)
                            },
                            onRemoveTag = { tagId ->
                                viewModel.removeTagFromArticle(selectedArticle.id, tagId)
                            }
                        )
                    } else {
                        currentScreen = Screen.Feed
                    }
                }
            }
        }
    }
}
