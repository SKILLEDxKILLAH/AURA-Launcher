package com.aura.launcher.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.Sky
import com.aura.launcher.data.TopLineMode
import com.aura.launcher.data.WeatherNow
import com.aura.launcher.ui.theme.Aura

fun defaultTopLabel() = "Android ${Build.VERSION.RELEASE}"

/** Everything the top-left line needs, shared by the home screen and the drawer. */
data class TopLineState(
    val mode: TopLineMode,
    val label: String?,
    val weather: WeatherNow?,
    val weatherNeedsPermission: Boolean,
    val onLabelChange: (String) -> Unit,
    val onWeather: () -> Unit,
    val onLongPress: () -> Unit,
) {
    companion object {
        val Default = TopLineState(TopLineMode.LABEL, null, null, false, {}, {}, {})
    }
}

/**
 * Top-left of the home screen: either the user's own text (tap to edit in place) or a
 * Pixel "At a Glance"-style weather line (tap to open the weather app).
 */
@Composable
fun TopLine(state: TopLineState, editable: Boolean) {
    when (state.mode) {
        TopLineMode.LABEL -> EditableLabel(state.label ?: defaultTopLabel(), editable, state.onLabelChange, state.onLongPress)
        TopLineMode.WEATHER -> GlanceWeather(state.weather, state.weatherNeedsPermission, state.onWeather, state.onLongPress)
    }
}

@Composable
private fun EditableLabel(label: String, editable: Boolean, onChange: (String) -> Unit, onLongPress: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var draft by remember(editing) { mutableStateOf(TextFieldValue(label, TextRange(0, label.length))) }
    var hadFocus by remember(editing) { mutableStateOf(false) }

    fun commit() {
        if (!editing) return
        editing = false
        keyboard?.hide()
        onChange(draft.text)
    }

    BackHandler(enabled = editing) {
        editing = false
        keyboard?.hide()
    }

    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = !editing,
                onClickLabel = if (editable) "Edit text" else null,
                onClick = { if (editable) editing = true },
                onLongClick = onLongPress,
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Pulse, size = 16.dp, color = Aura.Ink)
        Spacer(Modifier.width(10.dp))
        if (editing) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = draft,
                onValueChange = { if (it.text.length <= AuraPrefs.MAX_LABEL) draft = it },
                singleLine = true,
                textStyle = TextStyle(color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans),
                cursorBrush = SolidColor(Aura.GeminiViolet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 260.dp)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) commit() }
                    .drawBehind {
                        val y = size.height + 3.dp.toPx()
                        drawLine(Aura.GeminiViolet, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                    },
            )
        } else {
            Text(label, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GlanceWeather(weather: WeatherNow?, needsPermission: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClickLabel = "Open weather", onClick = onClick, onLongClick = onLongPress)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (weather != null) {
            WeatherGlyph(weather.sky, weather.isDay, size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text("${weather.temperature}${weather.unit}", color = Aura.Ink, fontSize = 18.sp, fontFamily = Aura.Sans)
            Text("  ·  ${weather.description}", color = Aura.InkDim, fontSize = 16.sp, fontFamily = Aura.Sans, maxLines = 1)
        } else {
            WeatherGlyph(Sky.PARTLY, true, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (needsPermission) "Tap for local weather" else "Weather…",
                color = Aura.InkDim, fontSize = 16.sp, fontFamily = Aura.Sans,
            )
        }
    }
}
