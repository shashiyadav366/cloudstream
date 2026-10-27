package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoPopupClassifierTest {

    private fun isPromo(vararg texts: String): Boolean {
        return PromoPopupClassifier.classify(texts.toList()).isPromotional
    }

    @Test
    fun eachKeywordAloneIsEnough() {
        assertTrue(isPromo("Telegram"))
        assertTrue(isPromo("Discord"))
        assertTrue(isPromo("Donate"))
        assertTrue(isPromo("Support"))
        assertTrue(isPromo("Community"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(isPromo("TELEGRAM"))
        assertTrue(isPromo("discord"))
        assertTrue(isPromo("DoNaTe"))
        assertTrue(isPromo("SuPpOrT"))
    }

    @Test
    fun realPopupTextsAreSuppressed() {
        assertTrue(
            isPromo(
                "Welcome to AllForU!",
                "Join our Telegram for updates & support!",
                "Join Telegram"
            )
        )
        assertTrue(isPromo("Support the project", "Donate to keep the servers running."))
        assertTrue(isPromo("Join the community", "Discord"))
    }

    @Test
    fun unsupportedIsNotTreatedAsSupport() {
        assertFalse(isPromo("Playback error", "Unsupported video format."))
    }

    @Test
    fun ordinaryTextWithoutKeywordIsLeftVisible() {
        assertFalse(isPromo("Select source", "Server 1", "1080p"))
        assertFalse(isPromo("Playback error", "Connection failed"))
    }

    @Test
    fun emptyDialogIsLeftVisible() {
        assertFalse(PromoPopupClassifier.classify(emptyList()).isPromotional)
        assertFalse(isPromo("", "   ", "\n"))
    }
}
