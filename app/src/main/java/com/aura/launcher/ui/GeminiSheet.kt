package com.aura.launcher.ui

import android.Manifest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ActivityBridge
import com.aura.launcher.ChatItem
import com.aura.launcher.ChatRole
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "Turn on the flashlight",
    "Set a timer for 10 minutes",
    "Text Mom I'm on my way",
    "Navigate home",
    "Wake me up at 6:30",
    "How much battery is left?",
    "Play something relaxing",
)

data class GeminiSheetState(
    val chat: List<ChatItem>,
    val busy: Boolean,
    val needsSignIn: Boolean,
    val signingIn: Boolean,
    val signInError: String?,
    val accountLabel: String?,
    val model: String,
    val listenRequest: Int,
    val models: List<com.aura.launcher.gemini.GeminiModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val thinking: com.aura.launcher.data.ThinkingLevel = com.aura.launcher.data.ThinkingLevel.AUTO,
)

@Composable
fun GeminiSheet(
    state: GeminiSheetState,
    onSend: (String) -> Unit,
    onSignIn: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onLoadModels: () -> Unit = {},
    onSelectModel: (String) -> Unit = {},
    onRunCheck: () -> Unit = {},
    onSelectThinking: (com.aura.launcher.data.ThinkingLevel) -> Unit = {},
) {
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    val submit: (String) -> Unit = { raw ->
        val t = raw.trim()
        if (t.isNotEmpty()) { onSend(t); text = "" }
    }
    val listen: () -> Unit = {
        scope.launch {
            if (ActivityBridge.ensurePermission(context, Manifest.permission.RECORD_AUDIO)) {
                voice.start { spoken -> submit(spoken) }
            }
        }
    }
    LaunchedEffect(state.listenRequest) {
        if (state.listenRequest > 0 && !state.needsSignIn) listen() else if (!state.needsSignIn) runCatching { focus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99100E0C))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 48.dp)
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .background(Color(0xF2262320))
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuraGlyph(Glyph.Sparkle, size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Gemini", color = Aura.Ink, fontSize = 20.sp, fontFamily = Aura.Sans)
                    Text(
                        state.accountLabel ?: "Built into AURA",
                        color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (state.chat.isNotEmpty() && !panelOpen) SmallTextButton("New chat", onClear)
                IconCircle(Glyph.Close, "Close", onDismiss)
            }
            Spacer(Modifier.height(12.dp))

            if (state.needsSignIn) {
                SignInCard(state.signingIn, state.signInError, onSignIn, onOpenSettings)
                return@Column
            }

            ModelBar(state, expanded = panelOpen) {
                panelOpen = !panelOpen
                if (panelOpen) { voice.stop(); onLoadModels() }
            }
            Spacer(Modifier.height(12.dp))
            if (panelOpen) {
                ModelPanel(
                    state = state,
                    onSelectModel = onSelectModel,
                    onSelectThinking = onSelectThinking,
                    onRetry = onLoadModels,
                    onRunCheck = { panelOpen = false; onRunCheck() },
                    onDone = { panelOpen = false },
                    // Shrinks to fit when the keyboard or a small screen leaves less room.
                    modifier = Modifier.weight(1f, fill = false),
                )
                return@Column
            }

            if (state.chat.isEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(suggestions) { s ->
                        Text(
                            s, color = Aura.Ink, fontSize = 14.sp, fontFamily = Aura.Sans,
                            modifier = Modifier.glass(RoundedCornerShape(20.dp)).clickable { submit(s) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                // Keep the newest message in view, including when the keyboard opens and the list shrinks.
                val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                LaunchedEffect(state.chat.size, state.busy, imeBottom) {
                    // Index from the data, not layoutInfo (which is empty before the first layout).
                    val last = state.chat.lastIndex + if (state.busy) 1 else 0
                    if (last >= 0) listState.animateScrollToItem(last)
                }
                LazyColumn(
                    state = listState,
                    // weight(fill = false): up to 380dp tall, but gives up space first so the
                    // typing field below always stays above the keyboard.
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.chat) { ChatBubble(it) }
                    if (state.busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Aura.GeminiViolet, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Working on it…", color = Aura.InkDim, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val shape = RoundedCornerShape(30.dp)
            Row(
                Modifier.fillMaxWidth().height(60.dp)
                    .geminiGlow(30.dp, blur = 12.dp, alpha = if (voice.listening) 0.7f else 0.35f)
                    .glass(shape, fill = Color(0xFF2E2A27), stroke = Aura.GeminiViolet.copy(alpha = 0.55f))
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    when {
                        voice.listening -> Text(
                            voice.partial.ifEmpty { "Listening…" },
                            color = if (voice.partial.isEmpty()) Aura.InkDim else Aura.Ink,
                            fontSize = 17.sp, fontFamily = Aura.Sans, maxLines = 2,
                        )
                        else -> {
                            if (text.isEmpty()) Text(
                                voice.error ?: "What can I do for you?",
                                color = Aura.InkDim, fontSize = 17.sp, fontFamily = Aura.Sans,
                            )
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans),
                                cursorBrush = SolidColor(Aura.GeminiViolet),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submit(text) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                    }
                }
                when {
                    voice.listening -> ListeningButton(voice.level) { voice.stop() }
                    text.isBlank() -> MicButton(listen, 44.dp)
                    else -> IconCircle(Glyph.Send, "Send", { submit(text) }, filled = true)
                }
            }
        }
    }
}

@Composable
private fun SignInCard(signingIn: Boolean, error: String?, onSignIn: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text("Use Gemini right here", color = Aura.Ink, fontSize = 22.sp, fontFamily = Aura.Sans)
        Spacer(Modifier.height(6.dp))
        Text(
            "Sign in with your Google account and allow AURA to use Gemini. You only do this once — " +
                "after that Gemini answers and acts on your phone straight from the launcher.",
            color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White)
                .clickable(enabled = !signingIn, onClick = onSignIn),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (signingIn) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Waiting for Google…", color = Color(0xFF1F1F1F), fontSize = 16.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
            } else {
                GoogleG(Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Continue with Google", color = Color(0xFF1F1F1F), fontSize = 16.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Color(0xFFFFB4A9), fontSize = 13.sp, fontFamily = Aura.Sans)
            SmallTextButton("Open AURA settings", onOpenSettings)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Four-color "G" mark drawn with arcs, used on the sign-in button. */
@Composable
private fun GoogleG(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.minDimension * 0.2f
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        drawArc(Color(0xFFEA4335), -150f, 105f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFFBBC05), 150f, 60f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF34A853), 45f, 105f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF4285F4), -10f, 55f, false, topLeft, arcSize, style = style)
        drawRect(
            Color(0xFF4285F4),
            androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2 - stroke / 2),
            androidx.compose.ui.geometry.Size(size.width / 2 - inset / 2, stroke),
        )
    }
}

@Composable
private fun ListeningButton(level: Float, onStop: () -> Unit) {
    val pulse by animateFloatAsState(1f + level * 0.25f, label = "pulse")
    Box(
        Modifier.size(44.dp).scale(pulse).clip(CircleShape).background(Aura.geminiFill)
            .clickable(onClickLabel = "Stop listening", onClick = onStop),
        contentAlignment = Alignment.Center,
    ) { AuraGlyph(Glyph.Mic, size = 20.dp, color = Color.White) }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    when (item.role) {
        ChatRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                item.text, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Aura.GlassStrong)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        ChatRole.GEMINI -> Row(verticalAlignment = Alignment.Top) {
            AuraGlyph(Glyph.Sparkle, size = 18.dp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.width(10.dp))
            Text(item.text, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
        }
        ChatRole.ACTION -> Row(
            Modifier.padding(start = 28.dp).glass(RoundedCornerShape(14.dp), fill = Color(0x14FFFFFF))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (item.ok) "✓" else "!", color = if (item.ok) Aura.GeminiBlue else Color(0xFFFFB4A9), fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(item.text, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans)
        }
        ChatRole.ERROR -> Text(item.text, color = Color(0xFFFFB4A9), fontSize = 14.sp, modifier = Modifier.padding(start = 28.dp))
        ChatRole.THOUGHT -> ThoughtBubble(item)
        ChatRole.NOTICE -> Text("ⓘ  ${item.text}", color = Aura.InkFaint, fontSize = 13.sp, fontFamily = Aura.Sans, modifier = Modifier.padding(start = 28.dp))
    }
}

@Composable
private fun IconCircle(glyph: Glyph, label: String, onClick: () -> Unit, filled: Boolean = false) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .then(if (filled) Modifier.background(Aura.geminiFill) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AuraGlyph(glyph, size = 20.dp, color = if (filled) Color.White else Aura.InkDim) }
}

@Composable
private fun SmallTextButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
