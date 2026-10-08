package com.lagradost.cloudstream3.ui.player

/**
 * Exponential-backoff `prepare()` retry budget, mirroring the reference player's first
 * recovery tier: on a playback error, the current media (same URL, same headers) may be
 * re-prepared while all three gates hold:
 *  - the player has reached a healthy ready+playing state at least once ("frame gate") —
 *    a stream that never started is surfaced immediately instead of blindly reloaded,
 *  - fewer than [maxRetries] retries were made since the last healthy state,
 *  - at least `baseDelayMs * 2^attempts` elapsed since the previous retry
 *    (5s, 10s, 20s, ... — the first retry after a healthy state waits nothing if the
 *    last retry is already older than the base delay).
 *
 * A successful recovery is reported through [markPlaybackHealthy], which re-opens the
 * frame gate and resets the attempt count while keeping the last-retry timestamp, so a
 * stream that keeps dying quickly still observes the backoff window.
 *
 * The reference player sits underneath Media3's own load-retry policy (each `prepare()`
 * already hides several internal load retries), so errors reaching this budget arrive
 * spaced out rather than hot-looping.
 *
 * All time handling goes through [now] so tests can drive a fake clock.
 */
class PrepareRetryPolicy(
    private val maxRetries: Int = MAX_RETRIES,
    private val baseDelayMs: Double = BASE_DELAY_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var attemptCount = 0
    private var lastRetryTime = 0L
    private var playbackHealthy = false

    /** Playback reached ready+playing: the frame gate opens and the attempt count resets. */
    fun markPlaybackHealthy() {
        playbackHealthy = true
        attemptCount = 0
    }

    /** Whether a failing player may re-prepare the current media right now. */
    fun shouldRetry(): Boolean {
        if (!playbackHealthy) return false
        if (attemptCount >= maxRetries) return false
        val elapsedMs = now() - lastRetryTime
        return elapsedMs >= Math.pow(2.0, attemptCount.toDouble()) * baseDelayMs
    }

    /** Records a retry attempt and returns its 1-based number for logging. */
    fun recordRetry(): Int {
        lastRetryTime = now()
        attemptCount++
        return attemptCount
    }

    /** Retries made since the last healthy state — exposed for logging and tests. */
    val attempts: Int get() = attemptCount

    companion object {
        const val MAX_RETRIES = 15
        const val BASE_DELAY_MS = 5000.0
    }
}
