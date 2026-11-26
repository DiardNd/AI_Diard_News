package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.config.ChangelogProvider
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.NewsTagCrossRef
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.TagEntity
import com.example.data.remote.FeedFetcher
import com.example.data.repository.NewsRepository
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.SourceHealthStatus
import com.example.domain.model.SourceSortMode
import com.example.domain.model.SourceStatusInfo
import com.example.domain.model.UrlNormalizer
import com.example.domain.model.sortSources
import com.example.presentation.components.AddSourceDialog
import com.example.presentation.screens.FeedScreen
import com.example.presentation.screens.HelpScreen
import com.example.presentation.screens.SourcesScreen
import com.example.presentation.viewmodel.NewsFeedUiState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceManagementImprovementsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var preferencesRepository: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesRepository = UserPreferencesRepository(context)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createFetcher(
        handler: (String) -> Triple<Int, String, String>
    ): FeedFetcher {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val (code, contentType, body) = handler(url)
            if (code == -1) {
                throw UnknownHostException("Unable to resolve host $url")
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error $code")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return FeedFetcher(client)
    }

    // =========================================================================
    // 1. Source Editing (preserving existing articles, tags, avoiding dupes)
    // =========================================================================

    @Test
    fun updateSourceChangesTitleAndUrlWithoutDeletingSavedNewsOrCreatingDuplicates() = runTest {
        val sourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Old Name", url = "https://old.example.org/rss.xml", isEnabled = true, sortOrder = 0)
        )
        val otherId = database.sourceDao().insertSource(
            SourceEntity(title = "Other Feed", url = "https://other.example.org/feed.xml", isEnabled = true, sortOrder = 1)
        )
        val tagId = database.tagDao().insertTag(
            TagEntity(name = "Release", colorHex = "#A29BFE", keywords = "release")
        )
        val existingNewsId = database.newsDao().insertNews(
            NewsEntity(
                sourceId = sourceId,
                guid = "shared-post-1",
                title = "Existing Saved Article",
                summary = "Saved summary",
                content = "Saved content",
                link = "https://new.example.org/post-1",
                pubDate = 1_700_000_000_000L
            )
        )
        database.newsDao().insertNewsTagCrossRef(NewsTagCrossRef(existingNewsId, tagId))

        val newFeedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>New Feed</title>
                    <item>
                        <title>Existing Saved Article (from new URL)</title>
                        <link>https://new.example.org/post-1</link>
                        <guid>shared-post-1</guid>
                        <description>Duplicate guid</description>
                        <pubDate>Tue, 10 Nov 2026 10:00:00 +0000</pubDate>
                    </item>
                    <item>
                        <title>Fresh Release News</title>
                        <link>https://new.example.org/post-2</link>
                        <guid>new-post-2</guid>
                        <description>New item after URL edit</description>
                        <pubDate>Wed, 11 Nov 2026 10:00:00 +0000</pubDate>
                    </item>
                </channel>
            </rss>
        """.trimIndent()

        val fetcher = createFetcher { Triple(200, "application/rss+xml; charset=utf-8", newFeedXml) }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        // Attempt to update URL to an existing duplicate source -> rejected without damaging data
        val dupResult = repo.updateSource(sourceId, "Renamed", "https://OTHER.example.org/feed.xml/")
        assertTrue(dupResult.isFailure)
        assertTrue(dupResult.exceptionOrNull()?.message?.contains("Этот источник уже добавлен") == true)
        val intactBeforeValidUpdate = database.sourceDao().getSourceById(sourceId)!!
        assertEquals("Old Name", intactBeforeValidUpdate.title)
        assertEquals("https://old.example.org/rss.xml", intactBeforeValidUpdate.url)
        assertEquals(1, database.newsDao().getNewsWithTagsForSourceSync(sourceId).size)

        // Valid update -> succeeds, keeps existing article + tag attached to same sourceId, adds only non-duplicate item
        val updateResult = repo.updateSource(sourceId, "Renamed Blog", "https://new.example.org/rss.xml")
        assertTrue(updateResult.isSuccess)

        val updatedSource = database.sourceDao().getSourceById(sourceId)!!
        assertEquals("Renamed Blog", updatedSource.title)
        assertEquals("https://new.example.org/rss.xml", updatedSource.url)
        assertEquals(otherId, database.sourceDao().getSourceById(otherId)!!.id)

        val savedArticles = database.newsDao().getNewsWithTagsForSourceSync(sourceId)
        assertEquals(2, savedArticles.size)
        assertEquals(listOf(sourceId), savedArticles.map { it.news.sourceId }.distinct())
        val preserved = savedArticles.first { it.news.id == existingNewsId }
        assertEquals("Existing Saved Article", preserved.news.title)
        assertEquals(listOf("Release"), preserved.tags.map { it.name })
    }

    @Test
    fun editSourceDialogSupportsCancelAndSave() {
        val source = NewsSource(
            id = 10L,
            title = "Dolphin Blog",
            url = "https://dolphin-emu.org/blog/feeds/",
            isEnabled = true,
            sortOrder = 0
        )
        var editedPair: Pair<String, String>? = null

        composeRule.setContent {
            MyApplicationTheme {
                SourcesScreen(
                    sources = listOf(source),
                    onToggleSource = { _, _ -> },
                    onAddSource = { _, _, cb -> cb(true, null) },
                    onDeleteSource = {},
                    onMoveSourceUp = {},
                    onMoveSourceDown = {},
                    onRestorePresets = {},
                    onExportToUri = {},
                    onImportFromUri = {},
                    onEditSource = { _, title, url, cb ->
                        editedPair = title to url
                        cb(true, null)
                    }
                )
            }
        }

        // Open edit dialog, change title, cancel -> onEditSource is NOT called
        composeRule.onNodeWithTag("edit_source_button_10").performClick()
        composeRule.onNodeWithTag("edit_source_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("edit_source_title_input").performTextClearance()
        composeRule.onNodeWithTag("edit_source_title_input").performTextInput("Abandoned Edit")
        composeRule.onNodeWithTag("cancel_edit_source_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("edit_source_dialog").assertDoesNotExist()
        assertEquals(null, editedPair)

        // Open again, edit title and URL, save -> onEditSource receives new values
        composeRule.onNodeWithTag("edit_source_button_10").performClick()
        composeRule.onNodeWithTag("edit_source_title_input").performTextClearance()
        composeRule.onNodeWithTag("edit_source_title_input").performTextInput("Dolphin Official")
        composeRule.onNodeWithTag("edit_source_url_input").performTextClearance()
        composeRule.onNodeWithTag("edit_source_url_input").performTextInput("https://dolphin-emu.org/rss/")
        composeRule.onNodeWithTag("submit_edit_source_button").performClick()
        composeRule.waitForIdle()
        assertEquals("Dolphin Official" to "https://dolphin-emu.org/rss/", editedPair)
    }

    // =========================================================================
    // 2. Source Sort Modes & Persistence
    // =========================================================================

    @Test
    fun sortSourcesSupportsAllFourModesAndPersistsSelection() = runTest {
        val input = listOf(
            NewsSource(1L, "Zulu", "https://z.test", isEnabled = false, sortOrder = 0, createdAt = 100L),
            NewsSource(2L, "alpha", "https://a.test", isEnabled = true, sortOrder = 2, createdAt = 300L),
            NewsSource(3L, "Beta", "https://b.test", isEnabled = true, sortOrder = 1, createdAt = 200L)
        )

        assertEquals(listOf(1L, 3L, 2L), sortSources(input, SourceSortMode.MANUAL).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), sortSources(input, SourceSortMode.TITLE_ASC).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), sortSources(input, SourceSortMode.ENABLED_FIRST).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), sortSources(input, SourceSortMode.CREATED_DESC).map { it.id })

        preferencesRepository.setSourceSortMode(SourceSortMode.ENABLED_FIRST)
        assertEquals(SourceSortMode.ENABLED_FIRST, preferencesRepository.sourceSortMode.first())

        // Recreate repository instance on same context -> persisted sort mode survives restart
        val freshPrefs = UserPreferencesRepository(context)
        assertEquals(SourceSortMode.ENABLED_FIRST, freshPrefs.sourceSortMode.first())
    }

    @Test
    fun sourcesScreenShowsManualArrowsOnlyInManualSortMode() {
        val sources = listOf(
            NewsSource(1L, "Alpha", "https://a.test", isEnabled = true, sortOrder = 0),
            NewsSource(2L, "Beta", "https://b.test", isEnabled = true, sortOrder = 1)
        )
        var currentMode by mutableStateOf(SourceSortMode.MANUAL)

        composeRule.setContent {
            MyApplicationTheme {
                SourcesScreen(
                    sources = sources,
                    onToggleSource = { _, _ -> },
                    onAddSource = { _, _, cb -> cb(true, null) },
                    onDeleteSource = {},
                    onMoveSourceUp = {},
                    onMoveSourceDown = {},
                    onRestorePresets = {},
                    onExportToUri = {},
                    onImportFromUri = {},
                    sourceSortMode = currentMode,
                    onSelectSortMode = { currentMode = it }
                )
            }
        }

        composeRule.onNodeWithTag("sort_mode_MANUAL").assertIsSelected()
        composeRule.onNodeWithTag("move_source_up_2").assertIsDisplayed()

        composeRule.onNodeWithTag("sort_mode_TITLE_ASC").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sort_mode_TITLE_ASC").assertIsSelected()
        composeRule.onNodeWithTag("move_source_up_2").assertDoesNotExist()
        composeRule.onAllNodesWithText("Автосортировка").assertCountEquals(2)
        composeRule.onAllNodesWithText("Автосортировка").onFirst().assertIsDisplayed()
    }

    // =========================================================================
    // 3. Collapsible Help Hint on SourcesScreen
    // =========================================================================

    @Test
    fun sourcesScreenHidesLargeHelpHintByDefaultAndTogglesOnClick() {
        composeRule.setContent {
            MyApplicationTheme {
                SourcesScreen(
                    sources = listOf(NewsSource(1L, "Alpha", "https://a.test", isEnabled = true, sortOrder = 0)),
                    onToggleSource = { _, _ -> },
                    onAddSource = { _, _, cb -> cb(true, null) },
                    onDeleteSource = {},
                    onMoveSourceUp = {},
                    onMoveSourceDown = {},
                    onRestorePresets = {},
                    onExportToUri = {},
                    onImportFromUri = {}
                )
            }
        }

        composeRule.onNodeWithTag("sources_help_hint_card").assertDoesNotExist()
        composeRule.onNodeWithTag("sources_help_toggle_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sources_help_hint_card").assertIsDisplayed()

        composeRule.onNodeWithTag("sources_help_toggle_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sources_help_hint_card").assertDoesNotExist()
    }

    // =========================================================================
    // 4. Informative Source Health Statuses (5 cases + UI badges)
    // =========================================================================

    @Test
    fun refreshAllFeedsDistinguishesNetworkEmptyNoNewStaleAndDbErrors() = runTest {
        val sNetwork = database.sourceDao().insertSource(
            SourceEntity(title = "Down Server", url = "https://down.test/rss", isEnabled = true, sortOrder = 0)
        )
        val sEmpty = database.sourceDao().insertSource(
            SourceEntity(title = "Empty Feed", url = "https://empty.test/rss", isEnabled = true, sortOrder = 1)
        )
        val sNoNew = database.sourceDao().insertSource(
            SourceEntity(title = "Caught Up Feed", url = "https://nonew.test/rss", isEnabled = true, sortOrder = 2)
        )
        val sStale = database.sourceDao().insertSource(
            SourceEntity(title = "Stale Blog", url = "https://stale.test/rss", isEnabled = true, sortOrder = 3)
        )
        val sDbError = database.sourceDao().insertSource(
            SourceEntity(title = "DB Error Feed", url = "https://dberr.test/rss", isEnabled = true, sortOrder = 4)
        )

        database.newsDao().insertNews(
            NewsEntity(
                sourceId = sNoNew,
                guid = "existing-1",
                title = "Already Stored",
                summary = "Sum",
                content = "Body",
                link = "https://nonew.test/1",
                pubDate = System.currentTimeMillis() - 3600_000L
            )
        )

        val emptyXml = """<?xml version="1.0"?><rss version="2.0"><channel><title>Empty</title></channel></rss>"""
        val caughtUpXml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>NoNew</title>
            <item><title>Already Stored</title><link>https://nonew.test/1</link><guid>existing-1</guid>
            <pubDate>Thu, 26 Nov 2026 03:00:00 +0000</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val staleXml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Stale</title>
            <item><title>Ancient Update</title><link>https://stale.test/old</link><guid>stale-1</guid>
            <pubDate>Wed, 15 Jan 2025 12:00:00 +0000</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val dbErrXml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>DbErr</title>
            <item><title>Post</title><link>https://dberr.test/1</link><guid>dberr-1</guid>
            <pubDate>Thu, 26 Nov 2026 03:00:00 +0000</pubDate></item>
            </channel></rss>
        """.trimIndent()

        val fetcher = createFetcher { url ->
            when {
                url.contains("down.test") -> Triple(-1, "text/plain", "")
                url.contains("empty.test") -> Triple(200, "application/rss+xml", emptyXml)
                url.contains("nonew.test") -> Triple(200, "application/rss+xml", caughtUpXml)
                url.contains("stale.test") -> Triple(200, "application/rss+xml", staleXml)
                url.contains("dberr.test") -> {
                    // Simulate DB save constraint failure by removing source row right before insert
                    runBlocking { database.sourceDao().deleteSourceById(sDbError) }
                    Triple(200, "application/rss+xml", dbErrXml)
                }
                else -> Triple(404, "text/plain", "Not Found")
            }
        }

        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val refreshRes = repo.refreshAllFeeds()
        assertTrue("Refresh must succeed when reachable sources exist", refreshRes.isSuccess)
        val summary = refreshRes.getOrThrow()
        assertEquals(4, summary.successfulSourcesCount)
        assertEquals(listOf(sNetwork), summary.failedSources.map { it.sourceId })

        val statuses = repo.sourceStatuses.value
        assertEquals(SourceHealthStatus.NETWORK_OR_HTTP_ERROR, statuses[sNetwork]?.status)
        assertEquals(SourceHealthStatus.EMPTY_FEED, statuses[sEmpty]?.status)
        assertEquals(SourceHealthStatus.NO_NEW_ARTICLES, statuses[sNoNew]?.status)
        assertEquals(SourceHealthStatus.STALE_FEED, statuses[sStale]?.status)
        assertTrue(statuses[sStale]?.message?.contains("Давно не обновлялся") == true)
        assertEquals(SourceHealthStatus.DB_SAVE_ERROR, statuses[sDbError]?.status)
    }

    @Test
    fun sourceWithOnlyNoNewArticlesIsNotTreatedAsNetworkError() = runTest {
        val sId = database.sourceDao().insertSource(
            SourceEntity(title = "Solo", url = "https://solo.test/rss", isEnabled = true)
        )
        database.newsDao().insertNews(
            NewsEntity(
                sourceId = sId,
                guid = "solo-1",
                title = "Only Post",
                summary = "S",
                content = "C",
                link = "https://solo.test/1",
                pubDate = System.currentTimeMillis()
            )
        )
        val xml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Solo</title>
            <item><title>Only Post</title><link>https://solo.test/1</link><guid>solo-1</guid>
            <pubDate>Thu, 26 Nov 2026 03:00:00 +0000</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val repo = NewsRepository(database, preferencesRepository, createFetcher { Triple(200, "application/rss+xml", xml) })
        val result = repo.refreshAllFeeds()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().successfulSourcesCount)
        assertTrue(result.getOrThrow().failedSources.isEmpty())
        assertEquals(SourceHealthStatus.NO_NEW_ARTICLES, repo.sourceStatuses.value[sId]?.status)
    }

    @Test
    fun emptyFeedIsDistinctFromHttpErrorAndNeverMarkedNoNewArticles() = runTest {
        val sEmpty = database.sourceDao().insertSource(
            SourceEntity(title = "Empty Only", url = "https://empty-only.test/rss", isEnabled = true, sortOrder = 0)
        )
        val sHttp500 = database.sourceDao().insertSource(
            SourceEntity(title = "HTTP 500", url = "https://err500.test/rss", isEnabled = true, sortOrder = 1)
        )
        val emptyXml = """<?xml version="1.0"?><rss version="2.0"><channel><title>Empty</title></channel></rss>"""

        val fetcher = createFetcher { url ->
            if (url.contains("empty-only.test")) {
                Triple(200, "application/rss+xml", emptyXml)
            } else {
                Triple(500, "text/plain", "Internal Server Error")
            }
        }
        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val res = repo.refreshAllFeeds()

        assertTrue(res.isSuccess)
        assertEquals(SourceHealthStatus.EMPTY_FEED, repo.sourceStatuses.value[sEmpty]?.status)
        assertNotEquals(SourceHealthStatus.NO_NEW_ARTICLES, repo.sourceStatuses.value[sEmpty]?.status)
        assertEquals(SourceHealthStatus.NETWORK_OR_HTTP_ERROR, repo.sourceStatuses.value[sHttp500]?.status)
        assertEquals(listOf(sHttp500), res.getOrThrow().failedSources.map { it.sourceId })
    }

    @Test
    fun staleFeedUsesActualParsedPubDateIgnoringParserFallbackDate() = runTest {
        val sMixedDates = database.sourceDao().insertSource(
            SourceEntity(title = "Mixed Dates Stale", url = "https://mixed-stale.test/rss", isEnabled = true, sortOrder = 0)
        )
        val sUnparseableOnly = database.sourceDao().insertSource(
            SourceEntity(title = "Bad Date Only", url = "https://baddate.test/rss", isEnabled = true, sortOrder = 1)
        )

        // Item 1 has valid old date (>180 days ago); Item 2 has invalid pubDate so parser fallback sets pubDate=now,
        // while parsedPubDate stays null -> STALE_FEED must still be detected from actual parsedPubDate!
        val mixedStaleXml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Mixed Stale</title>
            <item><title>Old Valid Item</title><link>https://mixed-stale.test/1</link><guid>ms-1</guid>
            <pubDate>Wed, 15 Jan 2025 12:00:00 +0000</pubDate></item>
            <item><title>Fallback Date Item</title><link>https://mixed-stale.test/2</link><guid>ms-2</guid>
            <pubDate>not-a-valid-date</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val unparseableOnlyXml = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Bad Dates</title>
            <item><title>No Date</title><link>https://baddate.test/1</link><guid>bd-1</guid>
            <pubDate>invalid-date-format</pubDate></item>
            </channel></rss>
        """.trimIndent()

        val fetcher = createFetcher { url ->
            if (url.contains("mixed-stale.test")) {
                Triple(200, "application/rss+xml", mixedStaleXml)
            } else {
                Triple(200, "application/rss+xml", unparseableOnlyXml)
            }
        }
        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val res = repo.refreshAllFeeds()
        assertTrue(res.isSuccess)

        assertEquals(SourceHealthStatus.STALE_FEED, repo.sourceStatuses.value[sMixedDates]?.status)
        assertTrue(repo.sourceStatuses.value[sMixedDates]?.message?.contains("15.01.2025") == true)
        assertEquals(SourceHealthStatus.OK, repo.sourceStatuses.value[sUnparseableOnly]?.status)
    }

    @Test
    fun sourceItemCardDisplaysHealthStatusBadge() {
        val sources = listOf(
            NewsSource(1L, "Stale Source", "https://stale.test", isEnabled = true, sortOrder = 0)
        )
        val statuses = mapOf(
            1L to SourceStatusInfo(
                sourceId = 1L,
                status = SourceHealthStatus.STALE_FEED,
                message = "Давно не обновлялся: последняя публикация 15.01.2025"
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                SourcesScreen(
                    sources = sources,
                    onToggleSource = { _, _ -> },
                    onAddSource = { _, _, cb -> cb(true, null) },
                    onDeleteSource = {},
                    onMoveSourceUp = {},
                    onMoveSourceDown = {},
                    onRestorePresets = {},
                    onExportToUri = {},
                    onImportFromUri = {},
                    sourceStatuses = statuses
                )
            }
        }

        composeRule.onNodeWithTag("source_status_badge_1").assertIsDisplayed()
        composeRule.onNodeWithText("Давно не обновлялся: последняя публикация 15.01.2025").assertIsDisplayed()
    }

    // =========================================================================
    // 5. Compact Search Interface on FeedScreen
    // =========================================================================

    @Test
    fun compactFeedSearchInputUpdatesAndClearsQueryWithoutHidingArticles() {
        val sources = listOf(NewsSource(1L, "Dolphin", "https://dolphin.test", isEnabled = true, sortOrder = 0))
        val articles = listOf(
            NewsArticle(
                id = 1L,
                sourceId = 1L,
                sourceTitle = "Dolphin",
                guid = "g1",
                title = "Vulkan Backend Update",
                summary = "Improved frame pacing",
                content = "Body",
                link = "https://dolphin.test/1",
                imageUrl = null,
                pubDate = System.currentTimeMillis()
            )
        )
        var query by mutableStateOf("")

        composeRule.setContent {
            MyApplicationTheme {
                FeedScreen(
                    uiState = NewsFeedUiState(
                        articles = articles,
                        sources = sources,
                        searchQuery = query,
                        isRefreshing = false
                    ),
                    onRefresh = {},
                    onArticleClick = {},
                    onOpenTags = {},
                    onOpenHelp = {},
                    onNavigateToSources = {},
                    onToggleSourceFilter = {},
                    onClearSourceFilters = {},
                    onSearchQueryChange = { query = it },
                    onDismissNetworkError = {}
                )
            }
        }

        composeRule.onNodeWithTag("feed_search_container").assertIsDisplayed()
        composeRule.onNodeWithTag("feed_search_input").assertIsDisplayed()
        composeRule.onNodeWithText("Vulkan Backend Update").assertIsDisplayed()

        composeRule.onNodeWithTag("feed_search_input").performTextInput("Vulkan")
        composeRule.waitForIdle()
        assertEquals("Vulkan", query)

        composeRule.onNodeWithTag("feed_search_clear_button").performClick()
        composeRule.waitForIdle()
        assertEquals("", query)
    }

    // =========================================================================
    // 6. Full Version History (ChangelogProvider & HelpScreen)
    // =========================================================================

    @Test
    fun changelogProviderLoadsAllVersionsFromRawResource() {
        val entries = ChangelogProvider.loadFromContext(context)
        assertEquals(listOf("0.0.5", "0.0.4", "0.0.3", "0.0.2", "0.0.1"), entries.map { it.version })
        assertTrue(entries.all { it.changes.isNotEmpty() })
    }

    @Test
    fun helpScreenShowsLatestAndExpandsFullVersionHistory() {
        composeRule.setContent {
            MyApplicationTheme {
                HelpScreen(onBack = {})
            }
        }

        composeRule.onNodeWithTag("whats_new_latest_card").assertIsDisplayed()
        composeRule.onNodeWithText("Что нового в версии 0.0.5").assertIsDisplayed()
        composeRule.onNodeWithTag("changelog_history_list").assertDoesNotExist()

        composeRule.onNodeWithTag("help_lazy_list").performScrollToNode(hasTestTag("changelog_history_card"))
        composeRule.onNodeWithTag("changelog_history_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("help_lazy_list").performScrollToNode(hasText("Версия 0.0.4", substring = true))
        composeRule.onNodeWithText("Версия 0.0.4", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("help_lazy_list").performScrollToNode(hasText("Версия 0.0.1", substring = true))
        composeRule.onNodeWithText("Версия 0.0.1", substring = true).assertIsDisplayed()
    }

    // =========================================================================
    // 7. Duplicate RSS Source Detection & URL Normalization
    // =========================================================================

    @Test
    fun urlNormalizerHandlesSpacesTrailingSlashDomainCasePortsQueriesPathsAndSchemes() {
        // Spaces at start and end
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed"),
            UrlNormalizer.normalizeForComparison("   https://example.org/feed   ")
        )

        // Trailing slashes (path and root)
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed"),
            UrlNormalizer.normalizeForComparison("https://example.org/feed/")
        )
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org"),
            UrlNormalizer.normalizeForComparison("https://example.org/")
        )

        // Different domain case
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/Feed"),
            UrlNormalizer.normalizeForComparison("https://EXAMPLE.ORG/Feed")
        )

        // Default ports :80 and :443 vs custom port :8080
        assertEquals(
            UrlNormalizer.normalizeForComparison("http://example.org/feed"),
            UrlNormalizer.normalizeForComparison("http://example.org:80/feed")
        )
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed"),
            UrlNormalizer.normalizeForComparison("https://example.org:443/feed")
        )
        assertNotEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed"),
            UrlNormalizer.normalizeForComparison("https://example.org:8443/feed")
        )

        // Different query params stay distinct; same query with/without trailing slash matches
        assertNotEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed?cat=1"),
            UrlNormalizer.normalizeForComparison("https://example.org/feed?cat=2")
        )
        assertEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/feed/?cat=1"),
            UrlNormalizer.normalizeForComparison("https://example.org/feed?cat=1")
        )

        // Different paths on same domain stay distinct
        assertNotEquals(
            UrlNormalizer.normalizeForComparison("https://example.org/news/rss.xml"),
            UrlNormalizer.normalizeForComparison("https://example.org/blog/rss.xml")
        )

        // http vs https stay distinct
        assertNotEquals(
            UrlNormalizer.normalizeForComparison("http://example.org/feed"),
            UrlNormalizer.normalizeForComparison("https://example.org/feed")
        )
    }

    @Test
    fun distinctFeedsOnSameSiteDoNotBlockEachOtherAsDuplicates() = runTest {
        val rssXml = """<?xml version="1.0"?><rss version="2.0"><channel><title>Feed</title></channel></rss>"""
        val fetcher = createFetcher { Triple(200, "application/rss+xml", rssXml) }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        assertTrue(repo.addSource("Site News", "https://site.example.org/news/rss.xml").isSuccess)
        assertTrue(repo.addSource("Site Blog", "https://site.example.org/blog/rss.xml").isSuccess)
        assertTrue(repo.addSource("Site Cat 1", "https://site.example.org/rss.xml?cat=1").isSuccess)
        assertTrue(repo.addSource("Site Cat 2", "https://site.example.org/rss.xml?cat=2").isSuccess)
        assertTrue(repo.addSource("Site HTTP", "http://site.example.org/news/rss.xml").isSuccess)

        // Exact normalized duplicate of one of them is still blocked
        val dup = repo.addSource("Duplicate News", "  HTTPS://SITE.EXAMPLE.ORG:443/news/rss.xml/  ")
        assertTrue(dup.isFailure)
        assertTrue(dup.exceptionOrNull()?.message?.contains("Этот источник уже добавлен («Site News»)") == true)
        assertEquals(5, database.sourceDao().getAllSourcesSync().size)
    }

    @Test
    fun addSourcePreventsNormalizedDuplicatesAndConcurrentDoubleSubmissions() = runTest {
        val rssXml = """<?xml version="1.0"?><rss version="2.0"><channel><title>Feed</title></channel></rss>"""
        val fetcher = createFetcher { Triple(200, "application/rss+xml", rssXml) }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        val first = repo.addSource("My Feed", "https://Example.com/rss/")
        assertTrue(first.isSuccess)

        val dupTrailing = repo.addSource("Another Name", "https://example.com/rss")
        assertTrue(dupTrailing.isFailure)
        assertTrue(dupTrailing.exceptionOrNull()?.message?.contains("Этот источник уже добавлен («My Feed»)") == true)

        val dupSpacesPort = repo.addSource("Third", "  HTTPS://example.com:443/rss/  ")
        assertTrue(dupSpacesPort.isFailure)

        // Rapid concurrent calls for the same new URL -> at most 1 succeeds
        val concurrent = listOf(
            async { repo.addSource("Concurrent", "https://concurrent.test/feed/") },
            async { repo.addSource("Concurrent", "https://concurrent.test/feed") }
        ).awaitAll()
        assertEquals(1, concurrent.count { it.isSuccess })
        assertEquals(1, concurrent.count { it.isFailure })
        assertEquals(2, database.sourceDao().getAllSourcesSync().size)
    }

    @Test
    fun addSourceDialogShowsClearErrorOnDuplicateUrl() {
        val existing = listOf(
            NewsSource(1L, "Dolphin Emulator", "https://dolphin-emu.org/blog/feeds/", isEnabled = true, sortOrder = 0)
        )
        var addCalls = 0

        composeRule.setContent {
            MyApplicationTheme {
                AddSourceDialog(
                    onDismiss = {},
                    onAdd = { _, _ -> addCalls++ },
                    existingSources = existing
                )
            }
        }

        composeRule.onNodeWithTag("source_url_input").performTextInput("HTTPS://dolphin-emu.org/blog/feeds")
        composeRule.onNodeWithTag("submit_add_source_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Этот источник уже добавлен («Dolphin Emulator»)").assertIsDisplayed()
        assertEquals(0, addCalls)
    }
}
