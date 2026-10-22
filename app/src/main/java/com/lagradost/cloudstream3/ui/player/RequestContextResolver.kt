package com.lagradost.cloudstream3.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import okhttp3.OkHttpClient

const val STREAM_REQUEST_LOG_TAG = "StreamRequestCtx"

/**
 * Stream request diagnostics are disabled unconditionally: nothing about stream requests
 * (hosts, paths, header names, status codes) is collected, logged or persisted in either
 * debug or release builds. Kept as a constant so callers stay branch-free and R8 can prune
 * the associated code paths entirely.
 */
const val STREAM_REQUEST_DEBUG_LOGS: Boolean = false

/**
 * Resolver applied to every request the Media3 HLS lifecycle generates for one stream
 * (master/variant playlists, live refreshes, segments, keys, license requests), so the
 * stream-scoped [StreamRequestContext] survives playlist parsing, redirects and refreshes
 * instead of only being visible on the very first request.
 */
@OptIn(UnstableApi::class)
fun createStreamRequestResolver(context: StreamRequestContext): ResolvingDataSource.Resolver {
    return ResolvingDataSource.Resolver { dataSpec ->
        context.applyTo(dataSpec)
    }
}

/**
 * Stream HTTP diagnostics are disabled; this returns the client untouched so no network
 * hop metadata is ever collected or logged.
 */
fun attachStreamHttpLogging(client: OkHttpClient): OkHttpClient = client
