package com.example

import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.entity.SourceEntity
import com.example.data.repository.NewsRepository
import com.example.domain.model.NewsArticle
import com.example.domain.model.NewsSource
import com.example.presentation.screens.FeedScreen
import com.example.presentation.viewmodel.NewsFeedUiState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceSortingAndFeedScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manualSourceReorderingPersistsAcrossDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "source_order_persist_test.db"
        context.deleteDatabase(dbName)

        var db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val prefs = UserPreferencesRepository(context)
        val repo = NewsRepository(db, prefs)

        val idA = db.sourceDao().insertSource(SourceEntity(title = "Alpha", url = "https://a.test", sortOrder = 0))
        val idB = db.sourceDao().insertSource(SourceEntity(title = "Beta", url = "https://b.test", sortOrder = 1))
        val idC = db.sourceDao().insertSource(SourceEntity(title = "Gamma", url = "https://c.test", sortOrder = 2))

        // Move Gamma up twice so order becomes: Gamma, Alpha, Beta
        repo.moveSource(idC, moveUp = true)
        repo.moveSource(idC, moveUp = true)
        // Moving top item further up is a safe no-op
        repo.moveSource(idC, moveUp = true)
        // Moving bottom item down is a safe no-op
        repo.moveSource(idB, moveUp = false)

        val inMemoryOrder = db.sourceDao().getAllSourcesSync().map { it.title }
        assertEquals(listOf("Gamma", "Alpha", "Beta"), inMemoryOrder)
        db.close()

        // Reopen database cleanly (simulating app restart)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            val persistedOrder = db.sourceDao().getAllSourcesSync().map { it.title }
            assertEquals(listOf("Gamma", "Alpha", "Beta"), persistedOrder)
            val persistedEnabledOrder = db.sourceDao().getEnabledSourcesSync().map { it.id }
            assertEquals(listOf(idC, idA, idB), persistedEnabledOrder)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun feedScrollPositionIsPreservedAfterOpeningArticleAndReturning() {
        val sources = listOf(
            NewsSource(1L, "Source 1", "https://s1.test", isEnabled = true, sortOrder = 0)
        )
        val articles = (1L..30L).map { id ->
            NewsArticle(
                id = id,
                sourceId = 1L,
                sourceTitle = "Source 1",
                guid = "guid-$id",
                title = "Article Title #$id",
                summary = "Summary #$id",
                content = "Body #$id",
                link = "https://s1.test/$id",
                imageUrl = null,
                pubDate = 1_700_000_000_000L - id * 1000L
            )
        }

        var showDetail by mutableStateOf(false)
        var capturedFirstVisibleIndex = -1

        composeRule.setContent {
            MyApplicationTheme {
                val hoistedListState = rememberLazyListState()
                capturedFirstVisibleIndex = hoistedListState.firstVisibleItemIndex

                if (!showDetail) {
                    FeedScreen(
                        uiState = NewsFeedUiState(articles = articles, sources = sources),
                        onRefresh = {},
                        onArticleClick = { showDetail = true },
                        onOpenTags = {},
                        onOpenHelp = {},
                        onNavigateToSources = {},
                        onToggleSourceFilter = {},
                        onClearSourceFilters = {},
                        onSearchQueryChange = {},
                        onDismissNetworkError = {},
                        listState = hoistedListState
                    )
                }
            }
        }

        // Scroll feed down to item 12
        composeRule.onNodeWithTag("news_feed_list").performScrollToIndex(12)
        composeRule.waitForIdle()
        assertTrue("List should be scrolled past top", capturedFirstVisibleIndex >= 10)
        val savedIndexBeforeDetail = capturedFirstVisibleIndex

        // Open article #13 -> FeedScreen leaves composition, Detail opens
        composeRule.onNodeWithTag("news_card_13").performClick()
        composeRule.waitForIdle()

        // Return back to FeedScreen -> scroll position must stay preserved
        showDetail = false
        composeRule.waitForIdle()
        assertEquals(savedIndexBeforeDetail, capturedFirstVisibleIndex)
    }

    @Test
    fun repeatingTapOnActiveFilterChipScrollsFeedToTop() {
        val sources = listOf(
            NewsSource(1L, "Libretro", "https://lib.test", isEnabled = true, sortOrder = 0),
            NewsSource(2L, "Dolphin", "https://dol.test", isEnabled = true, sortOrder = 1)
        )
        val articles = (1L..25L).map { id ->
            NewsArticle(
                id = id,
                sourceId = 1L,
                sourceTitle = "Libretro",
                guid = "guid-$id",
                title = "Item #$id",
                summary = "Summary #$id",
                content = "Body #$id",
                link = "https://lib.test/$id",
                imageUrl = null,
                pubDate = 1_700_000_000_000L - id * 1000L
            )
        }

        var selectedSourceIds by mutableStateOf(emptySet<Long>())
        var visibleIndex = -1

        composeRule.setContent {
            MyApplicationTheme {
                val listState = rememberLazyListState()
                visibleIndex = listState.firstVisibleItemIndex

                FeedScreen(
                    uiState = NewsFeedUiState(
                        articles = articles,
                        sources = sources,
                        selectedSourceIds = selectedSourceIds
                    ),
                    onRefresh = {},
                    onArticleClick = {},
                    onOpenTags = {},
                    onOpenHelp = {},
                    onNavigateToSources = {},
                    onToggleSourceFilter = { id ->
                        selectedSourceIds = if (selectedSourceIds.contains(id)) {
                            selectedSourceIds - id
                        } else {
                            selectedSourceIds + id
                        }
                    },
                    onClearSourceFilters = { selectedSourceIds = emptySet() },
                    onSearchQueryChange = {},
                    onDismissNetworkError = {},
                    listState = listState
                )
            }
        }

        // 1. Active "Все" chip: scroll down, then tap "Все" again -> scrolls to top
        composeRule.onNodeWithTag("news_feed_list").performScrollToIndex(15)
        composeRule.waitForIdle()
        assertTrue(visibleIndex >= 10)

        composeRule.onNodeWithTag("source_filter_all").performClick()
        composeRule.waitForIdle()
        assertEquals(0, visibleIndex)
        composeRule.onNodeWithTag("source_filter_all").assertIsSelected()

        // 2. Select source 1 ("Libretro"), scroll down, tap active source 1 again -> scrolls to top & keeps selected
        composeRule.onNodeWithTag("source_filter_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("source_filter_1").assertIsSelected()

        composeRule.onNodeWithTag("news_feed_list").performScrollToIndex(14)
        composeRule.waitForIdle()
        assertTrue(visibleIndex >= 10)

        composeRule.onNodeWithTag("source_filter_1").performClick()
        composeRule.waitForIdle()
        assertEquals(0, visibleIndex)
        composeRule.onNodeWithTag("source_filter_1").assertIsSelected()
    }
}
