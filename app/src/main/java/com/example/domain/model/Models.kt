package com.example.domain.model

data class TagItem(
    val id: Long,
    val name: String,
    val colorHex: String,
    val keywords: String = ""
)

data class NewsSource(
    val id: Long,
    val title: String,
    val url: String,
    val isEnabled: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

data class NewsArticle(
    val id: Long,
    val sourceId: Long,
    val sourceTitle: String,
    val guid: String,
    val title: String,
    val summary: String,
    val content: String,
    val link: String,
    val imageUrl: String?,
    val pubDate: Long,
    val tags: List<TagItem> = emptyList()
)
