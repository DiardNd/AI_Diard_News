package com.example.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

object RssParser {

    private val DATE_FORMATS = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        },
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    )

    private val IMG_PATTERN = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
    private val HTML_TAG_PATTERN = Pattern.compile("<[^>]+>")

    fun parse(xmlString: String): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlString))

        var feedTitle: String? = null
        var feedDescription: String? = null
        val items = mutableListOf<ParsedFeedItem>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name?.lowercase(Locale.ROOT)
                when (tagName) {
                    "item" -> {
                        val item = parseRssItem(parser)
                        if (item != null) items.add(item)
                    }
                    "entry" -> {
                        val item = parseAtomEntry(parser)
                        if (item != null) items.add(item)
                    }
                    "title" -> {
                        if (feedTitle == null) {
                            feedTitle = readText(parser)
                        }
                    }
                    "description", "subtitle" -> {
                        if (feedDescription == null) {
                            feedDescription = readText(parser)
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return ParsedFeed(
            title = feedTitle?.let { cleanHtml(it) },
            description = feedDescription?.let { cleanHtml(it) },
            items = items
        )
    }

    private fun parseRssItem(parser: XmlPullParser): ParsedFeedItem? {
        var title = ""
        var link = ""
        var description = ""
        var content = ""
        var guid = ""
        var pubDate = System.currentTimeMillis()
        var imageUrl: String? = null

        parser.require(XmlPullParser.START_TAG, null, "item")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            val name = parser.name?.lowercase(Locale.ROOT) ?: ""
            when {
                name == "title" -> title = readText(parser)
                name == "link" -> link = readText(parser).trim()
                name == "description" -> {
                    description = readText(parser)
                    if (imageUrl == null) {
                        imageUrl = extractImageUrl(description)
                    }
                }
                name == "content:encoded" || name == "content" -> {
                    content = readText(parser)
                    if (imageUrl == null) {
                        imageUrl = extractImageUrl(content)
                    }
                }
                name == "guid" -> guid = readText(parser).trim()
                name == "pubdate" -> {
                    val dateStr = readText(parser).trim()
                    pubDate = parseDate(dateStr)
                }
                name == "enclosure" -> {
                    val url = parser.getAttributeValue(null, "url")
                    val type = parser.getAttributeValue(null, "type") ?: ""
                    if (url != null && (type.contains("image") || isImageUrl(url))) {
                        imageUrl = url
                    }
                    skip(parser)
                }
                name == "media:content" || name == "media:thumbnail" -> {
                    val url = parser.getAttributeValue(null, "url")
                    if (url != null) {
                        imageUrl = url
                    }
                    skip(parser)
                }
                else -> skip(parser)
            }
        }

        val finalLink = link.ifEmpty { guid }
        val finalGuid = guid.ifEmpty { finalLink }
        if (finalLink.isEmpty() && title.isEmpty()) return null

        val summary = cleanHtml(if (description.isNotEmpty()) description else content)
            .take(300)
            .trim()

        val fullContent = if (content.isNotEmpty()) content else description

        return ParsedFeedItem(
            guid = finalGuid.ifEmpty { "item_${title.hashCode()}_$pubDate" },
            title = cleanHtml(title).ifEmpty { "Без названия" },
            link = finalLink,
            description = summary,
            content = fullContent.ifEmpty { summary },
            imageUrl = imageUrl,
            pubDate = pubDate
        )
    }

    private fun parseAtomEntry(parser: XmlPullParser): ParsedFeedItem? {
        var title = ""
        var link = ""
        var summary = ""
        var content = ""
        var id = ""
        var pubDate = System.currentTimeMillis()
        var imageUrl: String? = null

        parser.require(XmlPullParser.START_TAG, null, "entry")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            val name = parser.name?.lowercase(Locale.ROOT) ?: ""
            when {
                name == "title" -> title = readText(parser)
                name == "link" -> {
                    val rel = parser.getAttributeValue(null, "rel")
                    val href = parser.getAttributeValue(null, "href")
                    if (href != null && (rel == null || rel == "alternate")) {
                        link = href.trim()
                    } else if (href != null && rel == "enclosure") {
                        if (imageUrl == null && isImageUrl(href)) {
                            imageUrl = href
                        }
                    }
                    skip(parser)
                }
                name == "summary" -> {
                    summary = readText(parser)
                    if (imageUrl == null) {
                        imageUrl = extractImageUrl(summary)
                    }
                }
                name == "content" -> {
                    content = readText(parser)
                    if (imageUrl == null) {
                        imageUrl = extractImageUrl(content)
                    }
                }
                name == "id" -> id = readText(parser).trim()
                name == "published" || name == "updated" -> {
                    val dateStr = readText(parser).trim()
                    pubDate = parseDate(dateStr)
                }
                else -> skip(parser)
            }
        }

        val finalLink = link.ifEmpty { id }
        val finalGuid = id.ifEmpty { finalLink }
        if (finalLink.isEmpty() && title.isEmpty()) return null

        val cleanSummary = cleanHtml(if (summary.isNotEmpty()) summary else content)
            .take(300)
            .trim()
        val fullContent = if (content.isNotEmpty()) content else summary

        return ParsedFeedItem(
            guid = finalGuid.ifEmpty { "entry_${title.hashCode()}_$pubDate" },
            title = cleanHtml(title).ifEmpty { "Без названия" },
            link = finalLink,
            description = cleanSummary,
            content = fullContent.ifEmpty { cleanSummary },
            imageUrl = imageUrl,
            pubDate = pubDate
        )
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    fun cleanHtml(rawHtml: String): String {
        if (rawHtml.isEmpty()) return ""
        val withoutTags = HTML_TAG_PATTERN.matcher(rawHtml).replaceAll(" ")
        return withoutTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractImageUrl(html: String): String? {
        val matcher = IMG_PATTERN.matcher(html)
        if (matcher.find()) {
            val url = matcher.group(1)
            if (url != null && isImageUrl(url)) {
                return url
            }
        }
        return null
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".png") ||
                lower.endsWith(".webp") ||
                lower.endsWith(".gif") ||
                lower.contains("preview") ||
                lower.contains("thumbnail") ||
                lower.contains("imgur.com") ||
                lower.contains("i.redd.it")
    }

    private fun parseDate(dateStr: String): Long {
        for (format in DATE_FORMATS) {
            try {
                val date = format.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }
}
