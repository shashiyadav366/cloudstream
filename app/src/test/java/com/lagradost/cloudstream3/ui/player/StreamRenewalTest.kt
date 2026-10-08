package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
private fun renewalTestLink(
    url: String,
    name: String = "Hotstar",
    source: String = "TestProvider",
    headers: Map<String, String> = emptyMap(),
    referer: String = "",
    type: ExtractorLinkType = ExtractorLinkType.M3U8,
) = ExtractorLink(source, name, url, referer, 1080, headers, null, type)

private class FakeClock(@Volatile var time: Long = 0L) {
    fun advance(ms: Long) {
        time += ms
    }
}

class StreamLinkRenewalPickTest {

    @Test
    fun `picks a same-slot link with a new url`() {
        val current = renewalTestLink("https://a.example/1.m3u8?token=old")
        val fresh = renewalTestLink("https://a.example/1.m3u8?token=new")
        val other = renewalTestLink("https://b.example/other.m3u8", name = "Other")

        assertEquals(
            fresh,
            StreamLinkRenewal.pickFreshCandidate(current, listOf(other, fresh))
        )
    }

    @Test
    fun `rejects a duplicate even though it is another instance`() {
        // ExtractorLink uses identity equality; freshness must compare fields.
        val current = renewalTestLink("https://a.example/1.m3u8?token=old")
        val duplicate = renewalTestLink("https://a.example/1.m3u8?token=old")

        assertNull(StreamLinkRenewal.pickFreshCandidate(current, listOf(duplicate)))
    }

    @Test
    fun `rejects a different stream slot`() {
        val current = renewalTestLink("https://a.example/1.m3u8?token=old")
        val differentName = renewalTestLink(
            "https://a.example/1.m3u8?token=new",
            name = "Other Mirror",
        )

        assertNull(StreamLinkRenewal.pickFreshCandidate(current, listOf(differentName)))
    }

    @Test
    fun `accepts new headers with an unchanged url`() {
        val current = renewalTestLink(
            "https://a.example/1.m3u8",
            headers = mapOf("Authorization" to "old"),
        )
        val fresh = renewalTestLink(
            "https://a.example/1.m3u8",
            headers = mapOf("Authorization" to "new"),
        )

        assertEquals(fresh, StreamLinkRenewal.pickFreshCandidate(current, listOf(fresh)))
    }

    @Test
    fun `accepts a new referer with an unchanged url`() {
        val current = renewalTestLink("https://a.example/1.m3u8", referer = "")
        val fresh = renewalTestLink("https://a.example/1.m3u8", referer = "https://hotstar.com")

        assertEquals(fresh, StreamLinkRenewal.pickFreshCandidate(current, listOf(fresh)))
    }

    @Test
    fun `returns null when there is nothing to pick`() {
        val current = renewalTestLink("https://a.example/1.m3u8?token=old")
        assertNull(StreamLinkRenewal.pickFreshCandidate(current, emptyList()))
    }
}

class StreamRenewalBudgetTest {

    private fun budget(clock: FakeClock) = StreamRenewalBudget(now = { clock.time })

    @Test
    fun `allows at most two attempts per url and then refuses`() {
        val clock = FakeClock()
        val budget = budget(clock)

        assertTrue(budget.tryBeginAttempt("https://a.example/1.m3u8?token=old"))
        clock.advance(15_000)
        assertTrue(budget.tryBeginAttempt("https://a.example/1.m3u8?token=old"))
        clock.advance(15_000)
        assertFalse(budget.tryBeginAttempt("https://a.example/1.m3u8?token=old"))
    }

    @Test
    fun `refuses a second attempt inside the minimum interval`() {
        val clock = FakeClock()
        val budget = budget(clock)

        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(StreamRenewalBudget.MIN_ATTEMPT_INTERVAL_MS - 1)
        assertFalse(budget.tryBeginAttempt("url-2"))
    }

    @Test
    fun `refuses more than three attempts inside the rapid window regardless of url`() {
        val clock = FakeClock()
        val budget = budget(clock)

        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(10_000)
        assertTrue(budget.tryBeginAttempt("url-2"))
        clock.advance(10_000)
        assertTrue(budget.tryBeginAttempt("url-3"))
        clock.advance(10_000)
        // Fourth attempt while the first three are still inside the window
        assertFalse(budget.tryBeginAttempt("url-4"))

        // Once the window has passed, attempts are allowed again
        clock.advance(100_000)
        assertTrue(budget.tryBeginAttempt("url-5"))
    }

    @Test
    fun `recordRenewed starts a clean budget and lifts the interval guard`() {
        val clock = FakeClock()
        val budget = budget(clock)

        assertTrue(budget.tryBeginAttempt("url-1"))
        budget.recordRenewed("url-2")

        // Far sooner than the minimum interval, but the renewal succeeded
        clock.advance(2_000)
        assertTrue(budget.tryBeginAttempt("url-2"))
    }

    @Test
    fun `recordRenewed resets the per-url attempt count`() {
        val clock = FakeClock()
        val budget = budget(clock)

        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(15_000)
        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(15_000)
        assertFalse(budget.tryBeginAttempt("url-1"))

        // Renewal may keep the same url (fresh headers only), so only recordRenewed's
        // explicit reset can clear the per-url counter. The 100s gap lets the earlier
        // attempts age out of the rapid-failure window, which is kept on purpose.
        budget.recordRenewed("url-1")
        clock.advance(100_000)
        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(15_000)
        assertTrue(budget.tryBeginAttempt("url-1"))
        clock.advance(15_000)
        assertFalse(budget.tryBeginAttempt("url-1"))
    }

    @Test
    fun `a legitimate long-running stream can renew every ten minutes indefinitely`() {
        val clock = FakeClock()
        val budget = budget(clock)

        repeat(5) { cycle ->
            clock.advance(if (cycle == 0) 0 else 600_000)
            assertTrue("cycle $cycle must be allowed", budget.tryBeginAttempt("url-$cycle"))
            budget.recordRenewed("url-${cycle + 1}")
        }
    }
}
