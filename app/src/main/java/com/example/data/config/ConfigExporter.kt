package com.example.data.config

import org.json.JSONArray
import org.json.JSONObject

data class ExportedSource(
    val title: String,
    val url: String,
    val isEnabled: Boolean = true
)

data class ExportedTag(
    val name: String,
    val colorHex: String = "#00D2D3",
    val keywords: String = ""
)

data class ExportedArticleTags(
    val guid: String,
    val tagNames: List<String>
)

data class ExportedConfig(
    val version: Int = 1,
    val app: String = "DiardNews",
    val exportedAt: Long = System.currentTimeMillis(),
    val sources: List<ExportedSource>,
    val tags: List<ExportedTag>,
    val selectedTagNames: List<String>,
    val articleTags: List<ExportedArticleTags>
)

data class ImportResult(
    val sourcesCount: Int,
    val tagsCount: Int,
    val articleTagsCount: Int,
    val restoredSelectedTags: List<String>
)

object ConfigExporter {

    fun serializeToJson(config: ExportedConfig): String {
        val root = JSONObject()
        root.put("version", config.version)
        root.put("app", config.app)
        root.put("exportedAt", config.exportedAt)

        val sourcesArray = JSONArray()
        for (source in config.sources) {
            val obj = JSONObject()
            obj.put("title", source.title)
            obj.put("url", source.url)
            obj.put("isEnabled", source.isEnabled)
            sourcesArray.put(obj)
        }
        root.put("sources", sourcesArray)

        val tagsArray = JSONArray()
        for (tag in config.tags) {
            val obj = JSONObject()
            obj.put("name", tag.name)
            obj.put("colorHex", tag.colorHex)
            obj.put("keywords", tag.keywords)
            tagsArray.put(obj)
        }
        root.put("tags", tagsArray)

        val selectedTagsArray = JSONArray()
        for (name in config.selectedTagNames) {
            selectedTagsArray.put(name)
        }
        root.put("selectedTagNames", selectedTagsArray)

        val articleTagsArray = JSONArray()
        for (at in config.articleTags) {
            val obj = JSONObject()
            obj.put("guid", at.guid)
            val names = JSONArray()
            for (t in at.tagNames) {
                names.put(t)
            }
            obj.put("tagNames", names)
            articleTagsArray.put(obj)
        }
        root.put("articleTags", articleTagsArray)

        return root.toString(2)
    }

    fun deserializeFromJson(jsonString: String): Result<ExportedConfig> {
        if (jsonString.isBlank()) {
            return Result.failure(IllegalArgumentException("Файл конфигурации пуст"))
        }

        return try {
            val root = JSONObject(jsonString)

            if (!root.has("sources") && !root.has("tags")) {
                return Result.failure(
                    IllegalArgumentException("Неверная схема: файл должен содержать список источников ('sources') или тегов ('tags')")
                )
            }

            val version = root.optInt("version", 1)
            val app = root.optString("app", "DiardNews")
            val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())

            val sources = mutableListOf<ExportedSource>()
            if (root.has("sources")) {
                val array = root.getJSONArray("sources")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.optString("url", "").trim()
                    if (url.isNotBlank()) {
                        val title = obj.optString("title", "").ifBlank { "RSS Источник" }
                        val isEnabled = obj.optBoolean("isEnabled", true)
                        sources.add(ExportedSource(title = title, url = url, isEnabled = isEnabled))
                    }
                }
            }

            val tags = mutableListOf<ExportedTag>()
            if (root.has("tags")) {
                val array = root.getJSONArray("tags")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "").trim()
                    if (name.isNotBlank()) {
                        val colorHex = obj.optString("colorHex", "#00D2D3")
                        val keywords = obj.optString("keywords", "")
                        tags.add(ExportedTag(name = name, colorHex = colorHex, keywords = keywords))
                    }
                }
            }

            val selectedTagNames = mutableListOf<String>()
            if (root.has("selectedTagNames")) {
                val array = root.getJSONArray("selectedTagNames")
                for (i in 0 until array.length()) {
                    val name = array.optString(i, "").trim()
                    if (name.isNotBlank()) {
                        selectedTagNames.add(name)
                    }
                }
            }

            val articleTags = mutableListOf<ExportedArticleTags>()
            if (root.has("articleTags")) {
                val array = root.getJSONArray("articleTags")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val guid = obj.optString("guid", "").trim()
                    if (guid.isNotBlank() && obj.has("tagNames")) {
                        val namesArray = obj.getJSONArray("tagNames")
                        val names = mutableListOf<String>()
                        for (j in 0 until namesArray.length()) {
                            val tName = namesArray.optString(j, "").trim()
                            if (tName.isNotBlank()) {
                                names.add(tName)
                            }
                        }
                        articleTags.add(ExportedArticleTags(guid = guid, tagNames = names))
                    }
                }
            }

            Result.success(
                ExportedConfig(
                    version = version,
                    app = app,
                    exportedAt = exportedAt,
                    sources = sources,
                    tags = tags,
                    selectedTagNames = selectedTagNames,
                    articleTags = articleTags
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Ошибка разбора JSON: ${e.message}", e))
        }
    }
}
