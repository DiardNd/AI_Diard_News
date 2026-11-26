package com.example.data.remote

data class ParsedFeedItem(
    val guid: String,
    val title: String,
    val link: String,
    val description: String,
    val content: String,
    val imageUrl: String? = null,
    val pubDate: Long = System.currentTimeMillis(),
    val rawPubDate: String? = null,
    val parsedPubDate: Long? = null
)

data class ParsedFeed(
    val title: String?,
    val description: String?,
    val items: List<ParsedFeedItem>,
    val rootTag: String? = null
)
