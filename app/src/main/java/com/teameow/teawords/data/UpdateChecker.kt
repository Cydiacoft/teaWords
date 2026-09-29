package com.teameow.teawords.data

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 一次「检查更新」拿到的版本信息。 */
data class UpdateInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String?,
    /** true 表示这条来自 tag 而不是正式 release，发布说明自然为空。 */
    val fromTag: Boolean = false
)

/**
 * 严格校验 "v1.2.3"、"1.2"、"1.2.3-beta.1"；无法解析时返回无效标记 [0]。
 * 只在数字段上比较，绝不用字符串比较（"1.10" > "1.9" 这样才不会判错）。
 */
private val VERSION = Regex("^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$")

internal fun versionParts(raw: String?): List<Int> {
    val match = VERSION.matchEntire(raw?.trim().orEmpty()) ?: return listOf(0)
    return match.groupValues[1].split('.').map { it.toIntOrNull() ?: return listOf(0) }
}

fun isComparableVersion(raw: String?): Boolean = versionParts(raw).any { it > 0 }

/**
 * [latest] 是否比 [current] 新。解析不出有意义版本号时一律返回 false：
 * 宁可说「没查到新版本」，也不能凭一个看不懂的字符串谎报有更新。
 */
fun isNewerVersion(latest: String?, current: String?): Boolean {
    val candidate = versionParts(latest)
    if (!isComparableVersion(latest) || !isComparableVersion(current)) return false
    val installed = versionParts(current)
    for (index in 0 until maxOf(candidate.size, installed.size)) {
        val remote = candidate.getOrElse(index) { 0 }
        val local = installed.getOrElse(index) { 0 }
        if (remote != local) return remote > local
    }
    // A stable release supersedes a prerelease of the same numeric version.
    val remotePre = VERSION.matchEntire(latest!!.trim())!!.groupValues[2]
    val localPre = VERSION.matchEntire(current!!.trim())!!.groupValues[2]
    return remotePre.isEmpty() && localPre.isNotEmpty()
}

/**
 * 只读 GitHub 公开 API 的版本检查：
 *
 * - 不下载安装包、不自动安装、不发送任何设备或账号信息（连 token 都没有，匿名请求）。
 * - 先问 `/releases/latest`，404（仓库还没有正式发布）时退回 `/releases` 列表，再退回 `/tags`。
 * - 三者都为空时返回 `null`，由界面如实说明「仓库还没有发布版本」，而不是假装是最新版。
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient(),
    private val owner: String = OWNER,
    private val repo: String = REPO
) {
    suspend fun latest(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            releaseLatest()?.let { return@withContext Result.success(it) }
            releaseList()?.let { return@withContext Result.success(it) }
            tagList()?.let { return@withContext Result.success(it) }
            Result.success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("连接 GitHub 超时，请检查网络后重试", e))
        } catch (e: UnknownHostException) {
            Result.failure(IOException("无法解析 api.github.com，当前网络可能无法访问 GitHub", e))
        } catch (e: Exception) {
            Result.failure(IOException(e.message ?: "检查更新失败", e))
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo$path")
            .header("Accept", "application/vnd.github+json")
            // GitHub API 对没有 User-Agent 的请求直接 403，这个头不是可选项。
            .header("User-Agent", "teaWords-android")
            .build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }

    /** 仓库没有正式发布时返回 null；其它非 2xx 直接抛错，交给调用方显示原因。 */
    private fun releaseLatest(): UpdateInfo? {
        val (code, body) = get("/releases/latest")
        if (code == 404) return null
        require(code in 200..299) { "GitHub 返回 HTTP $code" }
        return releaseInfo(JsonParser.parseString(body).asJsonObject)
    }

    private fun releaseList(): UpdateInfo? {
        val (code, body) = get("/releases")
        require(code in 200..299) { "GitHub 返回 HTTP $code" }
        return JsonParser.parseString(body).asJsonArray.mapNotNull { releaseInfo(it.asJsonObject) }
            .reduceOrNull { best, next -> if (isNewerVersion(next.tag, best.tag)) next else best }
    }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun releaseInfo(json: JsonObject): UpdateInfo? {
        if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) return null
        val tag = json.text("tag_name")
        if (!isComparableVersion(tag)) return null
        return UpdateInfo(
            tag = tag,
            name = json.text("name"),
            notes = json.text("body"),
            pageUrl = json.text("html_url").ifBlank { repositoryPage() },
            publishedAt = json.text("published_at").ifBlank { null }
        )
    }

    private fun tagList(): UpdateInfo? {
        val (code, body) = get("/tags")
        require(code in 200..299) { "GitHub 返回 HTTP $code" }
        val name = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.text("name") }
            .filter { isComparableVersion(it) }
            .reduceOrNull { best, next -> if (isNewerVersion(next, best)) next else best } ?: return null
        return UpdateInfo(
            tag = name,
            name = name,
            notes = "",
            pageUrl = "https://github.com/$owner/$repo/releases/tag/$name",
            publishedAt = null,
            fromTag = true
        )
    }

    private fun repositoryPage() = "https://github.com/$owner/$repo"

    companion object {
        const val OWNER = "Cydiacoft"
        const val REPO = "teaWords"
        const val PAGE_URL = "https://github.com/Cydiacoft/teaWords"
        const val RELEASES_URL = "https://github.com/Cydiacoft/teaWords/releases"
    }
}
