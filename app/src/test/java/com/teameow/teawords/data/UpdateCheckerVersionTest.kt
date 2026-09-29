package com.teameow.teawords.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较必须按数字段比，且看不懂的字符串一律判「没有更新」：
 * 谎报有更新会让用户白跑一趟下载页，比漏报更糟。
 */
class UpdateCheckerVersionTest {

    @Test
    fun `parses common tag shapes`() {
        assertEquals(listOf(1, 2, 3), versionParts("v1.2.3"))
        assertEquals(listOf(1, 2), versionParts("1.2"))
        assertEquals(listOf(1, 2, 3), versionParts("V1.2.3-beta.1"))
        assertEquals(listOf(2, 0, 0), versionParts("2.0.0+build.5"))
        assertEquals(listOf(0), versionParts("nightly"))
        assertEquals(listOf(0), versionParts(""))
        assertEquals(listOf(0), versionParts(null))
    }

    @Test
    fun `compares numerically not lexicographically`() {
        // 字符串比较会认为 "1.9" > "1.10"，这里必须是 1.10 更新。
        assertTrue(isNewerVersion("v1.10", "1.9"))
        assertFalse(isNewerVersion("v1.9", "1.10"))
    }

    @Test
    fun `treats missing segments as zero`() {
        assertFalse(isNewerVersion("1.0", "1.0.0"))
        assertFalse(isNewerVersion("v1.0.0", "1.0"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
    }

    @Test
    fun `never claims an update for unparsable or older tags`() {
        assertFalse(isNewerVersion("nightly", "1.0"))
        assertFalse(isNewerVersion("", "1.0"))
        assertFalse(isNewerVersion(null, "1.0"))
        assertFalse(isNewerVersion("0.9", "1.0"))
        assertFalse(isNewerVersion("1.0", "1.0"))
    }

    @Test
    fun `pre-release suffix of a newer version still counts`() {
        assertTrue(isNewerVersion("v1.1.0-rc1", "1.0"))
        assertFalse(isNewerVersion("v1.0.0-rc1", "1.0"))
    }

    @Test fun `rejects malformed numeric tags and unknown installed versions`() {
        listOf("2garbage", "2..1", "2.x", "1.2/other", "9999999999999999999").forEach {
            assertFalse(it, isNewerVersion(it, "1.0"))
        }
        assertFalse(isNewerVersion("2.0", "dev"))
    }

    @Test fun `stable release supersedes prerelease but build metadata does not`() {
        assertTrue(isNewerVersion("1.0.0", "1.0-rc1"))
        assertFalse(isNewerVersion("1.0-rc1", "1.0.0"))
        assertFalse(isNewerVersion("1.0+build.10", "1.0+build.1"))
    }
}
