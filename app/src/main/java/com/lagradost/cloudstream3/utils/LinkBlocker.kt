package com.lagradost.cloudstream3.utils

import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp
import java.util.Locale

/**
 * Central blocker for external links that plugins try to open in the browser.
 *
 * Hardcoded domains (such as the omg10.com ad-redirect embedded in some plugins) are always
 * blocked and never count toward the user-configurable list.
 *
 * User entries are stored in the app datastore as a single string, one entry per line,
 * with a maximum of [MAX_USER_BLOCKED_LINKS] entries. An entry may be a bare domain
 * ("example.com"), a URL ("https://tracking.example.com"), and matching is case-insensitive
 * and includes subdomains.
 */
object LinkBlocker {
    const val MAX_USER_BLOCKED_LINKS = 5
    const val BLOCKED_LINKS_KEY = "blocked_external_links"

    // Hardcoded domains that are always blocked (including subdomains).
    private val hardcodedBlockedDomains = listOf("omg10.com")

    fun parseUserBlockedLinks(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_USER_BLOCKED_LINKS)
    }

    fun getActiveUserBlockedLinks(): List<String> {
        return parseUserBlockedLinks(CloudStreamApp.getKey<String>(BLOCKED_LINKS_KEY, ""))
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

        if (hardcodedBlockedDomains.any { lowerHost == it || lowerHost.endsWith(".$it") }) {
            return true
        }

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
