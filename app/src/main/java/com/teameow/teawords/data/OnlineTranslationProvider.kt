package com.teameow.teawords.data

import com.google.gson.JsonParser
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranslationProgress(val completed: Int, val total: Int, val text: String)
data class TranslationPiece(val text: String, val translate: Boolean)

/** Byte-limited chunks, preserving whitespace and never separating a surrogate pair. */
object TranslationText {
    const val MAX_INPUT_CHARS = 20_000
    const val REQUEST_BYTES = 480 // MyMemory allows at most 500 UTF-8 bytes.

    fun pieces(text: String, maxBytes: Int = REQUEST_BYTES): List<TranslationPiece> {
        require(maxBytes >= 4)
        val result = mutableListOf<TranslationPiece>()
        var start = 0
        while (start < text.length) {
            if (text[start].isWhitespace()) {
                var end = start + 1
                while (end < text.length && text[end].isWhitespace()) end++
                result.add(TranslationPiece(text.substring(start, end), false))
                start = end
                continue
            }
            var end = start
            var bytes = 0
            var sentenceEnd = start
            var spaceEnd = start
            while (end < text.length && text[end] !in "\r\n") {
                val code = text.codePointAt(end)
                val size = when { code <= 0x7f -> 1; code <= 0x7ff -> 2; code <= 0xffff -> 3; else -> 4 }
                if (bytes + size > maxBytes) break
                end += Character.charCount(code)
                bytes += size
                if (code.toChar() in ".!?。！？;；") sentenceEnd = end
                if (Character.isWhitespace(code)) spaceEnd = end
            }
            if (end < text.length && text[end] !in "\r\n") {
                // Avoid tiny fragments just because the first sentence/word was short.
                val halfway = start + (end - start) / 2
                end = when { sentenceEnd > halfway -> sentenceEnd; spaceEnd > halfway -> spaceEnd; else -> end }
            }
            val raw = text.substring(start, end)
            val content = raw.trimEnd()
            if (content.isNotEmpty()) result.add(TranslationPiece(content, true))
            if (content.length < raw.length) result.add(TranslationPiece(raw.substring(content.length), false))
            start = end
        }
        return result
    }

    /** Decode entities as text: original angle brackets are not interpreted as HTML tags. */
    fun decodeEntities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(text) {
        val token = it.groupValues[1]
        when (token) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> "\u00a0"
            else -> {
                val code = if (token.startsWith("#x")) token.drop(2).toIntOrNull(16) else token.drop(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code !in 0xd800..0xdfff) String(Character.toChars(code)) else it.value
            }
        }
    }
}

/** Public documented API: no model download or embedded credentials. */
class OnlineTranslationProvider(
    private val contactEmail: () -> String = { "" },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = "https://api.mymemory.translated.net/get".toHttpUrl()
) : TranslationProvider {
    private data class CacheKey(val text: String, val source: String, val target: String, val email: String)
    private val cache = object : LinkedHashMap<CacheKey, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?) = size > 256
    }
    override suspend fun isAvailable() = true
    override suspend fun prepare() = Unit
    override fun release() { synchronized(cache) { cache.clear() } }

    override suspend fun translate(text: String, source: String, target: String, onProgress: (TranslationProgress) -> Unit): String {
        require(source != target && source in listOf("en", "zh") && target in listOf("en", "zh"))
        require(text.length <= TranslationText.MAX_INPUT_CHARS) { "单次最多翻译 20,000 个字符，请分成几次提交；原文没有被截断。" }
        val pieces = TranslationText.pieces(text)
        val total = pieces.count { it.translate }
        val output = StringBuilder()
        var completed = 0
        val email = contactEmail().trim()
        onProgress(TranslationProgress(0, total, ""))
        for (piece in pieces) {
            currentCoroutineContext().ensureActive()
            if (!piece.translate) { output.append(piece.text); continue }
            val key = CacheKey(piece.text, source, target, email)
            val translated = synchronized(cache) { cache[key] } ?: request(piece.text, source, target, email).also {
                synchronized(cache) { cache[key] = it }
            }
            output.append(translated)
            completed++
            onProgress(TranslationProgress(completed, total, output.toString()))
        }
        return output.toString()
    }

    private suspend fun request(text: String, source: String, target: String, email: String): String {
        val url = endpoint.newBuilder().addQueryParameter("q", text)
            .addQueryParameter("langpair", "${if (source == "zh") "zh-CN" else source}|${if (target == "zh") "zh-CN" else target}")
            .addQueryParameter("mt", "1").apply { if (email.isNotBlank()) addQueryParameter("de", email) }.build()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        return try {
            call.awaitTranslationResponse().use { response ->
                if (response.code == 429) throw IOException(QUOTA_MESSAGE)
                if (!response.isSuccessful) throw IOException("在线翻译服务暂不可用（${response.code}），请稍后重试。")
                parse(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            // Do not echo request URLs, which contain the user's original text and optional email.
            if (e.message?.startsWith("在线翻译") == true) throw e
            throw IOException("在线翻译连接失败或超时，请检查网络后重试。已完成的部分会保留。")
        }
    }

    companion object {
        const val QUOTA_MESSAGE = "在线翻译今日免费额度已用完。可在翻译设置中填写本人有效邮箱提高额度，或明天重试。"
        fun parse(body: String): String {
            val root = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) {
                throw IOException("在线翻译服务返回了无法读取的内容，请稍后重试。")
            }
            val status = runCatching { root.get("responseStatus")?.asInt }.getOrNull()
            val quota = runCatching { root.get("quotaFinished")?.asBoolean }.getOrNull() == true
            if (quota || status == 429) throw IOException(QUOTA_MESSAGE)
            if (status != 200) throw IOException("在线翻译服务未能完成请求，请稍后重试。")
            val result = runCatching { root.getAsJsonObject("responseData").get("translatedText").asString }.getOrNull()
            if (result.isNullOrBlank() || result.startsWith("MYMEMORY WARNING", true))
                throw IOException("在线翻译服务没有返回有效译文，请稍后重试。")
            return TranslationText.decodeEntities(result).trim()
        }
    }
}

internal suspend fun Call.awaitTranslationResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            // The cancellation callback also closes a response delivered just before cancellation.
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
