package com.example.data.config

import android.content.Context
import com.example.R

data class VersionChangelogEntry(
    val version: String,
    val title: String,
    val changes: List<String>
)

object ChangelogProvider {

    fun loadFromContext(context: Context): List<VersionChangelogEntry> {
        return try {
            val rawText = context.resources.openRawResource(R.raw.changelog)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            parse(rawText)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parse(rawText: String): List<VersionChangelogEntry> {
        val entries = mutableListOf<VersionChangelogEntry>()
        var currentVersion: String? = null
        var currentTitle = ""
        val currentChanges = mutableListOf<String>()

        fun flushCurrent() {
            val ver = currentVersion
            if (!ver.isNullOrBlank()) {
                entries.add(
                    VersionChangelogEntry(
                        version = ver,
                        title = currentTitle,
                        changes = currentChanges.toList()
                    )
                )
            }
            currentVersion = null
            currentTitle = ""
            currentChanges.clear()
        }

        for (rawLine in rawText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("## ")) {
                flushCurrent()
                val header = line.removePrefix("## ").trim()
                val parts = header.split("|", limit = 2)
                currentVersion = parts[0].trim()
                currentTitle = parts.getOrNull(1)?.trim().orEmpty()
            } else if (line.startsWith("- ") && currentVersion != null) {
                currentChanges.add(line.removePrefix("- ").trim())
            } else if (currentVersion != null) {
                currentChanges.add(line)
            }
        }
        flushCurrent()

        return entries
    }
}
