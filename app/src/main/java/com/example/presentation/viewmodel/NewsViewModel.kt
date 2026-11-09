package com.example.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
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
    val selectedArticle: NewsArticle? = null,
    val translations: Map<Long, TranslatedArticle> = emptyMap()
)

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NewsRepository(AppDatabase.getDatabase(application))

    private val _selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _searchQuery = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)
    private val _isTranslating = MutableStateFlow(false)
    private val _userMessage = MutableStateFlow<String?>(null)
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
        _selectedArticleId,
        _translations
    ) { refreshing: Boolean, translating: Boolean, msg: String?, articleId: Long?, translations: Map<Long, TranslatedArticle> ->
        StateBundle(refreshing, translating, msg, articleId, translations)
    }

    val uiState: StateFlow<NewsFeedUiState> = combine(
        filteredData,
        extraState,
        _selectedTagIds,
        _searchQuery
    ) { data, extra, selectedTags, query ->
        val (allNews, filteredArticles, sourcesAndTags) = data
        val (sources, tags) = sourcesAndTags

        val selectedArticle = extra.articleId?.let { id ->
            allNews.firstOrNull { it.id == id }
        }

        NewsFeedUiState(
            articles = filteredArticles,
            sources = sources,
            tags = tags,
            selectedTagIds = selectedTags,
            searchQuery = query,
            isRefreshing = extra.isRefreshing,
            isTranslating = extra.isTranslating,
            userMessage = extra.userMessage,
            selectedArticle = selectedArticle,
            translations = extra.translations
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
                val count = result.getOrDefault(0)
                if (count > 0) {
                    _userMessage.value = "Загружено новых новостей: $count"
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Ошибка сети при загрузке"
                _userMessage.value = "Не удалось обновить: $err"
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
            // Toggle between Russian and original
            _translations.update { map ->
                map + (article.id to existing.copy(isShowingRussian = !existing.isShowingRussian))
            }
            return
        }

        viewModelScope.launch {
            _isTranslating.value = true
            val titleResult = repository.translateText(article.title)
            val summaryResult = if (article.summary.isNotBlank()) repository.translateText(article.summary) else Result.success("")
            val contentResult = if (article.content.isNotBlank()) repository.translateText(article.content) else Result.success("")

            _isTranslating.value = false

            if (titleResult.isSuccess) {
                val translated = TranslatedArticle(
                    titleRu = titleResult.getOrDefault(article.title),
                    summaryRu = summaryResult.getOrDefault(article.summary),
                    contentRu = contentResult.getOrDefault(article.content),
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

    fun clearUserMessage() {
        _userMessage.value = null
    }

    private data class StateBundle(
        val isRefreshing: Boolean,
        val isTranslating: Boolean,
        val userMessage: String?,
        val articleId: Long?,
        val translations: Map<Long, TranslatedArticle>
    )
}
