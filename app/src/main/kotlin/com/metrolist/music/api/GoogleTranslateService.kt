/**
 * MetroLoop Project (C) 2026
 * Based on the Metrolist project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Key-less lyrics translation backed by Google Translate's public web endpoint
 * (the same `translate_a/single` feed translate.google.com uses).
 *
 * Every lyric line is sent as its own request instead of one large blob. That keeps the
 * translated output aligned 1:1 with the source lines, which the synced lyrics view depends
 * on, and it avoids relying on Google preserving newlines. Identical lines are only
 * requested once and requests are throttled, so a typical song costs ~20-60 small calls.
 */
object GoogleTranslateService {
    const val MODE_LITERAL = "Literal"
    const val MODE_ROMANIZED = "Romanized"
    const val MODE_TRANSCRIBED = "Transcribed"

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CONCURRENT_REQUESTS = 4
    private const val MAX_LINE_LENGTH = 1200
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Translates [lines] into [targetLanguage] and returns a list of the exact same size.
     *
     * [mode] is one of [MODE_LITERAL] (meaning), [MODE_ROMANIZED] (Latin transliteration)
     * or [MODE_TRANSCRIBED] (transliteration into the target script).
     *
     * Blank lines are passed through untouched, and a line that cannot be translated is
     * returned as-is instead of failing the whole song.
     */
    suspend fun translate(
        lines: List<String>,
        targetLanguage: String,
        mode: String = MODE_LITERAL,
        maxRetries: Int = 2,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (lines.isEmpty()) return@withContext Result.success(lines)
            if (targetLanguage.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("A target language is required"))
            }

            val uniqueLines = lines.filter { it.isNotBlank() }.distinct()
            if (uniqueLines.isEmpty()) return@withContext Result.success(lines)

            val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            val results =
                coroutineScope {
                    uniqueLines
                        .map { line ->
                            async {
                                semaphore.withPermit {
                                    runCatching { requestWithRetry(line, targetLanguage, mode, maxRetries) }
                                }
                            }
                        }.awaitAll()
                }

            if (results.all { it.isFailure }) {
                return@withContext Result.failure(
                    results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IOException("Translation failed"),
                )
            }

            val translations =
                uniqueLines
                    .zip(results)
                    .associate { (line, result) ->
                        val translated = result.getOrNull()?.takeIf { it.isNotBlank() }
                        line to (translated ?: line)
                    }

            Result.success(lines.map { line -> if (line.isBlank()) line else translations[line] ?: line })
        }

    private suspend fun requestWithRetry(
        line: String,
        targetLanguage: String,
        mode: String,
        maxRetries: Int,
    ): String {
        var lastError: Exception? = null
        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            try {
                return request(line, targetLanguage, mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Translation failed")
    }

    private fun request(
        line: String,
        targetLanguage: String,
        mode: String,
    ): String {
        val language = normalizeLanguage(targetLanguage)
        val transliterating = mode == MODE_ROMANIZED || mode == MODE_TRANSCRIBED
        val url =
            baseUrl(
                // Romanization always targets Latin script; transcription targets the
                // selected language's script, both through Google's transliteration feed.
                dt = if (transliterating) "rm" else "t",
                tl = if (mode == MODE_ROMANIZED) "en" else language,
                query = line.take(MAX_LINE_LENGTH),
            )

        // Preferred shape: `dj=1` returns a plain JSON object with a "sentences" array.
        parse(fetch(url.newBuilder().addQueryParameter("dj", "1").build()), transliterating)
            ?.let { return it }

        // Fallback shape: the legacy nested-array response.
        parse(fetch(url), transliterating)?.let { return it }

        throw IOException("Unexpected translation response")
    }

    private fun baseUrl(
        dt: String,
        tl: String,
        query: String,
    ): HttpUrl =
        ENDPOINT
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", tl)
            .addQueryParameter("dt", dt)
            .addQueryParameter("ie", "UTF-8")
            .addQueryParameter("oe", "UTF-8")
            .addQueryParameter("q", query)
            .build()

    private fun fetch(url: HttpUrl): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 429 and server errors are worth retrying, client errors are not.
                if (response.code == 429 || response.code >= 500) {
                    throw IOException("Google Translate is busy (HTTP ${response.code})")
                }
                throw IllegalStateException("Translation failed: HTTP ${response.code}")
            }
            return response.body.string()
        }
    }

    /**
     * Extracts the translated (or transliterated) text out of either supported response shape.
     * Returns null when the payload carries nothing usable, which makes the caller retry
     * with the other shape.
     */
    private fun parse(
        body: String,
        transliterating: Boolean,
    ): String? =
        runCatching {
            val element = json.parseToJsonElement(body)
            val parts = mutableListOf<String>()

            val sentences = (element as? JsonObject)?.get("sentences") as? JsonArray
            if (sentences != null) {
                sentences.forEach { sentence ->
                    val fields = sentence as? JsonObject ?: return@forEach
                    val text =
                        if (transliterating) {
                            fields.string("translit") ?: fields.string("src_translit")
                        } else {
                            fields.string("trans")
                        }
                    if (!text.isNullOrEmpty()) parts += text
                }
            } else if (element is JsonArray) {
                // [[[ "text", "source", ... ], ...], ...]
                val chunks = element.firstOrNull() as? JsonArray
                chunks?.forEach { chunk ->
                    val text = (chunk as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (!text.isNullOrEmpty()) parts += text
                }
            }

            parts.joinToString("").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Google accepts plain BCP-47 tags, but a few legacy codes need normalizing. */
    private fun normalizeLanguage(language: String): String =
        when (val tag = language.trim().lowercase()) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "zh-hans", "zh-cn" -> "zh-CN"
            "zh-hant", "zh-tw" -> "zh-TW"
            "pt-br" -> "pt"
            "pt-pt" -> "pt-PT"
            else -> tag
        }
}
