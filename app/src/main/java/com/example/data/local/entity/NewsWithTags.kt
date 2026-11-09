package com.example.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class NewsWithTags(
    @Embedded
    val news: NewsEntity,

    @Relation(
        parentColumn = "sourceId",
        entityColumn = "id"
    )
    val source: SourceEntity?,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = NewsTagCrossRef::class,
            parentColumn = "newsId",
            entityColumn = "tagId"
        )
    )
    val tags: List<TagEntity> = emptyList()
)
