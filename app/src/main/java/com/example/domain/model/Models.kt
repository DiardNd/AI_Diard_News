package com.example.domain.model

import java.util.Locale

enum class SourceSortMode(val label: String, val shortLabel: String) {
    MANUAL("Ручная сортировка", "Ручная"),
    TITLE_ASC("По названию (А–Я)", "А–Я"),
    ENABLED_FIRST("Сначала включённые", "Включённые"),
    CREATED_DESC("По дате добавления (новые)", "Новые")
}

enum class SourceHealthStatus {
    OK,
    NO_NEW_ARTICLES,
    EMPTY_FEED,
    STALE_FEED,
    DB_SAVE_ERROR,
    NETWORK_OR_HTTP_ERROR
}

data class SourceStatusInfo(
    val sourceId: Long,
    val status: SourceHealthStatus,
    val message: String,
    val latestPubDate: Long? = null,
    val fetchedCount: Int = 0,
    val newCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

fun sortSources(sources: List<NewsSource>, mode: SourceSortMode): List<NewsSource> {
    return when (mode) {
        SourceSortMode.MANUAL -> sources.sortedWith(
            compareBy<NewsSource> { it.sortOrder }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
        SourceSortMode.TITLE_ASC -> sources.sortedWith(
            compareBy<NewsSource> { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
        SourceSortMode.ENABLED_FIRST -> sources.sortedWith(
            compareByDescending<NewsSource> { it.isEnabled }
                .thenBy { it.sortOrder }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
        SourceSortMode.CREATED_DESC -> sources.sortedWith(
            compareByDescending<NewsSource> { it.createdAt }
                .thenByDescending { it.id }
        )
    }
}

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
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
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
    val tags: List<TagItem> = emptyList(),
    val titleRu: String? = null,
    val summaryRu: String? = null
)
