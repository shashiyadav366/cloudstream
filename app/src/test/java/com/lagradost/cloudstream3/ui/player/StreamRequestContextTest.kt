package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
private fun testLink(
    url: String = "https://cdn.example.com/live/master.m3u8?token=secret",
    referer: String = "",
    headers: Map<String, String> = emptyMap(),
    name: String = "Hotstar",
    source: String = "TestProvider",
    type: ExtractorLinkType = ExtractorLinkType.M3U8,
) = ExtractorLink(source, name, url, referer, 1080, headers, null, type)

class StreamRequestContextTest {

    @Test
    fun `adds the link referer when the provider did not set one`() {
        val context = StreamRequestContext.from(testLink(referer = "https://hotstar.com/in"))

        assertEquals("https://hotstar.com/in", context.referer)
        assertEquals("https://hotstar.com/in", context.headers["referer"])
    }

    @Test
    fun `keeps the provider referer header without duplicating it`() {
        val context = StreamRequestContext.from(
            testLink(
                referer = "https://ignored.example/",
                headers = mapOf("Referer" to "https://provider.example/"),
            )
        )

        assertEquals(
            mapOf("Referer" to "https://provider.example/"),
            context.headers
        )
        assertEquals(1, context.headers.keys.count { it.equals("referer", ignoreCase = true) })
    }

    @Test
    fun `does not add a referer when the link has none`() {
        val context = StreamRequestContext.from(testLink())

        assertNull(context.referer)
        assertFalse(context.headers.keys.any { it.equals("referer", ignoreCase = true) })
    }

    @Test
    fun `extracts user agent origin and cookie from provider headers`() {
        val context = StreamRequestContext.from(
            testLink(
                headers = mapOf(
                    "User-Agent" to "HotstarApp/1.0",
                    "Origin" to "https://www.hotstar.com",
                    "Cookie" to "session=abc",
                )
            )
        )

        assertEquals("HotstarApp/1.0", context.userAgent)
        assertEquals("https://www.hotstar.com", context.origin)
        assertEquals("session=abc", context.cookies)
    }

    @Test
    fun `merge is case-insensitive and the override wins exactly once`() {
        val merged = StreamRequestContext.mergeHeaders(
            base = mapOf("Authorization" to "Bearer old"),
            override = mapOf("authorization" to "Bearer new"),
        )

        assertEquals(mapOf("Authorization" to "Bearer new"), merged)
        assertEquals(1, merged.keys.size)
    }

    @Test
    fun `per-request headers win over the context and missing ones are kept`() {
        val context = StreamRequestContext.from(
            testLink(headers = mapOf("Authorization" to "Bearer context"))
        )

        val merged = context.mergedRequestHeaders(
            mapOf("Authorization" to "Bearer request", "Range" to "bytes=0-")
        )

        assertEquals("Bearer request", merged["Authorization"])
        assertEquals("bytes=0-", merged["Range"])

        val untouched = context.mergedRequestHeaders(emptyMap())
        assertEquals("Bearer context", untouched["Authorization"])
    }

    @Test
    fun `merge falls back to whichever side is empty`() {
        val context = StreamRequestContext.from(testLink())

        assertEquals(
            mapOf("a" to "1"),
            StreamRequestContext.mergeHeaders(emptyMap(), mapOf("a" to "1"))
        )
        assertEquals(
            mapOf("a" to "1"),
            StreamRequestContext.mergeHeaders(mapOf("a" to "1"), emptyMap())
        )
    }

    @Test
    fun `safeTarget strips the query string`() {
        assertEquals(
            "https://cdn.example.com/live/master.m3u8",
            StreamRequestContext.safeTarget("https://cdn.example.com/live/master.m3u8?sig=abc")
        )
        assertEquals(
            "https://cdn.example.com/live/master.m3u8",
            StreamRequestContext.safeTarget("https://cdn.example.com/live/master.m3u8")
        )
    }

    @Test
    fun `redactUrls removes urls with their query strings from free text`() {
        val message =
            "failed loading https://cdn.example.com/v2/master.m3u8?token=secret at 12s"
        assertEquals(
            "failed loading <url> at 12s",
            StreamRequestContext.redactUrls(message)
        )
        assertNull(StreamRequestContext.redactUrls(null))
        assertEquals("plain text", StreamRequestContext.redactUrls("plain text"))
    }

    @Test
    fun `userInfoFrom extracts credentials from the uri authority only`() {
        assertEquals("user:pass", StreamRequestContext.userInfoFrom("http://user:pass@host:8080/api"))
        assertEquals("user", StreamRequestContext.userInfoFrom("rtsp://user@host/stream"))
        assertEquals("user:pass", StreamRequestContext.userInfoFrom("http://user:pass@host?x=1"))
        assertEquals("user", StreamRequestContext.userInfoFrom("https://user@host#frag"))

        assertNull(StreamRequestContext.userInfoFrom("https://cdn.example.com/master.m3u8"))
        assertNull(StreamRequestContext.userInfoFrom("https://host/path@notuserinfo"))
        assertNull(StreamRequestContext.userInfoFrom("https://host/a?b=c@d"))
        assertNull(StreamRequestContext.userInfoFrom("https://host/#a@b"))
        assertNull(StreamRequestContext.userInfoFrom("https://@host/")) // empty user-info
        assertNull(StreamRequestContext.userInfoFrom("not a url"))
        assertNull(StreamRequestContext.userInfoFrom(""))
    }

    @Test
    fun `moveUserInfoToBasicAuth strips the credentials and adds a Basic header`() {
        val (uri, headers) = StreamRequestContext.moveUserInfoToBasicAuth(
            rawUri = "http://user:pass@receiver.local:8080/api",
            userInfo = "user:pass",
            headers = emptyMap(),
        )

        assertEquals("http://receiver.local:8080/api", uri)
        assertEquals(mapOf("Authorization" to "Basic dXNlcjpwYXNz"), headers)
    }

    @Test
    fun `moveUserInfoToBasicAuth removes only the first occurrence and keeps other headers`() {
        val (uri, headers) = StreamRequestContext.moveUserInfoToBasicAuth(
            rawUri = "http://user:pass@host/user:pass@tail",
            userInfo = "user:pass",
            headers = mapOf("Cookie" to "session=abc"),
        )

        assertEquals("http://host/user:pass@tail", uri)
        assertEquals("session=abc", headers["Cookie"])
        assertEquals("Basic dXNlcjpwYXNz", headers["Authorization"])
    }

    @Test
    fun `moveUserInfoToBasicAuth overrides an existing Authorization keeping key spelling`() {
        val (uri, headers) = StreamRequestContext.moveUserInfoToBasicAuth(
            rawUri = "http://admin:s3cr3t@receiver.local/api",
            userInfo = "admin:s3cr3t",
            headers = mapOf("authorization" to "Bearer provider-token", "Accept" to "*/*"),
        )

        assertEquals("http://receiver.local/api", uri)
        assertEquals(mapOf("authorization" to "Basic YWRtaW46czNjcjN0", "Accept" to "*/*"), headers)
        assertEquals(1, headers.keys.count { it.equals("authorization", ignoreCase = true) })
    }
}
