package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "news_tags",
    primaryKeys = ["newsId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = NewsEntity::class,
            parentColumns = ["id"],
            childColumns = ["newsId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["newsId"]),
        Index(value = ["tagId"])
    ]
)
data class NewsTagCrossRef(
    val newsId: Long,
    val tagId: Long
)
