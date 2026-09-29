package com.teameow.teawords.data

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections

class TranslationTest {
    private fun client(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody()).build()
    }.build()
    private val good = """{"responseStatus":200,"quotaFinished":false,"responseData":{"translatedText":"译文"}}"""

    @Test fun contactEmailAllowsOptionalValueAndRealisticAddresses() {
        assertTrue(TranslationSettingsPolicy.validEmail("person@example.com"))
        assertTrue(TranslationSettingsPolicy.validEmail(""))
        assertTrue(TranslationSettingsPolicy.validEmail(" user.name+tag@example.com "))
        assertFalse(TranslationSettingsPolicy.validEmail("person example.com"))
        assertFalse(TranslationSettingsPolicy.validEmail("someone@"))
    }

    @Test fun byteChunksPreserveParagraphsAndNeverBreakUnicode() {
        val text = "  First paragraph. " + "Verylongword".repeat(100) + "\r\n\r\n" + "很长的中文段落🙂".repeat(100) + "\n  Last.  "
        val pieces = TranslationText.pieces(text)
        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.filter { it.translate }.all { it.text.toByteArray(Charsets.UTF_8).size <= 480 })
        assertTrue(pieces.filter { it.translate }.all { !it.text.startsWith("\udc42") && !it.text.endsWith("\ud83d") })
        assertTrue(pieces.any { !it.translate && it.text.contains("\r\n\r\n") })
    }

    @Test fun entitiesDoNotStripQuotedCodeOrOriginalAngleBrackets() {
        assertEquals("<code> & \"文字\" 🙂", TranslationText.decodeEntities("<code> &amp; &quot;文字&quot; &#x1F642;"))
        assertEquals("&#9999999999999;", TranslationText.decodeEntities("&#9999999999999;"))
    }

    @Test fun apiErrorsAreNeverPresentedAsTranslation() {
        listOf("{}", "not json", """{"responseStatus":429,"responseData":{"translatedText":"quota warning"}}""",
            """{"responseStatus":200,"quotaFinished":true,"responseData":{"translatedText":"warning"}}""",
            """{"responseStatus":200,"responseData":{"translatedText":""}}""").forEach {
            try { OnlineTranslationProvider.parse(it); fail("Must reject service errors") } catch (_: IOException) { }
        }
    }

    @Test fun longTextUsesCorrectDirectionAndKeepsNewlines() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<Request>())
        val provider = OnlineTranslationProvider(contactEmail = { "person@example.com" }, client = client { calls.add(it); 200 to good })
        val input = "This is a long sentence with complete words. ".repeat(30) + "\n\nSecond paragraph."
        val progress = mutableListOf<TranslationProgress>()
        val result = provider.translate(input, "en", "zh", progress::add)
        assertTrue(calls.size > 1)
        assertTrue(calls.all { it.url.queryParameter("q")!!.toByteArray(Charsets.UTF_8).size <= 500 })
        assertTrue(calls.all { it.url.queryParameter("langpair") == "en|zh-CN" && it.url.queryParameter("de") == "person@example.com" })
        assertTrue(result.contains("\n\n"))
        assertEquals(progress.last().total, progress.last().completed)
        val beforeRetry = calls.size
        assertEquals(result, provider.translate(input, "en", "zh"))
        assertEquals(beforeRetry, calls.size)
        assertEquals(TranslationText.pieces(input).filter { it.translate }.map { it.text }.distinct().size, calls.size) // retry is cached; no extra quota consumption
    }

    @Test fun failureRetainsCompletedProgressAndRetryOnlyRequestsUnfinishedSegments() = runBlocking {
        var failSecond = true
        var calls = 0
        val provider = OnlineTranslationProvider(client = client {
            calls++
            if (calls == 2 && failSecond) 429 to "{}" else 200 to good
        })
        val input = "a".repeat(490) + "\n" + "b".repeat(490)
        var partial = ""
        try { provider.translate(input, "en", "zh") { partial = it.text }; fail("Quota must stop the request") }
        catch (e: IOException) { assertTrue(e.message!!.contains("额度")) }
        assertEquals("译文", partial)
        failSecond = false
        provider.translate(input, "en", "zh")
        assertEquals(TranslationText.pieces(input).count { it.translate } + 1, calls)
    }

    @Test fun cancelStopsRemainingRequestsAndKeepsSuccessfulCache() = runBlocking {
        var calls = 0
        val provider = OnlineTranslationProvider(client = client { calls++; 200 to good })
        val input = "a".repeat(490) + "\n" + "b".repeat(490)
        val job = launch { provider.translate(input, "en", "zh") { if (it.completed == 1) cancel() } }
        job.join()
        assertEquals(1, calls)
        provider.translate(input, "en", "zh")
        assertEquals(TranslationText.pieces(input).count { it.translate }, calls)
    }

    @Test fun tooLongInputFailsBeforeSendingAnything() = runBlocking {
        var calls = 0
        val provider = OnlineTranslationProvider(client = client { calls++; 200 to good })
        try { provider.translate("x".repeat(20001), "en", "zh"); fail("Must reject oversized input") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("没有被截断")) }
        assertEquals(0, calls)
    }

    @Test fun deepSeekSendsWholeContextAndCredentialOnlyToOfficialEndpoint() = runBlocking {
        var sent: Request? = null
        val input = "First paragraph.\n\nSecond paragraph."
        val provider = DeepSeekTranslationProvider({ "synthetic-test-key" }, client {
            sent = it
            200 to """{"choices":[{"finish_reason":"stop","message":{"content":"第一段。\n\n第二段。"}}]}"""
        })
        assertEquals("第一段。\n\n第二段。", provider.translate(input, "en", "zh"))
        assertEquals("api.deepseek.com", sent!!.url.host)
        assertEquals("Bearer synthetic-test-key", sent!!.header("Authorization"))
        val buffer = okio.Buffer(); sent!!.body!!.writeTo(buffer)
        val payload = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals(DeepSeekTranslationProvider.MODEL, payload.get("model").asString)
        assertEquals(input, payload.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
        assertEquals("disabled", payload.getAsJsonObject("thinking").get("type").asString)
    }

    @Test fun deepSeekRejectsTruncatedAndMissingResponses() {
        listOf("{}", """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":""}}]}""").forEach {
            try { DeepSeekTranslationProvider.parse(it); fail("Incomplete responses must not count as success") } catch (_: IOException) { }
        }
    }

    @Test fun deepSeekMissingKeyNeverMakesAPaidRequest() = runBlocking {
        var calls = 0
        val provider = DeepSeekTranslationProvider({ "" }, client { calls++; 200 to "{}" })
        try { provider.translate("Sentence.", "en", "zh"); fail("Key is required") } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }
}
