package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.NewsDao
import com.example.data.local.dao.SourceDao
import com.example.data.local.dao.TagDao
import com.example.data.local.entity.NewsEntity
import com.example.data.local.entity.NewsTagCrossRef
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.TagEntity

@Database(
    entities = [
        SourceEntity::class,
        NewsEntity::class,
        TagEntity::class,
        NewsTagCrossRef::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun tagDao(): TagDao
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "emu_news.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
