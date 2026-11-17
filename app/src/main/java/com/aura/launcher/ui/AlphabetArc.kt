package com.aura.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.ui.theme.Aura
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val FAVORITES_LETTER = "☆"

/** Shared state of the lettered scroller: where the finger is and how far the arc has bloomed. */
class ScrubState {
    var progress by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var touch by mutableStateOf(Offset.Zero)
    var track by mutableStateOf(Rect.Zero)
    var selected by mutableStateOf<String?>(null)
    var hoveredKey by mutableStateOf<String?>(null)
    val rowBounds = mutableStateMapOf<String, Rect>()
}

/** Geometry of the arc, in pixels. */
data class ArcGeometry(
    val depth: Float,
    val slope: Float,
    val radius: Float,
    val maxSpacing: Float,
    val bubbleGap: Float,
    val bubbleRadius: Float,
) {
    /** How far left of the resting column a letter at height [y] is pushed while the finger is at [touchY]. */
    fun offsetFor(y: Float, touchY: Float, progress: Float): Float {
        if (progress <= 0f) return 0f
        val d = y - touchY
        return max(0f, depth - slope * (sqrt(radius * radius + d * d) - radius)) * progress
    }

    fun centers(count: Int, track: Rect): List<Float> {
        if (count == 0 || track.height <= 0f) return emptyList()
        val spacing = min(track.height / count, maxSpacing)
        val top = track.top + (track.height - spacing * count) / 2f
        return List(count) { top + spacing * (it + 0.5f) }
    }

    /** Left edge of the zone where the finger is treated as hovering the preview list. */
    fun previewEdge(track: Rect) = track.center.x - depth - radius * 0.4f
}

@Composable
fun rememberArcGeometry(): ArcGeometry {
    val d = LocalDensity.current
    return remember(d) {
        with(d) { ArcGeometry(depth = 124.dp.toPx(), slope = 0.5f, radius = 44.dp.toPx(), maxSpacing = 30.dp.toPx(), bubbleGap = 58.dp.toPx(), bubbleRadius = 31.dp.toPx()) }
    }
}

/** Touch strip on the right edge. It reports positions in root coordinates. */
fun Modifier.alphabetTouch(
    state: ScrubState,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (Offset) -> Unit,
    onCancel: () -> Unit,
): Modifier = this
    .onGloballyPositioned { state.track = it.boundsInRoot() }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = state.track.topLeft
            down.consume()
            onStart(origin + down.position)
            var finished = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val pos = origin + change.position
                    if (!change.pressed) {
                        finished = true
                        onEnd(pos)
                        break
                    }
                    change.consume()
                    onMove(pos)
                }
            } finally {
                if (!finished) onCancel()
            }
        }
    }

/**
 * Draws the alphabet. At rest it is a straight column with the current section highlighted.
 * While scrubbing, letters bow out into a curved arc that bends toward the finger, a bubble
 * shows the selected letter, the drawer dims, and that letter's apps appear on the left.
 */
@Composable
fun AlphabetArcOverlay(
    state: ScrubState,
    geometry: ArcGeometry,
    letters: List<String>,
    activeLetter: String?,
    previewApps: List<AppEntry>,
    repo: AppRepository,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val letterStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Aura.Sans)
    val layouts = remember(letters, measurer) { letters.map { measurer.measure(it, letterStyle) } }
    val bubbleStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Medium, fontFamily = Aura.Sans, color = Color.White)
    val p = state.progress

    Box(modifier) {
        if (p > 0.001f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color(0xE0141210)))
            val centers = geometry.centers(letters.size, state.track)
            val selY = letters.indexOf(state.selected).takeIf { it >= 0 }?.let { centers[it] } ?: state.touch.y
            PreviewList(state, geometry, previewApps, repo, selY)
        }

        Canvas(Modifier.fillMaxSize()) {
            val track = state.track
            if (track.width <= 0f) return@Canvas
            val centers = geometry.centers(letters.size, track)
            val restX = track.center.x
            val touchY = state.touch.y
            val falloff = 220.dp.toPx()

            letters.forEachIndexed { i, letter ->
                val y = centers[i]
                val x = restX - geometry.offsetFor(y, touchY, p)
                val near = if (p > 0f) (1f - min(1f, abs(y - touchY) / falloff)) else 0f

                if (letter == activeLetter && p < 1f) {
                    val a = 1f - p
                    drawCircle(Color.White.copy(alpha = 0.2f * a), 15.dp.toPx(), Offset(restX, y))
                    drawCircle(Aura.GeminiViolet.copy(alpha = 0.85f * a), 15.dp.toPx(), Offset(restX, y), style = Stroke(1.5.dp.toPx()))
                }

                val lay = layouts[i]
                val color = when {
                    p > 0f -> Aura.Ink.copy(alpha = 0.7f + 0.3f * near)
                    letter == activeLetter -> Aura.Ink
                    else -> Aura.InkDim
                }
                val s = 1f + 0.45f * near * p
                scale(s, Offset(x, y)) {
                    drawText(lay, color = color, topLeft = Offset(x - lay.size.width / 2f, y - lay.size.height / 2f))
                }
            }

            val sel = state.selected
            val selIndex = letters.indexOf(sel)
            if (p > 0.001f && sel != null && selIndex >= 0) {
                val y = centers[selIndex]
                val x = restX - geometry.offsetFor(y, y, p) - geometry.bubbleGap * p
                val r = geometry.bubbleRadius * p
                drawCircle(Aura.GeminiViolet.copy(alpha = 0.35f * p), r * 1.45f, Offset(x, y))
                drawCircle(Aura.geminiFill, r, Offset(x, y))
                val lay = measurer.measure(sel, bubbleStyle)
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, y - lay.size.height / 2f), alpha = p)
            }
        }
    }
}

@Composable
private fun PreviewList(state: ScrubState, geometry: ArcGeometry, apps: List<AppEntry>, repo: AppRepository, anchorY: Float) {
    val density = LocalDensity.current
    val track = state.track
    val rowHeight = with(density) { 76.dp.toPx() }
    val headerHeight = with(density) { 56.dp.toPx() }
    val maxRows = max(1, ((track.height - headerHeight) / rowHeight).toInt())
    val listHeight = headerHeight + rowHeight * min(max(apps.size, 1), maxRows)
    // Center the group on the selected letter, like the bubble, while staying on screen.
    val targetTop = (anchorY - listHeight / 2f).coerceIn(track.top, max(track.top, track.bottom - listHeight))
    val top by animateFloatAsState(targetTop, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), label = "previewTop")
    val maxWidth = with(density) { (geometry.previewEdge(track) - 8.dp.toPx()).toDp() }

    AnimatedContent(
        targetState = state.selected to apps,
        transitionSpec = {
            (fadeIn() + slideInVertically { it / 12 }) togetherWith fadeOut()
        },
        contentKey = { it.first },
        modifier = Modifier
            .offset { IntOffset(0, top.roundToInt()) }
            .graphicsLayer {
                alpha = state.progress
                translationX = -(1f - state.progress) * 60f
            }
            .padding(start = 20.dp)
            .widthIn(max = maxWidth.coerceAtLeast(150.dp)),
        label = "preview",
    ) { (letter, list) ->
        Column {
            Text(
                if (letter == FAVORITES_LETTER) "Favorites" else letter.orEmpty(),
                color = Aura.Ink, fontSize = 26.sp, fontFamily = Aura.Sans,
                modifier = Modifier.padding(start = 10.dp, bottom = 12.dp),
            )
            if (list.isEmpty()) {
                Text(
                    "Long-press any app to add it to favorites",
                    color = Aura.InkDim, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp),
                )
            }
            list.take(maxRows).forEach { app ->
                val hovered = state.hoveredKey == app.key
                Row(
                    Modifier
                        .onGloballyPositioned { state.rowBounds[app.key] = it.boundsInRoot() }
                        .graphicsLayer {
                            val s = if (hovered) 1.06f else 1f
                            scaleX = s; scaleY = s
                        }
                        .then(if (hovered) Modifier.glass(RoundedCornerShape(24.dp), fill = Aura.GlassStrong) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    AppIcon(app, repo, 54.dp)
                    Spacer(Modifier.width(18.dp))
                    Text(
                        app.label, color = Aura.Ink, fontSize = 21.sp, fontFamily = Aura.Sans,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            if (list.size > maxRows) {
                Text("+${list.size - maxRows} more", color = Aura.InkDim, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
            }
        }
    }
}
