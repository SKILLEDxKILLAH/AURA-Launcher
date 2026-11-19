package com.aura.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AppShortcut
import com.aura.launcher.data.DockItem
import com.aura.launcher.data.DrawerLayout
import com.aura.launcher.data.Sky
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.gemini.NanoStatus
import com.aura.launcher.ui.AppDrawer
import com.aura.launcher.ui.AppMenuActions
import com.aura.launcher.ui.AuraBackdrop
import com.aura.launcher.ui.DrawerActions
import com.aura.launcher.ui.GeminiSetupState
import com.aura.launcher.ui.HomeActions
import com.aura.launcher.ui.HomeScreen
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraPalette
import com.aura.launcher.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Google Play store assets rendered from the real app: phone screens (framed with captions by
 * tools/store_frames.py), the 512 px icon and the 1024×500 feature graphic.
 * Output: app/build/store/. App names and icons are generic placeholders (no other brands).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xxhdpi")
class StoreAssetsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = File("build/store").apply { mkdirs() }

    private val labels = listOf(
        "Books", "Calculator", "Calendar", "Camera", "Clock", "Contacts", "Files", "Fitness", "Gallery", "Maps",
        "Messages", "Music", "News", "Notes", "Phone", "Photos", "Podcasts", "Recorder", "Settings", "Translate",
        "Wallet", "Weather",
    )
    private val subtitles = mapOf(
        "Maps" to "Navigate, explore, plan", "Messages" to "Text, chat, and share", "Music" to "Play, browse, and manage",
        "News" to "Top stories and your interests", "Photos" to "Back up, organize, and edit", "Calendar" to "Plan your day",
        "Camera" to "Photos and video", "Calculator" to "Quick math", "Clock" to "Alarms, timers, world clock",
        "Contacts" to "People you know", "Books" to "Read and listen",
    )
    private val colors = listOf(0xFF5B8DEF, 0xFFE8735A, 0xFF4CAF7A, 0xFFF2B33D, 0xFF8E7CF0, 0xFF3BB3C3, 0xFFE06C9F, 0xFF6D7A8C)

    private fun app(label: String) = AppEntry(
        key = label, label = label, packageName = "x.${label.lowercase()}", component = ComponentName("x.${label.lowercase()}", label),
        user = Process.myUserHandle(), subtitle = subtitles[label] ?: "", letter = AppRepository.letterOf(label), isWork = false,
    )

    private val apps by lazy { labels.map(::app) }

    /** A simple generic icon: a colored circle with the app's first letter. */
    private fun icon(label: String): Bitmap {
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = colors[Math.floorMod(label.hashCode(), colors.size)].toInt()
        c.drawCircle(96f, 96f, 96f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE; textSize = 92f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        c.drawText(label.take(1), 96f, 96f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun repoWithIcons(): AppRepository = AppRepository(context).also { repo ->
        labels.forEach { l ->
            val b = icon(l).asImageBitmap()
            repo.putIcon(l, b)
            repo.putIcon("pkg:x.${l.lowercase()}", b)
        }
    }

    private val dock by lazy {
        listOf("Phone", "Messages", "Maps", "Photos", "Camera").map { DockItem(it, "x.${it.lowercase()}", android.content.Intent()) }
    }
    private val noop = DrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "raw").mkdirs()
        File(out, "raw/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(palette: AuraPalette, name: String) {
        val repo = repoWithIcons()
        compose.setContent {
            AuraTheme(palette) {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    HomeScreen(
                        repo = repo, dock = dock,
                        weather = WeatherNow(68, "°F", "Sunny", Sky.CLEAR, true),
                        showWeather = true, weatherNeedsPermission = false,
                        actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
        }
        save(name)
    }

    private fun drawer(palette: AuraPalette = AuraPalette.Dark, layout: DrawerLayout = DrawerLayout.LIST, menu: AppMenuActions = AppMenuActions(), list: List<AppEntry> = apps) {
        val repo = repoWithIcons()
        compose.setContent {
            AuraTheme(palette) {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 1f)
                    AppDrawer(
                        apps = list, favorites = emptyList(), favoriteKeys = emptySet(), repo = repo,
                        dock = dock, query = "", onQueryChange = {}, focusSearchToken = 0, actions = noop,
                        suggested = apps.filter { it.label in setOf("Maps", "Music", "Messages", "Photos", "Calendar") },
                        recent = apps.filter { it.label in setOf("Camera", "Weather") },
                        layout = layout, menuActions = menu,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun s1Home() = home(AuraPalette.Dark, "1_home")

    @Test fun s2Drawer() { drawer(); save("2_drawer") }

    @Test
    fun s3Scrub() {
        drawer()
        compose.onNodeWithTag("alphabet").performTouchInput {
            down(Offset(width / 2f, height * 0.3f))
            moveTo(Offset(width / 2f, height * 0.42f))
        }
        save("3_scrub")
    }

    @Test
    fun s4Gemini() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = listOf(
                                ChatItem(ChatRole.USER, "Turn on the flashlight and set a timer for 10 minutes"),
                                ChatItem(ChatRole.ACTION, "Flashlight on"),
                                ChatItem(ChatRole.ACTION, "Timer started · 10m"),
                                ChatItem(ChatRole.GEMINI, "Done! The flashlight is on and your 10-minute timer is running."),
                                ChatItem(ChatRole.USER, "What's a quick dinner I can make with eggs and spinach?"),
                                ChatItem(ChatRole.GEMINI, "Try a spinach omelette: wilt the spinach in butter, pour in beaten eggs, fold, and season. About 10 minutes."),
                            ),
                            busy = false, needsSignIn = false, signingIn = false, signInError = null,
                            accountLabel = "Your Gemini key", model = "gemini-flash-latest", listenRequest = 0,
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        save("4_gemini")
    }

    @Test
    fun s5AppMenu() {
        val user = Process.myUserHandle()
        drawer(
            menu = AppMenuActions(loadShortcuts = {
                listOf(
                    AppShortcut("home", "Navigate home", "x.maps", user, null),
                    AppShortcut("work", "Navigate to work", "x.maps", user, null),
                    AppShortcut("saved", "Saved places", "x.maps", user, null),
                )
            }),
            list = apps.filter { it.label in setOf("Maps", "Messages", "Music", "News", "Notes", "Phone", "Photos", "Podcasts") },
        )
        // The list row, not the Suggested shortcut above it.
        compose.onAllNodes(androidx.compose.ui.test.hasText("Maps")).onLast().performTouchInput { longClick() }
        save("5_app_menu")
    }

    @Test
    fun s6Setup() {
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.GeminiSheet(
                        state = com.aura.launcher.ui.GeminiSheetState(
                            chat = emptyList(), busy = false, needsSignIn = true, signingIn = false, signInError = null,
                            accountLabel = null, model = "gemini-flash-latest", listenRequest = 0,
                            setup = GeminiSetupState(nanoStatus = NanoStatus.AVAILABLE),
                        ),
                        onSend = {}, onSignIn = {}, onClear = {}, onOpenSettings = {}, onDismiss = {},
                    )
                }
            }
        }
        save("6_setup")
    }

    @Test fun s7LightGrid() { drawer(palette = AuraPalette.Light, layout = DrawerLayout.GRID); save("7_light_grid") }

    @Test
    fun s8News() {
        val now = java.time.Instant.now()
        fun photo(url: String, top: Int, bottom: Int) {
            val bmp = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 800f, 450f, top, bottom, android.graphics.Shader.TileMode.CLAMP) })
            c.drawCircle(620f, 140f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE2A8.toInt() })
            c.drawOval(-200f, 300f, 1000f, 700f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E4A3A.toInt() })
            com.aura.launcher.ui.NetImages.put(url, bmp)
        }
        photo("store://park", 0xFF6FA8DC.toInt(), 0xFFF6C38B.toInt())
        photo("store://lake", 0xFF4B6584.toInt(), 0xFFA5B1C2.toInt())
        val stories = listOf(
            com.aura.launcher.data.NewsStory("City opens a new riverside park with miles of trails", "Example Times", null, "https://example.com/1", now.minusSeconds(1800),
                summary = "The 40-acre park links three neighborhoods with walking and cycling paths along the water.", imageUrl = "store://park"),
            com.aura.launcher.data.NewsStory("Researchers unveil a quieter, more efficient heat pump", "Example Science", null, "https://example.com/2", now.minusSeconds(3 * 3600),
                summary = "Early tests show it uses a third less power than current models.", imageUrl = "store://lake"),
        )
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop()
                    com.aura.launcher.ui.FeedScreen(
                        state = com.aura.launcher.ui.FeedState(
                            topic = com.aura.launcher.data.NewsTopic.TOP, stories = stories, loading = false, error = null,
                            weather = WeatherNow(68, "°F", "Sunny", Sky.CLEAR, true, high = 72, low = 55),
                            weatherNeedsPermission = false,
                        ),
                        actions = com.aura.launcher.ui.FeedActions({}, {}, {}, {}, {}),
                    )
                }
            }
        }
        save("8_news")
    }

    /** The launcher icon as a full-bleed 512×512 square; Google Play rounds the corners itself. */
    private fun appIcon(px: Int): Bitmap {
        val d = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // The visible part of an adaptive icon is the middle 72 of its 108 units.
        val layer = px * 108 / 72
        val off = (px - layer) / 2
        listOfNotNull(d.background, d.foreground).forEach { it.setBounds(off, off, off + layer, off + layer); it.draw(c) }
        return bmp
    }

    @Test
    fun icon512() {
        File(out, "icon_512.png").outputStream().use { appIcon(512).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w512dp-h250dp-xhdpi")
    fun featureGraphic() = feature(appIcon(256), "feature_graphic.png")

    /** Same, with the original ring icon from tools/store_icon.py (run that first). */
    @Test
    @Config(qualifiers = "w512dp-h250dp-xhdpi")
    fun featureGraphicAlt() {
        val alt = File(out, "icon_512_alt.png").takeIf { it.exists() } ?: return
        feature(android.graphics.BitmapFactory.decodeFile(alt.path), "feature_graphic_alt.png")
    }

    private fun feature(iconBitmap: Bitmap, file: String) {
        val icon = iconBitmap.asImageBitmap()
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    AuraBackdrop(drift = 0.4f)
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(104.dp).clip(RoundedCornerShape(30.dp)))
                        Spacer(Modifier.width(28.dp))
                        Column(verticalArrangement = Arrangement.Center) {
                            Text("AURA", color = Aura.Ink, fontSize = 58.sp, fontFamily = Aura.Display, letterSpacing = 6.sp)
                            Text("A calm home screen with Gemini built in", color = Aura.InkDim, fontSize = 19.sp, fontFamily = Aura.Sans)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
