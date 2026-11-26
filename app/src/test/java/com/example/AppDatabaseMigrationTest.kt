package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.NewsEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration_test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate2To3PreservesExistingDataAndAddsTranslationColumns() = runTest {
        // 1. Create actual v2 SQLite database with legacy schema and populate sample rows
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `sources` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL,
                            `url` TEXT NOT NULL,
                            `isEnabled` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sources_url` ON `sources` (`url`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `news` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `sourceId` INTEGER NOT NULL,
                            `guid` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `summary` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `link` TEXT NOT NULL,
                            `imageUrl` TEXT,
                            `pubDate` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_news_guid` ON `news` (`guid`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_sourceId` ON `news` (`sourceId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_pubDate` ON `news` (`pubDate`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `tags` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `colorHex` TEXT NOT NULL,
                            `keywords` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `news_tags` (
                            `newsId` INTEGER NOT NULL,
                            `tagId` INTEGER NOT NULL,
                            PRIMARY KEY(`newsId`, `tagId`),
                            FOREIGN KEY(`newsId`) REFERENCES `news`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                            FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_tags_newsId` ON `news_tags` (`newsId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_tags_tagId` ON `news_tags` (`tagId`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        v2Helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO sources (id, title, url, isEnabled, createdAt) VALUES (1, 'Dolphin Blog', 'https://dolphin-emu.org/blog/feeds/', 1, 1000)")
            db.execSQL("INSERT INTO tags (id, name, colorHex, keywords) VALUES (10, 'Dolphin', '#0ABDE3', 'dolphin, wii')")
            db.execSQL(
                "INSERT INTO news (id, sourceId, guid, title, summary, content, link, imageUrl, pubDate, createdAt) " +
                    "VALUES (100, 1, 'guid-legacy-1', 'Dolphin Progress Report', 'Monthly update summary', 'Full body text', 'https://dolphin-emu.org/1', 'https://img.test/1.png', 2000, 3000)"
            )
            db.execSQL("INSERT INTO news_tags (newsId, tagId) VALUES (100, 10)")
        }
        v2Helper.close()

        // 2. Open with Room v4 using MIGRATION_2_3 + MIGRATION_3_4 (WITHOUT fallbackToDestructiveMigration)
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            val sources = migratedDb.sourceDao().getAllSourcesSync()
            assertEquals(1, sources.size)
            assertEquals("Dolphin Blog", sources[0].title)

            val tags = migratedDb.tagDao().getAllTagsSync()
            assertEquals(1, tags.size)
            assertEquals("Dolphin", tags[0].name)

            val newsWithTags = migratedDb.newsDao().getAllNewsWithTagsSync()
            assertEquals(1, newsWithTags.size)
            val legacyNews = newsWithTags[0].news
            assertEquals("guid-legacy-1", legacyNews.guid)
            assertEquals("Dolphin Progress Report", legacyNews.title)
            assertEquals("Monthly update summary", legacyNews.summary)
            assertNull(legacyNews.titleRu)
            assertNull(legacyNews.summaryRu)
            assertEquals(1, newsWithTags[0].tags.size)
            assertEquals("Dolphin", newsWithTags[0].tags[0].name)

            // Verify new columns can be written and read cleanly after migration
            migratedDb.newsDao().updateNewsPreviewTranslation(
                newsId = 100,
                titleRu = "Отчёт о прогрессе Dolphin",
                summaryRu = "Краткий ежемесячный отчёт"
            )
            val updated = migratedDb.newsDao().getNewsByGuid("guid-legacy-1")!!
            assertEquals("Отчёт о прогрессе Dolphin", updated.titleRu)
            assertEquals("Краткий ежемесячный отчёт", updated.summaryRu)

            val newId = migratedDb.newsDao().insertNews(
                NewsEntity(
                    sourceId = 1,
                    guid = "guid-v3-2",
                    title = "New Release",
                    summary = "New Summary",
                    content = "Body",
                    link = "https://dolphin-emu.org/2",
                    pubDate = 5000,
                    titleRu = "Новый релиз",
                    summaryRu = "Новое описание"
                )
            )
            assertEquals(true, newId > 0)
            assertEquals(2, migratedDb.newsDao().count())
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun migrate3To4PreservesSourcesAndInitializesDeterministicSortOrder() = runTest {
        val v3DbName = "migration_v3_v4_test.db"
        context.deleteDatabase(v3DbName)

        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(v3DbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `sources` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL,
                            `url` TEXT NOT NULL,
                            `isEnabled` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sources_url` ON `sources` (`url`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `news` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `sourceId` INTEGER NOT NULL,
                            `guid` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `summary` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `link` TEXT NOT NULL,
                            `imageUrl` TEXT,
                            `pubDate` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `titleRu` TEXT,
                            `summaryRu` TEXT,
                            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_news_guid` ON `news` (`guid`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_sourceId` ON `news` (`sourceId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_pubDate` ON `news` (`pubDate`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `tags` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `colorHex` TEXT NOT NULL,
                            `keywords` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `news_tags` (
                            `newsId` INTEGER NOT NULL,
                            `tagId` INTEGER NOT NULL,
                            PRIMARY KEY(`newsId`, `tagId`),
                            FOREIGN KEY(`newsId`) REFERENCES `news`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                            FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_tags_newsId` ON `news_tags` (`newsId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_news_tags_tagId` ON `news_tags` (`tagId`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val v3Helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        v3Helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO sources (id, title, url, isEnabled, createdAt) VALUES (1, 'Zeta Feed', 'https://zeta.test/feed', 1, 100)")
            db.execSQL("INSERT INTO sources (id, title, url, isEnabled, createdAt) VALUES (2, 'Alpha Feed', 'https://alpha.test/feed', 1, 200)")
            db.execSQL("INSERT INTO sources (id, title, url, isEnabled, createdAt) VALUES (3, 'Middle Feed', 'https://middle.test/feed', 0, 300)")
        }
        v3Helper.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, v3DbName)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            val ordered = migratedDb.sourceDao().getAllSourcesSync()
            assertEquals(listOf("Alpha Feed", "Middle Feed", "Zeta Feed"), ordered.map { it.title })
            assertEquals(listOf(0, 1, 2), ordered.map { it.sortOrder })
        } finally {
            migratedDb.close()
            context.deleteDatabase(v3DbName)
        }
    }
}
