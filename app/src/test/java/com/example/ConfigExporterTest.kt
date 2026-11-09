package com.example

import com.example.data.config.ConfigExporter
import com.example.data.config.ExportedArticleTags
import com.example.data.config.ExportedConfig
import com.example.data.config.ExportedSource
import com.example.data.config.ExportedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigExporterTest {

    @Test
    fun testExportAndValidateJson() {
        val originalConfig = ExportedConfig(
            version = 1,
            app = "DiardNews",
            exportedAt = 123456789L,
            sources = listOf(
                ExportedSource("RetroArch News", "https://www.libretro.com/feed", true),
                ExportedSource("Winlator Releases", "https://github.com/winlator/releases.atom", false)
            ),
            tags = listOf(
                ExportedTag("RetroArch", "#E91E63", "retroarch, libretro"),
                ExportedTag("Winlator", "#9C27B0", "winlator, wine")
            ),
            selectedTagNames = listOf("RetroArch"),
            articleTags = listOf(
                ExportedArticleTags("guid-12345", listOf("RetroArch"))
            )
        )

        val json = ConfigExporter.serializeToJson(originalConfig)
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("\"name\": \"RetroArch\""))
        assertTrue(json.contains("libretro.com"))

        val parsed = ConfigExporter.deserializeFromJson(json).getOrThrow()
        assertEquals(1, parsed.version)
        assertEquals(2, parsed.sources.size)
        assertEquals(2, parsed.tags.size)
        assertEquals(1, parsed.selectedTagNames.size)
        assertEquals("RetroArch", parsed.selectedTagNames[0])
        assertEquals(1, parsed.articleTags.size)
        assertEquals("guid-12345", parsed.articleTags[0].guid)
        assertEquals("RetroArch", parsed.articleTags[0].tagNames[0])
    }

    @Test
    fun testRejectInvalidJson() {
        val invalidResult = ConfigExporter.deserializeFromJson("not a valid json")
        assertTrue(invalidResult.isFailure)
    }

    @Test
    fun testRejectEmptyJson() {
        val emptyResult = ConfigExporter.deserializeFromJson("   ")
        assertTrue(emptyResult.isFailure)
    }

    @Test
    fun testRejectMissingSections() {
        val wrongSchema = """
            {
                "randomField": 123
            }
        """.trimIndent()
        val result = ConfigExporter.deserializeFromJson(wrongSchema)
        assertTrue(result.isFailure)
    }
}
