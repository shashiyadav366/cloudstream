package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalAPI::class)
class BlockedLinksTest {
    @Test
    fun roundtrip() {
        val input = "https://omg10.com/"
        val stored = input.toJsonLiteral()
        val read = parseJson<String>(stored)
        assertEquals(input, read)
    }

    @Test
    fun rawValueIsRejectedByJsonParser() {
        // A value stored raw (JSON-unquoted) fails parseJson<String> and yields null.
        // LinkBlocker compensates by falling back to the raw stored string.
        val raw = "https://omg10.com/"
        val read = runCatching { parseJson<String>(raw) }.getOrNull()
        assertEquals(null, read)
    }
}