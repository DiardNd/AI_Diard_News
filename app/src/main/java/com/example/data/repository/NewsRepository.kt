package com.example.data.repository

import com.example.data.config.ConfigExporter
import com.example.data.config.ExportedArticleTags
import com.example.data.config.ExportedConfig
import com.example.data.config.ExportedSource
import com.example.data.config.ExportedTag
import com.example.data.config.ImportResult
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.NewsTagCrossRef
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.TagEntity
import com.example.data.remote.FeedFetcher
import com.example.data.remote.RssParser
import com.example.data.remote.TranslationService
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.TagItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

data class FailedSourceInfo(
    val sourceId: Long,
    val sourceTitle: String,
    val sourceUrl: String,
    val errorMessage: String
)

data class RefreshFeedsResult(
    val newArticlesCount: Int,
    val totalSourcesCount: Int,
    val successfulSourcesCount: Int,
    val failedSources: List<FailedSourceInfo>
)

class NewsRepository(
    private val database: AppDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val feedFetcher: FeedFetcher = FeedFetcher(),
    private val translationService: TranslationService = TranslationService()
) {
    companion object {
        const val MAX_ITEMS_PER_FEED_FETCH = 30
        const val MAX_STORED_NEWS_PER_SOURCE = 50
    }

    private val sourceDao = database.sourceDao()
    private val tagDao = database.tagDao()
    private val newsDao = database.newsDao()
    private val translationMutex = Mutex()

    private val _failedSources = MutableStateFlow<List<FailedSourceInfo>>(emptyList())
    val failedSources: StateFlow<List<FailedSourceInfo>> = _failedSources.asStateFlow()

    val lastRefreshAt: Flow<Long> = userPreferencesRepository.lastRefreshAt

    val allSources: Flow<List<NewsSource>> = sourceDao.getAllSources().map { list ->
        list.map { it.toDomain() }
    }

    val enabledSources: Flow<List<NewsSource>> = sourceDao.getEnabledSources().map { list ->
        list.map { it.toDomain() }
    }

    val allTags: Flow<List<TagItem>> = tagDao.getAllTags().map { list ->
        list.map { it.toDomain() }
    }

    val allNews: Flow<List<NewsArticle>> = newsDao.getAllNewsWithTags().map { list ->
        list.map { newsWithTags ->
            NewsArticle(
                id = newsWithTags.news.id,
                sourceId = newsWithTags.news.sourceId,
                sourceTitle = newsWithTags.source?.title ?: "Неизвестный источник",
                guid = newsWithTags.news.guid,
                title = newsWithTags.news.title,
                summary = newsWithTags.news.summary,
                content = newsWithTags.news.content,
                link = newsWithTags.news.link,
                imageUrl = newsWithTags.news.imageUrl,
                pubDate = newsWithTags.news.pubDate,
                tags = newsWithTags.tags.map { it.toDomain() },
                titleRu = newsWithTags.news.titleRu,
                summaryRu = newsWithTags.news.summaryRu
            )
        }
    }

    fun getNewsById(newsId: Long): Flow<NewsArticle?> {
        return newsDao.getNewsWithTagsById(newsId).map { newsWithTags ->
            newsWithTags?.let {
                NewsArticle(
                    id = it.news.id,
                    sourceId = it.news.sourceId,
                    sourceTitle = it.source?.title ?: "Неизвестный источник",
                    guid = it.news.guid,
                    title = it.news.title,
                    summary = it.news.summary,
                    content = it.news.content,
                    link = it.news.link,
                    imageUrl = it.news.imageUrl,
                    pubDate = it.news.pubDate,
                    tags = it.tags.map { tag -> tag.toDomain() },
                    titleRu = it.news.titleRu,
                    summaryRu = it.news.summaryRu
                )
            }
        }
    }

    suspend fun ensureDefaultData() = withContext(Dispatchers.IO) {
        if (sourceDao.count() == 0) {
            val defaultSources = getDefaultSourcesList()
            sourceDao.insertSources(defaultSources)
        }

        if (tagDao.count() == 0) {
            val defaultTags = getDefaultTagsList()
            tagDao.insertTags(defaultTags)
        }
    }

    suspend fun resetDefaultSources() = withContext(Dispatchers.IO) {
        val defaultSources = getDefaultSourcesList()
        var nextOrder = sourceDao.getMaxSortOrder() + 1
        for (source in defaultSources) {
            val existing = sourceDao.getSourceByUrl(source.url)
            if (existing == null) {
                sourceDao.insertSource(source.copy(sortOrder = nextOrder++))
            } else {
                sourceDao.updateSource(existing.copy(isEnabled = true))
            }
        }
    }

    private fun getDefaultSourcesList(): List<SourceEntity> {
        return listOf(
            SourceEntity(
                title = "Libretro & RetroArch Blog",
                url = "https://www.libretro.com/index.php/feed/",
                isEnabled = true,
                sortOrder = 0
            ),
            SourceEntity(
                title = "Reddit r/emulation",
                url = "https://www.reddit.com/r/emulation/.rss",
                isEnabled = true,
                sortOrder = 1
            ),
            SourceEntity(
                title = "Dolphin Emulator",
                url = "https://dolphin-emu.org/blog/feeds/",
                isEnabled = true,
                sortOrder = 2
            ),
            SourceEntity(
                title = "PPSSPP PSP Emulator",
                url = "https://www.ppsspp.org/feed.xml",
                isEnabled = true,
                sortOrder = 3
            ),
            SourceEntity(
                title = "RPCS3 PS3 Emulator",
                url = "https://rpcs3.net/blog/feed/",
                isEnabled = true,
                sortOrder = 4
            )
        )
    }

    private fun getDefaultTagsList(): List<TagEntity> {
        return listOf(
            TagEntity(
                name = "RetroArch",
                colorHex = "#00D2D3",
                keywords = "retroarch, libretro, core, cores"
            ),
            TagEntity(
                name = "Winlator",
                colorHex = "#FF9F43",
                keywords = "winlator, box64, mobox, wine, x86"
            ),
            TagEntity(
                name = "PS2",
                colorHex = "#54A0FF",
                keywords = "ps2, playstation 2, pcsx2, aethersx2, netherx2"
            ),
            TagEntity(
                name = "Switch",
                colorHex = "#FF6B6B",
                keywords = "switch, yuzu, ryujinx, suyu, sudachi, nintendo switch"
            ),
            TagEntity(
                name = "Windows-эмуляция",
                colorHex = "#48DBFB",
                keywords = "windows, winlator, dxvk, wine, x86, directx, direct3d"
            ),
            TagEntity(
                name = "Гайд",
                colorHex = "#1DD1A1",
                keywords = "guide, tutorial, гайд, инструкция, how to, настройка, manual"
            ),
            TagEntity(
                name = "Релиз",
                colorHex = "#A29BFE",
                keywords = "release, released, релиз, update, v1., v2., v0., changelog, version"
            ),
            TagEntity(
                name = "Dolphin",
                colorHex = "#0ABDE3",
                keywords = "dolphin, gamecube, wii, gc/wii"
            ),
            TagEntity(
                name = "PPSSPP",
                colorHex = "#5F27CD",
                keywords = "ppsspp, psp, playstation portable"
            ),
            TagEntity(
                name = "Android",
                colorHex = "#10AC84",
                keywords = "android, snapdragon, mali, adreno, apk"
            ),
            TagEntity(
                name = "Оптимизация",
                colorHex = "#F368E0",
                keywords = "performance, fps, speed, оптимиз, boost, шейдеры, shader"
            )
        )
    }

    suspend fun refreshAllFeeds(): Result<RefreshFeedsResult> = withContext(Dispatchers.IO) {
        try {
            ensureDefaultData()
            val enabled = sourceDao.getEnabledSourcesSync()
            if (enabled.isEmpty()) {
                _failedSources.value = emptyList()
                return@withContext Result.success(
                    RefreshFeedsResult(
                        newArticlesCount = 0,
                        totalSourcesCount = 0,
                        successfulSourcesCount = 0,
                        failedSources = emptyList()
                    )
                )
            }

            val allDbTags = tagDao.getAllTagsSync()
            var newArticlesCount = 0
            var successfulSourcesCount = 0
            val failedList = mutableListOf<FailedSourceInfo>()

            for (source in enabled) {
                val fetchResult = feedFetcher.fetchFeed(source.url)
                if (fetchResult.isFailure) {
                    val msg = fetchResult.exceptionOrNull()?.message ?: "Ошибка загрузки"
                    failedList.add(
                        FailedSourceInfo(
                            sourceId = source.id,
                            sourceTitle = source.title,
                            sourceUrl = source.url,
                            errorMessage = msg
                        )
                    )
                    continue
                }

                val feed = fetchResult.getOrNull()
                if (feed == null) {
                    failedList.add(
                        FailedSourceInfo(
                            sourceId = source.id,
                            sourceTitle = source.title,
                            sourceUrl = source.url,
                            errorMessage = "Пустой ответ ленты"
                        )
                    )
                    continue
                }

                try {
                    val limitedItems = feed.items
                        .sortedByDescending { it.pubDate }
                        .take(MAX_ITEMS_PER_FEED_FETCH)

                    for (item in limitedItems) {
                        val existingByGuid = newsDao.getNewsByGuid(item.guid)
                        val existingByLink = if (existingByGuid == null) newsDao.getNewsByLink(item.link) else null

                        if (existingByGuid == null && existingByLink == null) {
                            val newsEntity = NewsEntity(
                                sourceId = source.id,
                                guid = item.guid,
                                title = item.title,
                                summary = item.description,
                                content = item.content,
                                link = item.link,
                                imageUrl = item.imageUrl,
                                pubDate = item.pubDate
                            )
                            val insertedId = newsDao.insertNews(newsEntity)
                            if (insertedId > 0) {
                                newArticlesCount++
                                // User-driven auto-tagging
                                val autoTags = findAutoTags(item.title, item.content, allDbTags)
                                val crossRefs = autoTags.map { NewsTagCrossRef(newsId = insertedId, tagId = it.id) }
                                if (crossRefs.isNotEmpty()) {
                                    newsDao.insertNewsTagCrossRefs(crossRefs)
                                }
                            }
                        }
                    }
                    newsDao.trimOldNewsForSource(source.id, MAX_STORED_NEWS_PER_SOURCE)
                    successfulSourcesCount++
                } catch (e: Exception) {
                    failedList.add(
                        FailedSourceInfo(
                            sourceId = source.id,
                            sourceTitle = source.title,
                            sourceUrl = source.url,
                            errorMessage = e.message ?: "Ошибка сохранения новостей"
                        )
                    )
                }
            }

            _failedSources.value = failedList

            if (successfulSourcesCount > 0) {
                userPreferencesRepository.setLastRefreshAt(System.currentTimeMillis())
                Result.success(
                    RefreshFeedsResult(
                        newArticlesCount = newArticlesCount,
                        totalSourcesCount = enabled.size,
                        successfulSourcesCount = successfulSourcesCount,
                        failedSources = failedList
                    )
                )
            } else {
                val errorDetails = failedList.joinToString("; ") { "${it.sourceTitle}: ${it.errorMessage}" }
                Result.failure(Exception("Ошибка сети при обновлении: $errorDetails"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setSourceEnabled(sourceId: Long, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        sourceDao.setSourceEnabled(sourceId, isEnabled)
    }

    suspend fun moveSource(sourceId: Long, moveUp: Boolean) = withContext(Dispatchers.IO) {
        val current = sourceDao.getAllSourcesSync().toMutableList()
        val index = current.indexOfFirst { it.id == sourceId }
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (index < 0 || targetIndex !in current.indices) return@withContext

        val item = current.removeAt(index)
        current.add(targetIndex, item)
        sourceDao.updateSortOrders(current.map { it.id })
    }

    suspend fun addSource(title: String, url: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val existing = sourceDao.getSourceByUrl(url)
            if (existing != null) {
                return@withContext Result.failure(Exception("Источник с таким URL уже добавлен"))
            }

            val testFetch = feedFetcher.fetchFeed(url)
            val feedTitle = testFetch.getOrNull()?.title
            val finalTitle = title.trim().ifEmpty { feedTitle ?: "RSS Источник" }
            val nextSortOrder = sourceDao.getMaxSortOrder() + 1

            val newSource = SourceEntity(
                title = finalTitle,
                url = url.trim(),
                isEnabled = true,
                sortOrder = nextSortOrder
            )
            val id = sourceDao.insertSource(newSource)
            if (id > 0) {
                testFetch.getOrNull()?.let { feed ->
                    val allDbTags = tagDao.getAllTagsSync()
                    val limitedItems = feed.items
                        .sortedByDescending { it.pubDate }
                        .take(MAX_ITEMS_PER_FEED_FETCH)

                    for (item in limitedItems) {
                        val entity = NewsEntity(
                            sourceId = id,
                            guid = item.guid,
                            title = item.title,
                            summary = item.description,
                            content = item.content,
                            link = item.link,
                            imageUrl = item.imageUrl,
                            pubDate = item.pubDate
                        )
                        val newsId = newsDao.insertNews(entity)
                        if (newsId > 0) {
                            val autoTags = findAutoTags(item.title, item.content, allDbTags)
                            val crossRefs = autoTags.map { NewsTagCrossRef(newsId = newsId, tagId = it.id) }
                            if (crossRefs.isNotEmpty()) {
                                newsDao.insertNewsTagCrossRefs(crossRefs)
                            }
                        }
                    }
                    newsDao.trimOldNewsForSource(id, MAX_STORED_NEWS_PER_SOURCE)
                }
                Result.success(id)
            } else {
                Result.failure(Exception("Не удалось сохранить источник"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        sourceDao.deleteSourceById(sourceId)
    }

    suspend fun saveTag(name: String, colorHex: String, keywords: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        val existing = tagDao.getTagByName(trimmed)
        if (existing != null) {
            tagDao.updateTag(existing.copy(colorHex = colorHex, keywords = keywords.trim()))
            existing.id
        } else {
            tagDao.insertTag(TagEntity(name = trimmed, colorHex = colorHex, keywords = keywords.trim()))
        }
    }

    suspend fun updateTagKeywords(tagId: Long, keywords: String) = withContext(Dispatchers.IO) {
        tagDao.updateKeywords(tagId, keywords.trim())
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        tagDao.deleteTagById(tagId)
    }

    suspend fun reapplyAutoTagsToAllNews(): Int = withContext(Dispatchers.IO) {
        val allDbTags = tagDao.getAllTagsSync()
        val allNewsList = newsDao.getAllNewsSync()
        var updatedCount = 0

        for (news in allNewsList) {
            val autoTags = findAutoTags(news.title, news.content, allDbTags)
            newsDao.clearTagsForNews(news.id)
            val crossRefs = autoTags.map { NewsTagCrossRef(newsId = news.id, tagId = it.id) }
            if (crossRefs.isNotEmpty()) {
                newsDao.insertNewsTagCrossRefs(crossRefs)
                updatedCount++
            }
        }
        updatedCount
    }

    suspend fun addTagToNews(newsId: Long, tagId: Long) = withContext(Dispatchers.IO) {
        newsDao.insertNewsTagCrossRef(NewsTagCrossRef(newsId, tagId))
    }

    suspend fun removeTagFromNews(newsId: Long, tagId: Long) = withContext(Dispatchers.IO) {
        newsDao.deleteNewsTagCrossRef(newsId, tagId)
    }

    suspend fun createAndAddTagToNews(newsId: Long, tagName: String): Long = withContext(Dispatchers.IO) {
        val trimmed = tagName.trim()
        val existing = tagDao.getTagByName(trimmed)
        val tagId = existing?.id ?: tagDao.insertTag(
            TagEntity(
                name = trimmed,
                colorHex = getRandomColorHex(trimmed),
                keywords = trimmed.lowercase(Locale.ROOT)
            )
        )
        if (tagId > 0) {
            newsDao.insertNewsTagCrossRef(NewsTagCrossRef(newsId, tagId))
        }
        tagId
    }

    suspend fun translateText(text: String): Result<String> {
        return translationService.translateToRussian(text)
    }

    suspend fun savePreviewTranslation(
        newsId: Long,
        titleRu: String?,
        summaryRu: String?
    ) = withContext(Dispatchers.IO) {
        newsDao.updateNewsPreviewTranslation(newsId, titleRu, summaryRu)
    }

    suspend fun translateUntranslatedPreviews(batchLimit: Int = 20): Int = withContext(Dispatchers.IO) {
        translationMutex.withLock {
            val candidates = newsDao.getUntranslatedNews(batchLimit)
            var translatedCount = 0

            for (item in candidates) {
                try {
                    var updatedTitleRu = item.titleRu
                    var updatedSummaryRu = item.summaryRu

                    if (updatedTitleRu == null) {
                        val cleanTitle = RssParser.cleanHtml(item.title)
                        if (cleanTitle.isBlank()) {
                            updatedTitleRu = ""
                        } else {
                            val titleRes = translationService.translateToRussian(cleanTitle)
                            if (titleRes.isSuccess) {
                                updatedTitleRu = RssParser.cleanHtml(titleRes.getOrThrow())
                            }
                        }
                    }

                    if (updatedSummaryRu == null) {
                        val cleanSummary = RssParser.cleanHtml(item.summary)
                        if (cleanSummary.isBlank()) {
                            updatedSummaryRu = ""
                        } else {
                            val summaryRes = translationService.translateToRussian(cleanSummary)
                            if (summaryRes.isSuccess) {
                                updatedSummaryRu = RssParser.cleanHtml(summaryRes.getOrThrow())
                            }
                        }
                    }

                    if (updatedTitleRu != item.titleRu || updatedSummaryRu != item.summaryRu) {
                        newsDao.updateNewsPreviewTranslation(
                            newsId = item.id,
                            titleRu = updatedTitleRu,
                            summaryRu = updatedSummaryRu
                        )
                        translatedCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Translation failures must never interrupt feed loading or app stability
                }
            }

            translatedCount
        }
    }

    // --- Configuration Export & Import (Task 1) ---
    suspend fun exportConfiguration(selectedTagIds: Set<Long>): String = withContext(Dispatchers.IO) {
        val sources = sourceDao.getAllSourcesSync().map {
            ExportedSource(title = it.title, url = it.url, isEnabled = it.isEnabled)
        }
        val tags = tagDao.getAllTagsSync().map {
            ExportedTag(name = it.name, colorHex = it.colorHex, keywords = it.keywords)
        }

        val allTagsMap = tagDao.getAllTagsSync().associateBy { it.id }
        val selectedTagNames = selectedTagIds.mapNotNull { allTagsMap[it]?.name }

        val newsWithTagsList = newsDao.getAllNewsWithTagsSync()
        val articleTags = newsWithTagsList.filter { it.tags.isNotEmpty() }.map { nwt ->
            ExportedArticleTags(
                guid = nwt.news.guid,
                tagNames = nwt.tags.map { it.name }
            )
        }

        val config = ExportedConfig(
            version = 1,
            app = "DiardNews",
            exportedAt = System.currentTimeMillis(),
            sources = sources,
            tags = tags,
            selectedTagNames = selectedTagNames,
            articleTags = articleTags
        )

        ConfigExporter.serializeToJson(config)
    }

    suspend fun importConfiguration(jsonString: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        val parseResult = ConfigExporter.deserializeFromJson(jsonString)
        if (parseResult.isFailure) {
            return@withContext Result.failure(parseResult.exceptionOrNull() ?: Exception("Ошибка разбора"))
        }

        val config = parseResult.getOrThrow()
        var importedSourcesCount = 0
        var importedTagsCount = 0
        var linkedArticleTagsCount = 0

        // Merge sources without duplicates
        var nextSortOrder = sourceDao.getMaxSortOrder() + 1
        for (src in config.sources) {
            val existing = sourceDao.getSourceByUrl(src.url)
            if (existing == null) {
                sourceDao.insertSource(
                    SourceEntity(
                        title = src.title,
                        url = src.url,
                        isEnabled = src.isEnabled,
                        sortOrder = nextSortOrder++
                    )
                )
                importedSourcesCount++
            } else {
                sourceDao.updateSource(existing.copy(title = src.title, isEnabled = src.isEnabled))
            }
        }

        // Merge tags without duplicates
        for (t in config.tags) {
            val existing = tagDao.getTagByName(t.name)
            if (existing == null) {
                tagDao.insertTag(
                    TagEntity(name = t.name, colorHex = t.colorHex, keywords = t.keywords)
                )
                importedTagsCount++
            } else {
                val updatedKeywords = if (t.keywords.isNotBlank()) t.keywords else existing.keywords
                tagDao.updateTag(existing.copy(colorHex = t.colorHex, keywords = updatedKeywords))
            }
        }

        // Merge article tags
        for (at in config.articleTags) {
            val news = newsDao.getNewsByGuid(at.guid)
            if (news != null) {
                for (tagName in at.tagNames) {
                    var tag = tagDao.getTagByName(tagName)
                    if (tag == null) {
                        val newId = tagDao.insertTag(
                            TagEntity(name = tagName, colorHex = getRandomColorHex(tagName))
                        )
                        tag = tagDao.getTagById(newId)
                    }
                    if (tag != null) {
                        val rowId = newsDao.insertNewsTagCrossRef(NewsTagCrossRef(news.id, tag.id))
                        if (rowId > 0) linkedArticleTagsCount++
                    }
                }
            }
        }

        Result.success(
            ImportResult(
                sourcesCount = importedSourcesCount,
                tagsCount = importedTagsCount,
                articleTagsCount = linkedArticleTagsCount,
                restoredSelectedTags = config.selectedTagNames
            )
        )
    }

    private fun findAutoTags(title: String, text: String, availableTags: List<TagEntity>): List<TagEntity> {
        val haystack = "$title $text".lowercase(Locale.ROOT)
        val matched = mutableListOf<TagEntity>()

        for (tag in availableTags) {
            val keywordsList = if (tag.keywords.isNotBlank()) {
                tag.keywords.split(",")
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
            } else {
                listOf(tag.name.trim().lowercase(Locale.ROOT))
            }

            val hasMatch = keywordsList.any { kw -> haystack.contains(kw) }
            if (hasMatch) {
                matched.add(tag)
            }
        }
        return matched
    }

    private fun getRandomColorHex(seed: String): String {
        val colors = listOf(
            "#00D2D3", "#FF9F43", "#54A0FF", "#FF6B6B",
            "#48DBFB", "#1DD1A1", "#A29BFE", "#F368E0",
            "#0ABDE3", "#EE5A24", "#10AC84", "#2E86DE"
        )
        val index = Math.abs(seed.hashCode()) % colors.size
        return colors[index]
    }

    private fun SourceEntity.toDomain() = NewsSource(
        id = id,
        title = title,
        url = url,
        isEnabled = isEnabled,
        createdAt = createdAt,
        sortOrder = sortOrder
    )

    private fun TagEntity.toDomain() = TagItem(
        id = id,
        name = name,
        colorHex = colorHex,
        keywords = keywords
    )
}
