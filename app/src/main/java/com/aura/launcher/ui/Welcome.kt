package com.aura.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.ColorSource
import com.aura.launcher.ui.theme.ThemeMode

/**
 * First launch: four short steps. How to get around, making AURA the home screen, choosing a
 * look, and setting up Gemini. Every step can be skipped; it never shows again once finished.
 */
@Composable
fun WelcomeScreen(
    prefs: AuraPrefs,
    isDefaultHome: Boolean,
    onSetHome: () -> Unit,
    onSetUpGemini: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 3
    Box(
        Modifier.fillMaxSize().background(Aura.colors.scrim.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .systemBarsPadding().padding(20.dp).testTag("welcome"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Dots(step, last + 1, Modifier.weight(1f))
                Text(
                    "Skip", color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onFinish).padding(10.dp),
                )
            }
            Spacer(Modifier.weight(0.4f))
            AnimatedContent(
                targetState = step,
                transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() },
                label = "welcomeStep",
                modifier = Modifier.weight(3f),
            ) { s ->
                Column(Modifier.fillMaxWidth()) {
                    when (s) {
                        0 -> GettingAround()
                        1 -> MakeHome(isDefaultHome, onSetHome)
                        2 -> PickLook(prefs)
                        else -> GeminiStep()
                    }
                }
            }
            when (step) {
                last -> {
                    Primary("Set up Gemini") { onFinish(); onSetUpGemini() }
                    Spacer(Modifier.height(8.dp))
                    Secondary("Maybe later", onFinish)
                }
                else -> Primary(if (step == 1 && !isDefaultHome) "Not now" else "Next") { step++ }
            }
        }
    }
}

@Composable
private fun Dots(current: Int, count: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.size(width = if (i == current) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                    .background(if (i == current) Aura.Accent else Aura.GlassStrong)
            )
        }
    }
}

@Composable
private fun Title(text: String, body: String) {
    Text(text, color = Aura.Ink, fontSize = 30.sp, fontFamily = Aura.Sans, lineHeight = 36.sp)
    Spacer(Modifier.height(10.dp))
    Text(body, color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans, lineHeight = 21.sp)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Tip(glyph: Glyph, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) { AuraGlyph(glyph, size = 20.dp, color = Aura.Ink) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
            Text(body, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans)
        }
    }
}

@Composable
private fun GettingAround() {
    Title("Welcome to AURA", "A calm home screen with Gemini built in. Here's how to get around:")
    Tip(Glyph.Grid, "Swipe left", "All your apps, A to Z. Slide along the letters to jump.")
    Tip(Glyph.External, "Swipe right", "Your news feed, with weather and a Gemini briefing.")
    Tip(Glyph.Pulse, "Swipe down", "Notifications. With two fingers: Quick Settings.")
    Tip(Glyph.Star, "Press and hold", "Widgets, wallpaper and AURA settings. Hold an app for its shortcuts.")
    Tip(Glyph.Sparkle, "Tap Gemini", "Ask anything, or tell your phone what to do.")
}

@Composable
private fun MakeHome(isDefaultHome: Boolean, onSetHome: () -> Unit) {
    Title(
        "Make AURA your home screen",
        "So the Home button and the swipe-up gesture bring you here. You can switch back any time in Android settings.",
    )
    if (isDefaultHome) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = Aura.Accent, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text("AURA is your home screen", color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
        }
    } else {
        Primary("Set AURA as home screen", onSetHome)
    }
}

@Composable
private fun PickLook(prefs: AuraPrefs) {
    Title("Pick a look", "You can change these later in Settings → Look.")
    Text("Theme", color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    Segmented(
        options = ThemeMode.entries.sortedBy { it != ThemeMode.SYSTEM },
        selected = prefs.themeMode, label = { it.label }, onSelect = prefs::updateThemeMode,
    )
    Spacer(Modifier.height(10.dp))
    Text("Colors", color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    Segmented(
        options = ColorSource.entries.toList(),
        selected = prefs.colorSource, label = { it.label }, onSelect = prefs::updateColorSource,
    )
}

@Composable
private fun GeminiStep() {
    Title(
        "Meet Gemini",
        "Ask questions, or say things like “set a timer for 10 minutes”, “text Mom I'm on my way” or “navigate home”. " +
            "Setup takes a minute and needs no Google Cloud: run it on your phone if it supports Gemini Nano, " +
            "or use a free key from Google AI Studio.",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        AuraGlyph(Glyph.Sparkle, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Text("What can I do for you?", color = Aura.Ink, fontSize = 19.sp, fontFamily = Aura.Sans)
    }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Aura.Accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Aura.OnAccent, fontSize = 16.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans) }
}
