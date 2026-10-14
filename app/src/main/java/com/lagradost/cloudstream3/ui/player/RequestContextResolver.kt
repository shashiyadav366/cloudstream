package com.lagradost.cloudstream3.ui.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.lagradost.cloudstream3.BuildConfig
import okhttp3.OkHttpClient

const val STREAM_REQUEST_LOG_TAG = "StreamRequestCtx"

/**
 * Diagnostic logging for verifying the stream request-context lifecycle. Only enabled in
 * debug builds, so it never ships in release: R8 inlines [BuildConfig.DEBUG] and strips the
 * dead logging branches. Only hosts, query-less paths and header *names* are logged — never
 * header values, cookies, tokens or query strings.
 */
val STREAM_REQUEST_DEBUG_LOGS: Boolean get() = BuildConfig.DEBUG

/**
 * Resolver applied to every request the Media3 HLS lifecycle generates for one stream
 * (master/variant playlists, live refreshes, segments, keys, license requests), so the
 * stream-scoped [StreamRequestContext] survives playlist parsing, redirects and refreshes
 * instead of only being visible on the very first request.
 */
@OptIn(UnstableApi::class)
fun createStreamRequestResolver(context: StreamRequestContext): ResolvingDataSource.Resolver {
    return ResolvingDataSource.Resolver { dataSpec ->
        val resolved = context.applyTo(dataSpec)
        // The user-info scan is only needed to annotate the log line; skip it when
        // diagnostics are off so the hot per-request path stays allocation-free.
        if (STREAM_REQUEST_DEBUG_LOGS) {
            val userInfoMoved =
                StreamRequestContext.userInfoFrom(dataSpec.uri.toString()) != null
            logStreamRequest(resolved, context, userInfoMoved)
        }
        resolved
    }
}

@OptIn(UnstableApi::class)
private fun logStreamRequest(
    dataSpec: DataSpec,
    context: StreamRequestContext,
    userInfoMoved: Boolean,
) {
    if (!STREAM_REQUEST_DEBUG_LOGS) return
    try {
        val uri = dataSpec.uri
        val path = uri.path ?: ""
        val host = uri.host ?: "unknown"
        val kind = when {
            path.endsWith(".m3u8", ignoreCase = true) -> "playlist"
            path.endsWith(".mpd", ignoreCase = true) -> "manifest"
            path.endsWith(".key", ignoreCase = true) -> "key"
            path.endsWith(".ts", ignoreCase = true) || path.endsWith(".m4s", ignoreCase = true) -> "segment"
            path.endsWith(".mp4", ignoreCase = true) -> "file"
            else -> "other"
        }
        Log.d(
            STREAM_REQUEST_LOG_TAG,
            "request kind=$kind host=$host path=$path " +
                    "method=${dataSpec.httpMethodString} " +
                    "contextApplied=${context.headers.isNotEmpty()} " +
                    "contextHeaderNames=${context.headers.keys} " +
                    "userInfoMoved=$userInfoMoved"
        )
    } catch (t: Throwable) {
        Log.d(STREAM_REQUEST_LOG_TAG, "request logging failed: ${t.javaClass.simpleName}")
    }
}

/**
 * Debug-only OkHttp network interceptor: logs one line per actual network hop
 * (each redirect hop individually) with method, host, query-less path and HTTP status
 * code. This makes exactly which request starts returning 401/403 visible during
 * verification. Never logs headers, cookies or query strings.
 */
fun attachStreamHttpLogging(client: OkHttpClient): OkHttpClient {
    if (!STREAM_REQUEST_DEBUG_LOGS) return client
    return client.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            Log.d(
                STREAM_REQUEST_LOG_TAG,
                "http method=${request.method} host=${request.url.host} " +
                        "path=${request.url.encodedPath} code=${response.code}"
            )
            response
        }
        .build()
}
