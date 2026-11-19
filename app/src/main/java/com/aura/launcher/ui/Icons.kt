package com.aura.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.launcher.data.Sky
import com.aura.launcher.ui.theme.Aura

enum class Glyph { Sparkle, Ring, Mic, Phone, Message, Gear, Grid, Search, Pulse, Close, Send, External, Star }

private val phonePath by lazy {
    PathParser().parsePathString(
        "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 " +
            "0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 " +
            "0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"
    ).toPath()
}

private val chatPath by lazy {
    PathParser().parsePathString(
        "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
            "M6,9h12v2H6V9zm8,5H6v-2h8v2zm4,-6H6V6h12v2z"
    ).toPath()
}

@Composable
fun AuraGlyph(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Aura.Ink) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        Glyph.Sparkle -> drawSparkle(Brush.linearGradient(listOf(Color.White, Aura.GeminiViolet, Aura.GeminiBlue), Offset.Zero, Offset(s, s)))
        Glyph.Star -> drawSparkle(Brush.linearGradient(listOf(color, color)))
        // AURA's own mark (same as the app icon): a ring of light with a bright point on it.
        Glyph.Ring -> {
            val r = s * 0.36f
            val c = Offset(s / 2, s / 2)
            drawCircle(
                Brush.linearGradient(listOf(Color(0xFFF8F0E4), Color(0xFFC4ACFF)), Offset(c.x - r, c.y), Offset(c.x + r, c.y)),
                r, c, style = Stroke(s * 0.1f),
            )
            val p = Offset(c.x + r * 0.7071f, c.y - r * 0.7071f)
            drawCircle(Color(0xFFFFFCF5), s * 0.075f, p)
        }
        Glyph.Mic -> {
            val w = s * 0.32f
            drawRoundRect(color, Offset((s - w) / 2, s * 0.1f), Size(w, s * 0.48f), CornerRadius(w / 2))
            val arc = Path().apply {
                moveTo(s * 0.24f, s * 0.46f)
                cubicTo(s * 0.24f, s * 0.78f, s * 0.76f, s * 0.78f, s * 0.76f, s * 0.46f)
            }
            drawPath(arc, color, style = stroke)
            drawLine(color, Offset(s / 2, s * 0.7f), Offset(s / 2, s * 0.88f), stroke.width, StrokeCap.Round)
        }
        Glyph.Phone -> scale(s / 24f, Offset.Zero) { drawPath(phonePath, color) }
        Glyph.Message -> scale(s / 24f, Offset.Zero) { drawPath(chatPath, color) }
        Glyph.Gear -> {
            val c = Offset(s / 2, s / 2)
            repeat(8) { i ->
                rotate(i * 45f, c) {
                    drawRoundRect(color, Offset(s / 2 - s * 0.075f, s * 0.08f), Size(s * 0.15f, s * 0.2f), CornerRadius(s * 0.03f))
                }
            }
            drawCircle(color, s * 0.24f, c, style = Stroke(s * 0.12f))
        }
        Glyph.Grid -> {
            for (r in 0..2) for (col in 0..2) {
                drawCircle(color, s * 0.075f, Offset(s * (0.22f + col * 0.28f), s * (0.22f + r * 0.28f)))
            }
        }
        Glyph.Search -> {
            drawCircle(color, s * 0.26f, Offset(s * 0.42f, s * 0.42f), style = stroke)
            drawLine(color, Offset(s * 0.62f, s * 0.62f), Offset(s * 0.86f, s * 0.86f), stroke.width, StrokeCap.Round)
        }
        Glyph.Pulse -> {
            val p = Path().apply {
                moveTo(s * 0.06f, s * 0.62f)
                lineTo(s * 0.3f, s * 0.3f)
                lineTo(s * 0.56f, s * 0.7f)
                lineTo(s * 0.94f, s * 0.34f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Close -> {
            drawLine(color, Offset(s * 0.26f, s * 0.26f), Offset(s * 0.74f, s * 0.74f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(s * 0.74f, s * 0.26f), Offset(s * 0.26f, s * 0.74f), stroke.width, StrokeCap.Round)
        }
        Glyph.Send -> {
            val p = Path().apply {
                moveTo(s * 0.5f, s * 0.84f); lineTo(s * 0.5f, s * 0.18f)
                moveTo(s * 0.24f, s * 0.42f); lineTo(s * 0.5f, s * 0.16f); lineTo(s * 0.76f, s * 0.42f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.External -> {
            val p = Path().apply {
                moveTo(s * 0.44f, s * 0.2f); lineTo(s * 0.2f, s * 0.2f); lineTo(s * 0.2f, s * 0.8f)
                lineTo(s * 0.8f, s * 0.8f); lineTo(s * 0.8f, s * 0.56f)
                moveTo(s * 0.56f, s * 0.2f); lineTo(s * 0.8f, s * 0.2f); lineTo(s * 0.8f, s * 0.44f)
                moveTo(s * 0.8f, s * 0.2f); lineTo(s * 0.46f, s * 0.54f)
            }
            drawPath(p, color, style = stroke)
        }
    }
}

/** Four-pointed Gemini-style sparkle with concave edges. */
fun DrawScope.drawSparkle(brush: Brush) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    val r = s * 0.48f
    val k = s * 0.07f
    val p = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + k, cy - k, cx + r, cy)
        quadraticTo(cx + k, cy + k, cx, cy + r)
        quadraticTo(cx - k, cy + k, cx - r, cy)
        quadraticTo(cx - k, cy - k, cx, cy - r)
        close()
    }
    drawPath(p, brush)
}

@Composable
fun WeatherGlyph(sky: Sky, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val sun = Color(0xFFFFD27A)
        val cloudLight = Color(0xFFF4F1EE)
        val cloudShade = Color(0xFFCFD6E4)
        fun cloud(dx: Float, dy: Float, k: Float) = translate(dx, dy) {
            drawCircle(cloudShade, s * 0.2f * k, Offset(s * 0.36f * k, s * 0.62f * k))
            drawCircle(cloudLight, s * 0.24f * k, Offset(s * 0.55f * k, s * 0.5f * k))
            drawCircle(cloudLight, s * 0.18f * k, Offset(s * 0.74f * k, s * 0.64f * k))
            drawRoundRect(cloudLight, Offset(s * 0.2f * k, s * 0.6f * k), Size(s * 0.66f * k, s * 0.22f * k), CornerRadius(s * 0.11f * k))
        }
        fun drops(c: Color) {
            for (i in 0..2) drawLine(
                c, Offset(s * (0.34f + i * 0.17f), s * 0.86f), Offset(s * (0.3f + i * 0.17f), s * 0.98f),
                s * 0.06f, StrokeCap.Round,
            )
        }
        when (sky) {
            Sky.CLEAR -> if (isDay) {
                drawCircle(sun, s * 0.24f)
                repeat(8) { i -> rotate(i * 45f) { drawLine(sun, Offset(s / 2, s * 0.04f), Offset(s / 2, s * 0.14f), s * 0.06f, StrokeCap.Round) } }
            } else {
                drawCircle(Color(0xFFE9E4FF), s * 0.3f)
                drawCircle(Color(0xFF3A3532), s * 0.26f, Offset(s * 0.64f, s * 0.38f))
            }
            Sky.PARTLY -> {
                drawCircle(if (isDay) sun else Color(0xFFE9E4FF), s * 0.2f, Offset(s * 0.62f, s * 0.36f))
                cloud(-s * 0.06f, s * 0.04f, 0.92f)
            }
            Sky.CLOUDY, Sky.FOG -> cloud(0f, 0f, 1f)
            Sky.RAIN -> { cloud(0f, -s * 0.12f, 1f); drops(Color(0xFF9EC3FF)) }
            Sky.SNOW -> { cloud(0f, -s * 0.12f, 1f); drops(Color.White) }
            Sky.STORM -> {
                cloud(0f, -s * 0.12f, 1f)
                val bolt = Path().apply {
                    moveTo(s * 0.54f, s * 0.7f); lineTo(s * 0.42f, s * 0.86f); lineTo(s * 0.54f, s * 0.86f); lineTo(s * 0.46f, s)
                }
                drawPath(bolt, sun, style = Stroke(s * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
