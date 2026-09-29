package com.teameow.teawords.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerResponseTest {
    private fun checker(responses: Map<String, Pair<Int, String>>) = UpdateChecker(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = checkNotNull(responses[request.url.encodedPath.substringAfter("/teaWords")])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build()
    )

    @Test fun `nullable release fields do not break update checks`() = runBlocking {
        val info = checker(mapOf("/releases/latest" to (200 to
            """{"tag_name":"v1.2","name":null,"body":null,"html_url":null,"published_at":null}""")))
            .latest().getOrThrow()!!
        assertEquals("v1.2", info.tag)
        assertEquals("", info.notes)
        assertEquals(UpdateChecker.PAGE_URL, info.pageUrl)
        assertNull(info.publishedAt)
    }

    @Test fun `fallback selects highest numeric tag rather than first tag`() = runBlocking {
        val info = checker(mapOf(
            "/releases/latest" to (404 to "{}"), "/releases" to (200 to "[]"),
            "/tags" to (200 to """[{"name":"nightly"},{"name":"v1.9"},{"name":"v1.10"}]""")
        )).latest().getOrThrow()!!
        assertEquals("v1.10", info.tag)
        assertTrue(info.fromTag)
    }

    @Test fun `fallback skips drafts and prereleases and tolerates nullable notes`() = runBlocking {
        val info = checker(mapOf(
            "/releases/latest" to (404 to "{}"),
            "/releases" to (200 to """[
                {"tag_name":"v3.0","draft":true},
                {"tag_name":"v2.0-rc1","prerelease":true},
                {"tag_name":"nightly"},
                {"tag_name":"v1.9","body":null},
                {"tag_name":"v1.10","body":null}
            ]""")
        )).latest().getOrThrow()!!
        assertEquals("v1.10", info.tag)
    }

    @Test fun `empty repository and unreadable tags have no comparable release`() = runBlocking {
        for (tags in listOf("[]", """[{"name":"nightly"}]""")) {
            assertNull(checker(mapOf(
                "/releases/latest" to (404 to "{}"), "/releases" to (200 to "[]"),
                "/tags" to (200 to tags)
            )).latest().getOrThrow())
        }
    }

    @Test fun `http errors remain failures rather than no release`() = runBlocking {
        val result = checker(mapOf("/releases/latest" to (403 to "{}"))).latest()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("403"))
    }
}
