package com.aura.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

object Aura {
    val Ink = Color(0xFFF6F1EA)
    val InkDim = Color(0xC7F6F1EA)
    val InkFaint = Color(0x80F6F1EA)
    val Glass = Color(0x1AFFFFFF)
    val GlassStrong = Color(0x2BFFFFFF)
    val GlassDark = Color(0x47201D1B)
    val Stroke = Color(0x2EFFFFFF)
    val Scrim = Color(0xB3141210)

    val GeminiBlue = Color(0xFF8AB0FF)
    val GeminiViolet = Color(0xFFC0A6FF)
    val GeminiPink = Color(0xFFF3BBD9)

    val geminiBrush = Brush.linearGradient(listOf(GeminiBlue, GeminiViolet, GeminiPink, GeminiViolet, GeminiBlue))
    val geminiFill = Brush.linearGradient(listOf(Color(0xFF7E9DF2), Color(0xFFA98CF0), Color(0xFFDE9CC6)))

    /** Google Sans on Pixel; falls back to the system sans elsewhere. */
    val Sans = FontFamily(
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Light),
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Medium),
    )
    val Display = FontFamily(
        Font(DeviceFontFamilyName("google-sans-flex"), FontWeight.ExtraLight),
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Light),
    )
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aura.GeminiViolet,
            onPrimary = Color(0xFF1E1830),
            secondary = Aura.GeminiBlue,
            surface = Color(0xFF26231F),
            onSurface = Aura.Ink,
            surfaceContainerHigh = Color(0xFF302C28),
            background = Color(0xFF1E1C1A),
            onBackground = Aura.Ink,
        ),
        content = content,
    )
}
