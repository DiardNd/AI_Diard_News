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
import com.example.data.remote.TranslationService
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.TagItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

class NewsRepository(
    private val database: AppDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val feedFetcher: FeedFetcher = FeedFetcher(),
    private val translationService: TranslationService = TranslationService()
) {
    private val sourceDao = database.sourceDao()
    private val tagDao = database.tagDao()
    private val newsDao = database.newsDao()

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
                tags = newsWithTags.tags.map { it.toDomain() }
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
                    tags = it.tags.map { tag -> tag.toDomain() }
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
        for (source in defaultSources) {
            val existing = sourceDao.getSourceByUrl(source.url)
            if (existing == null) {
                sourceDao.insertSource(source)
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
                isEnabled = true
            ),
            SourceEntity(
                title = "Reddit r/emulation",
                url = "https://www.reddit.com/r/emulation/.rss",
                isEnabled = true
            ),
            SourceEntity(
                title = "Dolphin Emulator",
                url = "https://dolphin-emu.org/blog/feeds/",
                isEnabled = true
            ),
            SourceEntity(
                title = "PPSSPP PSP Emulator",
                url = "https://www.ppsspp.org/feed.xml",
                isEnabled = true
            ),
            SourceEntity(
                title = "RPCS3 PS3 Emulator",
                url = "https://rpcs3.net/blog/feed/",
                isEnabled = true
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

    suspend fun refreshAllFeeds(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            ensureDefaultData()
            val enabled = sourceDao.getEnabledSourcesSync()
            if (enabled.isEmpty()) {
                return@withContext Result.success(0)
            }

            val allDbTags = tagDao.getAllTagsSync()
            var newArticlesCount = 0
            val errors = mutableListOf<String>()

            for (source in enabled) {
                val fetchResult = feedFetcher.fetchFeed(source.url)
                if (fetchResult.isFailure) {
                    val msg = fetchResult.exceptionOrNull()?.message ?: "Ошибка"
                    errors.add("${source.title}: $msg")
                    continue
                }

                val feed = fetchResult.getOrNull() ?: continue
                for (item in feed.items) {
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
            }

            // Always update lastRefreshAt timestamp when refresh finishes
            userPreferencesRepository.setLastRefreshAt(System.currentTimeMillis())

            if (errors.isNotEmpty()) {
                Result.failure(Exception("Ошибка сети при обновлении: ${errors.joinToString("; ")}"))
            } else {
                Result.success(newArticlesCount)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setSourceEnabled(sourceId: Long, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        sourceDao.setSourceEnabled(sourceId, isEnabled)
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

            val newSource = SourceEntity(
                title = finalTitle,
                url = url.trim(),
                isEnabled = true
            )
            val id = sourceDao.insertSource(newSource)
            if (id > 0) {
                testFetch.getOrNull()?.let { feed ->
                    val allDbTags = tagDao.getAllTagsSync()
                    for (item in feed.items) {
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
        for (src in config.sources) {
            val existing = sourceDao.getSourceByUrl(src.url)
            if (existing == null) {
                sourceDao.insertSource(
                    SourceEntity(title = src.title, url = src.url, isEnabled = src.isEnabled)
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
        createdAt = createdAt
    )

    private fun TagEntity.toDomain() = TagItem(
        id = id,
        name = name,
        colorHex = colorHex,
        keywords = keywords
    )
}
