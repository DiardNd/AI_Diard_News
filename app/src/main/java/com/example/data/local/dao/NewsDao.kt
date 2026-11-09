package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.NewsTagCrossRef
import com.example.data.local.entity.NewsWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {
    @Transaction
    @Query("SELECT * FROM news ORDER BY pubDate DESC")
    fun getAllNewsWithTags(): Flow<List<NewsWithTags>>

    @Transaction
    @Query("SELECT * FROM news ORDER BY pubDate DESC")
    suspend fun getAllNewsWithTagsSync(): List<NewsWithTags>

    @Query("SELECT * FROM news ORDER BY pubDate DESC")
    suspend fun getAllNewsSync(): List<NewsEntity>

    @Transaction
    @Query("SELECT * FROM news WHERE id = :newsId")
    fun getNewsWithTagsById(newsId: Long): Flow<NewsWithTags?>

    @Query("SELECT * FROM news WHERE guid = :guid LIMIT 1")
    suspend fun getNewsByGuid(guid: String): NewsEntity?

    @Query("SELECT * FROM news WHERE link = :link LIMIT 1")
    suspend fun getNewsByLink(link: String): NewsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNews(news: NewsEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewsTagCrossRef(crossRef: NewsTagCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewsTagCrossRefs(crossRefs: List<NewsTagCrossRef>)

    @Query("DELETE FROM news_tags WHERE newsId = :newsId AND tagId = :tagId")
    suspend fun deleteNewsTagCrossRef(newsId: Long, tagId: Long)

    @Query("DELETE FROM news_tags WHERE newsId = :newsId")
    suspend fun clearTagsForNews(newsId: Long)

    @Delete
    suspend fun deleteNews(news: NewsEntity)

    @Query("DELETE FROM news WHERE id = :newsId")
    suspend fun deleteNewsById(newsId: Long)

    @Query("DELETE FROM news")
    suspend fun clearAllNews()

    @Query("SELECT COUNT(*) FROM news")
    suspend fun count(): Int
}
