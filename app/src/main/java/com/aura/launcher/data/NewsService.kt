package com.aura.launcher.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** One or two sentences from the publisher, when the feed provides them. */
    val summary: String? = null,
    /** Lead image for the story, when the feed provides one. */
    val imageUrl: String? = null,
    /** The feed's original image, used if [imageUrl] (a lighter or sharper crop) isn't available. */
    val imageFallback: String? = null,
)

/** A publisher's official RSS/Atom feed. */
data class PublisherFeed(val publisher: String, val homepage: String, val url: String)

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

/**
 * News for the feed. For English-language phones it merges publishers' official RSS feeds,
 * which include a lead image and summary for each story. Elsewhere, or if those fail, it falls
 * back to Google News' public RSS (headlines only).
 */
object NewsService {

    private const val BBC = "https://feeds.bbci.co.uk/news"
    private const val NYT = "https://rss.nytimes.com/services/xml/rss/nyt"
    private const val GUARDIAN = "https://www.theguardian.com"

    private fun bbc(path: String) = PublisherFeed("BBC News", "https://www.bbc.com", "$BBC/$path")
    private fun nyt(section: String) = PublisherFeed("The New York Times", "https://www.nytimes.com", "$NYT/$section.xml")
    private fun guardian(path: String) = PublisherFeed("The Guardian", "https://www.theguardian.com", "$GUARDIAN/$path/rss")
    private val ars = PublisherFeed("Ars Technica", "https://arstechnica.com", "https://feeds.arstechnica.com/arstechnica/index")
    private val verge = PublisherFeed("The Verge", "https://www.theverge.com", "https://www.theverge.com/rss/index.xml")

    /** Publisher feeds for a topic, or empty when the phone's language isn't English. */
    fun publisherFeeds(topic: NewsTopic, locale: Locale = Locale.getDefault()): List<PublisherFeed> {
        if (locale.language != "en") return emptyList()
        val uk = locale.country == "GB"
        return when (topic) {
            NewsTopic.TOP -> listOf(bbc("rss.xml"), nyt("HomePage"), guardian(if (uk) "uk-news" else "us-news"))
            NewsTopic.WORLD -> listOf(bbc("world/rss.xml"), nyt("World"), guardian("world"))
            NewsTopic.NATION -> if (uk) listOf(bbc("uk/rss.xml"), guardian("uk-news"))
                else listOf(bbc("world/us_and_canada/rss.xml"), nyt("US"), guardian("us-news"))
            NewsTopic.BUSINESS -> listOf(bbc("business/rss.xml"), nyt("Business"), guardian("us/business"))
            NewsTopic.TECHNOLOGY -> listOf(bbc("technology/rss.xml"), nyt("Technology"), guardian("us/technology"), ars, verge)
            NewsTopic.SCIENCE -> listOf(bbc("science_and_environment/rss.xml"), nyt("Science"), guardian("science"))
            NewsTopic.HEALTH -> listOf(bbc("health/rss.xml"), nyt("Health"), guardian("society"))
            NewsTopic.SPORTS -> listOf(PublisherFeed("BBC Sport", "https://www.bbc.com", "https://feeds.bbci.co.uk/sport/rss.xml"), guardian("us/sport"))
            NewsTopic.ENTERTAINMENT -> listOf(bbc("entertainment_and_arts/rss.xml"), nyt("Arts"), guardian("us/culture"))
        }
    }

    fun feedUrl(topic: NewsTopic, locale: Locale = Locale.getDefault()): String {
        val country = locale.country.ifBlank { "US" }
        val language = locale.language.ifBlank { "en" }
        val hl = "$language-$country"
        val query = "hl=$hl&gl=$country&ceid=$country:$language"
        return if (topic.section == null) "https://news.google.com/rss?$query"
        else "https://news.google.com/rss/headlines/section/topic/${topic.section}?$query"
    }

    /** Picture stories from publisher feeds, merged newest first; Google News headlines as a fallback. */
    suspend fun fetch(topic: NewsTopic): List<NewsStory> {
        val feeds = publisherFeeds(topic)
        if (feeds.isNotEmpty()) {
            val stories = coroutineScope {
                feeds.map { f -> async(Dispatchers.IO) { runCatching { fetchFeed(f) }.getOrDefault(emptyList()) } }.awaitAll()
            }
            val merged = merge(stories)
            if (merged.isNotEmpty()) return merged
        }
        return fetchGoogleNews(topic)
    }

    /** Interleaves publishers newest first, drops duplicates, and leads with a story that has a picture. */
    fun merge(perFeed: List<List<NewsStory>>, limit: Int = 60): List<NewsStory> {
        val seen = HashSet<String>()
        val all = perFeed.flatten()
            .sortedByDescending { it.published ?: Instant.EPOCH }
            .filter { seen.add(it.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(60)) }
            .take(limit)
        val lead = all.indexOfFirst { it.imageUrl != null }
        if (lead < 0) return all
        val top = all[lead].let { s -> s.copy(imageUrl = leadImage(s.imageUrl!!), imageFallback = s.imageFallback ?: s.imageUrl) }
        return listOf(top) + all.filterIndexed { i, _ -> i != lead }
    }

    private fun fetchFeed(feed: PublisherFeed): List<NewsStory> {
        val conn = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AURA-Launcher")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            return conn.inputStream.use { parseFeed(it, feed) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parses an RSS 2.0 or Atom feed: title, link, date, a plain-text summary, and the best image
     * (largest media:content, media:thumbnail, image enclosure, or the first <img> in the body).
     */
    fun parseFeed(input: InputStream, feed: PublisherFeed): List<NewsStory> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val stories = mutableListOf<NewsStory>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var summary = ""; var body = ""
        var image: String? = null; var imageWidth = -1
        fun offer(url: String?, width: Int) {
            if (url.isNullOrBlank()) return
            if (image == null || width > imageWidth) { image = url; imageWidth = width }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "item", "entry" -> {
                        inItem = true; title = ""; link = ""; date = ""; summary = ""; body = ""; image = null; imageWidth = -1
                    }
                    else -> if (inItem) when (name) {
                        "title" -> title = parser.nextText().trim()
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            if (href != null) { if (link.isEmpty() && (rel == null || rel == "alternate")) link = href }
                            else link = parser.nextText().trim()
                        }
                        "pubDate", "updated", "published", "dc:date" -> if (date.isEmpty()) date = parser.nextText().trim()
                        "description", "summary" -> summary = parser.nextText()
                        "content:encoded", "content" -> body = parser.nextText()
                        "media:content", "media:thumbnail" -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val medium = parser.getAttributeValue(null, "medium").orEmpty()
                            if (type.isEmpty() || type.startsWith("image") || medium == "image") {
                                offer(parser.getAttributeValue(null, "url"), parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0)
                            }
                        }
                        "enclosure" -> if (parser.getAttributeValue(null, "type").orEmpty().startsWith("image")) {
                            offer(parser.getAttributeValue(null, "url"), 0)
                        }
                    }
                }
                XmlPullParser.END_TAG -> if ((parser.name == "item" || parser.name == "entry") && inItem) {
                    inItem = false
                    val cleanTitle = cleanText(title)
                    if (cleanTitle.isNotBlank() && link.isNotBlank()) {
                        val img = image ?: firstImg(body) ?: firstImg(summary)
                        stories += NewsStory(
                            title = cleanTitle,
                            source = feed.publisher,
                            sourceUrl = feed.homepage,
                            link = link,
                            published = parseAnyDate(date),
                            summary = summarize(summary.ifBlank { body }),
                            imageUrl = img?.let(::upgradeImage),
                            imageFallback = img?.replace("&amp;", "&")?.takeIf { it != img.let(::upgradeImage) },
                        )
                    }
                }
            }
            event = parser.next()
        }
        return stories
    }

    /** BBC lists 240px thumbnails; its image service serves the same picture at 976px. */
    fun upgradeImage(url: String): String =
        url.replace("&amp;", "&")
            .replace("ichef.bbci.co.uk/ace/standard/240/", "ichef.bbci.co.uk/ace/standard/976/")
            // NYT lists a 1800px square (~650 KB); its 600px landscape crop (~85 KB) suits a card.
            .replace(NYT_SQUARE, "-articleLarge.jpg")

    /** A sharper crop for the big top story. */
    fun leadImage(url: String): String = url.replace("-articleLarge.jpg", "-threeByTwoMediumAt2X.jpg")

    private val NYT_SQUARE = Regex("""-mediumSquareAt3X\.jpg$""")

    private val imgSrc = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private fun firstImg(html: String): String? = imgSrc.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

    /** Plain text from a feed's HTML summary, trimmed to about two sentences. */
    fun summarize(html: String): String? {
        val text = cleanText(html.replace(Regex("(?i)</(p|li|div)>"), " ").replace(Regex("<[^>]+>"), " "))
        if (text.length < 20) return null
        if (text.length <= 220) return text
        val cut = text.take(220)
        val sentence = cut.lastIndexOf(". ")
        return if (sentence > 80) cut.take(sentence + 1) else cut.substringBeforeLast(' ') + "…"
    }

    private fun cleanText(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            .replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun parseAnyDate(s: String): Instant? =
        parseDate(s) ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()

    private suspend fun fetchGoogleNews(topic: NewsTopic): List<NewsStory> = withContext(Dispatchers.IO) {
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
