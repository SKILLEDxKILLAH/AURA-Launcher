package com.aura.launcher.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.NewsService
import com.aura.launcher.data.NewsStory
import com.aura.launcher.data.NewsTopic
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class FeedState(
    val topic: NewsTopic,
    val stories: List<NewsStory>,
    val loading: Boolean,
    val error: String?,
    val weather: WeatherNow?,
    val weatherNeedsPermission: Boolean,
)

data class FeedActions(
    val selectTopic: (NewsTopic) -> Unit,
    val refresh: () -> Unit,
    val openStory: (NewsStory) -> Unit,
    val briefMe: () -> Unit,
    val onWeather: () -> Unit,
)

/** AURA's news feed, swiped in from the left like the Pixel launcher's feed. */
@Composable
fun FeedScreen(state: FeedState, actions: FeedActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Aura.Surface)) {
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { FeedHeader(actions.refresh, state.loading) }
            item(key = "weather") { WeatherCard(state.weather, state.weatherNeedsPermission, actions.onWeather) }
            item(key = "brief") { BriefButton(enabled = state.stories.isNotEmpty(), onClick = actions.briefMe) }
            item(key = "topics") { TopicTabs(state.topic, actions.selectTopic) }

            when {
                state.stories.isEmpty() && state.loading -> item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Aura.Accent, strokeWidth = 2.dp)
                    }
                }
                state.stories.isEmpty() && state.error != null -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(18.dp)) {
                        Text(state.error, color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans)
                        Text(
                            "Try again", color = Aura.Accent, fontSize = 15.sp,
                            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = actions.refresh).padding(6.dp),
                        )
                    }
                }
            }

            itemsIndexed(state.stories, key = { _, s -> s.link }) { index, story ->
                if (index == 0) LeadStory(story) { actions.openStory(story) } else StoryRow(story) { actions.openStory(story) }
            }

            if (state.stories.isNotEmpty()) item(key = "footer") {
                val sources = state.stories.map { it.source }.distinct()
                Text(
                    if (sources.size in 1..6) "Stories from " + sources.joinToString(", ") else "Headlines from Google News",
                    color = Aura.InkFaint, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FeedHeader(onRefresh: () -> Unit, loading: Boolean) {
    val now = LocalDateTime.now()
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    // Follows the phone's language, including changes while AURA is open.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val date = now.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting, color = Aura.Ink, fontSize = 30.sp, fontFamily = Aura.Display, fontWeight = FontWeight.Light)
            Text(date, color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans)
        }
        Text(
            if (loading) "Updating…" else "Refresh", color = Aura.Accent, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !loading, onClick = onRefresh).padding(10.dp),
        )
    }
}

@Composable
private fun WeatherCard(weather: WeatherNow?, needsPermission: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Open weather", onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (weather != null) {
            WeatherGlyph(weather.sky, weather.isDay, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${weather.temperature}${weather.unit}", color = Aura.Ink, fontSize = 28.sp, fontFamily = Aura.Sans)
                Text(weather.description, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans)
            }
            if (weather.high != null && weather.low != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("H ${weather.high}°", color = Aura.Ink, fontSize = 14.sp, fontFamily = Aura.Sans)
                    Text("L ${weather.low}°", color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans)
                }
            }
        } else {
            WeatherGlyph(com.aura.launcher.data.Sky.PARTLY, true, size = 34.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                if (needsPermission) "Tap to show your local weather" else "Getting the weather…",
                color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans,
            )
        }
    }
}

@Composable
private fun BriefButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().geminiGlow(22.dp, blur = 10.dp, alpha = if (enabled) 0.35f else 0.1f)
            .glass(RoundedCornerShape(22.dp), fill = Aura.GlassDark, stroke = Aura.GeminiViolet.copy(alpha = 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Sparkle, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Brief me", color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
            Text("Gemini sums up these headlines", color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans)
        }
    }
}

@Composable
private fun TopicTabs(current: NewsTopic, onSelect: (NewsTopic) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(NewsTopic.entries) { t ->
            val selected = t == current
            Text(
                t.label,
                color = if (selected) Color.White else Aura.Ink,
                fontSize = 14.sp, fontFamily = Aura.Sans,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .then(if (selected) Modifier.background(Aura.geminiFill) else Modifier.glass(RoundedCornerShape(18.dp)))
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}


@Composable
private fun LeadStory(story: NewsStory, onClick: () -> Unit) =
    if (story.imageUrl != null) PictureCard(story, imageHeight = 260.dp, titleSize = 22, onClick = onClick)
    else TextCard(story, titleSize = 21, onClick = onClick)

@Composable
private fun StoryRow(story: NewsStory, onClick: () -> Unit) =
    if (story.imageUrl != null) PictureCard(story, imageHeight = 190.dp, titleSize = 18, onClick = onClick)
    else TextCard(story, titleSize = 16, onClick = onClick)

/**
 * Banner card: the article's photo across the top, fading down into the card, with the source
 * and headline on the fade and a short description underneath.
 */
@Composable
private fun PictureCard(story: NewsStory, imageHeight: Dp, titleSize: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    // Solid card color the photo fades into, so text sits on a clean background.
    val CardColor = Aura.Card
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(CardColor).border(1.dp, Aura.Stroke, shape).clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(imageHeight)) {
            NetImage(story.imageUrl!!, targetWidthPx = widthPx, contentDescription = null, fallbackUrl = story.imageFallback)
            // Fade the photo into the card: clear at the top, solid behind the headline.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to CardColor.copy(alpha = 0.15f),
                        0.7f to CardColor.copy(alpha = 0.85f),
                        1f to CardColor,
                    )
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 2.dp)) {
                SourceLine(story)
                Spacer(Modifier.height(6.dp))
                Text(
                    story.title, color = Aura.Ink, fontSize = titleSize.sp, lineHeight = (titleSize * 1.28f).sp,
                    fontFamily = Aura.Sans, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        story.summary?.let {
            Text(
                it, color = Aura.InkDim, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Aura.Sans,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TextCard(story: NewsStory, titleSize: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), fill = Aura.Glass).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        SourceLine(story)
        Spacer(Modifier.height(6.dp))
        Text(story.title, color = Aura.Ink, fontSize = titleSize.sp, lineHeight = (titleSize * 1.35f).sp, fontFamily = Aura.Sans, maxLines = 3, overflow = TextOverflow.Ellipsis)
        story.summary?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Aura.InkDim, fontSize = 14.sp, lineHeight = 19.sp, fontFamily = Aura.Sans, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SourceLine(story: NewsStory) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NetIcon(NewsService.iconUrl(story.sourceUrl), 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            story.source.ifBlank { "News" }, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        val age = NewsService.age(story.published)
        if (age.isNotEmpty()) Text("  ·  $age", color = Aura.InkFaint, fontSize = 13.sp, fontFamily = Aura.Sans)
    }
}

private object IconCache {
    val cache = LruCache<String, ImageBitmap>(200)

    suspend fun load(url: String): ImageBitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
            try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

/** Small round publisher icon; a neutral dot until (or unless) it loads. */
@Composable
private fun NetIcon(url: String?, size: Dp) {
    val bmp by produceState(url?.let { IconCache.cache.get(it) }, url) { if (url != null && value == null) value = IconCache.load(url) }
    Box(Modifier.size(size).clip(CircleShape).background(Aura.GlassStrong)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
