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
import com.example.domain.model.DiagnosticStep
import com.example.domain.model.DiagnosticStepStatus
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceHealthStatus
import com.example.domain.model.SourceStatusInfo
import com.example.domain.model.TagItem
import com.example.domain.model.UrlNormalizer
import com.example.presentation.viewmodel.filterNewsArticles
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
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
        // 180 days (~6 months): monthly/quarterly software & emulator devblogs stay healthy;
        // half a year without posts indicates an inactive or abandoned feed.
        const val STALE_FEED_THRESHOLD_DAYS = 180L
        private const val STALE_FEED_THRESHOLD_MS = STALE_FEED_THRESHOLD_DAYS * 24L * 60L * 60L * 1000L
    }

    private val sourceDao = database.sourceDao()
    private val tagDao = database.tagDao()
    private val newsDao = database.newsDao()
    private val translationMutex = Mutex()
    private val sourceMutationMutex = Mutex()

    private val _failedSources = MutableStateFlow<List<FailedSourceInfo>>(emptyList())
    val failedSources: StateFlow<List<FailedSourceInfo>> = _failedSources.asStateFlow()

    private val _sourceStatuses = MutableStateFlow<Map<Long, SourceStatusInfo>>(emptyMap())
    val sourceStatuses: StateFlow<Map<Long, SourceStatusInfo>> = _sourceStatuses.asStateFlow()

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
            val statusMap = _sourceStatuses.value.toMutableMap()
            val now = System.currentTimeMillis()

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
                    statusMap[source.id] = SourceStatusInfo(
                        sourceId = source.id,
                        status = SourceHealthStatus.NETWORK_OR_HTTP_ERROR,
                        message = msg
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
                    statusMap[source.id] = SourceStatusInfo(
                        sourceId = source.id,
                        status = SourceHealthStatus.NETWORK_OR_HTTP_ERROR,
                        message = "Пустой ответ ленты"
                    )
                    continue
                }

                if (feed.items.isEmpty()) {
                    successfulSourcesCount++
                    statusMap[source.id] = SourceStatusInfo(
                        sourceId = source.id,
                        status = SourceHealthStatus.EMPTY_FEED,
                        message = "Источник доступен, но RSS содержит 0 записей",
                        fetchedCount = 0,
                        newCount = 0
                    )
                    continue
                }

                try {
                    val limitedItems = feed.items
                        .sortedByDescending { it.pubDate }
                        .take(MAX_ITEMS_PER_FEED_FETCH)

                    var sourceNewCount = 0
                    var sourceAlreadyInDbCount = 0
                    var sourceSaveFailedCount = 0

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
                                sourceNewCount++
                                // User-driven auto-tagging
                                val autoTags = findAutoTags(item.title, item.content, allDbTags)
                                val crossRefs = autoTags.map { NewsTagCrossRef(newsId = insertedId, tagId = it.id) }
                                if (crossRefs.isNotEmpty()) {
                                    newsDao.insertNewsTagCrossRefs(crossRefs)
                                }
                            } else {
                                sourceSaveFailedCount++
                            }
                        } else {
                            sourceAlreadyInDbCount++
                        }
                    }
                    newsDao.trimOldNewsForSource(source.id, MAX_STORED_NEWS_PER_SOURCE)
                    successfulSourcesCount++

                    val latestActualPubDate = feed.items.mapNotNull { it.parsedPubDate }.maxOrNull()
                    val isStale = latestActualPubDate != null && (now - latestActualPubDate) > STALE_FEED_THRESHOLD_MS

                    statusMap[source.id] = when {
                        sourceSaveFailedCount > 0 && sourceNewCount == 0 -> SourceStatusInfo(
                            sourceId = source.id,
                            status = SourceHealthStatus.DB_SAVE_ERROR,
                            message = "Записи получены (${limitedItems.size}), но не удалось сохранить их в базу",
                            latestPubDate = latestActualPubDate,
                            fetchedCount = feed.items.size,
                            newCount = 0
                        )
                        isStale -> {
                            val dateStr = formatShortDate(latestActualPubDate!!)
                            val suffix = if (sourceNewCount == 0 && sourceAlreadyInDbCount > 0) {
                                " (все записи уже в базе)"
                            } else {
                                ""
                            }
                            SourceStatusInfo(
                                sourceId = source.id,
                                status = SourceHealthStatus.STALE_FEED,
                                message = "Давно не обновлялся: последняя публикация $dateStr$suffix",
                                latestPubDate = latestActualPubDate,
                                fetchedCount = feed.items.size,
                                newCount = sourceNewCount
                            )
                        }
                        sourceNewCount == 0 && sourceAlreadyInDbCount > 0 -> SourceStatusInfo(
                            sourceId = source.id,
                            status = SourceHealthStatus.NO_NEW_ARTICLES,
                            message = "Новых записей нет (все $sourceAlreadyInDbCount уже в базе)",
                            latestPubDate = latestActualPubDate,
                            fetchedCount = feed.items.size,
                            newCount = 0
                        )
                        else -> SourceStatusInfo(
                            sourceId = source.id,
                            status = SourceHealthStatus.OK,
                            message = "Загружено новых записей: $sourceNewCount",
                            latestPubDate = latestActualPubDate,
                            fetchedCount = feed.items.size,
                            newCount = sourceNewCount
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Ошибка сохранения новостей"
                    statusMap[source.id] = SourceStatusInfo(
                        sourceId = source.id,
                        status = SourceHealthStatus.DB_SAVE_ERROR,
                        message = "Записи получены, но не удалось сохранить в базу: $errMsg",
                        fetchedCount = feed.items.size
                    )
                    successfulSourcesCount++
                }
            }

            _failedSources.value = failedList
            _sourceStatuses.value = statusMap

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

    suspend fun diagnoseSource(
        sourceId: Long,
        selectedSourceIds: Set<Long> = emptySet(),
        searchQuery: String = ""
    ): Result<SourceDiagnosticReport> = withContext(Dispatchers.IO) {
        try {
            val sourceEntity = sourceDao.getSourceById(sourceId)
                ?: return@withContext Result.failure(Exception("Источник не найден в локальной базе"))

            val allSources = sourceDao.getAllSourcesSync().map { it.toDomain() }
            val sourceTitleById = allSources.associate { it.id to it.title }
            val storedWithTags = newsDao.getNewsWithTagsForSourceSync(sourceId)
            val storedArticles = storedWithTags.map { newsWithTags ->
                NewsArticle(
                    id = newsWithTags.news.id,
                    sourceId = newsWithTags.news.sourceId,
                    sourceTitle = sourceEntity.title,
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

            val visibleStoredArticles = filterNewsArticles(
                allNews = storedArticles,
                sources = allSources,
                selectedSourceIds = selectedSourceIds,
                searchQuery = searchQuery
            )

            val fetchResult = feedFetcher.diagnoseFeed(sourceEntity.url)
            val parsedFeed = fetchResult.parsedFeed

            val fetchedItemsCount = parsedFeed?.items?.size ?: 0
            val limitedItems = parsedFeed?.items
                ?.sortedByDescending { it.pubDate }
                ?.take(MAX_ITEMS_PER_FEED_FETCH)
                .orEmpty()

            val validDates = parsedFeed?.items?.mapNotNull { it.parsedPubDate }.orEmpty()
            val latestFeedPubDate = validDates.maxOrNull()
            val latestFeedRawPubDate = if (latestFeedPubDate != null) {
                parsedFeed?.items
                    ?.firstOrNull { it.parsedPubDate == latestFeedPubDate && !it.rawPubDate.isNullOrBlank() }
                    ?.rawPubDate
            } else {
                parsedFeed?.items?.firstNotNullOfOrNull { it.rawPubDate }
            }
            val itemsWithValidDatesCount = validDates.size
            val itemsWithoutValidDatesCount = (fetchedItemsCount - itemsWithValidDatesCount).coerceAtLeast(0)

            var wouldInsertNewCount = 0
            var alreadyInDbSameSourceCount = 0
            var guidCollisionOtherSourceCount = 0
            var wouldBeTrimmedCount = 0
            var duplicateInsideFeedCount = 0
            val collidingSourceTitles = linkedSetOf<String>()

            val seenGuids = HashSet<String>()
            val seenLinks = HashSet<String>()
            val cutoffStoredPubDate = if (storedWithTags.size >= MAX_STORED_NEWS_PER_SOURCE) {
                storedWithTags[MAX_STORED_NEWS_PER_SOURCE - 1].news.pubDate
            } else {
                null
            }

            if (parsedFeed != null) {
                for (item in limitedItems) {
                    val isDupInBatch = (item.guid.isNotBlank() && !seenGuids.add(item.guid)) ||
                        (item.link.isNotBlank() && !seenLinks.add(item.link))
                    if (isDupInBatch) {
                        duplicateInsideFeedCount++
                        continue
                    }

                    val existingByGuid = newsDao.getNewsByGuid(item.guid)
                    val existingByLink = if (existingByGuid == null && item.link.isNotBlank()) {
                        newsDao.getNewsByLink(item.link)
                    } else {
                        null
                    }
                    val existing = existingByGuid ?: existingByLink

                    when {
                        existing == null -> {
                            wouldInsertNewCount++
                            if (cutoffStoredPubDate != null && item.pubDate < cutoffStoredPubDate) {
                                wouldBeTrimmedCount++
                            }
                        }
                        existing.sourceId == sourceId -> {
                            alreadyInDbSameSourceCount++
                        }
                        else -> {
                            guidCollisionOtherSourceCount++
                            collidingSourceTitles.add(
                                sourceTitleById[existing.sourceId] ?: "Источник #${existing.sourceId}"
                            )
                        }
                    }
                }
            }

            val dbStep = when {
                parsedFeed == null -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.SKIPPED,
                    summary = "Проверка входящих записей пропущена из-за ошибки получения/парсинга. В БД сейчас сохранено записей этого источника: ${storedWithTags.size}.",
                    technicalDetails = "Текущий объём в Room для sourceId=$sourceId: ${storedWithTags.size} из макс. $MAX_STORED_NEWS_PER_SOURCE"
                )
                limitedItems.isEmpty() -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "Новых записей для сохранения нет (лента пуста). В локальной БД сейчас хранится: ${storedWithTags.size}.",
                    technicalDetails = "В базе Room для sourceId=$sourceId: ${storedWithTags.size} записей"
                )
                guidCollisionOtherSourceCount > 0 && wouldInsertNewCount == 0 && alreadyInDbSameSourceCount == 0 -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.ERROR,
                    summary = "Все проверенные записи (${guidCollisionOtherSourceCount}) совпадают по guid/link с другим источником и не сохранятся повторно.",
                    technicalDetails = buildString {
                        append("Конфликт уникального индекса guid/link с источниками: ${collidingSourceTitles.joinToString(", ")}")
                        append("\nВ БД уже хранится у текущего источника: ${storedWithTags.size}")
                    }
                )
                guidCollisionOtherSourceCount > 0 -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "Новых к сохранению: $wouldInsertNewCount, уже в БД: $alreadyInDbSameSourceCount, пропущено из-за совпадения guid/link с другим источником: $guidCollisionOtherSourceCount.",
                    technicalDetails = buildString {
                        append("Проверено свежих записей: ${limitedItems.size} (лимит за обновление: $MAX_ITEMS_PER_FEED_FETCH)")
                        append("\nКонфликтующие источники: ${collidingSourceTitles.joinToString(", ")}")
                        append("\nВсего в Room по этому источнику: ${storedWithTags.size} (макс. $MAX_STORED_NEWS_PER_SOURCE)")
                    }
                )
                wouldInsertNewCount > 0 && wouldBeTrimmedCount == wouldInsertNewCount -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "Все $wouldInsertNewCount новых записей старше уже сохранённых $MAX_STORED_NEWS_PER_SOURCE новостей и будут отсечены лимитом хранения.",
                    technicalDetails = "В Room уже хранится ${storedWithTags.size} более свежих записей этого источника"
                )
                else -> DiagnosticStep(
                    id = "database",
                    title = "Анализ сохранения в локальную БД",
                    status = DiagnosticStepStatus.SUCCESS,
                    summary = "Конфликтов сохранения не найдено: новых записей $wouldInsertNewCount, уже сохранено в БД $alreadyInDbSameSourceCount (всего по источнику в БД: ${storedWithTags.size}).",
                    technicalDetails = buildString {
                        append("Проанализировано: ${limitedItems.size} из $fetchedItemsCount (лимит за раз: $MAX_ITEMS_PER_FEED_FETCH)")
                        append("\nНовых к записи: $wouldInsertNewCount")
                        append("\nУже в БД (sourceId=$sourceId): $alreadyInDbSameSourceCount")
                        if (duplicateInsideFeedCount > 0) {
                            append("\nДублей guid/link внутри самого фида: $duplicateInsideFeedCount")
                        }
                        if (wouldBeTrimmedCount > 0) {
                            append("\nБудет отсечено лимитом $MAX_STORED_NEWS_PER_SOURCE записей: $wouldBeTrimmedCount")
                        }
                    }
                )
            }

            val enabledSourceIds = allSources.filter { it.isEnabled }.map { it.id }.toSet()
            val activeFilterIds = selectedSourceIds.intersect(enabledSourceIds)
            val displayStep = when {
                !sourceEntity.isEnabled -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "Источник выключен тумблером — его новости (${storedWithTags.size} в БД) скрыты и не обновляются.",
                    technicalDetails = "isEnabled=false; в Room хранится ${storedWithTags.size}, в ленте отображается 0"
                )
                storedWithTags.isEmpty() && parsedFeed == null -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.SKIPPED,
                    summary = "В локальной базе пока нет сохранённых статей этого источника; отображение в ленте невозможно до устранения ошибки получения.",
                    technicalDetails = "storedInDbCount=0, visibleInFeedCount=0"
                )
                storedWithTags.isEmpty() && wouldInsertNewCount == 0 -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "В локальной базе пока нет статей этого источника для отображения в ленте.",
                    technicalDetails = "storedInDbCount=0, visibleInFeedCount=0"
                )
                storedWithTags.isEmpty() && wouldInsertNewCount > 0 -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.WARNING,
                    summary = "Источник готов, но новости ещё не загружены в БД (обнаружено $wouldInsertNewCount новых записей — обновите ленту).",
                    technicalDetails = "Диагностика не изменяет БД. После обновления ленты в БД появится до $wouldInsertNewCount записей."
                )
                visibleStoredArticles.isEmpty() && storedWithTags.isNotEmpty() -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.WARNING,
                    summary = buildString {
                        append("В БД есть ${storedWithTags.size} статей, но в ленте сейчас скрыты ")
                        val reasons = mutableListOf<String>()
                        if (activeFilterIds.isNotEmpty() && sourceId !in activeFilterIds) {
                            reasons.add("фильтром по другим источникам")
                        }
                        if (searchQuery.isNotBlank()) {
                            reasons.add("поисковым запросом «$searchQuery»")
                        }
                        append(reasons.ifEmpty { listOf("активными фильтрами") }.joinToString(" и "))
                        append(".")
                    },
                    technicalDetails = "В БД: ${storedWithTags.size}; после filterNewsArticles: 0; selectedSourceIds=$activeFilterIds; query='$searchQuery'"
                )
                else -> DiagnosticStep(
                    id = "display",
                    title = "Отображение в ленте",
                    status = DiagnosticStepStatus.SUCCESS,
                    summary = "В ленте сейчас отображается ${visibleStoredArticles.size} из ${storedWithTags.size} сохранённых статей источника.",
                    technicalDetails = buildString {
                        append("isEnabled=true")
                        append("\nОтображается в ленте: ${visibleStoredArticles.size}")
                        append("\nВсего в БД: ${storedWithTags.size}")
                        if (itemsWithoutValidDatesCount > 0) {
                            append("\nВнимание: у $itemsWithoutValidDatesCount записей дата в XML не распознана — при обновлении подставляется время загрузки, что влияет на порядок сортировки.")
                        }
                    }
                )
            }

            val now = System.currentTimeMillis()
            val isStaleFeed = latestFeedPubDate != null && (now - latestFeedPubDate) > STALE_FEED_THRESHOLD_MS
            val effectiveParsingStep = if (
                isStaleFeed &&
                fetchResult.parsingStep.status == DiagnosticStepStatus.SUCCESS
            ) {
                val dateStr = formatShortDate(latestFeedPubDate!!)
                fetchResult.parsingStep.copy(
                    status = DiagnosticStepStatus.WARNING,
                    summary = "${fetchResult.parsingStep.summary} Однако источник давно не обновлялся (последняя публикация: $dateStr).",
                    technicalDetails = buildString {
                        if (!fetchResult.parsingStep.technicalDetails.isNullOrBlank()) {
                            append(fetchResult.parsingStep.technicalDetails)
                            append("\n")
                        }
                        append("Порог устаревания: более $STALE_FEED_THRESHOLD_DAYS дн. без новых записей")
                    }
                )
            } else {
                fetchResult.parsingStep
            }

            val allSteps = listOf(
                fetchResult.networkStep,
                fetchResult.httpStep,
                fetchResult.contentTypeStep,
                effectiveParsingStep,
                dbStep,
                displayStep
            )

            val firstError = allSteps.firstOrNull { it.status == DiagnosticStepStatus.ERROR }
            val firstWarning = allSteps.firstOrNull { it.status == DiagnosticStepStatus.WARNING }
            val overallStatus = when {
                firstError != null -> DiagnosticStepStatus.ERROR
                firstWarning != null -> DiagnosticStepStatus.WARNING
                else -> DiagnosticStepStatus.SUCCESS
            }
            val failedStageTitle = firstError?.title ?: firstWarning?.title

            Result.success(
                SourceDiagnosticReport(
                    sourceId = sourceEntity.id,
                    sourceTitle = sourceEntity.title,
                    sourceUrl = sourceEntity.url,
                    isSourceEnabled = sourceEntity.isEnabled,
                    checkedAt = System.currentTimeMillis(),
                    overallStatus = overallStatus,
                    failedStageTitle = failedStageTitle,
                    httpCode = fetchResult.httpCode,
                    finalUrl = fetchResult.finalUrl,
                    contentType = fetchResult.contentType,
                    responseTimeMs = fetchResult.responseTimeMs,
                    responseSizeBytes = fetchResult.responseSizeBytes,
                    feedFormat = fetchResult.feedFormat,
                    fetchedItemsCount = fetchedItemsCount,
                    processableItemsCount = limitedItems.size,
                    latestFeedPubDate = latestFeedPubDate,
                    latestFeedRawPubDate = latestFeedRawPubDate,
                    itemsWithValidDatesCount = itemsWithValidDatesCount,
                    itemsWithoutValidDatesCount = itemsWithoutValidDatesCount,
                    storedInDbCount = storedWithTags.size,
                    wouldInsertNewCount = wouldInsertNewCount,
                    alreadyInDbSameSourceCount = alreadyInDbSameSourceCount,
                    guidCollisionOtherSourceCount = guidCollisionOtherSourceCount,
                    wouldBeTrimmedCount = wouldBeTrimmedCount,
                    visibleInFeedCount = visibleStoredArticles.size,
                    steps = allSteps
                )
            )
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

    private suspend fun findDuplicateSourceByUrl(
        candidateUrl: String,
        excludingSourceId: Long? = null
    ): SourceEntity? {
        val normalizedCandidate = UrlNormalizer.normalizeForComparison(candidateUrl)
        if (normalizedCandidate.isEmpty()) return null
        return sourceDao.getAllSourcesSync().firstOrNull { existing ->
            (excludingSourceId == null || existing.id != excludingSourceId) &&
                UrlNormalizer.normalizeForComparison(existing.url) == normalizedCandidate
        }
    }

    suspend fun addSource(title: String, url: String): Result<Long> = withContext(Dispatchers.IO) {
        sourceMutationMutex.withLock {
            try {
                val cleanUrl = url.trim()
                if (cleanUrl.toHttpUrlOrNull() == null) {
                    return@withContext Result.failure(Exception("Введите корректный URL (http:// или https://)"))
                }

                val existing = findDuplicateSourceByUrl(cleanUrl)
                if (existing != null) {
                    return@withContext Result.failure(
                        Exception("Этот источник уже добавлен («${existing.title}»)")
                    )
                }

                val testFetch = feedFetcher.fetchFeed(cleanUrl)
                val feedTitle = testFetch.getOrNull()?.title
                val finalTitle = title.trim().ifEmpty { feedTitle ?: "RSS Источник" }
                val nextSortOrder = sourceDao.getMaxSortOrder() + 1

                val newSource = SourceEntity(
                    title = finalTitle,
                    url = cleanUrl,
                    isEnabled = true,
                    sortOrder = nextSortOrder
                )
                val id = sourceDao.insertSource(newSource)
                if (id > 0) {
                    val feed = testFetch.getOrNull()
                    if (feed != null) {
                        val allDbTags = tagDao.getAllTagsSync()
                        val limitedItems = feed.items
                            .sortedByDescending { it.pubDate }
                            .take(MAX_ITEMS_PER_FEED_FETCH)

                        var savedCount = 0
                        for (item in limitedItems) {
                            val existingGuid = newsDao.getNewsByGuid(item.guid)
                            val existingLink = if (existingGuid == null) newsDao.getNewsByLink(item.link) else null
                            if (existingGuid == null && existingLink == null) {
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
                                    savedCount++
                                    val autoTags = findAutoTags(item.title, item.content, allDbTags)
                                    val crossRefs = autoTags.map { NewsTagCrossRef(newsId = newsId, tagId = it.id) }
                                    if (crossRefs.isNotEmpty()) {
                                        newsDao.insertNewsTagCrossRefs(crossRefs)
                                    }
                                }
                            }
                        }
                        newsDao.trimOldNewsForSource(id, MAX_STORED_NEWS_PER_SOURCE)
                        _sourceStatuses.value = _sourceStatuses.value + (
                            id to if (feed.items.isEmpty()) {
                                SourceStatusInfo(id, SourceHealthStatus.EMPTY_FEED, "Источник доступен, но RSS содержит 0 записей")
                            } else {
                                SourceStatusInfo(
                                    id,
                                    SourceHealthStatus.OK,
                                    "Загружено новых записей: $savedCount",
                                    latestPubDate = feed.items.mapNotNull { it.parsedPubDate }.maxOrNull(),
                                    fetchedCount = feed.items.size,
                                    newCount = savedCount
                                )
                            }
                        )
                    } else if (testFetch.isFailure) {
                        val err = testFetch.exceptionOrNull()?.message ?: "Сервер не отвечает"
                        _sourceStatuses.value = _sourceStatuses.value + (
                            id to SourceStatusInfo(id, SourceHealthStatus.NETWORK_OR_HTTP_ERROR, err)
                        )
                    }
                    Result.success(id)
                } else {
                    Result.failure(Exception("Не удалось сохранить источник"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updateSource(
        sourceId: Long,
        title: String,
        url: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        sourceMutationMutex.withLock {
            try {
                val existing = sourceDao.getSourceById(sourceId)
                    ?: return@withContext Result.failure(Exception("Источник не найден"))

                val cleanUrl = url.trim()
                if (cleanUrl.toHttpUrlOrNull() == null) {
                    return@withContext Result.failure(Exception("Введите корректный URL (http:// или https://)"))
                }

                val duplicate = findDuplicateSourceByUrl(cleanUrl, excludingSourceId = sourceId)
                if (duplicate != null) {
                    return@withContext Result.failure(
                        Exception("Этот источник уже добавлен («${duplicate.title}»)")
                    )
                }

                val finalTitle = title.trim().ifEmpty { existing.title }
                val urlChanged = UrlNormalizer.normalizeForComparison(existing.url) !=
                    UrlNormalizer.normalizeForComparison(cleanUrl)

                // Update in-place by primary key so existing saved news (ForeignKey sourceId) remain intact
                sourceDao.updateSource(existing.copy(title = finalTitle, url = cleanUrl))

                if (urlChanged) {
                    if (existing.isEnabled) {
                        val fetchResult = feedFetcher.fetchFeed(cleanUrl)
                        val feed = fetchResult.getOrNull()
                        if (feed != null) {
                            val allDbTags = tagDao.getAllTagsSync()
                            val limitedItems = feed.items
                                .sortedByDescending { it.pubDate }
                                .take(MAX_ITEMS_PER_FEED_FETCH)

                            var savedCount = 0
                            var alreadyInDbCount = 0
                            var saveFailedCount = 0

                            for (item in limitedItems) {
                                val existingByGuid = newsDao.getNewsByGuid(item.guid)
                                val existingByLink = if (existingByGuid == null) newsDao.getNewsByLink(item.link) else null
                                if (existingByGuid == null && existingByLink == null) {
                                    val newsId = newsDao.insertNews(
                                        NewsEntity(
                                            sourceId = sourceId,
                                            guid = item.guid,
                                            title = item.title,
                                            summary = item.description,
                                            content = item.content,
                                            link = item.link,
                                            imageUrl = item.imageUrl,
                                            pubDate = item.pubDate
                                        )
                                    )
                                    if (newsId > 0) {
                                        savedCount++
                                        val autoTags = findAutoTags(item.title, item.content, allDbTags)
                                        val crossRefs = autoTags.map { NewsTagCrossRef(newsId = newsId, tagId = it.id) }
                                        if (crossRefs.isNotEmpty()) {
                                            newsDao.insertNewsTagCrossRefs(crossRefs)
                                        }
                                    } else {
                                        saveFailedCount++
                                    }
                                } else {
                                    alreadyInDbCount++
                                }
                            }
                            newsDao.trimOldNewsForSource(sourceId, MAX_STORED_NEWS_PER_SOURCE)

                            val latestDate = feed.items.mapNotNull { it.parsedPubDate }.maxOrNull()
                            val isStale = latestDate != null &&
                                (System.currentTimeMillis() - latestDate) > STALE_FEED_THRESHOLD_MS

                            _sourceStatuses.value = _sourceStatuses.value + (
                                sourceId to when {
                                    feed.items.isEmpty() -> SourceStatusInfo(
                                        sourceId, SourceHealthStatus.EMPTY_FEED,
                                        "Источник доступен, но RSS содержит 0 записей"
                                    )
                                    saveFailedCount > 0 && savedCount == 0 -> SourceStatusInfo(
                                        sourceId, SourceHealthStatus.DB_SAVE_ERROR,
                                        "Записи получены, но не удалось сохранить их в базу",
                                        latestPubDate = latestDate, fetchedCount = feed.items.size
                                    )
                                    isStale -> SourceStatusInfo(
                                        sourceId, SourceHealthStatus.STALE_FEED,
                                        "Давно не обновлялся: последняя публикация ${formatShortDate(latestDate!!)}",
                                        latestPubDate = latestDate, fetchedCount = feed.items.size, newCount = savedCount
                                    )
                                    savedCount == 0 && alreadyInDbCount > 0 -> SourceStatusInfo(
                                        sourceId, SourceHealthStatus.NO_NEW_ARTICLES,
                                        "Новых записей нет (все $alreadyInDbCount уже в базе)",
                                        latestPubDate = latestDate, fetchedCount = feed.items.size
                                    )
                                    else -> SourceStatusInfo(
                                        sourceId, SourceHealthStatus.OK,
                                        "Источник обновлён, новых записей: $savedCount",
                                        latestPubDate = latestDate, fetchedCount = feed.items.size, newCount = savedCount
                                    )
                                }
                            )
                        } else {
                            val errMsg = fetchResult.exceptionOrNull()?.message ?: "Ошибка загрузки нового URL"
                            _sourceStatuses.value = _sourceStatuses.value + (
                                sourceId to SourceStatusInfo(sourceId, SourceHealthStatus.NETWORK_OR_HTTP_ERROR, errMsg)
                            )
                        }
                    } else {
                        _sourceStatuses.value = _sourceStatuses.value - sourceId
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        sourceDao.deleteSourceById(sourceId)
        _sourceStatuses.value = _sourceStatuses.value - sourceId
        _failedSources.value = _failedSources.value.filterNot { it.sourceId == sourceId }
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

        // Merge sources without duplicates (using normalized URL matching)
        var nextSortOrder = sourceDao.getMaxSortOrder() + 1
        for (src in config.sources) {
            val existing = findDuplicateSourceByUrl(src.url)
            if (existing == null) {
                sourceDao.insertSource(
                    SourceEntity(
                        title = src.title,
                        url = src.url.trim(),
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

    private fun formatShortDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun TagEntity.toDomain() = TagItem(
        id = id,
        name = name,
        colorHex = colorHex,
        keywords = keywords
    )
}
