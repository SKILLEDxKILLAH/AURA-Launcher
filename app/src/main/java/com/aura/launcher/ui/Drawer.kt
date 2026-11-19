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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.aura.launcher.data.DrawerLayout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
    layout: DrawerLayout = DrawerLayout.LIST,
    menuActions: AppMenuActions? = null,
    showTip: Boolean = false,
    onTipDismiss: () -> Unit = {},
    dockEntry: (DockItem) -> AppEntry? = { null },
) {
    val menu = menuActions ?: AppMenuActions(
        toggleFavorite = actions.toggleFavorite, appInfo = actions.appInfo, uninstall = actions.uninstall,
        isFavorite = { it.key in favoriteKeys },
    )
    val showSmart = suggested.isNotEmpty() || recent.isNotEmpty()
    /** Rows above the A–Z list (tip, Suggested/Recent) shift list indices. */
    val listOffset = (if (showTip) 1 else 0) + (if (showSmart) 1 else 0)
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

    val grid = layout == DrawerLayout.GRID
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val firstVisible by remember(grid) { derivedStateOf { if (grid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex } }
    suspend fun scrollToIndex(index: Int) = if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index)

    val scrub = remember { ScrubState() }
    val geometry = rememberArcGeometry()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var scrubAnim by remember { mutableStateOf<Job?>(null) }

    // The big letter shrinks as you scroll so more apps fit, and grows back at the top.
    val density = LocalDensity.current
    val collapseRange = with(density) { (BIG_LETTER_HEIGHT - SMALL_LETTER_HEIGHT).toPx() }
    var collapsed by remember { mutableFloatStateOf(0f) }
    val collapse = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val next = (collapsed - available.y).coerceAtMost(collapseRange)
                val used = next - collapsed
                collapsed = next
                return Offset(0f, -used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val next = (collapsed - available.y).coerceAtLeast(0f)
                val used = collapsed - next
                collapsed = next
                return Offset(0f, used)
            }
        }
    }
    val collapseFraction = if (collapseRange > 0f) collapsed / collapseRange else 0f

    val listLetter by remember(rows, listOffset) {
        derivedStateOf { rows.getOrNull(firstVisible - listOffset)?.letter }
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
                    BigLetter(
                        displayLetter,
                        collapse = if (query.isBlank()) collapseFraction else 1f,
                        modifier = Modifier.fillMaxWidth().testTag("big_letter").height(
                            lerp(BIG_LETTER_HEIGHT, SMALL_LETTER_HEIGHT, if (query.isBlank()) collapseFraction else 1f)
                        ),
                    )
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
                    Box(Modifier.weight(1f).nestedScroll(collapse)) {
                        val bottomPad = if (showGeminiButton) 88.dp else 16.dp
                        if (query.isNotBlank()) {
                            SearchResults(query, repo, menu, actions)
                        } else if (grid) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                state = gridState,
                                modifier = Modifier.fillMaxSize().fadeMask().testTag("drawer_list"),
                                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, bottom = bottomPad),
                            ) {
                                if (showTip) item(key = "tip", span = { GridItemSpan(maxLineSpan) }) { DrawerTip(onTipDismiss) }
                                if (showSmart) item(key = "smart_row", span = { GridItemSpan(maxLineSpan) }, contentType = "smart") {
                                    SmartRow(suggested, recent, repo, actions.launch, menu)
                                }
                                items(
                                    rows, key = { it.key },
                                    span = { if (it is DrawerRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                    contentType = { it is DrawerRow.Header },
                                ) { row ->
                                    when (row) {
                                        is DrawerRow.Header -> SectionHeader(row.letter)
                                        is DrawerRow.App -> AppCell(row.entry, repo, menu, actions.launch)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().fadeMask().testTag("drawer_list"),
                                contentPadding = PaddingValues(start = 14.dp, end = 4.dp, bottom = bottomPad),
                            ) {
                                if (showTip) item(key = "tip") { DrawerTip(onTipDismiss) }
                                if (showSmart) item(key = "smart_row", contentType = "smart") {
                                    SmartRow(suggested, recent, repo, actions.launch, menu)
                                }
                                items(rows, key = { it.key }, contentType = { it is DrawerRow.Header }) { row ->
                                    when (row) {
                                        is DrawerRow.Header -> SectionHeader(row.letter, compact = layout == DrawerLayout.COMPACT)
                                        is DrawerRow.App -> AppRow(row.entry, repo, menu, actions.launch, compact = layout == DrawerLayout.COMPACT)
                                    }
                                }
                            }
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
                                        // Jumping into the list: make room for apps, not the letter.
                                        if (index > 0) collapsed = collapseRange
                                        scrollToIndex(index)
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
            Dock(dock, repo, actions.launchDock, Modifier.padding(bottom = 18.dp), entryFor = dockEntry, menuActions = menu)
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

private val BIG_LETTER_HEIGHT = 164.dp
private val SMALL_LETTER_HEIGHT = 64.dp

/** One-time help for the two things people don't find on their own. */
@Composable
private fun DrawerTip(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp, end = 10.dp)
            .glass(RoundedCornerShape(22.dp), fill = Aura.GlassStrong).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tips", color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Slide along the letters on the right to jump, then slide left to pick an app.\n" +
                    "Hold any app for its shortcuts, favorites, hiding and uninstall.",
                color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, lineHeight = 18.sp,
            )
        }
        Text(
            "Got it", color = Aura.Accent, fontSize = 14.sp, fontFamily = Aura.Sans,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onDismiss).padding(10.dp),
        )
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
                0.55f to Color.Black,
                1f to Color.Black.copy(alpha = 0.5f),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun BigLetter(letter: String, modifier: Modifier = Modifier, collapse: Float = 0f) {
    // Shrinks from the bottom centre as the header collapses.
    val scale = lerp(1f, 0.38f, collapse)
    Box(
        modifier.graphicsLayer {
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = letter,
            // Full size even when the header is short, so scaling (not squeezing) makes it small.
            modifier = Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.9f)) togetherWith
                    fadeOut(tween(160)) using SizeTransform(clip = false)
            },
            label = "bigLetter",
        ) { l ->
            Text(
                if (l == FAVORITES_LETTER) "★" else l,
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(Aura.Ink.copy(alpha = 0.85f), Aura.Ink.copy(alpha = 0.4f), Aura.Ink.copy(alpha = 0.05f))),
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
                cursorBrush = SolidColor(Aura.Accent),
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
private fun SectionHeader(letter: String, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = if (compact) 8.dp else 14.dp, bottom = if (compact) 2.dp else 6.dp, end = 12.dp),
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
private fun AppRow(
    entry: AppEntry,
    repo: AppRepository,
    menu: AppMenuActions,
    launch: (AppEntry) -> Unit,
    compact: Boolean = false,
    note: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .combinedClickable(
                    onClickLabel = "Open",
                    onLongClickLabel = "Shortcuts and options",
                    onClick = { launch(entry) },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        open = true
                    },
                )
                .padding(horizontal = 6.dp, vertical = if (compact) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(entry, repo, if (compact) 42.dp else 58.dp)
            Spacer(Modifier.width(if (compact) 16.dp else 20.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.label, color = Aura.Ink, fontSize = if (compact) 17.sp else 19.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = note ?: entry.subtitle.takeIf { !compact }
                if (!sub.isNullOrEmpty()) {
                    Text(sub, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (menu.isFavorite(entry)) AuraGlyph(Glyph.Star, size = 12.dp, color = Aura.Accent)
        }
        AppMenu(entry, repo, expanded = open, onDismiss = { open = false }, actions = menu)
    }
}

/** Grid layout: icon with its name underneath. */
@Composable
private fun AppCell(entry: AppEntry, repo: AppRepository, menu: AppMenuActions, launch: (AppEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClickLabel = "Open",
                    onLongClickLabel = "Shortcuts and options",
                    onClick = { launch(entry) },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        open = true
                    },
                )
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(entry, repo, 54.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                entry.label, color = Aura.Ink, fontSize = 12.sp, fontFamily = Aura.Sans, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        AppMenu(entry, repo, expanded = open, onDismiss = { open = false }, actions = menu)
    }
}

@Composable
private fun SearchResults(
    query: String,
    repo: AppRepository,
    menu: AppMenuActions,
    actions: DrawerActions,
) {
    val apps = remember(query) { repo.findApps(query, 8) }
    val settings = remember(query) { SettingShortcuts.search(query) }
    val sum = remember(query) { com.aura.launcher.data.Calculator.evaluate(query) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val contacts by androidx.compose.runtime.produceState(emptyList<com.aura.launcher.data.ContactHit>(), query) {
        kotlinx.coroutines.delay(120) // wait for typing to pause
        value = com.aura.launcher.data.ContactSearch.find(context, query)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("search_results"),
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp, bottom = 88.dp),
    ) {
        // Typed a sum? The answer comes first; tap to copy it.
        if (sum != null) item(key = "calc") {
            ResultRow(
                leading = { Box(Modifier.size(58.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Text("=", color = Aura.Ink, fontSize = 26.sp) } },
                title = sum,
                subtitle = "$query · tap to copy",
                onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Result", sum.replace(",", "")))
                },
            )
        }
        item(key = "ask") {
            ResultRow(
                leading = { Box(Modifier.size(58.dp).glass(CircleShape, fill = Aura.GlassDark), contentAlignment = Alignment.Center) { AuraGlyph(Glyph.Sparkle, size = 30.dp) } },
                title = "Ask Gemini",
                subtitle = "“$query”",
                onClick = { actions.askGemini(query) },
            )
        }
        if (apps.isNotEmpty()) item(key = "apps_label") { SectionHeader("Apps") }
        // Hidden apps still turn up here, so they're never lost.
        items(apps, key = { it.key }) { AppRow(it, repo, menu, actions.launch, note = if (menu.isHidden(it)) "Hidden from app drawer" else null) }
        if (contacts.isNotEmpty()) item(key = "contacts_label") { SectionHeader("Contacts") }
        items(contacts, key = { "c_" + it.lookupUri }) { c -> ContactRow(c) }
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

/** A contact in search: tap for their card, or call / text right away. */
@Composable
private fun ContactRow(c: com.aura.launcher.data.ContactHit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .clickable { com.aura.launcher.ActivityBridge.start(context, com.aura.launcher.data.ContactSearch.openIntent(c)) }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp).glass(CircleShape, fill = Aura.GlassStrong), contentAlignment = Alignment.Center) {
            Text(c.name.take(1).uppercase(), color = Aura.Ink, fontSize = 22.sp, fontFamily = Aura.Sans)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Aura.Ink, fontSize = 19.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.number?.let { Text(it, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans, maxLines = 1) }
        }
        c.number?.let { n ->
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Call ${c.name}") { com.aura.launcher.ActivityBridge.start(context, com.aura.launcher.data.ContactSearch.dialIntent(n)) },
                contentAlignment = Alignment.Center,
            ) { AuraGlyph(Glyph.Phone, size = 20.dp, color = Aura.Ink) }
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Text ${c.name}") { com.aura.launcher.ActivityBridge.start(context, com.aura.launcher.data.ContactSearch.messageIntent(n)) },
                contentAlignment = Alignment.Center,
            ) { AuraGlyph(Glyph.Message, size = 20.dp, color = Aura.Ink) }
        }
    }
}
