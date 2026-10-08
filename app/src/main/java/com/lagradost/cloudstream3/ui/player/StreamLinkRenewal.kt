package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Pure decision helpers for the legitimate re-resolution of an expired authorized stream.
 * [ExtractorLink] uses identity equality, so freshness has to be compared on fields.
 */
object StreamLinkRenewal {
    /** Same logical stream slot (provider, display name, kind) in the link list. */
    fun matchesIdentity(current: ExtractorLink, candidate: ExtractorLink): Boolean =
        candidate.source == current.source &&
                candidate.name == current.name &&
                candidate.type == current.type

    /** The candidate carries a genuinely new URL or new request headers. */
    fun isFresher(current: ExtractorLink, candidate: ExtractorLink): Boolean =
        candidate.url != current.url ||
                candidate.headers != current.headers ||
                candidate.referer != current.referer

    /** First re-resolved link that occupies the same slot with new credentials, if any. */
    fun pickFreshCandidate(
        current: ExtractorLink,
        candidates: List<ExtractorLink>,
    ): ExtractorLink? = candidates.firstOrNull {
        matchesIdentity(current, it) && isFresher(current, it)
    }
}

/**
 * Bounds how often automatic re-resolution may start, so a misbehaving provider can
 * never cause an error/renewal loop:
 *  - at most [maxAttemptsPerUrl] attempts per expired URL,
 *  - at most [maxRapidFailures] attempts inside [rapidFailureWindowMs] (global hard bound,
 *    also covers providers that hand out a different URL on every attempt),
 *  - at least [minAttemptIntervalMs] between consecutive attempts.
 *
 * A successful renewal ([recordRenewed]) starts a fresh per-URL budget and lifts the
 * interval guard, while past failure timestamps are kept — a stream that keeps dying
 * quickly still hits the rapid-failure bound.
 *
 * All time handling goes through [now] so tests can drive a fake clock.
 */
class StreamRenewalBudget(
    private val maxAttemptsPerUrl: Int = MAX_ATTEMPTS_PER_URL,
    private val rapidFailureWindowMs: Long = RAPID_FAILURE_WINDOW_MS,
    private val maxRapidFailures: Int = MAX_RAPID_FAILURES,
    private val minAttemptIntervalMs: Long = MIN_ATTEMPT_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var targetUrl: String? = null
    private var attemptsForUrl = 0
    private var lastAttemptAt: Long? = null
    private val recentAttempts = ArrayDeque<Long>()

    /**
     * Returns true if one more automatic re-resolution may start for [url], recording the
     * attempt. False means the original playback error must be surfaced to the user.
     */
    fun tryBeginAttempt(url: String): Boolean {
        val time = now()
        prune(time)

        if (targetUrl != url) {
            targetUrl = url
            attemptsForUrl = 0
        }

        if (attemptsForUrl >= maxAttemptsPerUrl) return false
        val last = lastAttemptAt
        if (last != null && time - last < minAttemptIntervalMs) return false
        if (recentAttempts.size >= maxRapidFailures) return false

        attemptsForUrl++
        lastAttemptAt = time
        recentAttempts.addLast(time)
        return true
    }

    /** A renewal succeeded: clean per-URL budget for [newUrl], failure debounce lifted. */
    fun recordRenewed(newUrl: String) {
        targetUrl = newUrl
        attemptsForUrl = 0
        lastAttemptAt = null
    }

    private fun prune(time: Long) {
        while (recentAttempts.isNotEmpty() && time - recentAttempts.first() > rapidFailureWindowMs) {
            recentAttempts.removeFirst()
        }
    }

    companion object {
        const val MAX_ATTEMPTS_PER_URL = 2
        const val RAPID_FAILURE_WINDOW_MS = 90_000L
        const val MAX_RAPID_FAILURES = 3
        const val MIN_ATTEMPT_INTERVAL_MS = 10_000L
    }
}
