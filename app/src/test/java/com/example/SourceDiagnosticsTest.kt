package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.SourceEntity
import com.example.data.remote.FeedFetcher
import com.example.data.remote.RssParser
import com.example.data.repository.NewsRepository
import com.example.domain.model.DiagnosticStep
import com.example.domain.model.DiagnosticStepStatus
import com.example.domain.model.FeedFormat
import com.example.domain.model.NewsSource
import com.example.domain.model.SourceDiagnosticReport
import com.example.presentation.screens.SourcesScreen
import com.example.presentation.viewmodel.SourceDiagnosticsUiState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceDiagnosticsTest {

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
            if (code == -2) {
                throw SocketTimeoutException("Read timed out for $url")
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

    @Test
    fun diagnoseHealthyCustomRssFeedReportsActualDateCountsAndDoesNotMutateDatabase() = runTest {
        val sourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Custom Blog", url = "https://custom.example.org/rss.xml", isEnabled = true)
        )
        // One article already saved in Room, one new incoming in feed
        val existingPubDate = RssParser.parseDateOrNull("Mon, 09 Nov 2026 10:00:00 +0000")!!
        val newestPubDate = RssParser.parseDateOrNull("Tue, 10 Nov 2026 15:30:00 +0000")!!
        database.newsDao().insertNews(
            NewsEntity(
                sourceId = sourceId,
                guid = "post-1",
                title = "Existing Post",
                summary = "Summary 1",
                content = "Content 1",
                link = "https://custom.example.org/post-1",
                pubDate = existingPubDate
            )
        )

        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Custom Blog Feed</title>
                    <item>
                        <title>Older Post</title>
                        <link>https://custom.example.org/post-1</link>
                        <guid>post-1</guid>
                        <description>Old item</description>
                        <pubDate>Mon, 09 Nov 2026 10:00:00 +0000</pubDate>
                    </item>
                    <item>
                        <title>Brand New Post</title>
                        <link>https://custom.example.org/post-2</link>
                        <guid>post-2</guid>
                        <description>New item</description>
                        <pubDate>Tue, 10 Nov 2026 15:30:00 +0000</pubDate>
                    </item>
                </channel>
            </rss>
        """.trimIndent()

        val fetcher = createFetcher { Triple(200, "application/rss+xml; charset=utf-8", rssXml) }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        val beforeCount = database.newsDao().count()
        val beforeRefreshAt = preferencesRepository.lastRefreshAt.first()

        val result = repo.diagnoseSource(sourceId)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()

        assertEquals(DiagnosticStepStatus.SUCCESS, report.overallStatus)
        assertNull(report.failedStageTitle)
        assertEquals(200, report.httpCode)
        assertEquals(FeedFormat.RSS_2_0, report.feedFormat)
        assertEquals(2, report.fetchedItemsCount)
        assertEquals(2, report.processableItemsCount)
        assertEquals(newestPubDate, report.latestFeedPubDate)
        assertEquals("Tue, 10 Nov 2026 15:30:00 +0000", report.latestFeedRawPubDate)
        assertEquals(1, report.storedInDbCount)
        assertEquals(1, report.alreadyInDbSameSourceCount)
        assertEquals(1, report.wouldInsertNewCount)
        assertEquals(0, report.guidCollisionOtherSourceCount)
        assertEquals(1, report.visibleInFeedCount)
        assertEquals(6, report.steps.size)
        assertTrue(report.steps.all { it.status == DiagnosticStepStatus.SUCCESS })

        // Verify diagnostics did NOT modify Room or DataStore
        assertEquals(beforeCount, database.newsDao().count())
        assertEquals(beforeRefreshAt, preferencesRepository.lastRefreshAt.first())
    }

    @Test
    fun diagnoseAtomFeedWithUnrecognizedDateDoesNotUseFallbackTimestamp() = runTest {
        val sourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Atom Feed", url = "https://atom.example.org/feed.atom", isEnabled = true)
        )

        val atomXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
                <title>Custom Atom</title>
                <entry>
                    <id>entry-1</id>
                    <title>Entry with bad date</title>
                    <link rel="alternate" href="https://atom.example.org/1"/>
                    <summary>Atom summary</summary>
                    <updated>10/11/2026 invalid-date</updated>
                </entry>
            </feed>
        """.trimIndent()

        val fetcher = createFetcher { Triple(200, "application/atom+xml", atomXml) }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        val report = repo.diagnoseSource(sourceId).getOrThrow()

        assertEquals(FeedFormat.ATOM, report.feedFormat)
        assertEquals(1, report.fetchedItemsCount)
        assertNull("Unparseable XML date must remain null, not fallback to now", report.latestFeedPubDate)
        assertEquals("10/11/2026 invalid-date", report.latestFeedRawPubDate)
        assertEquals(0, report.itemsWithValidDatesCount)
        assertEquals(1, report.itemsWithoutValidDatesCount)

        val parsingStep = report.steps.first { it.id == "parsing" }
        assertEquals(DiagnosticStepStatus.WARNING, parsingStep.status)
        assertTrue(parsingStep.technicalDetails?.contains("10/11/2026 invalid-date") == true)
        assertEquals(0, database.newsDao().count())
    }

    @Test
    fun diagnoseIdentifiesHttpErrorStageAndHtmlResponseStageAndXmlErrorStageAndNetworkStage() = runTest {
        val httpErrId = database.sourceDao().insertSource(
            SourceEntity(title = "Forbidden Feed", url = "https://err403.example.org/feed", isEnabled = true)
        )
        val htmlErrId = database.sourceDao().insertSource(
            SourceEntity(title = "Html Page", url = "https://html.example.org/blog", isEnabled = true)
        )
        val xmlErrId = database.sourceDao().insertSource(
            SourceEntity(title = "Broken Xml", url = "https://broken.example.org/rss", isEnabled = true)
        )
        val netErrId = database.sourceDao().insertSource(
            SourceEntity(title = "Timeout Server", url = "https://timeout.example.org/rss", isEnabled = true)
        )

        val fetcher = createFetcher { url ->
            when {
                url.contains("err403") -> Triple(403, "text/html", "<html>Forbidden</html>")
                url.contains("html.") -> Triple(200, "text/html; charset=utf-8", "<!DOCTYPE html><html><body>Website</body></html>")
                url.contains("broken.") -> Triple(200, "application/xml", "<rss><channel><item><title>Unclosed</rss>")
                else -> Triple(-2, "text/plain", "")
            }
        }

        val repo = NewsRepository(database, preferencesRepository, fetcher)

        val httpReport = repo.diagnoseSource(httpErrId).getOrThrow()
        assertEquals(DiagnosticStepStatus.ERROR, httpReport.overallStatus)
        assertEquals("HTTP-ответ сервера", httpReport.failedStageTitle)
        assertEquals(403, httpReport.httpCode)
        assertEquals(DiagnosticStepStatus.SKIPPED, httpReport.steps.first { it.id == "parsing" }.status)

        val htmlReport = repo.diagnoseSource(htmlErrId).getOrThrow()
        assertEquals(DiagnosticStepStatus.ERROR, htmlReport.overallStatus)
        assertEquals("Тип полученного контента", htmlReport.failedStageTitle)
        assertEquals(DiagnosticStepStatus.ERROR, htmlReport.steps.first { it.id == "content_type" }.status)

        val xmlReport = repo.diagnoseSource(xmlErrId).getOrThrow()
        assertEquals(DiagnosticStepStatus.ERROR, xmlReport.overallStatus)
        assertEquals("Парсинг RSS/Atom", xmlReport.failedStageTitle)
        assertEquals(DiagnosticStepStatus.ERROR, xmlReport.steps.first { it.id == "parsing" }.status)

        val netReport = repo.diagnoseSource(netErrId).getOrThrow()
        assertEquals(DiagnosticStepStatus.ERROR, netReport.overallStatus)
        assertEquals("Доступность сервера", netReport.failedStageTitle)
        assertEquals(DiagnosticStepStatus.ERROR, netReport.steps.first { it.id == "network" }.status)
    }

    @Test
    fun diagnoseDetectsGuidCollisionDisabledSourceAndActiveFeedFiltersWithoutMutation() = runTest {
        val otherSourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Primary Feed", url = "https://primary.example.org/rss", isEnabled = true)
        )
        val collidingSourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Mirror Feed", url = "https://mirror.example.org/rss", isEnabled = true)
        )
        val hiddenSourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Hidden Feed", url = "https://hidden.example.org/rss", isEnabled = false)
        )

        database.newsDao().insertNews(
            NewsEntity(
                sourceId = otherSourceId,
                guid = "shared-guid-1",
                title = "Shared News",
                summary = "Summary",
                content = "Content",
                link = "https://primary.example.org/shared",
                pubDate = 1_700_000_000_000L
            )
        )
        database.newsDao().insertNews(
            NewsEntity(
                sourceId = hiddenSourceId,
                guid = "hidden-guid-1",
                title = "Hidden News",
                summary = "Summary",
                content = "Content",
                link = "https://hidden.example.org/1",
                pubDate = 1_700_000_000_000L
            )
        )

        val mirrorRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Mirror</title>
                    <item>
                        <title>Shared News</title>
                        <link>https://primary.example.org/shared</link>
                        <guid>shared-guid-1</guid>
                        <pubDate>Tue, 10 Nov 2026 12:00:00 +0000</pubDate>
                    </item>
                </channel>
            </rss>
        """.trimIndent()
        val hiddenRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Hidden</title>
                    <item>
                        <title>Hidden News</title>
                        <link>https://hidden.example.org/1</link>
                        <guid>hidden-guid-1</guid>
                        <pubDate>Tue, 10 Nov 2026 12:00:00 +0000</pubDate>
                    </item>
                </channel>
            </rss>
        """.trimIndent()

        val fetcher = createFetcher { url ->
            if (url.contains("mirror")) Triple(200, "application/rss+xml", mirrorRss)
            else Triple(200, "application/rss+xml", hiddenRss)
        }
        val repo = NewsRepository(database, preferencesRepository, fetcher)

        // 1. GUID collision with Primary Feed
        val collisionReport = repo.diagnoseSource(collidingSourceId).getOrThrow()
        assertEquals(1, collisionReport.guidCollisionOtherSourceCount)
        assertEquals(0, collisionReport.wouldInsertNewCount)
        val dbStep = collisionReport.steps.first { it.id == "database" }
        assertEquals(DiagnosticStepStatus.ERROR, dbStep.status)
        assertTrue(dbStep.technicalDetails?.contains("Primary Feed") == true)

        // 2. Disabled source hides stored articles from feed
        val disabledReport = repo.diagnoseSource(hiddenSourceId).getOrThrow()
        assertEquals(1, disabledReport.storedInDbCount)
        assertEquals(0, disabledReport.visibleInFeedCount)
        val displayStep = disabledReport.steps.first { it.id == "display" }
        assertEquals(DiagnosticStepStatus.WARNING, displayStep.status)
        assertTrue(displayStep.summary.contains("выключен"))

        // 3. Active filter selecting another source hides stored articles
        val filteredReport = repo.diagnoseSource(
            sourceId = otherSourceId,
            selectedSourceIds = setOf(collidingSourceId),
            searchQuery = ""
        ).getOrThrow()
        assertEquals(1, filteredReport.storedInDbCount)
        assertEquals(0, filteredReport.visibleInFeedCount)
        assertEquals(DiagnosticStepStatus.WARNING, filteredReport.steps.first { it.id == "display" }.status)
        assertTrue(
            filteredReport.steps.first { it.id == "display" }.summary.contains("фильтром")
        )
    }

    @Test
    fun sourceCardDiagnosticsButtonOpensDiagnosticsDialogWithReport() {
        val sources = listOf(
            NewsSource(
                id = 7L,
                title = "User Custom RSS",
                url = "https://userfeed.example.org/rss.xml",
                isEnabled = true,
                sortOrder = 0
            )
        )
        var diagnosedSourceId: Long? = null
        var diagnosticsState by mutableStateOf(SourceDiagnosticsUiState())

        val sampleReport = SourceDiagnosticReport(
            sourceId = 7L,
            sourceTitle = "User Custom RSS",
            sourceUrl = "https://userfeed.example.org/rss.xml",
            isSourceEnabled = true,
            checkedAt = 1_762_788_600_000L,
            overallStatus = DiagnosticStepStatus.SUCCESS,
            httpCode = 200,
            responseTimeMs = 145L,
            feedFormat = FeedFormat.RSS_2_0,
            fetchedItemsCount = 12,
            processableItemsCount = 12,
            latestFeedPubDate = 1_762_788_600_000L,
            storedInDbCount = 12,
            wouldInsertNewCount = 0,
            visibleInFeedCount = 12,
            steps = listOf(
                DiagnosticStep("network", "Доступность сервера", DiagnosticStepStatus.SUCCESS, "Сервер ответил за 145 мс."),
                DiagnosticStep("http", "HTTP-ответ сервера", DiagnosticStepStatus.SUCCESS, "HTTP 200 (OK)."),
                DiagnosticStep("content_type", "Тип полученного контента", DiagnosticStepStatus.SUCCESS, "Получен XML-контент."),
                DiagnosticStep("parsing", "Парсинг RSS/Atom", DiagnosticStepStatus.SUCCESS, "Формат RSS 2.0 успешно разобран, получено записей: 12."),
                DiagnosticStep("database", "Анализ сохранения в локальную БД", DiagnosticStepStatus.SUCCESS, "Конфликтов сохранения не найдено."),
                DiagnosticStep("display", "Отображение в ленте", DiagnosticStepStatus.SUCCESS, "В ленте сейчас отображается 12 из 12 сохранённых статей.")
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                SourcesScreen(
                    sources = sources,
                    onToggleSource = { _, _ -> },
                    onAddSource = { _, _, _ -> },
                    onDeleteSource = {},
                    onMoveSourceUp = {},
                    onMoveSourceDown = {},
                    onRestorePresets = {},
                    onExportToUri = {},
                    onImportFromUri = {},
                    onDiagnoseSource = { id ->
                        diagnosedSourceId = id
                        diagnosticsState = SourceDiagnosticsUiState(
                            isDialogVisible = true,
                            isLoading = false,
                            sourceId = id,
                            sourceTitle = "User Custom RSS",
                            sourceUrl = "https://userfeed.example.org/rss.xml",
                            report = sampleReport
                        )
                    },
                    diagnosticsState = diagnosticsState,
                    onDismissDiagnostics = {
                        diagnosticsState = SourceDiagnosticsUiState()
                    },
                    onRetryDiagnostics = {}
                )
            }
        }

        composeRule.onNodeWithTag("diagnose_source_button_7").performClick()
        composeRule.waitForIdle()

        assertEquals(7L, diagnosedSourceId)
        composeRule.onNodeWithTag("source_diagnostics_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_overall_card").assertIsDisplayed()
        composeRule.onNodeWithText("Источник полностью исправен").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_metrics_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_step_parsing").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_step_display").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("diagnostics_close_button").performClick()
        composeRule.waitForIdle()
        assertNotNull(diagnosticsState)
        assertEquals(false, diagnosticsState.isDialogVisible)
    }
}
