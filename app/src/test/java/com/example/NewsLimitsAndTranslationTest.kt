package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.SourceEntity
import com.example.data.remote.FeedFetcher
import com.example.data.remote.TranslationService
import com.example.data.repository.NewsRepository
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.domain.model.TagItem
import com.example.presentation.viewmodel.filterNewsArticles
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewsLimitsAndTranslationTest {

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
            val (code, body) = handler(req.url.toString())
            val mediaType = if (code == 200) "application/xml".toMediaType() else "text/html".toMediaType()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error $code")
                .body(body.toResponseBody(mediaType))
                .build()
        }
        return FeedFetcher(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    private fun rfc822(timestampMs: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestampMs))
    }

    private fun buildRssWithItems(count: Int, prefix: String, baseTimeMs: Long = 1_700_000_000_000L): String {
        val itemsXml = (1..count).joinToString("\n") { idx ->
            val pubTime = baseTimeMs + idx * 60_000L
            """
            <item>
                <title>$prefix Title $idx</title>
                <link>https://example.com/$prefix/$idx</link>
                <guid>$prefix-guid-$idx</guid>
                <pubDate>${rfc822(pubTime)}</pubDate>
                <description>$prefix summary $idx</description>
            </item>
            """.trimIndent()
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
    <channel>
        <title>$prefix Feed</title>
        $itemsXml
    </channel>
</rss>""".trimIndent()
    }

    @Test
    fun refreshProcessesAtMost30NewestItemsAndStoresAtMost50PerSourceIndependently() = runTest {
        val source1Id = database.sourceDao().insertSource(
            SourceEntity(title = "Source One", url = "https://s1.test/feed", isEnabled = true)
        )
        val source2Id = database.sourceDao().insertSource(
            SourceEntity(title = "Source Two", url = "https://s2.test/feed", isEnabled = true)
        )

        // Pre-seed 40 older articles for Source One
        for (i in 1..40) {
            database.newsDao().insertNews(
                NewsEntity(
                    sourceId = source1Id,
                    guid = "old-s1-$i",
                    title = "Old S1 $i",
                    summary = "Old summary $i",
                    content = "Old body $i",
                    link = "https://s1.test/old/$i",
                    pubDate = 1_600_000_000_000L + i * 1_000L
                )
            )
        }
        // Pre-seed 5 articles for Source Two (must NOT be evicted when Source One trims)
        for (i in 1..5) {
            database.newsDao().insertNews(
                NewsEntity(
                    sourceId = source2Id,
                    guid = "old-s2-$i",
                    title = "Old S2 $i",
                    summary = "Old s2 summary $i",
                    content = "Old s2 body $i",
                    link = "https://s2.test/old/$i",
                    pubDate = 1_500_000_000_000L + i * 1_000L
                )
            )
        }

        // Source One feed returns 45 items (items 1..45 where 45 is newest) -> only newest 30 (16..45) processed.
        // Combined 40 + 30 = 70 -> trimmed to 50 newest for Source One!
        // Source Two feed returns 10 items -> 5 + 10 = 15 for Source Two (all kept).
        val fetcher = createFetcher { url ->
            when {
                url.contains("s1.test") -> 200 to buildRssWithItems(45, "s1")
                url.contains("s2.test") -> 200 to buildRssWithItems(10, "s2")
                else -> 404 to "Not found"
            }
        }

        val repo = NewsRepository(database, preferencesRepository, fetcher)
        val refreshResult = repo.refreshAllFeeds()

        assertTrue(refreshResult.isSuccess)
        val resultData = refreshResult.getOrThrow()
        assertEquals(40, resultData.newArticlesCount) // 30 from s1 + 10 from s2

        assertEquals(50, database.newsDao().countForSource(source1Id))
        assertEquals(15, database.newsDao().countForSource(source2Id))

        // Verify newest 30 of Source One were inserted (s1-guid-45 present, s1-guid-1 absent)
        assertTrue(database.newsDao().getNewsByGuid("s1-guid-45") != null)
        assertTrue(database.newsDao().getNewsByGuid("s1-guid-16") != null)
        assertNull(database.newsDao().getNewsByGuid("s1-guid-15"))
    }

    @Test
    fun autoTranslatePreviewsCachesInRoomDoesNotRetranslateAndSurvivesFailures() = runTest {
        val sourceId = database.sourceDao().insertSource(
            SourceEntity(title = "Test News", url = "https://t.test/feed", isEnabled = true)
        )

        val id1 = database.newsDao().insertNews(
            NewsEntity(
                sourceId = sourceId,
                guid = "guid-1",
                title = "Dolphin Update Released",
                summary = "Improved Vulkan shader compilation.",
                content = "Full English article body remains untouched.",
                link = "https://t.test/1",
                pubDate = 3000
            )
        )
        val id2 = database.newsDao().insertNews(
            NewsEntity(
                sourceId = sourceId,
                guid = "guid-2",
                title = "Broken Translation Item",
                summary = "Should fail gracefully",
                content = "Body 2",
                link = "https://t.test/2",
                pubDate = 2000
            )
        )
        val id3 = database.newsDao().insertNews(
            NewsEntity(
                sourceId = sourceId,
                guid = "guid-3",
                title = "Already Translated",
                summary = "Original summary",
                content = "Body 3",
                link = "https://t.test/3",
                pubDate = 1000,
                titleRu = "Уже переведено",
                summaryRu = "Готовое описание"
            )
        )

        val translatedInputs = mutableListOf<String>()
        val fakeTranslator = object : TranslationService() {
            override suspend fun translateToRussian(text: String): Result<String> {
                translatedInputs.add(text)
                if (text.contains("Broken") || text.contains("fail")) {
                    return Result.failure(RuntimeException("HTTP 503"))
                }
                return Result.success("RU: $text")
            }
        }

        val repo = NewsRepository(
            database = database,
            userPreferencesRepository = preferencesRepository,
            translationService = fakeTranslator
        )

        // First run translates id1 and attempts id2 (fails without crashing), skips id3 completely
        val translatedCount = repo.translateUntranslatedPreviews(batchLimit = 20)
        assertEquals(1, translatedCount)
        assertEquals(4, translatedInputs.size) // id1 title+summary, id2 title+summary (id3 skipped)

        val updated1 = database.newsDao().getNewsByGuid("guid-1")!!
        assertEquals("RU: Dolphin Update Released", updated1.titleRu)
        assertEquals("RU: Improved Vulkan shader compilation.", updated1.summaryRu)
        // Originals must NOT be overwritten
        assertEquals("Dolphin Update Released", updated1.title)
        assertEquals("Improved Vulkan shader compilation.", updated1.summary)
        assertEquals("Full English article body remains untouched.", updated1.content)

        // id2 failed cleanly without overwriting originals or crashing
        val updated2 = database.newsDao().getNewsByGuid("guid-2")!!
        assertNull(updated2.titleRu)
        assertNull(updated2.summaryRu)

        // Verify id3 untouched
        val updated3 = database.newsDao().getNewsByGuid("guid-3")!!
        assertEquals("Уже переведено", updated3.titleRu)

        // Run a second time -> id1 and id3 are NOT re-translated
        translatedInputs.clear()
        repo.translateUntranslatedPreviews(batchLimit = 20)
        assertEquals(2, translatedInputs.size) // Only id2 retried; id1 and id3 skipped
        assertTrue(translatedInputs.none { it.contains("Dolphin") || it.contains("Already") })
    }

    @Test
    fun translationServiceSkipsAlreadyRussianText() = runTest {
        val service = TranslationService()
        assertTrue(service.isAlreadyRussian("Вышел новый релиз Winlator 9.0 для Android"))
        assertEquals(false, service.isAlreadyRussian("Dolphin Progress Report: October and November"))
    }

    @Test
    fun sourceBasedFilteringSupportsMultiSelectResetAndVisualOnlyTags() {
        val sources = listOf(
            NewsSource(id = 1, title = "Libretro", url = "https://libretro.test", isEnabled = true),
            NewsSource(id = 2, title = "Dolphin", url = "https://dolphin.test", isEnabled = true),
            NewsSource(id = 3, title = "RPCS3", url = "https://rpcs3.test", isEnabled = true),
            NewsSource(id = 4, title = "Disabled Source", url = "https://off.test", isEnabled = false)
        )
        val tagA = TagItem(id = 10, name = "RetroArch", colorHex = "#00D2D3")
        val tagB = TagItem(id = 20, name = "Dolphin", colorHex = "#0ABDE3")

        val articles = listOf(
            NewsArticle(1, 1, "Libretro", "g1", "Core Update", "Summary 1", "Body", "u1", null, 4000, listOf(tagA), titleRu = "Обновление ядер"),
            NewsArticle(2, 2, "Dolphin", "g2", "GameCube Fix", "Summary 2", "Body", "u2", null, 3000, listOf(tagB), titleRu = "Исправление GameCube"),
            NewsArticle(3, 3, "RPCS3", "g3", "PS3 Shaders", "Summary 3", "Body", "u3", null, 2000, emptyList()),
            NewsArticle(4, 4, "Disabled Source", "g4", "Hidden", "Hidden", "Body", "u4", null, 1000, emptyList())
        )

        // Empty selection ("Все"): shows all enabled sources (1, 2, 3), excludes disabled (4)
        val allVisible = filterNewsArticles(articles, sources, emptySet(), "")
        assertEquals(listOf(1L, 2L, 3L), allVisible.map { it.id })

        // Single source selected
        val dolphinOnly = filterNewsArticles(articles, sources, setOf(2L), "")
        assertEquals(listOf(2L), dolphinOnly.map { it.id })

        // Multi-select sources (Libretro + RPCS3)
        val multiSelected = filterNewsArticles(articles, sources, setOf(1L, 3L), "")
        assertEquals(listOf(1L, 3L), multiSelected.map { it.id })

        // Selecting a disabled source never exposes its articles
        val disabledSelected = filterNewsArticles(articles, sources, setOf(4L), "")
        assertEquals(listOf(1L, 2L, 3L), disabledSelected.map { it.id })

        // Search works across Russian translated title within selected sources
        val searchInRu = filterNewsArticles(articles, sources, setOf(1L, 2L), "обновление")
        assertEquals(listOf(1L), searchInRu.map { it.id })
    }
}
