package com.aura.launcher

import com.aura.launcher.data.NewsService
import com.aura.launcher.data.NewsTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NewsServiceTest {
    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>Top stories - Google News</title>
          <image><title>Google News</title></image>
          <item>
            <title>City opens new riverside park - Example Times</title>
            <link>https://news.google.com/rss/articles/abc</link>
            <pubDate>Tue, 17 Nov 2026 22:08:36 GMT</pubDate>
            <source url="https://www.example.com">Example Times</source>
          </item>
          <item>
            <title>Headline without a suffix</title>
            <link>https://news.google.com/rss/articles/def</link>
            <pubDate>not a date</pubDate>
            <source url="https://news.example.org">Example News</source>
          </item>
          <item><title></title><link></link></item>
        </channel></rss>"""

    @Test
    fun parsesStoriesAndStripsSourceSuffix() {
        val stories = NewsService.parse(sample.byteInputStream())
        assertEquals(2, stories.size)
        assertEquals("City opens new riverside park", stories[0].title)
        assertEquals("Example Times", stories[0].source)
        assertEquals("https://www.example.com", stories[0].sourceUrl)
        assertEquals(Instant.parse("2026-11-17T22:08:36Z"), stories[0].published)
        assertEquals("Headline without a suffix", stories[1].title)
        assertNull(stories[1].published)
    }

    @Test
    fun feedUrlsFollowTheDeviceLocale() {
        assertEquals("https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", NewsService.feedUrl(NewsTopic.TOP, Locale.US))
        assertEquals(
            "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=de-DE&gl=DE&ceid=DE:de",
            NewsService.feedUrl(NewsTopic.TECHNOLOGY, Locale.GERMANY),
        )
    }

    @Test
    fun agesAreCompact() {
        val now = Instant.parse("2026-11-17T22:00:00Z")
        assertEquals("now", NewsService.age(now, now))
        assertEquals("5m", NewsService.age(now.minusSeconds(300), now))
        assertEquals("3h", NewsService.age(now.minusSeconds(3 * 3600), now))
        assertEquals("2d", NewsService.age(now.minusSeconds(2 * 86400), now))
        assertTrue(NewsService.iconUrl("https://www.example.com")!!.contains("domain=www.example.com"))
    }
}
