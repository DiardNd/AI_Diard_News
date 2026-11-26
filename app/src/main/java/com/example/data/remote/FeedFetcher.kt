package com.example.data.remote

import com.example.domain.model.DiagnosticStep
import com.example.domain.model.DiagnosticStepStatus
import com.example.domain.model.FeedFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

data class FeedDiagnosticFetchResult(
    val finalUrl: String? = null,
    val httpCode: Int? = null,
    val contentType: String? = null,
    val responseTimeMs: Long? = null,
    val responseSizeBytes: Long? = null,
    val feedFormat: FeedFormat = FeedFormat.UNKNOWN,
    val parsedFeed: ParsedFeed? = null,
    val networkStep: DiagnosticStep,
    val httpStep: DiagnosticStep,
    val contentTypeStep: DiagnosticStep,
    val parsingStep: DiagnosticStep
)

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
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Ошибка сервера: HTTP ${response.code}"))
                }

                val bodyString = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Ответ сервера пуст"))

                val feed = RssParser.parse(bodyString)
                Result.success(feed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun diagnoseFeed(url: String): FeedDiagnosticFetchResult = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        val validUrl = trimmedUrl.toHttpUrlOrNull()
        if (validUrl == null) {
            return@withContext FeedDiagnosticFetchResult(
                networkStep = DiagnosticStep(
                    id = "network",
                    title = "Доступность сервера и URL",
                    status = DiagnosticStepStatus.ERROR,
                    summary = "Некорректный адрес RSS/Atom-источника.",
                    technicalDetails = "URL «$trimmedUrl» не соответствует формату http:// или https://"
                ),
                httpStep = skippedStep("http", "HTTP-ответ сервера", "Пропущено из-за некорректного URL."),
                contentTypeStep = skippedStep("content_type", "Тип полученного контента", "Пропущено из-за некорректного URL."),
                parsingStep = skippedStep("parsing", "Парсинг RSS/Atom", "Пропущено из-за некорректного URL.")
            )
        }

        val startNs = System.nanoTime()
        try {
            val request = Request.Builder()
                .url(validUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
                .build()

            client.newCall(request).execute().use { response ->
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val finalUrl = response.request.url.toString()
                val httpCode = response.code
                val httpMessage = response.message.ifBlank { httpReasonPhrase(httpCode) }
                val rawContentType = response.header("Content-Type")
                    ?: response.body?.contentType()?.toString()

                val redirected = !finalUrl.equals(validUrl.toString(), ignoreCase = true)
                val networkStep = DiagnosticStep(
                    id = "network",
                    title = "Доступность сервера",
                    status = DiagnosticStepStatus.SUCCESS,
                    summary = "Сервер ответил за $elapsedMs мс.",
                    technicalDetails = buildString {
                        append("Запрос: ${validUrl}")
                        if (redirected) {
                            append("\nРедирект на: $finalUrl")
                        }
                        append("\nВремя ответа: $elapsedMs мс")
                    }
                )

                val bodyBytes = response.body?.bytes()
                val responseSize = bodyBytes?.size?.toLong() ?: 0L

                if (!response.isSuccessful) {
                    val httpSummary = describeHttpFailure(httpCode)
                    val httpStep = DiagnosticStep(
                        id = "http",
                        title = "HTTP-ответ сервера",
                        status = DiagnosticStepStatus.ERROR,
                        summary = httpSummary,
                        technicalDetails = buildString {
                            append("HTTP $httpCode $httpMessage")
                            append("\nContent-Type: ${rawContentType ?: "не указан"}")
                            append("\nРазмер ответа: $responseSize байт")
                            append("\nИтоговый URL: $finalUrl")
                        }
                    )
                    return@withContext FeedDiagnosticFetchResult(
                        finalUrl = finalUrl,
                        httpCode = httpCode,
                        contentType = rawContentType,
                        responseTimeMs = elapsedMs,
                        responseSizeBytes = responseSize,
                        networkStep = networkStep,
                        httpStep = httpStep,
                        contentTypeStep = skippedStep(
                            "content_type",
                            "Тип полученного контента",
                            "Анализ содержимого пропущен: сервер вернул ошибку HTTP $httpCode."
                        ),
                        parsingStep = skippedStep(
                            "parsing",
                            "Парсинг RSS/Atom",
                            "Парсинг не выполнялся из-за ошибки HTTP $httpCode."
                        )
                    )
                }

                val httpStep = DiagnosticStep(
                    id = "http",
                    title = "HTTP-ответ сервера",
                    status = DiagnosticStepStatus.SUCCESS,
                    summary = if (redirected) {
                        "HTTP $httpCode (выполнен редирект)."
                    } else {
                        "HTTP $httpCode ($httpMessage)."
                    },
                    technicalDetails = buildString {
                        append("Статус: HTTP $httpCode $httpMessage")
                        append("\nРазмер ответа: $responseSize байт")
                        if (redirected) {
                            append("\nИтоговый URL: $finalUrl")
                        }
                    }
                )

                if (bodyBytes == null || bodyBytes.isEmpty()) {
                    val contentStep = DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.ERROR,
                        summary = "Сервер вернул пустое тело ответа (0 байт).",
                        technicalDetails = "HTTP $httpCode, Content-Type: ${rawContentType ?: "не указан"}"
                    )
                    return@withContext FeedDiagnosticFetchResult(
                        finalUrl = finalUrl,
                        httpCode = httpCode,
                        contentType = rawContentType,
                        responseTimeMs = elapsedMs,
                        responseSizeBytes = 0L,
                        networkStep = networkStep,
                        httpStep = httpStep,
                        contentTypeStep = contentStep,
                        parsingStep = skippedStep(
                            "parsing",
                            "Парсинг RSS/Atom",
                            "Нечего разбирать: тело ответа пустое."
                        )
                    )
                }

                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val decodedBody = bodyBytes.toString(charset).removePrefix("\uFEFF")
                val trimmedBody = decodedBody.trimStart()
                val lowerPrefix = trimmedBody.take(512).lowercase(Locale.ROOT)
                val lowerContentType = rawContentType?.lowercase(Locale.ROOT).orEmpty()

                val looksLikeXml = lowerPrefix.startsWith("<?xml") ||
                    lowerPrefix.startsWith("<rss") ||
                    lowerPrefix.startsWith("<feed") ||
                    lowerPrefix.startsWith("<rdf")
                val looksLikeHtml = lowerPrefix.startsWith("<!doctype html") ||
                    lowerPrefix.startsWith("<html")
                val looksLikeJson = lowerPrefix.startsWith("{") ||
                    lowerPrefix.startsWith("[") ||
                    lowerContentType.contains("json")
                val isXmlContentType = lowerContentType.contains("xml") ||
                    lowerContentType.contains("rss") ||
                    lowerContentType.contains("atom")

                val contentTypeStep = when {
                    trimmedBody.isBlank() -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.ERROR,
                        summary = "Ответ содержит только пробельные символы.",
                        technicalDetails = "Content-Type: ${rawContentType ?: "не указан"}, размер: $responseSize байт"
                    )
                    looksLikeHtml && !looksLikeXml -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.ERROR,
                        summary = "Вместо RSS/Atom-ленты сервер вернул HTML-страницу (возможно, указан адрес сайта, а не фида, или сработала защита от ботов).",
                        technicalDetails = "Content-Type: ${rawContentType ?: "не указан"}\nНачало ответа: ${snippet(trimmedBody)}"
                    )
                    looksLikeJson -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.ERROR,
                        summary = "Сервер вернул JSON вместо XML-ленты RSS/Atom.",
                        technicalDetails = "Content-Type: ${rawContentType ?: "не указан"}\nНачало ответа: ${snippet(trimmedBody)}"
                    )
                    isXmlContentType -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.SUCCESS,
                        summary = "Получен XML-контент (${rawContentType ?: "XML"}).",
                        technicalDetails = "Content-Type: $rawContentType\nКодировка: ${charset.name()}\nРазмер: $responseSize байт"
                    )
                    looksLikeXml -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.WARNING,
                        summary = "Сервер передал нестандартный Content-Type (${rawContentType ?: "не указан"}), но содержимое начинается как XML.",
                        technicalDetails = "Content-Type: ${rawContentType ?: "отсутствует"}\nКодировка: ${charset.name()}"
                    )
                    else -> DiagnosticStep(
                        id = "content_type",
                        title = "Тип полученного контента",
                        status = DiagnosticStepStatus.WARNING,
                        summary = "Неожиданный тип контента (${rawContentType ?: "не указан"}); попытка разбора как XML.",
                        technicalDetails = "Content-Type: ${rawContentType ?: "не указан"}\nНачало ответа: ${snippet(trimmedBody)}"
                    )
                }

                if (contentTypeStep.status == DiagnosticStepStatus.ERROR) {
                    return@withContext FeedDiagnosticFetchResult(
                        finalUrl = finalUrl,
                        httpCode = httpCode,
                        contentType = rawContentType,
                        responseTimeMs = elapsedMs,
                        responseSizeBytes = responseSize,
                        networkStep = networkStep,
                        httpStep = httpStep,
                        contentTypeStep = contentTypeStep,
                        parsingStep = skippedStep(
                            "parsing",
                            "Парсинг RSS/Atom",
                            "Парсинг пропущен: сервер вернул неподдерживаемый формат вместо RSS/Atom."
                        )
                    )
                }

                // Parse RSS/Atom using the production RssParser
                val parsedFeed = try {
                    RssParser.parse(decodedBody)
                } catch (e: XmlPullParserException) {
                    return@withContext FeedDiagnosticFetchResult(
                        finalUrl = finalUrl,
                        httpCode = httpCode,
                        contentType = rawContentType,
                        responseTimeMs = elapsedMs,
                        responseSizeBytes = responseSize,
                        networkStep = networkStep,
                        httpStep = httpStep,
                        contentTypeStep = contentTypeStep,
                        parsingStep = DiagnosticStep(
                            id = "parsing",
                            title = "Парсинг RSS/Atom",
                            status = DiagnosticStepStatus.ERROR,
                            summary = "Ошибка структуры XML при разборе ленты.",
                            technicalDetails = buildString {
                                append("XmlPullParserException")
                                if (e.lineNumber > 0) {
                                    append(" (строка ${e.lineNumber}, столбец ${e.columnNumber})")
                                }
                                append(": ${e.message ?: "невалидный XML"}")
                            }
                        )
                    )
                } catch (e: Exception) {
                    return@withContext FeedDiagnosticFetchResult(
                        finalUrl = finalUrl,
                        httpCode = httpCode,
                        contentType = rawContentType,
                        responseTimeMs = elapsedMs,
                        responseSizeBytes = responseSize,
                        networkStep = networkStep,
                        httpStep = httpStep,
                        contentTypeStep = contentTypeStep,
                        parsingStep = DiagnosticStep(
                            id = "parsing",
                            title = "Парсинг RSS/Atom",
                            status = DiagnosticStepStatus.ERROR,
                            summary = "Неожиданный сбой при разборе ленты.",
                            technicalDetails = "${e.javaClass.simpleName}: ${e.message ?: "без описания"}"
                        )
                    )
                }

                val feedFormat = detectFeedFormat(parsedFeed.rootTag)
                val totalItems = parsedFeed.items.size
                val validDatesCount = parsedFeed.items.count { it.parsedPubDate != null }
                val invalidDatesCount = totalItems - validDatesCount
                val latestParsedDate = parsedFeed.items.mapNotNull { it.parsedPubDate }.maxOrNull()

                val parsingStep = when {
                    feedFormat == FeedFormat.UNKNOWN && totalItems == 0 -> DiagnosticStep(
                        id = "parsing",
                        title = "Парсинг RSS/Atom",
                        status = DiagnosticStepStatus.ERROR,
                        summary = "XML-документ разобран, но не является лентой RSS или Atom.",
                        technicalDetails = "Корневой тег: <${parsedFeed.rootTag ?: "не обнаружен"}>, найдено записей: 0"
                    )
                    totalItems == 0 -> DiagnosticStep(
                        id = "parsing",
                        title = "Парсинг RSS/Atom",
                        status = DiagnosticStepStatus.WARNING,
                        summary = "Формат ${feedFormat.label} распознан, но записей в ленте не найдено (0).",
                        technicalDetails = buildString {
                            append("Формат: ${feedFormat.label} (<${parsedFeed.rootTag}>)")
                            if (!parsedFeed.title.isNullOrBlank()) {
                                append("\nЗаголовок ленты: ${parsedFeed.title}")
                            }
                        }
                    )
                    validDatesCount == 0 -> DiagnosticStep(
                        id = "parsing",
                        title = "Парсинг RSS/Atom",
                        status = DiagnosticStepStatus.WARNING,
                        summary = "Формат ${feedFormat.label}: получено записей $totalItems, но ни в одной не удалось распознать дату публикации.",
                        technicalDetails = buildString {
                            append("Формат: ${feedFormat.label}, записей: $totalItems")
                            val sampleRawDate = parsedFeed.items.firstNotNullOfOrNull { it.rawPubDate }
                            if (sampleRawDate != null) {
                                append("\nПример нераспознанной даты: «$sampleRawDate»")
                            } else {
                                append("\nТеги даты (<pubDate>/<published>/<updated>) отсутствуют.")
                            }
                        }
                    )
                    invalidDatesCount > 0 -> DiagnosticStep(
                        id = "parsing",
                        title = "Парсинг RSS/Atom",
                        status = DiagnosticStepStatus.WARNING,
                        summary = "Формат ${feedFormat.label}: получено $totalItems зап., у $invalidDatesCount из них дата не распознана.",
                        technicalDetails = buildString {
                            append("Формат: ${feedFormat.label}")
                            append("\nС валидной датой: $validDatesCount из $totalItems")
                            val badRaw = parsedFeed.items.firstOrNull { it.parsedPubDate == null }?.rawPubDate
                            if (badRaw != null) {
                                append("\nПример нераспознанной даты: «$badRaw»")
                            }
                        }
                    )
                    else -> DiagnosticStep(
                        id = "parsing",
                        title = "Парсинг RSS/Atom",
                        status = DiagnosticStepStatus.SUCCESS,
                        summary = "Формат ${feedFormat.label} успешно разобран, получено записей: $totalItems.",
                        technicalDetails = buildString {
                            append("Корневой тег: <${parsedFeed.rootTag ?: "?"}>")
                            if (!parsedFeed.title.isNullOrBlank()) {
                                append("\nНазвание из фида: ${parsedFeed.title}")
                            }
                            append("\nКорректных дат: $validDatesCount/$totalItems")
                            if (latestParsedDate != null) {
                                val raw = parsedFeed.items
                                    .filter { it.parsedPubDate == latestParsedDate }
                                    .firstNotNullOfOrNull { it.rawPubDate }
                                if (raw != null) {
                                    append("\nСвежая дата (raw): $raw")
                                }
                            }
                        }
                    )
                }

                FeedDiagnosticFetchResult(
                    finalUrl = finalUrl,
                    httpCode = httpCode,
                    contentType = rawContentType,
                    responseTimeMs = elapsedMs,
                    responseSizeBytes = responseSize,
                    feedFormat = feedFormat,
                    parsedFeed = parsedFeed,
                    networkStep = networkStep,
                    httpStep = httpStep,
                    contentTypeStep = contentTypeStep,
                    parsingStep = parsingStep
                )
            }
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val networkSummary = describeNetworkException(e)
            FeedDiagnosticFetchResult(
                responseTimeMs = elapsedMs,
                networkStep = DiagnosticStep(
                    id = "network",
                    title = "Доступность сервера",
                    status = DiagnosticStepStatus.ERROR,
                    summary = networkSummary,
                    technicalDetails = "${e.javaClass.simpleName}: ${e.message ?: "без подробностей"}\nВремя до сбоя: $elapsedMs мс"
                ),
                httpStep = skippedStep("http", "HTTP-ответ сервера", "Пропущено: не удалось получить ответ от сервера."),
                contentTypeStep = skippedStep("content_type", "Тип полученного контента", "Пропущено: нет ответа от сервера."),
                parsingStep = skippedStep("parsing", "Парсинг RSS/Atom", "Пропущено: нет данных для парсинга.")
            )
        }
    }

    private fun skippedStep(id: String, title: String, summary: String): DiagnosticStep {
        return DiagnosticStep(
            id = id,
            title = title,
            status = DiagnosticStepStatus.SKIPPED,
            summary = summary
        )
    }

    private fun detectFeedFormat(rootTag: String?): FeedFormat {
        return when (rootTag?.lowercase(Locale.ROOT)) {
            "rss" -> FeedFormat.RSS_2_0
            "feed" -> FeedFormat.ATOM
            "rdf:rdf", "rdf" -> FeedFormat.RDF
            else -> FeedFormat.UNKNOWN
        }
    }

    private fun describeNetworkException(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "Сервер не найден (ошибка DNS). Проверьте URL источника или подключение к интернету."
            is SocketTimeoutException, is InterruptedIOException -> "Превышено время ожидания ответа от сервера (таймаут)."
            is SSLHandshakeException, is SSLException -> "Ошибка защищённого SSL/TLS-соединения с сервером."
            is ConnectException -> "Сервер отклонил подключение или недоступен."
            else -> "Ошибка сети при обращении к серверу."
        }
    }

    private fun describeHttpFailure(code: Int): String {
        return when (code) {
            401 -> "Требуется авторизация на сервере (HTTP 401)."
            403 -> "Сервер запретил доступ (HTTP 403 — возможна блокировка User-Agent или Cloudflare)."
            404, 410 -> "Лента не найдена по указанному адресу (HTTP $code)."
            429 -> "Слишком много запросов к серверу (HTTP 429). Попробуйте позже."
            in 500..599 -> "Временная ошибка на стороне сервера (HTTP $code)."
            else -> "Сервер вернул неуспешный статус HTTP $code."
        }
    }

    private fun httpReasonPhrase(code: Int): String {
        return when (code) {
            200 -> "OK"
            301 -> "Moved Permanently"
            302 -> "Found"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status $code"
        }
    }

    private fun snippet(text: String): String {
        return text.replace(Regex("\\s+"), " ").take(140)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 EmuNews/1.0"
        private const val ACCEPT_HEADER =
            "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"
    }
}
