package com.example.domain.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

object UrlNormalizer {
    fun normalizeForComparison(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        val httpUrl = trimmed.toHttpUrlOrNull()
        if (httpUrl != null) {
            val scheme = httpUrl.scheme.lowercase(Locale.ROOT)
            val host = httpUrl.host.lowercase(Locale.ROOT)
            val isDefaultPort = (scheme == "http" && httpUrl.port == 80) ||
                (scheme == "https" && httpUrl.port == 443)
            val portPart = if (isDefaultPort) "" else ":${httpUrl.port}"
            val normalizedPath = httpUrl.encodedPath.trimEnd('/')
            val queryPart = httpUrl.encodedQuery?.let { "?$it" } ?: ""
            return "$scheme://$host$portPart$normalizedPath$queryPart"
        }

        return trimmed.lowercase(Locale.ROOT).trimEnd('/')
    }
}
