package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslationService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun translateToRussian(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(text)

        try {
            // Split into manageable chunks by paragraphs or punctuation if too large
            val chunks = splitTextIntoChunks(text, 1200)
            val translatedChunks = mutableListOf<String>()

            for (chunk in chunks) {
                val encodedText = URLEncoder.encode(chunk, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=$encodedText"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 DiardNews/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Ошибка сервера перевода: HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: ""
                val translated = parseGoogleTranslateResponse(body)
                translatedChunks.add(translated.ifEmpty { chunk })
            }

            Result.success(translatedChunks.joinToString("\n\n"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGoogleTranslateResponse(jsonString: String): String {
        return try {
            val rootArray = JSONArray(jsonString)
            val sentences = rootArray.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                sb.append(sentence.getString(0))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun splitTextIntoChunks(text: String, maxChunkSize: Int): List<String> {
        if (text.length <= maxChunkSize) return listOf(text)

        val paragraphs = text.split("\n\n")
        val result = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (para in paragraphs) {
            if (currentChunk.length + para.length + 2 > maxChunkSize) {
                if (currentChunk.isNotEmpty()) {
                    result.add(currentChunk.toString().trim())
                    currentChunk = StringBuilder()
                }
                if (para.length > maxChunkSize) {
                    // Split long paragraph by sentence
                    val sentences = para.split(Regex("(?<=[.!?])\\s+"))
                    for (sentence in sentences) {
                        if (currentChunk.length + sentence.length + 1 > maxChunkSize) {
                            if (currentChunk.isNotEmpty()) {
                                result.add(currentChunk.toString().trim())
                                currentChunk = StringBuilder()
                            }
                        }
                        if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                        currentChunk.append(sentence)
                    }
                } else {
                    currentChunk.append(para)
                }
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(para)
            }
        }

        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString().trim())
        }

        return result.ifEmpty { listOf(text) }
    }
}
