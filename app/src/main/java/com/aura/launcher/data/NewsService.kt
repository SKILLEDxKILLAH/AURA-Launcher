package com.aura.launcher.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NewsStory(
    val title: String,
    val source: String,
    val sourceUrl: String?,
    val link: String,
    val published: Instant?,
)

/** Sections of the feed. [section] is Google News' topic id; null = Top stories. */
enum class NewsTopic(val label: String, val section: String?) {
    TOP("Top stories", null),
    WORLD("World", "WORLD"),
    NATION("Local & national", "NATION"),
    BUSINESS("Business", "BUSINESS"),
    TECHNOLOGY("Technology", "TECHNOLOGY"),
    SCIENCE("Science", "SCIENCE"),
    HEALTH("Health", "HEALTH"),
    SPORTS("Sports", "SPORTS"),
    ENTERTAINMENT("Entertainment", "ENTERTAINMENT"),
}

/** Headlines from Google News' public RSS feeds, localized to the device's language and region. */
object NewsService {

    fun feedUrl(topic: NewsTopic, locale: Locale = Locale.getDefault()): String {
        val country = locale.country.ifBlank { "US" }
        val language = locale.language.ifBlank { "en" }
        val hl = "$language-$country"
        val query = "hl=$hl&gl=$country&ceid=$country:$language"
        return if (topic.section == null) "https://news.google.com/rss?$query"
        else "https://news.google.com/rss/headlines/section/topic/${topic.section}?$query"
    }

    suspend fun fetch(topic: NewsTopic): List<NewsStory> = withContext(Dispatchers.IO) {
        val conn = (URL(feedUrl(topic)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "AURA-Launcher")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("News is unavailable right now (${conn.responseCode})")
            conn.inputStream.use { parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Parses a Google News RSS document. */
    fun parse(input: InputStream): List<NewsStory> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val stories = mutableListOf<NewsStory>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var source = ""; var sourceUrl: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> { inItem = true; title = ""; link = ""; date = ""; source = ""; sourceUrl = null }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "link" -> if (inItem) link = parser.nextText().trim()
                    "pubDate" -> if (inItem) date = parser.nextText().trim()
                    "source" -> if (inItem) {
                        sourceUrl = parser.getAttributeValue(null, "url")
                        source = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    inItem = false
                    if (title.isNotBlank() && link.isNotBlank()) {
                        // Google appends " - Source" to every headline; the source is shown separately.
                        val clean = if (source.isNotBlank() && title.endsWith(" - $source")) title.removeSuffix(" - $source") else title
                        stories += NewsStory(clean, source, sourceUrl, link, parseDate(date))
                    }
                }
            }
            event = parser.next()
        }
        return stories
    }

    private fun parseDate(s: String): Instant? =
        runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()

    /** "5m", "3h", "2d" — compact age like the Pixel feed. */
    fun age(published: Instant?, now: Instant = Instant.now()): String {
        published ?: return ""
        val minutes = (now.epochSecond - published.epochSecond) / 60
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }

    /** Small site icon for a story's publisher. */
    fun iconUrl(sourceUrl: String?): String? {
        val host = sourceUrl?.let { runCatching { URL(it).host }.getOrNull() } ?: return null
        return "https://www.google.com/s2/favicons?domain=$host&sz=64"
    }
}
