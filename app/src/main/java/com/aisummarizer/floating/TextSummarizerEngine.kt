package com.aisummarizer.floating

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

data class SummaryResult(
    val headline: String,
    val bullets: List<String>,
    val originalWordCount: Int,
    val summaryWordCount: Int,
    val timeSavedFormatted: String
)

object TextSummarizerEngine {

    private val STOPWORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are",
        "aren't", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both",
        "but", "by", "can", "can't", "cannot", "could", "couldn't", "did", "didn't", "do", "does",
        "doesn't", "doing", "don't", "down", "during", "each", "few", "for", "from", "further", "had",
        "hadn't", "has", "hasn't", "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her",
        "here", "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i", "i'd",
        "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's", "its", "itself",
        "let's", "me", "more", "most", "mustn't", "my", "myself", "no", "nor", "not", "of", "off",
        "on", "once", "only", "or", "other", "ought", "our", "ours", "ourselves", "out", "over", "own",
        "same", "shan't", "she", "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such",
        "than", "that", "that's", "the", "their", "theirs", "them", "themselves", "then", "there",
        "there's", "these", "they", "they'd", "they'll", "they're", "they've", "this", "those", "through",
        "to", "too", "under", "until", "up", "very", "was", "wasn't", "we", "we'd", "we'll", "we're",
        "we've", "were", "weren't", "what", "what's", "when", "when's", "where", "where's", "which",
        "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would", "wouldn't", "you",
        "you'd", "you'll", "you're", "you've", "your", "yours", "yourself", "yourselves"
    )

    private val UI_BOILERPLATE = setOf(
        "share", "like", "comment", "subscribe", "follow", "reply", "menu", "settings",
        "cancel", "submit", "login", "sign in", "sign up", "terms", "privacy", "home",
        "search", "notifications", "profile", "more", "download", "install", "open"
    )

    private val BULLET_ICONS = listOf("📌", "💡", "⚡", "📊", "🎯", "🔍", "✨", "🔑")

    /**
     * Extracts sentences, ranks their informational salience, and synthesizes key takeaways.
     */
    fun summarize(rawText: String, maxBullets: Int = 4): SummaryResult {
        if (rawText.isBlank()) {
            return SummaryResult(
                headline = "No readable text detected on screen",
                bullets = listOf("Ensure the screen contains readable articles, documents, chats, or web pages."),
                originalWordCount = 0,
                summaryWordCount = 0,
                timeSavedFormatted = "0 sec"
            )
        }

        // 1. Split raw text into candidate sentences
        val candidateSentences = rawText
            .split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { isValidSentence(it) }

        if (candidateSentences.isEmpty()) {
            return SummaryResult(
                headline = "Text too short to summarize",
                bullets = listOf(rawText.take(160)),
                originalWordCount = rawText.split("\\s+".toRegex()).size,
                summaryWordCount = rawText.split("\\s+".toRegex()).size,
                timeSavedFormatted = "0 sec"
            )
        }

        val totalWords = candidateSentences.sumOf { it.split("\\s+".toRegex()).size }

        // If the entire text is already short, present as direct bullet points
        if (candidateSentences.size <= maxBullets) {
            val bullets = candidateSentences.mapIndexed { idx, s ->
                val icon = BULLET_ICONS[idx % BULLET_ICONS.size]
                "$icon $s"
            }
            return SummaryResult(
                headline = candidateSentences.first().take(80),
                bullets = bullets,
                originalWordCount = totalWords,
                summaryWordCount = totalWords,
                timeSavedFormatted = "Quick Read"
            )
        }

        // 2. Build Keyword Frequency Distribution (TF-IDF approximation)
        val wordFreq = mutableMapOf<String, Int>()
        for (sentence in candidateSentences) {
            val words = extractKeywords(sentence)
            for (w in words) {
                wordFreq[w] = (wordFreq[w] ?: 0) + 1
            }
        }

        val maxFreq = wordFreq.values.maxOrNull()?.toDouble() ?: 1.0

        // 3. Score sentences based on Informational Density & Structural Significance
        val scoredSentences = candidateSentences.mapIndexed { index, sentence ->
            val words = extractKeywords(sentence)
            val wordCount = words.size

            // Keyword salience score
            var score = 0.0
            for (w in words) {
                val freq = wordFreq[w] ?: 0
                score += (freq / maxFreq)
            }

            // Normalization by sentence length (avoid runaway scores for long sentences)
            if (wordCount > 0) {
                score /= wordCount.toDouble()
            }

            // Bonus for position: Opening paragraphs usually state main thesis
            val positionBonus = if (index == 0) 1.6 else if (index < 3) 1.3 else 1.0
            score *= positionBonus

            // Bonus for sentences with metrics, percentages, or numbers
            if (sentence.contains(Regex("\\d+%|\\$\\d+|\\d+\\s*(million|billion|thousand|users|growth|profit|percent)"))) {
                score *= 1.4
            }

            Triple(index, sentence, score)
        }

        // 4. Select top K sentences
        val topSentences = scoredSentences
            .sortedByDescending { it.third }
            .take(maxBullets)
            .sortedBy { it.first } // Re-sort chronologically for narrative flow

        val bullets = topSentences.mapIndexed { idx, item ->
            val icon = BULLET_ICONS[idx % BULLET_ICONS.size]
            "$icon ${item.second.trim()}"
        }

        val summaryWords = topSentences.sumOf { it.second.split("\\s+".toRegex()).size }
        val wordsSaved = max(0, totalWords - summaryWords)
        // Average reading speed: 220 words per minute (~3.6 words/sec)
        val secondsSaved = (wordsSaved / 3.6).toInt()
        val timeSavedFormatted = if (secondsSaved >= 60) {
            val mins = secondsSaved / 60
            val secs = secondsSaved % 60
            "Saved ~$mins min $secs sec read"
        } else {
            "Saved ~$secondsSaved sec read"
        }

        val headline = topSentences.firstOrNull()?.second?.take(90) ?: "Summary Highlights"

        return SummaryResult(
            headline = headline,
            bullets = bullets,
            originalWordCount = totalWords,
            summaryWordCount = summaryWords,
            timeSavedFormatted = timeSavedFormatted
        )
    }

    private fun isValidSentence(s: String): Boolean {
        if (s.length < 20 || s.length > 500) return false
        val words = s.split("\\s+".toRegex())
        if (words.size < 4) return false

        // Filter out single UI buttons / navigation links
        val lower = s.lowercase(Locale.ROOT).trim()
        if (UI_BOILERPLATE.contains(lower)) return false
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false

        return true
    }

    private fun extractKeywords(sentence: String): List<String> {
        return sentence.lowercase(Locale.ROOT)
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && !STOPWORDS.contains(it) }
    }
}
