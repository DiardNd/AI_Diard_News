package com.example.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.remote.RssParser
import com.example.data.repository.NewsRepository
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.TagItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

data class NewsFeedUiState(
    val articles: List<NewsArticle> = emptyList(),
    val sources: List<NewsSource> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isTranslating: Boolean = false,
    val userMessage: String? = null,
    val networkError: String? = null,
    val selectedArticle: NewsArticle? = null,
    val translations: Map<Long, TranslatedArticle> = emptyMap(),
    val lastRefreshAt: Long = 0L
)

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = UserPreferencesRepository(application)
    private val repository = NewsRepository(
        database = AppDatabase.getDatabase(application),
        userPreferencesRepository = preferencesRepository
    )

    private val _selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _searchQuery = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)
    private val _isTranslating = MutableStateFlow(false)
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _networkError = MutableStateFlow<String?>(null)
    private val _selectedArticleId = MutableStateFlow<Long?>(null)
    private val _translations = MutableStateFlow<Map<Long, TranslatedArticle>>(emptyMap())

    private val filteredData = combine(
        repository.allNews,
        repository.allSources,
        repository.allTags,
        _selectedTagIds,
        _searchQuery
    ) { allNews: List<NewsArticle>, sources: List<NewsSource>, tags: List<TagItem>, selectedTags: Set<Long>, query: String ->

        val enabledSourceIds = sources.filter { it.isEnabled }.map { it.id }.toSet()

        val filteredArticles = allNews.filter { article ->
            if (!enabledSourceIds.contains(article.sourceId)) {
                return@filter false
            }

            if (selectedTags.isNotEmpty()) {
                val articleTagIds = article.tags.map { it.id }.toSet()
                val hasMatchingTag = selectedTags.any { it in articleTagIds }
                if (!hasMatchingTag) return@filter false
            }

            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val inTitle = article.title.lowercase().contains(q)
                val inSummary = article.summary.lowercase().contains(q)
                val inTags = article.tags.any { it.name.lowercase().contains(q) }
                if (!inTitle && !inSummary && !inTags) return@filter false
            }

            true
        }

        Triple(allNews, filteredArticles, Pair(sources, tags))
    }

    private val extraState = combine(
        _isRefreshing,
        _isTranslating,
        _userMessage,
        _networkError,
        repository.lastRefreshAt
    ) { refreshing: Boolean, translating: Boolean, msg: String?, netErr: String?, refreshAt: Long ->
        ExtraStateBundle(refreshing, translating, msg, netErr, refreshAt)
    }

    val uiState: StateFlow<NewsFeedUiState> = combine(
        filteredData,
        extraState,
        _selectedArticleId,
        _translations,
        _selectedTagIds
    ) { data, extra, articleId, translations, selectedTags ->
        val (allNews, filteredArticles, sourcesAndTags) = data
        val (sources, tags) = sourcesAndTags

        val selectedArticle = articleId?.let { id ->
            allNews.firstOrNull { it.id == id }
        }

        NewsFeedUiState(
            articles = filteredArticles,
            sources = sources,
            tags = tags,
            selectedTagIds = selectedTags,
            searchQuery = _searchQuery.value,
            isRefreshing = extra.isRefreshing,
            isTranslating = extra.isTranslating,
            userMessage = extra.userMessage,
            networkError = extra.networkError,
            selectedArticle = selectedArticle,
            translations = translations,
            lastRefreshAt = extra.lastRefreshAt
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
        }
    }

    fun refreshFeeds() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.refreshAllFeeds()
            _isRefreshing.value = false
            if (result.isSuccess) {
                _networkError.value = null
                val count = result.getOrDefault(0)
                if (count > 0) {
                    _userMessage.value = "Загружено новых новостей: $count"
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Сетевая ошибка"
                _networkError.value = err
            }
        }
    }

    fun toggleSource(sourceId: Long, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setSourceEnabled(sourceId, isEnabled)
        }
    }

    fun addSource(title: String, url: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repository.addSource(title, url)
            _isRefreshing.value = false
            if (res.isSuccess) {
                _userMessage.value = "Источник успешно добавлен!"
                onComplete(true, null)
            } else {
                val errorMsg = res.exceptionOrNull()?.message ?: "Не удалось добавить источник"
                onComplete(false, errorMsg)
            }
        }
    }

    fun deleteSource(sourceId: Long) {
        viewModelScope.launch {
            repository.deleteSource(sourceId)
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

    fun toggleTagFilter(tagId: Long) {
        _selectedTagIds.update { current ->
            if (current.contains(tagId)) current - tagId else current + tagId
        }
    }

    fun clearTagFilters() {
        _selectedTagIds.value = emptySet()
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

    // --- On-Screen Translator Logic ---
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

            val titleResult = repository.translateText(cleanTitle)
            val summaryResult = if (cleanSummary.isNotBlank()) repository.translateText(cleanSummary) else Result.success("")
            val contentResult = if (cleanContent.isNotBlank()) repository.translateText(cleanContent) else Result.success("")

            _isTranslating.value = false

            if (titleResult.isSuccess) {
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
                _userMessage.value = "Переведено на русский язык"
            } else {
                val err = titleResult.exceptionOrNull()?.message ?: "Ошибка перевода"
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
            _selectedTagIds.update { it - tagId }
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
        return repository.exportConfiguration(_selectedTagIds.value)
    }

    fun importConfigJson(jsonString: String) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.importConfiguration(jsonString)
            _isRefreshing.value = false

            if (result.isSuccess) {
                val res = result.getOrThrow()
                // Restore selected tags if matching tags exist
                if (res.restoredSelectedTags.isNotEmpty()) {
                    val allTags = uiState.value.tags
                    val idsToSelect = allTags.filter { it.name in res.restoredSelectedTags }.map { it.id }.toSet()
                    if (idsToSelect.isNotEmpty()) {
                        _selectedTagIds.value = idsToSelect
                    }
                }
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

    private data class ExtraStateBundle(
        val isRefreshing: Boolean,
        val isTranslating: Boolean,
        val userMessage: String?,
        val networkError: String?,
        val lastRefreshAt: Long
    )
}
