package com.aura.launcher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.DockItem
import com.aura.launcher.data.SettingShortcut
import com.aura.launcher.data.SettingShortcuts
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

data class DrawerActions(
    val launch: (AppEntry) -> Unit,
    val appInfo: (AppEntry) -> Unit,
    val uninstall: (AppEntry) -> Unit,
    val toggleFavorite: (AppEntry) -> Unit,
    val openSetting: (SettingShortcut) -> Unit,
    val askGemini: (String) -> Unit,
    val webSearch: (String) -> Unit,
    val openGeminiSheet: () -> Unit,
    val geminiLongPress: () -> Unit,
    val geminiVoice: () -> Unit,
    val openGoogle: () -> Unit,
    val openSettings: () -> Unit,
    val launchDock: (DockItem) -> Unit,
)

private sealed interface DrawerRow {
    val key: String
    val letter: String

    data class Header(override val letter: String) : DrawerRow {
        override val key get() = "header_$letter"
    }

    data class App(val entry: AppEntry) : DrawerRow {
        override val key get() = entry.key
        override val letter get() = entry.letter
    }
}

@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    favoriteKeys: Set<String>,
    repo: AppRepository,
    dock: List<DockItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    focusSearchToken: Int,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    topLine: TopLineState = TopLineState.Default,
    suggested: List<AppEntry> = emptyList(),
    recent: List<AppEntry> = emptyList(),
    showGeminiBar: Boolean = true,
    showGeminiButton: Boolean = true,
) {
    val showSmart = suggested.isNotEmpty() || recent.isNotEmpty()
    /** The Suggested/Recent row sits above the A–Z list, shifting list indices by one. */
    val listOffset = if (showSmart) 1 else 0
    val rows = remember(apps) {
        buildList {
            apps.groupBy { it.letter }.forEach { (letter, group) ->
                add(DrawerRow.Header(letter))
                group.forEach { add(DrawerRow.App(it)) }
            }
        }
    }
    val headerIndex = remember(rows) {
        rows.withIndex().filter { it.value is DrawerRow.Header }.associate { it.value.letter to it.index }
    }
    val letters = remember(headerIndex, favorites.isNotEmpty()) {
        (if (favorites.isNotEmpty()) listOf(FAVORITES_LETTER) else emptyList()) + headerIndex.keys
    }

    val listState = rememberLazyListState()
    val scrub = remember { ScrubState() }
    val geometry = rememberArcGeometry()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var scrubAnim by remember { mutableStateOf<Job?>(null) }

    val listLetter by remember(rows, listOffset) {
        derivedStateOf { rows.getOrNull(listState.firstVisibleItemIndex - listOffset)?.letter }
    }
    val displayLetter = (if (scrub.active) scrub.selected else listLetter) ?: letters.firstOrNull { it != FAVORITES_LETTER } ?: "A"

    fun animateScrub(to: Float) {
        scrubAnim?.cancel()
        scrubAnim = scope.launch {
            animate(scrub.progress, to, animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                scrub.progress = v
            }
        }
    }

    fun track(pos: androidx.compose.ui.geometry.Offset) {
        scrub.touch = pos
        // Enter the preview past the edge; leave only when clearly back over the letters.
        val edge = geometry.previewEdge(scrub.track)
        scrub.inPreview = if (scrub.inPreview) pos.x < edge + geometry.radius * 0.6f else pos.x < edge
        if (scrub.inPreview) {
            val list = previewAppsFor(scrub.selected, apps, favorites)
            val key = list.getOrNull(scrub.hoverIndexAt(pos.y))?.key
            if (key != null && key != scrub.hoveredKey) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            scrub.hoveredKey = key
            return
        }
        scrub.hoveredKey = null
        val centers = geometry.centers(letters.size, scrub.track)
        if (centers.isEmpty()) return
        val idx = centers.indices.minBy { abs(centers[it] - pos.y) }
        val letter = letters[idx]
        if (letter != scrub.selected) {
            scrub.selected = letter
            scrub.resetPreview()
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }

    val previewApps = previewAppsFor(scrub.selected, apps, favorites)

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearchToken) {
        if (focusSearchToken > 0) runCatching { searchFocus.requestFocus() }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            AuraTopBar(repo, onGoogle = actions.openGoogle, topLine = topLine, editable = false)
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f)) {
                    BigLetter(displayLetter, Modifier.fillMaxWidth().height(164.dp))
                    AuraSearchBar(
                        query = query,
                        onChange = onQueryChange,
                        onSubmit = {
                            val hits = repo.findApps(query, 2)
                            if (hits.size == 1) actions.launch(hits.first()) else actions.askGemini(query)
                        },
                        focusRequester = searchFocus,
                        modifier = Modifier.padding(start = 18.dp, end = if (query.isBlank()) 0.dp else 18.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().fadeMask(),
                                contentPadding = PaddingValues(start = 14.dp, end = 4.dp, bottom = if (showGeminiButton) 88.dp else 16.dp),
                            ) {
                                if (showSmart) item(key = "smart_row", contentType = "smart") {
                                    SmartRow(suggested, recent, repo, actions.launch)
                                }
                                items(rows, key = { it.key }, contentType = { it is DrawerRow.Header }) { row ->
                                    when (row) {
                                        is DrawerRow.Header -> SectionHeader(row.letter)
                                        is DrawerRow.App -> AppRow(
                                            entry = row.entry,
                                            repo = repo,
                                            isFavorite = row.entry.key in favoriteKeys,
                                            actions = actions,
                                        )
                                    }
                                }
                            }
                        } else {
                            SearchResults(query, repo, favoriteKeys, actions)
                        }
                        if (showGeminiButton) SparkleFab(
                            onClick = actions.geminiLongPress,
                            onLongClick = actions.geminiVoice,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 10.dp),
                        )
                    }
                }
                if (query.isBlank()) {
                    Box(
                        Modifier.width(46.dp).fillMaxHeight().testTag("alphabet").alphabetTouch(
                            state = scrub,
                            onStart = { pos ->
                                scrub.active = true
                                scrub.selected = null
                                track(pos)
                                animateScrub(1f)
                            },
                            onMove = ::track,
                            onEnd = { pos ->
                                track(pos)
                                val hovered = scrub.hoveredKey?.let { key -> previewAppsFor(scrub.selected, apps, favorites).firstOrNull { it.key == key } }
                                if (hovered != null) {
                                    actions.launch(hovered)
                                } else {
                                    val target = scrub.selected
                                    scope.launch {
                                        val index = if (target == FAVORITES_LETTER) 0 else (headerIndex[target] ?: return@launch) + listOffset
                                        listState.scrollToItem(index)
                                    }
                                }
                                scrub.active = false
                                scrub.resetPreview()
                                animateScrub(0f)
                            },
                            onCancel = {
                                scrub.active = false
                                scrub.resetPreview()
                                animateScrub(0f)
                            },
                        )
                    )
                }
            }
            if (showGeminiBar) {
                GeminiPill(
                    onOpen = actions.openGeminiSheet,
                    onMic = actions.geminiVoice,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 18.dp),
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }
            Dock(dock, repo, actions.launchDock, Modifier.padding(bottom = 18.dp))
        }

        if (query.isBlank()) {
            AlphabetArcOverlay(
                state = scrub,
                geometry = geometry,
                letters = letters,
                activeLetter = listLetter,
                previewApps = previewApps,
                repo = repo,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun previewAppsFor(letter: String?, apps: List<AppEntry>, favorites: List<AppEntry>) = when (letter) {
    null -> emptyList()
    FAVORITES_LETTER -> favorites
    else -> apps.filter { it.letter == letter }
}

/** Fades the list toward the bottom so the current section reads strongest, as in the mockup. */
private fun Modifier.fadeMask() = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.03f to Color.Black,
                0.42f to Color.Black,
                1f to Color.Black.copy(alpha = 0.16f),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun BigLetter(letter: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = letter,
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.9f)) togetherWith
                    fadeOut(tween(160)) using SizeTransform(clip = false)
            },
            label = "bigLetter",
        ) { l ->
            Text(
                if (l == FAVORITES_LETTER) "★" else l,
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0x66F2E6D8), Color(0x0DFFFFFF))),
                    fontSize = 184.sp,
                    lineHeight = 184.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            )
        }
    }
}

@Composable
private fun AuraSearchBar(
    query: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier.fillMaxWidth().height(56.dp).glass(RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Search, size = 22.dp, color = Aura.Ink)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search apps, settings, or ask Gemini…",
                    color = Aura.InkDim, fontSize = 16.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans),
                cursorBrush = SolidColor(Aura.GeminiViolet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Clear search") { onChange("") },
                contentAlignment = Alignment.Center,
            ) { AuraGlyph(Glyph.Close, size = 18.dp, color = Aura.InkDim) }
        }
    }
}

@Composable
private fun SectionHeader(letter: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 14.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(letter, color = Aura.InkDim, fontSize = 20.sp, fontFamily = Aura.Sans)
        Spacer(Modifier.width(18.dp))
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(Aura.Stroke.copy(alpha = 0.5f), Aura.Stroke, Color.Transparent)))
        )
    }
}

@Composable
private fun AppRow(entry: AppEntry, repo: AppRepository, isFavorite: Boolean, actions: DrawerActions) {
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .combinedClickable(
                    onClick = { actions.launch(entry) },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        menu = true
                    },
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(entry, repo, 58.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.label, color = Aura.Ink, fontSize = 19.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotEmpty()) {
                    Text(entry.subtitle, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isFavorite) AuraGlyph(Glyph.Star, size = 12.dp, color = Aura.GeminiViolet)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = { menu = false; actions.toggleFavorite(entry) },
            )
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; actions.appInfo(entry) })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menu = false; actions.uninstall(entry) })
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    repo: AppRepository,
    favoriteKeys: Set<String>,
    actions: DrawerActions,
) {
    val apps = remember(query) { repo.findApps(query, 8) }
    val settings = remember(query) { SettingShortcuts.search(query) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp, bottom = 88.dp),
    ) {
        item(key = "ask") {
            ResultRow(
                leading = { Box(Modifier.size(58.dp).glass(CircleShape, fill = Aura.GlassDark), contentAlignment = Alignment.Center) { AuraGlyph(Glyph.Sparkle, size = 30.dp) } },
                title = "Ask Gemini",
                subtitle = "“$query”",
                onClick = { actions.askGemini(query) },
            )
        }
        if (apps.isNotEmpty()) item(key = "apps_label") { SectionHeader("Apps") }
        items(apps, key = { it.key }) { AppRow(it, repo, it.key in favoriteKeys, actions) }
        if (settings.isNotEmpty()) item(key = "settings_label") { SectionHeader("Settings") }
        items(settings, key = { "s_" + it.id }) { s ->
            ResultRow(
                leading = { Box(Modifier.size(58.dp).glass(CircleShape), contentAlignment = Alignment.Center) { AuraGlyph(Glyph.Gear, size = 24.dp) } },
                title = s.title,
                subtitle = "Settings",
                onClick = { actions.openSetting(s) },
            )
        }
        item(key = "web") {
            ResultRow(
                leading = { Box(Modifier.size(58.dp).glass(CircleShape), contentAlignment = Alignment.Center) { AuraGlyph(Glyph.Search, size = 24.dp) } },
                title = "Search the web",
                subtitle = query,
                onClick = { actions.webSearch(query) },
            )
        }
    }
}

@Composable
private fun ResultRow(leading: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, color = Aura.Ink, fontSize = 19.sp, fontFamily = Aura.Sans)
            Text(subtitle, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
