package com.aura.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot

/**
 * AURA's signature backdrop: charcoal sky, a diagonal shaft of warm light, and soft
 * sand-dune silhouettes with glowing ridges, fading to warm beige at the bottom. Colors come
 * from the current theme (dark, light or wallpaper-based).
 */
@Composable
fun AuraBackdrop(modifier: Modifier = Modifier, drift: Float = 0f) {
    val c = com.aura.launcher.ui.theme.Aura.colors.backdrop
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            Brush.verticalGradient(
                0f to c.sky[0],
                0.38f to c.sky[1],
                0.7f to c.sky[2],
                1f to c.sky[3],
            )
        )

        // Diagonal light shafts from the upper right.
        lightShaft(Offset(w * 0.78f, h * 0.18f), Offset(-0.62f, 0.78f), w * 0.9f, c.shafts[0])
        lightShaft(Offset(w * 0.98f, h * 0.36f), Offset(-0.7f, 0.72f), w * 0.5f, c.shafts[1])

        val d = drift * h * 0.02f

        // Back dune.
        dune(
            Offset(0f, h * 0.47f + d), Offset(w * 0.3f, h * 0.41f + d), Offset(w * 0.62f, h * 0.48f + d), Offset(w, h * 0.38f + d),
            Brush.verticalGradient(c.dunes[0], h * 0.38f, h),
            c.ridges[0],
        )

        // Middle dune sweeping up to the right.
        dune(
            Offset(0f, h * 0.66f + d), Offset(w * 0.22f, h * 0.6f + d), Offset(w * 0.5f, h * 0.53f + d), Offset(w, h * 0.5f + d),
            Brush.verticalGradient(c.dunes[1], h * 0.5f, h),
            c.ridges[1],
        )

        // Front dune, lighter and warmer.
        dune(
            Offset(0f, h * 0.84f + d), Offset(w * 0.3f, h * 0.7f + d), Offset(w * 0.66f, h * 0.72f + d), Offset(w, h * 0.8f + d),
            Brush.verticalGradient(c.dunes[2], h * 0.7f, h),
            c.ridges[2],
        )

        // Gentle vignette for legibility of white text.
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, c.vignette),
                center = Offset(w / 2, h * 0.42f),
                radius = hypot(w, h) * 0.7f,
            )
        )
    }
}

private fun DrawScope.lightShaft(center: Offset, dir: Offset, halfWidth: Float, color: Color) {
    // Gradient runs perpendicular to the shaft direction so it reads as a band of light.
    val n = Offset(dir.y, -dir.x)
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.42f to Color.Transparent,
            0.5f to color,
            0.58f to Color.Transparent,
            1f to Color.Transparent,
            start = center - n * halfWidth,
            end = center + n * halfWidth,
        )
    )
}

/** A dune body filled with [fill], plus a glowing ridge line along its crest only. */
private fun DrawScope.dune(a: Offset, c1: Offset, c2: Offset, b: Offset, fill: Brush, ridgeColor: Color) {
    val crest = Path().apply { moveTo(a.x, a.y); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y) }
    val body = Path().apply { addPath(crest); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
    drawPath(body, fill)
    val brush = Brush.horizontalGradient(
        listOf(Color.Transparent, ridgeColor, ridgeColor.copy(alpha = ridgeColor.alpha * 0.4f)), 0f, size.width,
    )
    drawPath(crest, brush, style = Stroke(width = 18f), alpha = 0.18f)
    drawPath(crest, brush, style = Stroke(width = 2.2f))
}
