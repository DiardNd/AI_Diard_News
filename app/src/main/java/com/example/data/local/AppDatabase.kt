package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun tagDao(): TagDao
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE news ADD COLUMN titleRu TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN summaryRu TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE sources
                    SET sortOrder = (
                        SELECT COUNT(*)
                        FROM sources AS s2
                        WHERE s2.title < sources.title
                           OR (s2.title = sources.title AND s2.id < sources.id)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "emu_news.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
