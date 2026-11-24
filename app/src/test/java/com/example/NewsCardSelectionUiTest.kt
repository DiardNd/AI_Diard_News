package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.domain.model.NewsArticle
import com.example.domain.model.TagItem
import com.example.presentation.components.NewsCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewsCardSelectionUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newsCardShowsTranslatedPreviewAndPreservesClickWithSelectionContainer() {
        var clickCount = 0
        val article = NewsArticle(
            id = 42L,
            sourceId = 1L,
            sourceTitle = "Dolphin Blog",
            guid = "guid-42",
            title = "Original English Title",
            summary = "Original English Summary",
            content = "Full content",
            link = "https://example.com/42",
            imageUrl = null,
            pubDate = System.currentTimeMillis(),
            tags = listOf(TagItem(1L, "Dolphin", "#0ABDE3")),
            titleRu = "Переведённый заголовок новости",
            summaryRu = "Переведённое краткое описание статьи"
        )

        composeRule.setContent {
            MyApplicationTheme {
                NewsCard(
                    article = article,
                    onClick = { clickCount++ }
                )
            }
        }

        // Translated preview strings should be displayed
        composeRule.onNodeWithText("Переведённый заголовок новости").assertIsDisplayed()
        composeRule.onNodeWithText("Переведённое краткое описание статьи").assertIsDisplayed()

        // Tapping directly on the card works
        composeRule.onNodeWithTag("news_card_42").performClick()
        assertEquals(1, clickCount)

        // Tapping directly on selectable title text still opens the card
        composeRule.onNodeWithText("Переведённый заголовок новости").performClick()
        assertEquals(2, clickCount)
    }
}
