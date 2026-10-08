package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.lagradost.cloudstream3.utils.ExtractorLink
import okio.ByteString.Companion.encodeUtf8

/**
 * Immutable, stream-scoped HTTP request context for every request the Media3 HLS
 * lifecycle makes for one playing stream: master playlist, variant playlists, live
 * refreshes, media segments, encryption keys and (through the same factory) DRM
 * license requests.
 *
 * The context is created once per stream in `CS3IPlayer.createVideoSource` and is only
 * referenced by that stream's `ResolvingDataSource` wrapper, so it is released together
 * with the player/source factory — it is never global and never outlives the stream.
 *
 * Header precedence is deterministic (highest wins), comparison is case-insensitive:
 *  1. headers already present on the [DataSpec] (set by the playlist parser or upstream)
 *  2. this context (provider-supplied headers from [ExtractorLink] + its referer)
 *  3. factory defaults (`OkHttpDataSource` default request properties / user agent)
 *
 * The context only ever carries headers the provider put on the link — no header is
 * invented, injected globally, or persisted anywhere.
 */
data class StreamRequestContext(
    val uri: String,
    val headers: Map<String, String>,
    val userAgent: String?,
    val referer: String?,
    val origin: String?,
    val cookies: String?,
) {
    /**
     * Merges [perRequestHeaders] (highest precedence) over this context's headers.
     * The override value wins per header name, the first-seen key spelling is kept so a
     * header is never sent twice under different casings.
     */
    fun mergedRequestHeaders(perRequestHeaders: Map<String, String>): Map<String, String> =
        mergeHeaders(base = headers, override = perRequestHeaders)

    /**
     * Applies this context to [dataSpec] without clobbering headers already set on it.
     * Additionally performs the reference player's per-request credential rewrite: URI
     * user-info (`scheme://user:pass@host/...`) is moved to an `Authorization: Basic`
     * header and stripped from the URI. Returns the same instance when nothing changes.
     */
    fun applyTo(dataSpec: DataSpec): DataSpec {
        val existing = dataSpec.httpRequestHeaders
        val merged = mergedRequestHeaders(existing)
        val rawUri = dataSpec.uri?.toString()
        val userInfo = rawUri?.let { userInfoFrom(it) }
        if (userInfo != null) {
            val (cleanedUri, withAuth) = moveUserInfoToBasicAuth(rawUri, userInfo, merged)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(cleanedUri))
                .setHttpRequestHeaders(withAuth)
                .build()
        }
        if (merged == existing) return dataSpec
        return dataSpec.buildUpon().setHttpRequestHeaders(merged).build()
    }

    companion object {
        /**
         * Matches scheme-qualified URLs inside free text (e.g. exception messages) so
         * signed query strings never end up in logs.
         */
        private val URL_IN_TEXT =
            Regex("""(?:https?|rtmps?|wss?)://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

        /**
         * Builds the context for one stream from the provider's [link]. Same semantics as
         * [ExtractorLink.getAllHeaders]: the provider's headers win and the referer is only
         * added when the provider did not set one itself.
         */
        fun from(link: ExtractorLink): StreamRequestContext {
            val headers = link.getAllHeaders()
            return StreamRequestContext(
                uri = link.url,
                headers = headers,
                userAgent = findHeader(headers, "User-Agent"),
                referer = findHeader(headers, "Referer"),
                origin = findHeader(headers, "Origin"),
                cookies = findHeader(headers, "Cookie"),
            )
        }

        /** Case-insensitive merge; values in [override] win over [base]. */
        fun mergeHeaders(
            base: Map<String, String>,
            override: Map<String, String>,
        ): Map<String, String> {
            if (base.isEmpty()) return override
            if (override.isEmpty()) return base

            val merged = LinkedHashMap<String, String>(base.size + override.size)
            val canonicalKeyByLowercase = HashMap<String, String>(base.size + override.size)

            for ((key, value) in base) {
                merged[key] = value
                canonicalKeyByLowercase[key.lowercase()] = key
            }
            for ((key, value) in override) {
                val canonical = canonicalKeyByLowercase[key.lowercase()]
                if (canonical != null) {
                    merged[canonical] = value
                } else {
                    merged[key] = value
                    canonicalKeyByLowercase[key.lowercase()] = key
                }
            }
            return merged
        }

        fun findHeader(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /**
         * User-info (credentials) embedded in a URI authority:
         * `scheme://user:pass@host/path`. Returns the raw user-info, or null when the
         * URI carries none — or an empty one (`scheme://@host`), which the reference
         * player also leaves untouched. Only the authority component is scanned, so
         * `@` in a path or query never matches. Pure string logic, unit-testable.
         */
        internal fun userInfoFrom(rawUri: String): String? {
            val schemeEnd = rawUri.indexOf("://")
            if (schemeEnd < 0) return null
            val authorityStart = schemeEnd + 3
            if (authorityStart >= rawUri.length) return null
            var authorityEnd = rawUri.length
            for (i in authorityStart until rawUri.length) {
                val c = rawUri[i]
                if (c == '/' || c == '?' || c == '#') {
                    authorityEnd = i
                    break
                }
            }
            if (authorityEnd <= authorityStart) return null
            val authority = rawUri.substring(authorityStart, authorityEnd)
            val at = authority.lastIndexOf('@')
            if (at <= 0) return null
            return authority.substring(0, at)
        }

        /**
         * Reference player's per-request credential rewrite ("Enigma2 intercept"):
         * strips `user:pass@` from [rawUri] and sets
         * `Authorization: Basic base64(user:pass)` in the request headers. The header
         * value overrides any existing Authorization (the reference player overwrites it
         * unconditionally as well), case-insensitively, keeping the first-seen key
         * spelling so exactly one Authorization entry is sent. Pure, unit-testable.
         */
        internal fun moveUserInfoToBasicAuth(
            rawUri: String,
            userInfo: String,
            headers: Map<String, String>,
        ): Pair<String, Map<String, String>> {
            val cleanedUri = rawUri.replaceFirst("$userInfo@", "")
            val encoded = userInfo.encodeUtf8().base64()
            val withAuth = mergeHeaders(
                base = headers,
                override = mapOf("Authorization" to "Basic $encoded"),
            )
            return cleanedUri to withAuth
        }

        /** Host + path of [rawUri] with the query string removed — safe to log. */
        fun safeTarget(rawUri: String): String = rawUri.substringBefore('?')

        /** Replaces URLs inside free text with `<url>` so tokens stay out of logs. */
        fun redactUrls(text: String?): String? = text?.replace(URL_IN_TEXT, "<url>")
    }
}
