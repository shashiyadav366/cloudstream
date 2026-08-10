package com.lagradost.cloudstream3.network

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.Security

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}


// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
    
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 4)
    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
            }
        }.apply {
            applyProxy(context, settingsManager)
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

/**
 * Routes all app traffic through an HTTP or SOCKS5 proxy when enabled in settings.
 * Applied to the base client, so it covers extension requests, image loading and
 * video playback (which all build on this client).
 */
private fun OkHttpClient.Builder.applyProxy(context: Context, settingsManager: SharedPreferences) {
    val enabled = settingsManager.getBoolean(context.getString(R.string.proxy_enabled_key), false)
    if (!enabled) return

    val host = settingsManager.getString(context.getString(R.string.proxy_host_key), null)?.trim()
    val port = settingsManager.getString(context.getString(R.string.proxy_port_key), null)?.toIntOrNull()
    if (host.isNullOrEmpty() || port == null || port < 1 || port > 65535) return

    val isSocks =
        settingsManager.getString(context.getString(R.string.proxy_type_key), "HTTP") == "SOCKS5"
    proxy(Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(host, port)))

    if (!isSocks) {
        val username = settingsManager.getString(context.getString(R.string.proxy_username_key), null)?.trim()
        val password = settingsManager.getString(context.getString(R.string.proxy_password_key), null)
        if (!username.isNullOrEmpty()) {
            val credentials = Credentials.basic(username, password.orEmpty())
            proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", credentials)
                    .build()
            }
        }
    }
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}