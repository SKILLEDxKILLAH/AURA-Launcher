package com.aura.launcher.ui

import android.appwidget.AppWidgetHostView
import android.util.SizeF
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AuraWidgets
import com.aura.launcher.data.PlacedWidget
import com.aura.launcher.data.WidgetChoice
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home screen's widgets, stacked full width between the clock and Gemini. Hold a widget
 * to resize, move or remove it; everything else (taps, scrolling inside it) goes to the widget.
 */
@Composable
fun WidgetArea(widgets: AuraWidgets, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val placed = widgets.placed
        placed.forEachIndexed { index, w ->
            key(w.id) { WidgetSlot(w, widgets, isFirst = index == 0, isLast = index == placed.lastIndex) }
        }
    }
}

@Composable
private fun WidgetSlot(w: PlacedWidget, widgets: AuraWidgets, isFirst: Boolean, isLast: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(w.heightDp.dp)
            .testTag("widget_${w.id}")
            .widgetLongPress {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                menu = true
            }
    ) {
        AndroidView(
            factory = { ctx ->
                widgets.createView(ctx, w.id) ?: android.widget.TextView(ctx).apply {
                    text = "This widget isn't available"
                    setTextColor(android.graphics.Color.GRAY)
                    gravity = android.view.Gravity.CENTER
                }
            },
            update = { v ->
                // Tell the widget its real size so it picks a layout that fits.
                if (v is AppWidgetHostView && v.width > 0) {
                    val wDp = v.width / density.density
                    runCatching {
                        v.updateAppWidgetSize(widgets.sizeOptions(w.heightDp, wDp.toInt()), listOf(SizeF(wDp, w.heightDp.toFloat())))
                    }
                }
            },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
        )
        DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            shape = RoundedCornerShape(22.dp), containerColor = Aura.Menu, border = BorderStroke(1.dp, Aura.Stroke),
        ) {
            val name = remember(w.id) { widgets.info(w.id)?.let { runCatching { it.loadLabel(view.context.packageManager) }.getOrNull() } }
            name?.let {
                Text(it, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (w.heightDp < AuraWidgets.MAX_HEIGHT) Item("Make taller") { menu = false; widgets.resize(w.id, STEP) }
            if (w.heightDp > AuraWidgets.MIN_HEIGHT) Item("Make shorter") { menu = false; widgets.resize(w.id, -STEP) }
            if (!isFirst) Item("Move up") { menu = false; widgets.move(w.id, -1) }
            if (!isLast) Item("Move down") { menu = false; widgets.move(w.id, 1) }
            Item("Remove") { menu = false; widgets.remove(w.id) }
        }
    }
}

private const val STEP = 48

@Composable
private fun Item(label: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(label, color = Aura.Ink, fontFamily = Aura.Sans) }, onClick = onClick)

/**
 * Press and hold anywhere on a widget → [onLongPress], even over the widget's own buttons.
 * Claims the press so the home screen's own press-and-hold menu stays out of the way, and
 * once it fires, cancels the touch for the widget so its button doesn't also get tapped.
 */
private fun Modifier.widgetLongPress(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") true
        }
        if (released == null) {
            onLongPress()
            // Consume the rest of this touch before the widget sees it (it gets a cancel).
            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
            } while (e.changes.any { it.pressed })
        }
    }
}.pointerInput(Unit) {
    // Main pass, after the widget: mark the press as taken so home's press-and-hold menu
    // (which only fires on unclaimed presses) doesn't open on top of the widget menu.
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
}

/** Full-screen list of every widget your apps offer, grouped by app, with previews. */
@Composable
fun WidgetPicker(
    widgets: AuraWidgets,
    repo: AppRepository,
    onPick: (WidgetChoice) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val choices by produceState<List<WidgetChoice>?>(null) { value = withContext(Dispatchers.IO) { widgets.choices() } }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Aura.Surface).systemBarsPadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Widgets", color = Aura.Ink, fontSize = 28.sp, fontFamily = Aura.Sans, modifier = Modifier.weight(1f))
            Text(
                "Done", color = Aura.Accent, fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(10.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).glass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuraGlyph(Glyph.Search, size = 18.dp, color = Aura.InkDim)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search widgets", color = Aura.InkDim, fontSize = 15.sp, fontFamily = Aura.Sans)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans),
                    cursorBrush = SolidColor(Aura.Accent), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val list = choices
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Aura.Accent)
            }
            list.isEmpty() -> Text(
                "None of your apps offer home-screen widgets.", color = Aura.InkDim, fontSize = 15.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
            else -> {
                val q = query.trim()
                val shown = if (q.isEmpty()) list else list.filter { it.appLabel.contains(q, true) || it.label.contains(q, true) }
                val groups = shown.groupBy { it.packageName to it.user }
                LazyColumn(Modifier.fillMaxSize().testTag("widget_list")) {
                    groups.forEach { (k, group) ->
                        item(key = "h_${k.first}_${k.second.hashCode()}") {
                            Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PackageIcon(k.first, repo, 28.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(group.first().appLabel, color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(8.dp))
                                Text("${group.size}", color = Aura.InkFaint, fontSize = 13.sp)
                            }
                        }
                        items(group, key = { "w_${it.info.provider.flattenToString()}_${it.user.hashCode()}" }) { choice ->
                            WidgetChoiceCard(choice, context) { onPick(choice) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetChoiceCard(choice: WidgetChoice, context: android.content.Context, onClick: () -> Unit) {
    val preview by produceState<ImageBitmap?>(null, choice.info.provider) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dpi = context.resources.displayMetrics.densityDpi
                val d = choice.info.loadPreviewImage(context, dpi) ?: choice.info.loadIcon(context, dpi)
                val maxW = 600
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 300
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 300
                val scale = minOf(1f, maxW.toFloat() / w)
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).glass(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "Add to home screen", onClick = onClick).padding(14.dp)
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 180.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(choice.label, color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            choice.cells?.let { Text(it, color = Aura.InkFaint, fontSize = 13.sp) }
        }
    }
}
