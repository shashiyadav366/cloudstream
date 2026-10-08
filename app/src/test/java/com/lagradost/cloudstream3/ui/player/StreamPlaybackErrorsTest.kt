package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.BehindLiveWindowException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

class StreamPlaybackErrorsTest {

    @Test
    fun `401 and 403 classify as authorization expired`() {
        assertEquals(
            StreamErrorKind.AuthorizationExpired,
            StreamPlaybackErrors.kindForStatus(401)
        )
        assertEquals(
            StreamErrorKind.AuthorizationExpired,
            StreamPlaybackErrors.kindForStatus(403)
        )
    }

    @Test
    fun `404 and 410 classify as not found`() {
        assertEquals(StreamErrorKind.NotFound, StreamPlaybackErrors.kindForStatus(404))
        assertEquals(StreamErrorKind.NotFound, StreamPlaybackErrors.kindForStatus(410))
    }

    @Test
    fun `408 and 429 classify as transient`() {
        assertEquals(StreamErrorKind.TransientNetwork, StreamPlaybackErrors.kindForStatus(408))
        assertEquals(StreamErrorKind.TransientNetwork, StreamPlaybackErrors.kindForStatus(429))
    }

    @Test
    fun `5xx classifies as server error`() {
        assertEquals(StreamErrorKind.ServerError, StreamPlaybackErrors.kindForStatus(500))
        assertEquals(StreamErrorKind.ServerError, StreamPlaybackErrors.kindForStatus(503))
    }

    @Test
    fun `other statuses classify as bad http status`() {
        assertEquals(StreamErrorKind.BadHttpStatus, StreamPlaybackErrors.kindForStatus(400))
        assertEquals(StreamErrorKind.BadHttpStatus, StreamPlaybackErrors.kindForStatus(418))
    }

    @Test
    fun `unknown host is transient even when wrapped`() {
        val error = RuntimeException("playlist load failed", UnknownHostException("cdn.example.com"))

        assertEquals(StreamErrorKind.TransientNetwork, StreamPlaybackErrors.classify(error))
    }

    @Test
    fun `socket timeout is transient`() {
        assertEquals(
            StreamErrorKind.TransientNetwork,
            StreamPlaybackErrors.classify(SocketTimeoutException("timeout"))
        )
    }

    @Test
    fun `cancellation is never retried`() {
        assertEquals(
            StreamErrorKind.Cancelled,
            StreamPlaybackErrors.classify(CancellationException("cancelled"))
        )
    }

    @Test
    fun `behind live window is recognized`() {
        assertEquals(
            StreamErrorKind.BehindLiveWindow,
            StreamPlaybackErrors.classify(BehindLiveWindowException())
        )
    }

    @Test
    fun `stuck playlist is recognized`() {
        // Uri.parse returns null under unit tests (returnDefaultValues); the exception
        // only stores the value, classification never dereferences it.
        val stuck = HlsPlaylistTracker.PlaylistStuckException(
            Uri.parse("https://example.com/playlist.m3u8")
        )

        assertEquals(StreamErrorKind.PlaylistStuck, StreamPlaybackErrors.classify(stuck))
    }

    @Test
    fun `parser failures classify as malformed content`() {
        val parserError = ParserException.createForMalformedContainer("bad playlist", IOException())

        assertEquals(
            StreamErrorKind.MalformedContent,
            StreamPlaybackErrors.classify(RuntimeException("wrapped", parserError))
        )
    }

    @Test
    fun `generic failures stay unknown so they never trigger re-resolution`() {
        assertEquals(StreamErrorKind.Unknown, StreamPlaybackErrors.classify(RuntimeException("boom")))
        assertEquals(StreamErrorKind.Unknown, StreamPlaybackErrors.classify(IOException("io")))
    }

    @Test
    fun `deep cause chains terminate`() {
        var error: Throwable = RuntimeException("level 0")
        repeat(100) { level -> error = RuntimeException("level ${level + 1}", error) }

        assertEquals(StreamErrorKind.Unknown, StreamPlaybackErrors.classify(error))
    }
}
