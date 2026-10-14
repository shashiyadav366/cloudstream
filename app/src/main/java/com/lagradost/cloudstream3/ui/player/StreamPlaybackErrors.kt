package com.lagradost.cloudstream3.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Why a stream request failed, at the granularity the player needs. Only
 * [AuthorizationExpired] may trigger legitimate re-resolution from the provider —
 * everything else maps onto the pre-existing error handling paths unchanged.
 */
enum class StreamErrorKind {
    /** 401/403 (or an explicit authentication-expired code): the authorization on this
     * stream URL ran out, a fresh link must be requested from the provider. */
    AuthorizationExpired,

    /** 404/410: the resource is gone; re-resolving is not warranted automatically. */
    NotFound,

    /** DNS/connect/timeouts and 408/429: transient network conditions. */
    TransientNetwork,

    /** 5xx: server-side failure, Media3 already retried before we see this. */
    ServerError,

    /** Other 4xx/3xx: caller or content problem, surface as-is. */
    BadHttpStatus,

    /** The HLS playlist stopped refreshing. */
    PlaylistStuck,

    /** The live window moved past the current position. */
    BehindLiveWindow,

    /** Unparseable playlist/container content. */
    MalformedContent,

    /** The load was cancelled (player released, new link loading, ...). */
    Cancelled,

    /** Unclassified — never treated as an authorization problem. */
    Unknown,
}

object StreamPlaybackErrors {
    private const val MAX_CAUSE_DEPTH = 16

    /** Maps an HTTP status to a [StreamErrorKind]; 401/403 mean the authorization expired. */
    fun kindForStatus(statusCode: Int): StreamErrorKind = when {
        statusCode == 401 || statusCode == 403 -> StreamErrorKind.AuthorizationExpired
        statusCode == 404 || statusCode == 410 -> StreamErrorKind.NotFound
        statusCode == 408 || statusCode == 429 -> StreamErrorKind.TransientNetwork
        statusCode in 500..599 -> StreamErrorKind.ServerError
        else -> StreamErrorKind.BadHttpStatus
    }

    /** First HTTP status code found in the cause chain, if any (safe to log). */
    @OptIn(UnstableApi::class)
    fun httpStatus(error: Throwable): Int? =
        causeChain(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode

    /**
     * Walks the cause chain (bounded to [MAX_CAUSE_DEPTH] levels, never follows cycles)
     * and classifies the failure. Structural live errors and cancellation are recognized
     * first, then the HTTP status, then network/parser causes, then playback error codes.
     */
    @OptIn(UnstableApi::class)
    fun classify(error: Throwable): StreamErrorKind {
        val chain = causeChain(error)

        for (cause in chain) {
            if (cause is CancellationException) return StreamErrorKind.Cancelled
            if (cause is HlsPlaylistTracker.PlaylistStuckException) return StreamErrorKind.PlaylistStuck
            if (cause is BehindLiveWindowException) return StreamErrorKind.BehindLiveWindow
        }

        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.let { return kindForStatus(it.responseCode) }

        if (chain.any {
                it is UnknownHostException || it is ConnectException ||
                        it is SocketTimeoutException || it is NoRouteToHostException ||
                        it is InterruptedIOException || it is SSLException
            }
        ) return StreamErrorKind.TransientNetwork

        if (error is PlaybackException) when (error.errorCode) {
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED -> return StreamErrorKind.AuthorizationExpired
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> return StreamErrorKind.BehindLiveWindow
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> return StreamErrorKind.TransientNetwork
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> return StreamErrorKind.MalformedContent
        }

        if (chain.any { it is ParserException }) return StreamErrorKind.MalformedContent

        return StreamErrorKind.Unknown
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            chain.add(current)
            val next = current.cause
            if (next === current) break
            current = next
            depth++
        }
        return chain
    }
}
