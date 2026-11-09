package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class FeedFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetchFeed(url: String): Result<ParsedFeed> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 EmuNews/1.0")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Ошибка сервера: HTTP ${response.code}"))
            }

            val bodyString = response.body?.string()
                ?: return@withContext Result.failure(IOException("Ответ сервера пуст"))

            val feed = RssParser.parse(bodyString)
            Result.success(feed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
