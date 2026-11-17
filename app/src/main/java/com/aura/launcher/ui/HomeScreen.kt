package com.aura.launcher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.DockItem
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeActions(
    val openGeminiSheet: () -> Unit,
    val geminiLongPress: () -> Unit,
    val geminiVoice: () -> Unit,
    val openClock: () -> Unit,
    val openCalendar: () -> Unit,
    val onWeather: () -> Unit,
    val openGoogle: () -> Unit,
    val openSettings: () -> Unit,
    val launchDock: (DockItem) -> Unit,
)

@Composable
fun HomeScreen(
    repo: AppRepository,
    dock: List<DockItem>,
    weather: WeatherNow?,
    showWeather: Boolean,
    weatherNeedsPermission: Boolean,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    topLine: TopLineState = TopLineState.Default,
) {
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        AuraTopBar(repo, onGoogle = actions.openGoogle, topLine = topLine, editable = true)

        Spacer(Modifier.weight(0.75f))
        ClockBlock(
            weather = weather.takeIf { showWeather },
            weatherHint = if (showWeather && weather == null && weatherNeedsPermission) "Tap for local weather" else null,
            onClock = actions.openClock,
            onDate = actions.openCalendar,
            onWeather = actions.onWeather,
        )
        Spacer(Modifier.weight(3f))

        GeminiCard(
            onOpen = actions.openGeminiSheet,
            onLongPress = actions.geminiLongPress,
            onMic = actions.geminiVoice,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(36.dp))
        Dock(dock, repo, actions.launchDock, Modifier.padding(bottom = 18.dp))
    }
}

@Composable
private fun ClockBlock(
    weather: WeatherNow?,
    weatherHint: String?,
    onClock: () -> Unit,
    onDate: () -> Unit,
    onWeather: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val time = now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm"))
    val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")
    val date = now.format(DateTimeFormatter.ofPattern(datePattern))

    Column(Modifier.fillMaxWidth().padding(horizontal = 52.dp)) {
        Text(
            time,
            color = Aura.Ink,
            fontSize = 112.sp,
            lineHeight = 112.sp,
            fontFamily = Aura.Display,
            fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-2).sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open clock", onClick = onClock),
        )
        Text(
            date,
            color = Aura.Ink,
            fontSize = 34.sp,
            fontFamily = Aura.Sans,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open calendar", onClick = onDate),
        )
        if (weather != null || weatherHint != null) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.padding(start = 6.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onWeather).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (weather != null) {
                    WeatherGlyph(weather.sky, weather.isDay, size = 30.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("${weather.temperature}${weather.unit}", color = Aura.Ink, fontSize = 21.sp, fontFamily = Aura.Sans)
                    Text("  ·  ", color = Aura.InkDim, fontSize = 21.sp)
                    Text(weather.description, color = Aura.Ink, fontSize = 21.sp, fontFamily = Aura.Sans)
                } else if (weatherHint != null) {
                    WeatherGlyph(com.aura.launcher.data.Sky.PARTLY, true, size = 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(weatherHint, color = Aura.InkDim, fontSize = 16.sp, fontFamily = Aura.Sans)
                }
            }
        }
    }
}
