package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.SourceEntity
import com.example.data.remote.FeedFetcher
import com.example.data.repository.NewsRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewsRepositoryRefreshTest {

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

    private fun createFetcher(handler: (String) -> Pair<Int, String>): FeedFetcher {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val (code, body) = handler(url)
            if (code == -1) {
                throw IOException("Simulated timeout for $url")
            }
            val mediaType = if (code == 200) "application/xml".toMediaType() else "text/html".toMediaType()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error $code")
                .body(body.toResponseBody(mediaType))
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return FeedFetcher(client)
    }

    private fun sampleRss(guid: String, title: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
            <channel>
                <title>Test Feed</title>
                <item>
                    <title>$title</title>
                    <link>https://example.com/$guid</link>
                    <guid>$guid</guid>
                    <description>RetroArch emulation update for $guid</description>
                </item>
            </channel>
        </rss>
    """.trimIndent()

    @Test
    fun partialFailureStillSavesSuccessfulSourcesAndReportsSuccess() = runTest {
        val sourceDao = database.sourceDao()
        sourceDao.insertSources(
            listOf(
                SourceEntity(title = "Working Source 1", url = "https://ok1.test/feed", isEnabled = true),
                SourceEntity(title = "Broken 404", url = "https://err404.test/feed", isEnabled = true),
                SourceEntity(title = "Working Source 2", url = "https://ok2.test/feed", isEnabled = true),
                SourceEntity(title = "Broken 403", url = "https://err403.test/feed", isEnabled = true)
            )
        )

        val fetcher = createFetcher { url ->
            when {
                url.contains("ok1.test") -> 200 to sampleRss("guid-1", "Article 1")
                url.contains("ok2.test") -> 200 to sampleRss("guid-2", "Article 2")
                url.contains("err404.test") -> 404 to "Not Found"
                url.contains("err403.test") -> 403 to "Forbidden"
                else -> 500 to "Error"
            }
        }

        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val result = repo.refreshAllFeeds()

        assertTrue("Refresh should succeed when at least one source works", result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(4, data.totalSourcesCount)
        assertEquals(2, data.successfulSourcesCount)
        assertEquals(2, data.newArticlesCount)
        assertEquals(2, data.failedSources.size)

        // Failed sources stored separately (SourceDao queries ORDER BY title ASC)
        val failed = repo.failedSources.value
        assertEquals(2, failed.size)
        assertEquals(listOf("Broken 403", "Broken 404"), failed.map { it.sourceTitle })
        assertTrue(failed[0].errorMessage.contains("403"))
        assertTrue(failed[1].errorMessage.contains("404"))

        // News from working sources saved to DB
        val savedNews = database.newsDao().getAllNewsSync()
        assertEquals(2, savedNews.size)
        assertTrue(preferencesRepository.lastRefreshAt.first() > 0L)
    }

    @Test
    fun totalFailureReturnsFailurePreservesExistingNewsAndRecordsAllFailedSources() = runTest {
        val sourceDao = database.sourceDao()
        sourceDao.insertSources(
            listOf(
                SourceEntity(title = "Working Source", url = "https://ok.test/feed", isEnabled = true),
                SourceEntity(title = "Failing Source", url = "https://fail.test/feed", isEnabled = true)
            )
        )

        var shouldFailAll = false
        val fetcher = createFetcher { url ->
            if (shouldFailAll) {
                if (url.contains("ok.test")) -1 to "" else 404 to "Not Found"
            } else {
                if (url.contains("ok.test")) 200 to sampleRss("guid-initial", "Saved Article")
                else 404 to "Not Found"
            }
        }

        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val firstResult = repo.refreshAllFeeds()
        assertTrue(firstResult.isSuccess)
        val initialRefreshTimestamp = preferencesRepository.lastRefreshAt.first()
        assertTrue(initialRefreshTimestamp > 0L)

        // Now simulate complete network outage / all sources failing
        shouldFailAll = true
        val secondResult = repo.refreshAllFeeds()

        assertTrue("Refresh must fail if zero sources succeeded", secondResult.isFailure)
        assertEquals(2, repo.failedSources.value.size)

        // Cached news must still be present
        val savedNews = database.newsDao().getAllNewsSync()
        assertEquals(1, savedNews.size)
        assertEquals("Saved Article", savedNews[0].title)

        // Timestamp should not advance on total failure
        assertEquals(initialRefreshTimestamp, preferencesRepository.lastRefreshAt.first())
    }

    @Test
    fun noEnabledSourcesReturnsSuccessAndClearsFailedSources() = runTest {
        val sourceDao = database.sourceDao()
        sourceDao.insertSource(
            SourceEntity(title = "Disabled Source", url = "https://disabled.test/feed", isEnabled = false)
        )

        val fetcher = createFetcher { 500 to "Should not be called" }
        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val result = repo.refreshAllFeeds()

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(0, data.totalSourcesCount)
        assertEquals(0, data.successfulSourcesCount)
        assertTrue(repo.failedSources.value.isEmpty())
    }
}
