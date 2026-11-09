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
    private val BR_TAG_REGEX = Regex("(?i)<br\\s*/?>")
    private val P_CLOSE_REGEX = Regex("(?i)</p>")
    private val DIV_CLOSE_REGEX = Regex("(?i)</div>")
    private val LI_CLOSE_REGEX = Regex("(?i)</li>")
    private val HEADING_CLOSE_REGEX = Regex("(?i)</h[1-6]>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HEX_ENTITY_REGEX = Regex("(?i)&#x([0-9a-f]+);")

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
        if (rawHtml.isBlank()) return ""

        var text = rawHtml
        // Preserve structure by turning block element closings into newlines
        text = BR_TAG_REGEX.replace(text, "\n")
        text = P_CLOSE_REGEX.replace(text, "\n\n")
        text = DIV_CLOSE_REGEX.replace(text, "\n")
        text = LI_CLOSE_REGEX.replace(text, "\n")
        text = HEADING_CLOSE_REGEX.replace(text, "\n\n")

        // Strip all remaining HTML tags
        val withoutTags = HTML_TAG_PATTERN.matcher(text).replaceAll("")

        // Decode HTML entities (run twice to resolve double-escaped entities like &amp;quot;)
        val decodedOnce = decodeHtmlEntities(withoutTags)
        val decoded = decodeHtmlEntities(decodedOnce)

        // Normalize whitespace and blank lines while preserving paragraph spacing
        val lines = decoded.split("\n")
        val cleanedLines = lines.map { it.replace(Regex("[ \\t]+"), " ").trim() }
        return cleanedLines.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun decodeHtmlEntities(input: String): String {
        if (!input.contains("&")) return input

        var text = input
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&#8216;", "'")
            .replace("&#8217;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace("&bull;", "•")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&trade;", "™")

        // Decimal numeric entities &#123;
        text = NUMERIC_ENTITY_REGEX.replace(text) { matchResult ->
            try {
                val code = matchResult.groupValues[1].toInt()
                Char(code).toString()
            } catch (_: Exception) {
                matchResult.value
            }
        }

        // Hex numeric entities &#x7A;
        text = HEX_ENTITY_REGEX.replace(text) { matchResult ->
            try {
                val code = matchResult.groupValues[1].toInt(16)
                Char(code).toString()
            } catch (_: Exception) {
                matchResult.value
            }
        }

        return text
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
