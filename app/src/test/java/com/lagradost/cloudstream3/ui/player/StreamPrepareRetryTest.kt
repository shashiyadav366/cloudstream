package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPrepareRetryTest {

    private class FakeClock(var time: Long = 100_000L) {
        fun advance(ms: Long) {
            time += ms
        }
    }

    @Test
    fun `never retries before playback was healthy once`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(now = { clock.time })

        assertFalse(policy.shouldRetry())
        policy.recordRetry() // would be a no-op gate-wise; must not open the gate
        assertFalse(policy.shouldRetry())
        assertEquals(1, policy.attempts)
    }

    @Test
    fun `first retry after healthy playback needs no backoff beyond the base delay`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(now = { clock.time })

        policy.markPlaybackHealthy()
        assertTrue(policy.shouldRetry())
        assertEquals(1, policy.recordRetry())
        assertEquals(1, policy.attempts)
    }

    @Test
    fun `backoff window doubles per attempt`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(now = { clock.time })
        policy.markPlaybackHealthy()

        // attempt 1 recorded at t=100_000; next needs 5000 * 2^1 = 10_000
        policy.recordRetry()
        clock.advance(9_999)
        assertFalse(policy.shouldRetry())
        clock.advance(1)
        assertTrue(policy.shouldRetry())

        // attempt 2 recorded at t=110_000; next needs 5000 * 2^2 = 20_000
        assertEquals(2, policy.recordRetry())
        clock.advance(19_999)
        assertFalse(policy.shouldRetry())
        clock.advance(1)
        assertTrue(policy.shouldRetry())

        // attempt 3 recorded at t=130_000; next needs 5000 * 2^3 = 40_000
        assertEquals(3, policy.recordRetry())
        clock.advance(39_999)
        assertFalse(policy.shouldRetry())
        clock.advance(1)
        assertTrue(policy.shouldRetry())
    }

    @Test
    fun `gives up after fifteen attempts no matter how much time passes`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(now = { clock.time })
        policy.markPlaybackHealthy()

        repeat(PrepareRetryPolicy.MAX_RETRIES) {
            assertTrue(policy.shouldRetry())
            policy.recordRetry()
            clock.advance(1_000_000_000L)
        }

        assertEquals(PrepareRetryPolicy.MAX_RETRIES, policy.attempts)
        assertFalse(policy.shouldRetry())
        clock.advance(10_000_000L)
        assertFalse(policy.shouldRetry())
    }

    @Test
    fun `healthy playback resets the attempt count but keeps the backoff anchor`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(now = { clock.time })
        policy.markPlaybackHealthy()

        policy.recordRetry() // attempt 1 at t=100_000
        policy.recordRetry() // attempt 2 at t=100_000
        assertEquals(2, policy.attempts)

        // Recovery happens much later: the old anchor is stale, so retrying is allowed.
        clock.advance(60_000)
        policy.markPlaybackHealthy()
        assertEquals(0, policy.attempts)
        assertTrue(policy.shouldRetry())

        // Recovery immediately after a retry (no time passing): the anchor still gates.
        policy.recordRetry() // t=160_000
        policy.markPlaybackHealthy()
        clock.advance(4_999)
        assertFalse(policy.shouldRetry())
        clock.advance(1)
        assertTrue(policy.shouldRetry())
    }

    @Test
    fun `custom limits are honored`() {
        val clock = FakeClock()
        val policy = PrepareRetryPolicy(maxRetries = 2, baseDelayMs = 1000.0, now = { clock.time })
        policy.markPlaybackHealthy()

        assertTrue(policy.shouldRetry())
        policy.recordRetry()
        clock.advance(1_999)
        assertFalse(policy.shouldRetry())
        clock.advance(1)
        assertTrue(policy.shouldRetry())
        policy.recordRetry()
        clock.advance(60_000)
        assertFalse(policy.shouldRetry())
    }
}
