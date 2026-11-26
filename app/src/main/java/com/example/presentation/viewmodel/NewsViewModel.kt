package com.example.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.remote.RssParser
import com.example.data.repository.FailedSourceInfo
import com.example.data.repository.NewsRepository
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceSortMode
import com.example.domain.model.SourceStatusInfo
import com.example.domain.model.TagItem
import com.example.domain.model.sortSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslatedArticle(
    val titleRu: String,
    val summaryRu: String,
    val contentRu: String,
    val isShowingRussian: Boolean = true
)

data class SourceDiagnosticsUiState(
    val isDialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val sourceId: Long? = null,
    val sourceTitle: String = "",
    val sourceUrl: String = "",
    val report: SourceDiagnosticReport? = null,
    val errorMessage: String? = null
)

data class NewsFeedUiState(
    val articles: List<NewsArticle> = emptyList(),
    val sources: List<NewsSource> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val selectedSourceIds: Set<Long> = emptySet(),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isTranslating: Boolean = false,
    val userMessage: String? = null,
    val networkError: String? = null,
    val selectedArticle: NewsArticle? = null,
    val translations: Map<Long, TranslatedArticle> = emptyMap(),
    val lastRefreshAt: Long = 0L,
    val failedSources: List<FailedSourceInfo> = emptyList(),
    val sourceSortMode: SourceSortMode = SourceSortMode.MANUAL,
    val sourceStatuses: Map<Long, SourceStatusInfo> = emptyMap()
)

internal fun filterNewsArticles(
    allNews: List<NewsArticle>,
    sources: List<NewsSource>,
    selectedSourceIds: Set<Long>,
    searchQuery: String
): List<NewsArticle> {
    val enabledSourceIds = sources.filter { it.isEnabled }.map { it.id }.toSet()
    val activeSelectedSourceIds = selectedSourceIds.intersect(enabledSourceIds)
    val q = searchQuery.trim().lowercase()

    return allNews.filter { article ->
        if (!enabledSourceIds.contains(article.sourceId)) {
            return@filter false
        }

        if (activeSelectedSourceIds.isNotEmpty() && article.sourceId !in activeSelectedSourceIds) {
            return@filter false
        }

        if (q.isNotBlank()) {
            val inTitle = article.title.lowercase().contains(q)
            val inTitleRu = article.titleRu?.lowercase()?.contains(q) == true
            val inSummary = article.summary.lowercase().contains(q)
            val inSummaryRu = article.summaryRu?.lowercase()?.contains(q) == true
            val inTags = article.tags.any { it.name.lowercase().contains(q) }
            if (!inTitle && !inTitleRu && !inSummary && !inSummaryRu && !inTags) return@filter false
        }

        true
    }
}

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = UserPreferencesRepository(application)
    private val repository = NewsRepository(
        database = AppDatabase.getDatabase(application),
        userPreferencesRepository = preferencesRepository
    )

    private val _selectedSourceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _searchQuery = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)
    private val _isTranslating = MutableStateFlow(false)
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _networkError = MutableStateFlow<String?>(null)
    private val _selectedArticleId = MutableStateFlow<Long?>(null)
    private val _translations = MutableStateFlow<Map<Long, TranslatedArticle>>(emptyMap())
    private val _sourceDiagnosticsState = MutableStateFlow(SourceDiagnosticsUiState())
    val sourceDiagnosticsState: StateFlow<SourceDiagnosticsUiState> = _sourceDiagnosticsState.asStateFlow()
    private var diagnosticsJob: Job? = null

    private val filteredData = combine(
        repository.allNews,
        repository.allSources,
        repository.allTags,
        _selectedSourceIds,
        _searchQuery
    ) { allNews: List<NewsArticle>, sources: List<NewsSource>, tags: List<TagItem>, selectedSources: Set<Long>, query: String ->
        val filteredArticles = filterNewsArticles(
            allNews = allNews,
            sources = sources,
            selectedSourceIds = selectedSources,
            searchQuery = query
        )
        Triple(allNews, filteredArticles, Pair(sources, tags))
    }

    private val refreshMeta = combine(
        repository.lastRefreshAt,
        repository.failedSources,
        preferencesRepository.sourceSortMode,
        repository.sourceStatuses
    ) { refreshAt: Long, failed: List<FailedSourceInfo>, sortMode: SourceSortMode, statuses: Map<Long, SourceStatusInfo> ->
        RefreshMetaBundle(refreshAt, failed, sortMode, statuses)
    }

    private val extraState = combine(
        _isRefreshing,
        _isTranslating,
        _userMessage,
        _networkError,
        refreshMeta
    ) { refreshing: Boolean, translating: Boolean, msg: String?, netErr: String?, meta: RefreshMetaBundle ->
        ExtraStateBundle(
            isRefreshing = refreshing,
            isTranslating = translating,
            userMessage = msg,
            networkError = netErr,
            lastRefreshAt = meta.lastRefreshAt,
            failedSources = meta.failedSources,
            sourceSortMode = meta.sourceSortMode,
            sourceStatuses = meta.sourceStatuses
        )
    }

    val uiState: StateFlow<NewsFeedUiState> = combine(
        filteredData,
        extraState,
        _selectedArticleId,
        _translations,
        _selectedSourceIds
    ) { data, extra, articleId, translations, selectedSources ->
        val (allNews, filteredArticles, sourcesAndTags) = data
        val (rawSources, tags) = sourcesAndTags
        val sortedSources = sortSources(rawSources, extra.sourceSortMode)
        val enabledIds = sortedSources.filter { it.isEnabled }.map { it.id }.toSet()

        val selectedArticle = articleId?.let { id ->
            allNews.firstOrNull { it.id == id }
        }

        NewsFeedUiState(
            articles = filteredArticles,
            sources = sortedSources,
            tags = tags,
            selectedSourceIds = selectedSources.intersect(enabledIds),
            searchQuery = _searchQuery.value,
            isRefreshing = extra.isRefreshing,
            isTranslating = extra.isTranslating,
            userMessage = extra.userMessage,
            networkError = extra.networkError,
            selectedArticle = selectedArticle,
            translations = translations,
            lastRefreshAt = extra.lastRefreshAt,
            failedSources = extra.failedSources,
            sourceSortMode = extra.sourceSortMode,
            sourceStatuses = extra.sourceStatuses
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NewsFeedUiState(isRefreshing = true)
    )

    init {
        viewModelScope.launch {
            repository.ensureDefaultData()
            refreshFeeds()
            schedulePreviewTranslations()
        }
    }

    private fun schedulePreviewTranslations() {
        viewModelScope.launch {
            repository.translateUntranslatedPreviews(batchLimit = 30)
        }
    }

    fun refreshFeeds() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.refreshAllFeeds()
            _isRefreshing.value = false
            if (result.isSuccess) {
                _networkError.value = null
                val refreshResult = result.getOrNull()
                val count = refreshResult?.newArticlesCount ?: 0
                if (count > 0) {
                    _userMessage.value = "Загружено новых новостей: $count"
                }
                schedulePreviewTranslations()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Сетевая ошибка"
                _networkError.value = err
            }
        }
    }

    fun toggleSource(sourceId: Long, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setSourceEnabled(sourceId, isEnabled)
            if (!isEnabled) {
                _selectedSourceIds.update { it - sourceId }
            }
        }
    }

    fun moveSourceUp(sourceId: Long) {
        viewModelScope.launch {
            repository.moveSource(sourceId, moveUp = true)
        }
    }

    fun moveSourceDown(sourceId: Long) {
        viewModelScope.launch {
            repository.moveSource(sourceId, moveUp = false)
        }
    }

    fun setSourceSortMode(mode: SourceSortMode) {
        viewModelScope.launch {
            preferencesRepository.setSourceSortMode(mode)
        }
    }

    fun addSource(title: String, url: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repository.addSource(title, url)
            _isRefreshing.value = false
            if (res.isSuccess) {
                _userMessage.value = "Источник успешно добавлен!"
                schedulePreviewTranslations()
                onComplete(true, null)
            } else {
                val errorMsg = res.exceptionOrNull()?.message ?: "Не удалось добавить источник"
                onComplete(false, errorMsg)
            }
        }
    }

    fun editSource(sourceId: Long, title: String, url: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repository.updateSource(sourceId, title, url)
            _isRefreshing.value = false
            if (res.isSuccess) {
                _userMessage.value = "Источник обновлён"
                schedulePreviewTranslations()
                onComplete(true, null)
            } else {
                val errorMsg = res.exceptionOrNull()?.message ?: "Не удалось обновить источник"
                onComplete(false, errorMsg)
            }
        }
    }

    fun deleteSource(sourceId: Long) {
        viewModelScope.launch {
            repository.deleteSource(sourceId)
            _selectedSourceIds.update { it - sourceId }
            _userMessage.value = "Источник удален"
        }
    }

    fun resetDefaultSources() {
        viewModelScope.launch {
            repository.resetDefaultSources()
            _userMessage.value = "Стандартные источники восстановлены"
            refreshFeeds()
        }
    }

    fun runSourceDiagnostics(sourceId: Long) {
        diagnosticsJob?.cancel()
        val sourceInfo = uiState.value.sources.firstOrNull { it.id == sourceId }
        _sourceDiagnosticsState.value = SourceDiagnosticsUiState(
            isDialogVisible = true,
            isLoading = true,
            sourceId = sourceId,
            sourceTitle = sourceInfo?.title ?: "RSS-источник",
            sourceUrl = sourceInfo?.url ?: "",
            report = null,
            errorMessage = null
        )

        diagnosticsJob = viewModelScope.launch {
            val result = repository.diagnoseSource(
                sourceId = sourceId,
                selectedSourceIds = _selectedSourceIds.value,
                searchQuery = _searchQuery.value
            )
            if (result.isSuccess) {
                val report = result.getOrThrow()
                _sourceDiagnosticsState.update { current ->
                    if (!current.isDialogVisible || current.sourceId != sourceId) current
                    else current.copy(
                        isLoading = false,
                        sourceTitle = report.sourceTitle,
                        sourceUrl = report.sourceUrl,
                        report = report,
                        errorMessage = null
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Не удалось выполнить диагностику"
                _sourceDiagnosticsState.update { current ->
                    if (!current.isDialogVisible || current.sourceId != sourceId) current
                    else current.copy(
                        isLoading = false,
                        errorMessage = err
                    )
                }
            }
        }
    }

    fun dismissSourceDiagnostics() {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        _sourceDiagnosticsState.value = SourceDiagnosticsUiState()
    }

    fun toggleSourceFilter(sourceId: Long) {
        _selectedSourceIds.update { current ->
            if (current.contains(sourceId)) current - sourceId else current + sourceId
        }
    }

    fun clearSourceFilters() {
        _selectedSourceIds.value = emptySet()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectArticle(articleId: Long?) {
        _selectedArticleId.value = articleId
    }

    fun addTagToArticle(articleId: Long, tagName: String) {
        viewModelScope.launch {
            repository.createAndAddTagToNews(articleId, tagName)
        }
    }

    fun removeTagFromArticle(articleId: Long, tagId: Long) {
        viewModelScope.launch {
            repository.removeTagFromNews(articleId, tagId)
        }
    }

    // --- On-Screen Translator Logic (Manual Full-Text Translation in DetailScreen) ---
    fun toggleArticleTranslation(article: NewsArticle) {
        val existing = _translations.value[article.id]
        if (existing != null) {
            _translations.update { map ->
                map + (article.id to existing.copy(isShowingRussian = !existing.isShowingRussian))
            }
            return
        }

        viewModelScope.launch {
            _isTranslating.value = true
            val cleanTitle = RssParser.cleanHtml(article.title)
            val cleanSummary = RssParser.cleanHtml(article.summary)
            val cleanContent = RssParser.cleanHtml(
                if (article.content.isNotBlank()) article.content else article.summary
            )

            val titleResult = article.titleRu
                ?.takeIf { it.isNotBlank() }
                ?.let { Result.success(it) }
                ?: repository.translateText(cleanTitle)

            val summaryResult = when {
                !article.summaryRu.isNullOrBlank() -> Result.success(article.summaryRu)
                cleanSummary.isBlank() -> Result.success("")
                else -> repository.translateText(cleanSummary)
            }

            val contentResult = if (cleanContent.isNotBlank()) {
                repository.translateText(cleanContent)
            } else {
                Result.success("")
            }

            _isTranslating.value = false

            if (titleResult.isSuccess && contentResult.isSuccess) {
                val cleanTitleRu = RssParser.cleanHtml(titleResult.getOrDefault(cleanTitle))
                val cleanSummaryRu = RssParser.cleanHtml(summaryResult.getOrDefault(cleanSummary))
                val cleanContentRu = RssParser.cleanHtml(contentResult.getOrDefault(cleanContent))

                val translated = TranslatedArticle(
                    titleRu = cleanTitleRu,
                    summaryRu = cleanSummaryRu,
                    contentRu = cleanContentRu,
                    isShowingRussian = true
                )
                _translations.update { it + (article.id to translated) }

                if (article.titleRu == null || article.summaryRu == null) {
                    repository.savePreviewTranslation(
                        newsId = article.id,
                        titleRu = article.titleRu ?: cleanTitleRu,
                        summaryRu = article.summaryRu ?: cleanSummaryRu
                    )
                }

                _userMessage.value = "Переведено на русский язык"
            } else {
                val err = contentResult.exceptionOrNull()?.message
                    ?: titleResult.exceptionOrNull()?.message
                    ?: "Ошибка перевода"
                _userMessage.value = "Не удалось перевести: $err"
            }
        }
    }

    // --- User-driven Auto-tag Management ---
    fun saveTag(name: String, colorHex: String, keywords: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            repository.saveTag(name, colorHex, keywords)
            _userMessage.value = "Тег «$name» сохранён!"
            onDone?.invoke()
        }
    }

    fun updateTagKeywords(tagId: Long, keywords: String) {
        viewModelScope.launch {
            repository.updateTagKeywords(tagId, keywords)
            _userMessage.value = "Ключевые слова авто-тега обновлены"
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            repository.deleteTag(tagId)
            _userMessage.value = "Тег удален"
        }
    }

    fun reapplyAutoTags() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val count = repository.reapplyAutoTagsToAllNews()
            _isRefreshing.value = false
            _userMessage.value = "Авто-теги обновлены для $count статей!"
        }
    }

    // --- Configuration Export & Import (Task 1) ---
    suspend fun exportConfigJson(): String {
        return repository.exportConfiguration(emptySet())
    }

    fun importConfigJson(jsonString: String) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.importConfiguration(jsonString)
            _isRefreshing.value = false

            if (result.isSuccess) {
                val res = result.getOrThrow()
                _userMessage.value = "Импорт успешен: источников +${res.sourcesCount}, тегов +${res.tagsCount}"
                refreshFeeds()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Не удалось импортировать файл"
                _userMessage.value = "Ошибка импорта: $errorMsg"
            }
        }
    }

    fun clearNetworkError() {
        _networkError.value = null
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    private data class RefreshMetaBundle(
        val lastRefreshAt: Long,
        val failedSources: List<FailedSourceInfo>,
        val sourceSortMode: SourceSortMode,
        val sourceStatuses: Map<Long, SourceStatusInfo>
    )

    private data class ExtraStateBundle(
        val isRefreshing: Boolean,
        val isTranslating: Boolean,
        val userMessage: String?,
        val networkError: String?,
        val lastRefreshAt: Long,
        val failedSources: List<FailedSourceInfo>,
        val sourceSortMode: SourceSortMode,
        val sourceStatuses: Map<Long, SourceStatusInfo>
    )
}
