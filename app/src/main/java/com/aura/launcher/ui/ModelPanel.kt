package com.aura.launcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ChatItem
import com.aura.launcher.data.ThinkingLevel
import com.aura.launcher.gemini.GeminiApi
import com.aura.launcher.gemini.GeminiModel
import com.aura.launcher.ui.theme.Aura

/** Friendly name for a model id when the API list hasn't loaded yet. */
fun prettyModelName(id: String): String =
    "Gemini " + id.removePrefix("gemini-").split('-').joinToString(" ") { part ->
        if (part == "lite") "Lite" else part.replaceFirstChar(Char::uppercase)
    }.replace("Flash Lite", "Flash-Lite")

private fun nameOf(state: GeminiSheetState, id: String) =
    state.models.firstOrNull { it.id == id }?.displayName ?: prettyModelName(id)

/** The tappable "✦ Gemini 4 Hydrogen · Thinking: Auto ⌄" bar under the header. */
@Composable
fun ModelBar(state: GeminiSheetState, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aura.Glass)
            .border(1.dp, if (expanded) Aura.GeminiViolet.copy(alpha = 0.6f) else Aura.Stroke, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "Change model and thinking", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Sparkle, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nameOf(state, state.model), color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("Thinking: ${state.thinking.label}", color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(if (expanded) "Done" else "Change", color = Aura.GeminiViolet, fontSize = 14.sp, fontFamily = Aura.Sans)
    }
}

private data class Pick(val title: String, val blurb: String, val model: GeminiModel)

/** Best / Balanced / Fastest, preferring the always-up-to-date "-latest" aliases. */
private fun recommended(models: List<GeminiModel>): List<Pick> {
    fun find(alias: String, match: (String) -> Boolean) =
        models.firstOrNull { it.id == alias } ?: models.firstOrNull { match(it.id) }
    return listOfNotNull(
        find("gemini-pro-latest") { "pro" in it }?.let { Pick("Best", "Most capable · slower", it) },
        find("gemini-flash-latest") { "flash" in it && "lite" !in it }?.let { Pick("Balanced", "Fast and smart · recommended", it) },
        find("gemini-flash-lite-latest") { "flash-lite" in it }?.let { Pick("Fastest", "Quickest replies · lightest", it) },
    ).distinctBy { it.model.id }
}

private fun familyOf(id: String): String =
    if (id.endsWith("-latest")) "Always up to date"
    else Regex("""gemini-(\d+(?:\.\d+)?)""").find(id)?.groupValues?.get(1)?.let { "Gemini $it" } ?: "Other"

@Composable
fun ModelPanel(
    state: GeminiSheetState,
    onSelectModel: (String) -> Unit,
    onSelectThinking: (ThinkingLevel) -> Unit,
    onRetry: () -> Unit,
    onRunCheck: () -> Unit,
    onDone: () -> Unit,
) {
    val picks = remember(state.models) { recommended(state.models) }
    var showAll by remember { mutableStateOf(state.models.isNotEmpty() && picks.none { it.model.id == state.model }) }

    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).animateContentSize()) {
        PanelTitle("Model")
        when {
            state.modelsLoading && state.models.isEmpty() -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Aura.GeminiViolet, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading your models…", color = Aura.InkDim, fontSize = 14.sp)
            }
            state.modelsError != null && state.models.isEmpty() -> Column(Modifier.padding(8.dp)) {
                Text(state.modelsError, color = Color(0xFFFFB4A9), fontSize = 13.sp)
                Text("Try again", color = Aura.GeminiViolet, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onRetry).padding(8.dp))
            }
        }

        Column(Modifier.glass(RoundedCornerShape(22.dp)).padding(vertical = 4.dp)) {
            picks.forEach { p ->
                OptionRow(
                    title = p.title,
                    subtitle = "${p.model.displayName}\n${p.blurb}",
                    selected = p.model.id == state.model,
                ) { onSelectModel(p.model.id) }
            }
            if (picks.isEmpty() && state.models.isEmpty()) {
                OptionRow(nameOf(state, state.model), "Current model", selected = true) {}
            }
        }

        if (state.models.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showAll = !showAll }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("All models (${state.models.size})", color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans, modifier = Modifier.weight(1f))
                Text(if (showAll) "Hide" else "Show", color = Aura.GeminiViolet, fontSize = 14.sp)
            }
            if (showAll) {
                state.models.groupBy { familyOf(it.id) }.forEach { (family, list) ->
                    Text(family.uppercase(), color = Aura.InkFaint, fontSize = 11.sp, letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp))
                    Column(Modifier.glass(RoundedCornerShape(18.dp)).padding(vertical = 2.dp)) {
                        list.forEach { m ->
                            OptionRow(
                                title = m.displayName,
                                subtitle = m.description.lineSequence().firstOrNull()?.take(90)?.ifBlank { null } ?: m.id,
                                selected = m.id == state.model,
                                compact = true,
                            ) { onSelectModel(m.id) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        PanelTitle("Thinking")
        ThinkingSelector(state.thinking, onSelectThinking)
        Text(
            state.thinking.blurb + if (GeminiApi.usesBudget(state.model) && state.thinking != ThinkingLevel.AUTO) " (sent as a token budget for this model)" else "",
            color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Text(
            "If a model doesn't support a level, AURA uses the closest one it does and lets you know.",
            color = Aura.InkFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Run Gemini check", color = Aura.InkDim, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onRunCheck).padding(10.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "Done", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Aura.geminiFill).clickable(onClick = onDone)
                    .padding(horizontal = 26.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp))
}

@Composable
private fun OptionRow(title: String, subtitle: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (selected) Aura.GeminiViolet.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) Aura.GeminiViolet else Aura.InkFaint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Aura.GeminiViolet)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Aura.Ink, fontSize = if (compact) 15.sp else 16.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Five-way segmented control: Auto · Minimal · Low · Medium · High. */
@Composable
private fun ThinkingSelector(current: ThinkingLevel, onSelect: (ThinkingLevel) -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThinkingLevel.entries.forEach { level ->
            val selected = level == current
            Text(
                level.label,
                color = if (selected) Color.White else Aura.InkDim,
                fontSize = 13.sp, fontFamily = Aura.Sans, maxLines = 1, textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .then(if (selected) Modifier.background(Aura.geminiFill) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(level) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** Collapsible "✦ Thought for 3s" summary in the chat. */
@Composable
fun ThoughtBubble(item: ChatItem) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 28.dp).animateContentSize()) {
        Text(
            "Thought for ${formatSeconds(item.millis)}  ${if (open) "▾" else "▸"}",
            color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp),
        )
        if (open) {
            Text(
                item.text, color = Aura.InkFaint, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontFamily = Aura.Sans,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

private fun formatSeconds(ms: Long): String = if (ms < 1000) "<1s" else "${(ms + 500) / 1000}s"
