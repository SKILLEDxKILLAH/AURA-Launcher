package com.aura.launcher.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Light, dark, or whichever the phone is using. */
enum class ThemeMode(val label: String) { SYSTEM("Match phone"), DARK("Dark"), LIGHT("Light") }

/** Where AURA's colors come from. */
enum class ColorSource(val label: String) { AURA("AURA sand"), WALLPAPER("Wallpaper colors") }

/** The colors of AURA's backdrop: sky from top to bottom, light shafts, three dunes and their ridges. */
@Immutable
data class BackdropColors(
    val sky: List<Color>,
    val shafts: List<Color>,
    val dunes: List<List<Color>>,
    val ridges: List<Color>,
    val vignette: Color,
)

/** Every color AURA draws with, for one theme. */
@Immutable
data class AuraPalette(
    val isLight: Boolean,
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    /** Strongest text, e.g. on a selected chip. */
    val inkStrong: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassDark: Color,
    val stroke: Color,
    val scrim: Color,
    /** Highlights: switches, selection, the search cursor, favourites. */
    val accent: Color,
    val onAccent: Color,
    /** Full-screen panels (Settings, news feed, checks). */
    val surface: Color,
    /** The Gemini panel. */
    val sheet: Color,
    /** Pop-up menus. */
    val menu: Color,
    /** Typing fields inside panels. */
    val field: Color,
    /** News cards. */
    val card: Color,
    val error: Color,
    /** Laid over the backdrop behind the drawer and feed. */
    val veil: Color,
    val iconBackground: Color,
    val iconForeground: Color,
    val backdrop: BackdropColors,
) {
    companion object {
        val Dark = AuraPalette(
            isLight = false,
            ink = Color(0xFFF6F1EA), inkDim = Color(0xC7F6F1EA), inkFaint = Color(0x80F6F1EA), inkStrong = Color.White,
            glass = Color(0x1AFFFFFF), glassStrong = Color(0x2BFFFFFF), glassDark = Color(0x47201D1B), stroke = Color(0x2EFFFFFF),
            scrim = Color(0xB3141210),
            accent = Color(0xFFC0A6FF), onAccent = Color(0xFF1E1830),
            surface = Color(0xF71D1B19), sheet = Color(0xF2262320), menu = Color(0xF02A2724), field = Color(0xFF2E2A27),
            card = Color(0xFF221F1D), error = Color(0xFFFFB4A9), veil = Color(0x59000000),
            iconBackground = Color(0xFF3A3531), iconForeground = Color(0xFFEADBC8),
            backdrop = BackdropColors(
                sky = listOf(Color(0xFF2F2D2C), Color(0xFF3B3836), Color(0xFF554D47), Color(0xFFC9B6A1)),
                shafts = listOf(Color(0x52E9D8C4), Color(0x2EF2E4D2)),
                dunes = listOf(
                    listOf(Color(0xFF45403C), Color(0xFF3A3532), Color(0xFF6A5E54)),
                    listOf(Color(0xFF524942), Color(0xFF433C37), Color(0xFF8C7B6C)),
                    listOf(Color(0xFF6D6159), Color(0xFFA8957F), Color(0xFFD9C6B0)),
                ),
                ridges = listOf(Color(0x66EADBC8), Color(0x80F2E2CE), Color(0x66FFF3E4)),
                vignette = Color(0x55000000),
            ),
        )

        val Light = AuraPalette(
            isLight = true,
            ink = Color(0xFF221D18), inkDim = Color(0xB8221D18), inkFaint = Color(0x80221D18), inkStrong = Color.Black,
            glass = Color(0x59FFFFFF), glassStrong = Color(0x8CFFFFFF), glassDark = Color(0x73FFFFFF), stroke = Color(0x26000000),
            scrim = Color(0x99EFE9E2),
            accent = Color(0xFF6A4FD0), onAccent = Color.White,
            surface = Color(0xFAF6F2EC), sheet = Color(0xF7FBF8F4), menu = Color(0xF7FFFDFA), field = Color(0xFFEFE9E2),
            card = Color(0xFFFFFCF8), error = Color(0xFFB3261E), veil = Color(0x66F6F1EA),
            iconBackground = Color(0xFFEDE3D7), iconForeground = Color(0xFF4A3F35),
            backdrop = BackdropColors(
                sky = listOf(Color(0xFFF4F0EA), Color(0xFFEDE6DE), Color(0xFFDCD0C3), Color(0xFFC7B29C)),
                shafts = listOf(Color(0x80FFFFFF), Color(0x52FFFFFF)),
                dunes = listOf(
                    listOf(Color(0xFFE6DDD3), Color(0xFFDDD3C8), Color(0xFFCDBFAF)),
                    listOf(Color(0xFFDCD0C2), Color(0xFFD1C3B3), Color(0xFFBDA994)),
                    listOf(Color(0xFFD2C3B1), Color(0xFFBFA990), Color(0xFFAE967D)),
                ),
                ridges = listOf(Color(0xB3FFFFFF), Color(0xCCFFFFFF), Color(0xB3FFFFFF)),
                vignette = Color(0x14000000),
            ),
        )

        /**
         * Material You: the same design, colored from the phone's wallpaper-based system palette
         * (Android 12+). Built from the system's tonal colors so it changes with the wallpaper.
         */
        fun fromSystem(context: Context, light: Boolean): AuraPalette {
            fun c(id: Int) = Color(context.getColor(id))
            fun Color.a(alpha: Float) = copy(alpha = alpha)
            val r = android.R.color::class.java
            fun t(name: String) = c(r.getField(name).getInt(null))
            fun n1(tone: Int) = t("system_neutral1_$tone")
            fun n2(tone: Int) = t("system_neutral2_$tone")
            fun a1(tone: Int) = t("system_accent1_$tone")
            fun a2(tone: Int) = t("system_accent2_$tone")
            fun a3(tone: Int) = t("system_accent3_$tone")
            val base = if (light) Light else Dark
            return if (!light) base.copy(
                ink = n1(50), inkDim = n1(50).a(0.78f), inkFaint = n1(50).a(0.5f),
                glassDark = n2(900).a(0.3f),
                scrim = n1(900).a(0.7f),
                accent = a1(200), onAccent = a1(800),
                surface = n1(900).a(0.97f), sheet = n2(900).a(0.95f), menu = n2(800).a(0.94f), field = n2(800),
                card = n1(900), veil = n1(1000).a(0.35f),
                iconBackground = n2(800), iconForeground = a1(100),
                backdrop = BackdropColors(
                    sky = listOf(n2(900), n2(800), a2(700), a2(300)),
                    shafts = listOf(a1(100).a(0.32f), a3(100).a(0.18f)),
                    dunes = listOf(
                        listOf(n2(800), n2(900), a2(700)),
                        listOf(a2(800), n2(800), a2(500)),
                        listOf(a2(700), a2(400), a2(200)),
                    ),
                    ridges = listOf(a1(100).a(0.4f), a1(50).a(0.5f), a3(50).a(0.4f)),
                    vignette = Color(0x55000000),
                ),
            ) else base.copy(
                ink = n1(900), inkDim = n1(900).a(0.72f), inkFaint = n1(900).a(0.5f),
                scrim = n1(50).a(0.6f),
                accent = a1(600), onAccent = Color.White,
                surface = n1(10).a(0.98f), sheet = n1(10).a(0.97f), menu = n1(10).a(0.97f), field = n2(100),
                card = n1(10), veil = n1(10).a(0.4f),
                iconBackground = a1(100), iconForeground = n2(700),
                backdrop = BackdropColors(
                    sky = listOf(n1(50), n2(50), n2(100), a2(300)),
                    shafts = listOf(Color.White.a(0.5f), Color.White.a(0.32f)),
                    dunes = listOf(
                        listOf(n2(100), n2(50), a2(200)),
                        listOf(a2(100), n2(100), a2(300)),
                        listOf(a2(200), a2(300), a2(400)),
                    ),
                    ridges = listOf(Color.White.a(0.7f), Color.White.a(0.8f), Color.White.a(0.7f)),
                    vignette = Color(0x14000000),
                ),
            )
        }

        fun resolve(context: Context, mode: ThemeMode, source: ColorSource, systemDark: Boolean): AuraPalette {
            val light = when (mode) { ThemeMode.LIGHT -> true; ThemeMode.DARK -> false; ThemeMode.SYSTEM -> !systemDark }
            return if (source == ColorSource.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
                runCatching { fromSystem(context, light) }.getOrElse { if (light) Light else Dark }
            } else if (light) Light else Dark
        }
    }
}

val LocalAuraPalette = staticCompositionLocalOf { AuraPalette.Dark }

/**
 * AURA's colors and type. Colors come from the current [AuraPalette] (light, dark or
 * wallpaper-based); the Gemini colors stay the same in every theme.
 */
object Aura {
    val colors: AuraPalette @Composable @ReadOnlyComposable get() = LocalAuraPalette.current

    val Ink: Color @Composable @ReadOnlyComposable get() = colors.ink
    val InkDim: Color @Composable @ReadOnlyComposable get() = colors.inkDim
    val InkFaint: Color @Composable @ReadOnlyComposable get() = colors.inkFaint
    val InkStrong: Color @Composable @ReadOnlyComposable get() = colors.inkStrong
    val Glass: Color @Composable @ReadOnlyComposable get() = colors.glass
    val GlassStrong: Color @Composable @ReadOnlyComposable get() = colors.glassStrong
    val GlassDark: Color @Composable @ReadOnlyComposable get() = colors.glassDark
    val Stroke: Color @Composable @ReadOnlyComposable get() = colors.stroke
    val Scrim: Color @Composable @ReadOnlyComposable get() = colors.scrim
    val Accent: Color @Composable @ReadOnlyComposable get() = colors.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = colors.onAccent
    val Surface: Color @Composable @ReadOnlyComposable get() = colors.surface
    val Sheet: Color @Composable @ReadOnlyComposable get() = colors.sheet
    val Menu: Color @Composable @ReadOnlyComposable get() = colors.menu
    val Field: Color @Composable @ReadOnlyComposable get() = colors.field
    val Card: Color @Composable @ReadOnlyComposable get() = colors.card
    val Error: Color @Composable @ReadOnlyComposable get() = colors.error

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

/** Uses [palette] for everything inside, including Material components (menus, switches). */
@Composable
fun AuraTheme(palette: AuraPalette = AuraPalette.Dark, content: @Composable () -> Unit) {
    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent, secondary = Aura.GeminiBlue,
            surface = palette.menu.copy(alpha = 1f), onSurface = palette.ink, onSurfaceVariant = palette.inkDim,
            surfaceContainer = palette.menu.copy(alpha = 1f), surfaceContainerHigh = palette.field,
            background = palette.surface.copy(alpha = 1f), onBackground = palette.ink, error = palette.error,
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent, secondary = Aura.GeminiBlue,
            surface = palette.menu.copy(alpha = 1f), onSurface = palette.ink, onSurfaceVariant = palette.inkDim,
            surfaceContainer = palette.menu.copy(alpha = 1f), surfaceContainerHigh = palette.field,
            background = palette.surface.copy(alpha = 1f), onBackground = palette.ink, error = palette.error,
        )
    }
    CompositionLocalProvider(LocalAuraPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Same palette for the content, without touching Material colors; used for wallpaper-facing screens. */
@Composable
fun ProvidePalette(palette: AuraPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAuraPalette provides palette, content = content)
}
