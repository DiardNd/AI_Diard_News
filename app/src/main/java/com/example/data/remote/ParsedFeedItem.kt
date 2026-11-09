package com.example.data.remote

data class ParsedFeedItem(
    val guid: String,
    val title: String,
    val link: String,
    val description: String,
    val content: String,
    val imageUrl: String? = null,
    val pubDate: Long = System.currentTimeMillis()
)

data class ParsedFeed(
    val title: String?,
    val description: String?,
    val items: List<ParsedFeedItem>
)
