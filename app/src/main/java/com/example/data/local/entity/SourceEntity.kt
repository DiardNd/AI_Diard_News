package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
