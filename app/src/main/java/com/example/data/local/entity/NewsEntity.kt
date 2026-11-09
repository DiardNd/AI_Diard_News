package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "news",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["sourceId"]),
        Index(value = ["pubDate"])
    ]
)
data class NewsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceId: Long,
    val guid: String,
    val title: String,
    val summary: String,
    val content: String,
    val link: String,
    val imageUrl: String? = null,
    val pubDate: Long,
    val createdAt: Long = System.currentTimeMillis()
)
