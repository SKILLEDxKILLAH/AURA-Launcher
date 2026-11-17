package com.aura.launcher.ui

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.theme.Aura

/** Frosted glass surface: translucent fill and a hairline light edge. */
fun Modifier.glass(shape: Shape, fill: Color = Aura.Glass, stroke: Color = Aura.Stroke, strokeWidth: Dp = 1.dp) =
    this.clip(shape).background(fill).border(strokeWidth, stroke, shape)

/** Soft Gemini-gradient halo drawn behind a rounded shape. */
fun Modifier.geminiGlow(corner: Dp, blur: Dp = 16.dp, alpha: Float = 0.45f) = this.drawBehind {
    val r = corner.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, size.width, size.height,
            intArrayOf(Aura.GeminiBlue.toArgb(), Aura.GeminiViolet.toArgb(), Aura.GeminiPink.toArgb()),
            null, Shader.TileMode.CLAMP,
        )
        maskFilter = BlurMaskFilter(blur.toPx(), BlurMaskFilter.Blur.NORMAL)
        this.alpha = (alpha * 255).toInt()
    }
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
}
