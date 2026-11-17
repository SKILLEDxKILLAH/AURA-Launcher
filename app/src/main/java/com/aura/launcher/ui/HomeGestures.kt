package com.aura.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home-screen gestures that don't fight the horizontal drawer swipe or child taps:
 * - press and hold on empty space → [onLongPress] with the press position
 * - swipe down with one finger → [onSwipeDown] (1), two or more fingers → [onSwipeDown] (2)
 *
 * Place it *before* the drawer's draggable in the modifier chain so the draggable sees events
 * first; once anything consumes the gesture, this detector stands down.
 */
fun Modifier.homeGestures(onLongPress: (Offset) -> Unit, onSwipeDown: (fingers: Int) -> Unit): Modifier =
    pointerInput(Unit) {
        val swipeDistance = 64.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A child (clock, Gemini card, dock…) already owns taps/long-presses that start on it.
            val childOwnsPress = down.isConsumed
            val start = down.position
            var fingers = 1

            // Phase 1: wait for a long press, a release, or movement past touch slop.
            val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    fingers = max(fingers, event.changes.count { it.pressed })
                    if (event.changes.none { it.pressed }) return@withTimeoutOrNull false
                    if (event.changes.any { it.isConsumed }) return@withTimeoutOrNull false
                    val primary = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    if ((primary.position - start).getDistance() > viewConfiguration.touchSlop || fingers > 1) {
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            }

            if (moved == null) {
                // Timed out without moving: a long press on empty space.
                if (!childOwnsPress && fingers == 1) {
                    onLongPress(start)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                }
                return@awaitEachGesture
            }
            if (moved == false) return@awaitEachGesture

            // Phase 2: track the swipe until it's clearly downward, sideways, or released.
            while (true) {
                val event = awaitPointerEvent()
                fingers = max(fingers, event.changes.count { it.pressed })
                if (event.changes.none { it.pressed }) return@awaitEachGesture
                // The horizontal drawer drag claimed it.
                if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                val primary = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: event.changes.first { it.pressed }
                val delta = primary.position - start
                if (abs(delta.x) > abs(delta.y) * 1.2f && abs(delta.x) > viewConfiguration.touchSlop) return@awaitEachGesture
                if (delta.y > swipeDistance && delta.y > abs(delta.x) * 1.5f) {
                    onSwipeDown(if (fingers >= 2) 2 else 1)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                    return@awaitEachGesture
                }
            }
        }
    }

/** Glass popup shown where you press and hold on the home screen. */
@Composable
fun HomeMenu(
    anchor: Offset,
    usingSystemWallpaper: Boolean,
    onWallpaper: () -> Unit,
    onUseAuraBackdrop: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x33000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        // Place the menu at the press point, kept fully on screen.
        Layout(
            content = {
                Column(
                    Modifier.width(236.dp).glass(RoundedCornerShape(26.dp), fill = Color(0xE62A2724)).padding(vertical = 8.dp)
                ) {
                    MenuItem(Glyph.External, "Wallpaper & style", onWallpaper)
                    if (usingSystemWallpaper) MenuItem(Glyph.Sparkle, "Use AURA backdrop", onUseAuraBackdrop)
                    MenuItem(Glyph.Gear, "AURA settings", onSettings)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val menu = measurables.first().measure(Constraints())
            val margin = 16.dp.roundToPx()
            val x = (anchor.x - menu.width / 2f).roundToInt().coerceIn(margin, constraints.maxWidth - menu.width - margin)
            val y = (anchor.y - menu.height - 12.dp.toPx()).roundToInt().let { above ->
                if (above >= margin * 3) above else (anchor.y + 12.dp.toPx()).roundToInt()
            }.coerceIn(margin, constraints.maxHeight - menu.height - margin)
            layout(constraints.maxWidth, constraints.maxHeight) { menu.place(IntOffset(x, y)) }
        }
    }
}

@Composable
private fun MenuItem(glyph: Glyph, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(glyph, size = 20.dp, color = Aura.Ink)
        Spacer(Modifier.width(16.dp))
        Text(label, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
    }
}

/** One-time prompt shown if the system won't let AURA pull down the shade on its own. */
@Composable
fun GestureSetupBanner(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(16.dp).offset(y = 56.dp).fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), fill = Color(0xEB2A2724)).padding(18.dp)
        ) {
            Text("Swipe down for notifications", color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans)
            Spacer(Modifier.width(4.dp))
            Text(
                "Turn on “AURA gestures” in Accessibility so one-finger and two-finger swipes can open your " +
                    "notifications and Quick Settings. It only opens those panels and reads nothing on screen.",
                color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(
                    "Not now", color = Aura.InkDim, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onDismiss).padding(12.dp),
                )
                Text(
                    "Turn on", color = Aura.GeminiViolet, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onEnable).padding(12.dp),
                )
            }
        }
    }
}
