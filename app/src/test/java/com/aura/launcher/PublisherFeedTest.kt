package com.aura.launcher

import com.aura.launcher.data.NewsService
import com.aura.launcher.data.NewsStory
import com.aura.launcher.data.NewsTopic
import com.aura.launcher.data.PublisherFeed
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
class PublisherFeedTest {
    private val feed = PublisherFeed("Example", "https://example.com", "https://example.com/rss")

    @Test
    fun rssWithSmallThumbnailIsUpgraded() {
        val xml = """<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item>
            <title><![CDATA[Park opens by the river]]></title>
            <description><![CDATA[The city opened a new riverside park on Tuesday.]]></description>
            <link>https://www.bbc.co.uk/news/articles/abc?at_medium=RSS&amp;at_campaign=rss</link>
            <pubDate>Tue, 17 Nov 2026 22:51:15 GMT</pubDate>
            <media:thumbnail width="240" height="135" url="https://ichef.bbci.co.uk/ace/standard/240/cpsprodpb/x.jpg"/>
          </item></channel></rss>"""
        val s = NewsService.parseFeed(xml.byteInputStream(), feed).single()
        assertEquals("Park opens by the river", s.title)
        assertEquals("The city opened a new riverside park on Tuesday.", s.summary)
        assertEquals("https://ichef.bbci.co.uk/ace/standard/976/cpsprodpb/x.jpg", s.imageUrl)
        assertEquals("https://www.bbc.co.uk/news/articles/abc?at_medium=RSS&at_campaign=rss", s.link)
        assertEquals(Instant.parse("2026-11-17T22:51:15Z"), s.published)
    }

    @Test
    fun picksTheLargestImageAndStripsHtmlSummaries() {
        val xml = """<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item>
            <title>Player arrested at airport</title>
            <description>&lt;ul&gt;&lt;li&gt;&lt;p&gt;Suspect named&lt;/p&gt;&lt;/li&gt;&lt;li&gt;&lt;p&gt;Charge carries a long sentence&lt;/p&gt;&lt;/li&gt;&lt;/ul&gt;</description>
            <link>https://www.theguardian.com/sport/x</link>
            <pubDate>Mon, 16 Nov 2026 13:16:36 GMT</pubDate>
            <media:content width="140" url="https://i.guim.co.uk/a.jpg?width=140&amp;s=1"/>
            <media:content width="700" url="https://i.guim.co.uk/a.jpg?width=700&amp;s=3"/>
            <media:content width="460" url="https://i.guim.co.uk/a.jpg?width=460&amp;s=2"/>
          </item></channel></rss>"""
        val s = NewsService.parseFeed(xml.byteInputStream(), feed).single()
        assertEquals("https://i.guim.co.uk/a.jpg?width=700&s=3", s.imageUrl)
        assertEquals("Suspect named Charge carries a long sentence", s.summary)
    }

    @Test
    fun atomEntriesWithImageInTheBody() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title type="html"><![CDATA[New phone launches]]></title>
            <link rel="alternate" type="text/html" href="https://www.theverge.com/x/123"/>
            <updated>2026-11-17T17:42:33-05:00</updated>
            <summary type="html"><![CDATA[It has a bigger battery and a brighter screen than last year's model.]]></summary>
            <content type="html"><![CDATA[<figure><img alt="A phone" src="https://platform.theverge.com/wp-content/uploads/phone.jpg?quality=90&amp;strip=all" /></figure><p>Body</p>]]></content>
          </entry></feed>"""
        val s = NewsService.parseFeed(xml.byteInputStream(), feed).single()
        assertEquals("https://www.theverge.com/x/123", s.link)
        assertEquals("https://platform.theverge.com/wp-content/uploads/phone.jpg?quality=90&strip=all", s.imageUrl)
        assertEquals(Instant.parse("2026-11-17T22:42:33Z"), s.published)
        assertTrue(s.summary!!.startsWith("It has a bigger battery"))
    }

    @Test
    fun longSummariesAreTrimmedAtASentence() {
        val long = "First sentence is here and is long enough to matter for the reader. " +
            "Second sentence adds more context about the story and why it matters today. " +
            "Third sentence goes on and on with extra detail that the card does not need to show."
        val out = NewsService.summarize(long)!!
        assertTrue(out.endsWith("today."))
        assertTrue(out.length <= 220)
        assertNull(NewsService.summarize("<p>Hi</p>"))
    }

    @Test
    fun mergeIsNewestFirstDedupedAndLeadsWithAPicture() {
        fun st(title: String, mins: Long, img: Boolean) = NewsStory(
            title, "S", null, "https://x/$title", Instant.parse("2026-11-17T22:00:00Z").minusSeconds(mins * 60),
            imageUrl = if (img) "https://img/$title" else null,
        )
        val merged = NewsService.merge(listOf(
            listOf(st("Text only newest", 1, false), st("Shared story", 30, true)),
            listOf(st("Picture story", 10, true), st("Shared Story!", 31, true)),
        ))
        assertEquals(listOf("Picture story", "Text only newest", "Shared story"), merged.map { it.title })
    }

    @Test
    fun englishPhonesGetPublisherFeedsOthersFallBack() {
        assertTrue(NewsService.publisherFeeds(NewsTopic.TOP, Locale.US).isNotEmpty())
        assertTrue(NewsService.publisherFeeds(NewsTopic.NATION, Locale.UK).any { it.url.contains("/uk/") })
        assertTrue(NewsService.publisherFeeds(NewsTopic.TECHNOLOGY, Locale.GERMANY).isEmpty())
    }

    @Test
    fun nytSquareBecomesALighterLandscapeCropWithFallback() {
        val xml = """<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item>
            <title>Story</title><link>https://www.nytimes.com/x.html</link>
            <pubDate>Wed, 18 Nov 2026 00:06:19 +0000</pubDate>
            <media:content height="1800" medium="image" url="https://static01.nyt.com/images/2026/11/17/a/17x-mediumSquareAt3X.jpg" width="1800"/>
          </item></channel></rss>"""
        val s = NewsService.parseFeed(xml.byteInputStream(), feed).single()
        assertEquals("https://static01.nyt.com/images/2026/11/17/a/17x-articleLarge.jpg", s.imageUrl)
        assertEquals("https://static01.nyt.com/images/2026/11/17/a/17x-mediumSquareAt3X.jpg", s.imageFallback)
        val lead = NewsService.merge(listOf(listOf(s))).first()
        assertEquals("https://static01.nyt.com/images/2026/11/17/a/17x-threeByTwoMediumAt2X.jpg", lead.imageUrl)
        assertEquals("https://static01.nyt.com/images/2026/11/17/a/17x-mediumSquareAt3X.jpg", lead.imageFallback)
    }
}
