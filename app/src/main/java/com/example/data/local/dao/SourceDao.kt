package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, title ASC, id ASC")
    fun getAllSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, title ASC, id ASC")
    suspend fun getAllSourcesSync(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE isEnabled = 1 ORDER BY sortOrder ASC, title ASC, id ASC")
    fun getEnabledSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE isEnabled = 1 ORDER BY sortOrder ASC, title ASC, id ASC")
    suspend fun getEnabledSourcesSync(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getSourceById(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE url = :url LIMIT 1")
    suspend fun getSourceByUrl(url: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: SourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<SourceEntity>)

    @Update
    suspend fun updateSource(source: SourceEntity)

    @Query("UPDATE sources SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setSourceEnabled(id: Long, isEnabled: Boolean)

    @Delete
    suspend fun deleteSource(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSourceById(id: Long)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sources")
    suspend fun getMaxSortOrder(): Int

    @Query("UPDATE sources SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun updateSortOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateSortOrder(id, index)
        }
    }
}
