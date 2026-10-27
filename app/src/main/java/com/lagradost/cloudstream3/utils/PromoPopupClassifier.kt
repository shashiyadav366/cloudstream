package com.lagradost.cloudstream3.utils

/**
 * Simple text classifier used by [PromoPopupFilter].
 *
 * Any window that contains one of the words below is treated as promotional:
 * Telegram, Discord, Donate, Support or Community. Matching is case-insensitive and only whole
 * words are matched, so text like "unsupported" does not count as "support".
 */
object PromoPopupClassifier {

    enum class Keyword {
        TELEGRAM,
        DISCORD,
        DONATE,
        SUPPORT,
        COMMUNITY,
    }

    data class Decision(
        val isPromotional: Boolean,
        val score: Int,
        val reasons: List<String>,
    )

    private val keywordPatterns: Map<Keyword, Regex> = linkedMapOf(
        Keyword.TELEGRAM to Regex("\\btelegram\\b", RegexOption.IGNORE_CASE),
        Keyword.DISCORD to Regex("\\bdiscord\\b", RegexOption.IGNORE_CASE),
        Keyword.DONATE to Regex("\\bdonat(?:e|es|ed|ing|ion|ions)\\b", RegexOption.IGNORE_CASE),
        Keyword.SUPPORT to Regex("\\bsupport(?:s|ed|ing)?\\b", RegexOption.IGNORE_CASE),
        Keyword.COMMUNITY to Regex("\\bcommunit(?:y|ies)\\b", RegexOption.IGNORE_CASE),
    )

    private fun normalize(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * @param texts title, message, button labels and any other visible text collected from the
     * window in any order.
     */
    fun classify(texts: Iterable<String?>): Decision {
        val normalized = texts.asSequence()
            .mapNotNull(::normalize)
            .distinct()
            .take(200)
            .toList()

        if (normalized.isEmpty()) {
            return Decision(false, 0, listOf("no visible text"))
        }

        val allText = normalized.joinToString("\n")
        val found = keywordPatterns.filterValues { it.containsMatchIn(allText) }.keys

        if (found.isEmpty()) {
            return Decision(false, 0, listOf("no promotional keyword"))
        }

        return Decision(
            isPromotional = true,
            score = found.size,
            reasons = found.map { "keyword: ${it.name}" },
        )
    }
}
