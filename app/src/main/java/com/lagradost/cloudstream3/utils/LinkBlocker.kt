package com.lagradost.cloudstream3.utils

import android.net.Uri
import android.content.Intent
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import java.util.Locale

/**
 * Central blocker for external links that plugins try to open in the browser.
 *
 * User entries are stored in the app datastore as a single string, one entry per line,
 * with a maximum of [MAX_USER_BLOCKED_LINKS] entries. An entry may be a bare domain
 * ("example.com"), a URL ("https://tracking.example.com"), and matching is case-insensitive
 * and includes subdomains.
 */
object LinkBlocker {
    const val MAX_USER_BLOCKED_LINKS = 5
    const val BLOCKED_LINKS_KEY = "blocked_external_links"

    fun parseUserBlockedLinks(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_USER_BLOCKED_LINKS)
    }

    fun getActiveUserBlockedLinks(): List<String> {
        // Preferred path: DataStore value saved through the app (JSON-encoded string).
        var links = parseUserBlockedLinks(CloudStreamApp.getKey<String>(BLOCKED_LINKS_KEY, ""))
        if (links.isEmpty()) {
            // Fallback: backups restored with setKeyRaw (or hand-edited backups) store the
            // value raw instead of JSON-encoded, which parseJson<String> rejects.
            links = parseUserBlockedLinks(
                CloudStreamApp.context
                    ?.getSharedPrefs()
                    ?.getString(BLOCKED_LINKS_KEY, null)
                    ?.trim()
                    ?.removeSurrounding("\"")
            )
        }
        return links
    }

    fun isBlocked(intent: Intent?): Boolean {
        return intent?.dataString?.let { isBlocked(it) } ?: false
    }

    fun saveUserBlockedLinks(links: List<String>) {
        CloudStreamApp.setKey(BLOCKED_LINKS_KEY, parseUserBlockedLinks(links.joinToString("\n")).joinToString("\n"))
    }

    fun isBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            Uri.parse(url.trim()).host
        } catch (_: Exception) {
            null
        } ?: return false
        val lowerHost = host.lowercase(Locale.ROOT)

        return getActiveUserBlockedLinks().any { entry -> matches(lowerHost, entry) }
    }

    private fun matches(lowerHost: String, entry: String): Boolean {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return false
        val lowerEntry = trimmed.lowercase(Locale.ROOT)
        val entryHost = try {
            Uri.parse(lowerEntry).host
        } catch (_: Exception) {
            null
        }
        return if (entryHost != null) {
            lowerHost == entryHost || lowerHost.endsWith(".$entryHost")
        } else {
            val domain = lowerEntry
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
            if (domain.isEmpty()) return false
            lowerHost == domain || lowerHost.endsWith(".$domain")
        }
    }
}
