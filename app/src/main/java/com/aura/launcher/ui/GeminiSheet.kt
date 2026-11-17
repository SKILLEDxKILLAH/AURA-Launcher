package com.aura.launcher.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ChatItem
import com.aura.launcher.ChatRole
import com.aura.launcher.ui.theme.Aura

private val suggestions = listOf(
    "Turn on the flashlight",
    "Set a timer for 10 minutes",
    "Text Mom I'm on my way",
    "Navigate home",
    "Wake me up at 6:30",
    "How much battery is left?",
    "Play something relaxing",
)

@Composable
fun GeminiSheet(
    chat: List<ChatItem>,
    busy: Boolean,
    agentReady: Boolean,
    model: String,
    onSend: (String) -> Unit,
    onVoice: () -> Unit,
    onOpenGeminiApp: () -> Unit,
    onClear: () -> Unit,
    onSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onSend)
        }
    }
    val mic: () -> Unit = {
        if (agentReady) {
            runCatching {
                speech.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Gemini")
                )
            }.onFailure { onVoice() }
        } else {
            onVoice()
        }
    }
    val submit: () -> Unit = {
        val t = text.trim()
        if (t.isNotEmpty()) { onSend(t); text = "" }
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
                        if (agentReady) "Acting on your phone · $model" else "Sends to the Google Gemini app",
                        color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans,
                    )
                }
                if (chat.isNotEmpty()) SmallTextButton("New chat", onClear)
                IconCircle(Glyph.Close, "Close", onDismiss)
            }
            Spacer(Modifier.height(14.dp))

            if (chat.isEmpty()) {
                if (!agentReady) {
                    Text(
                        "Tip: add a Gemini API key in AURA settings to let Gemini act on your phone right here " +
                            "(flashlight, alarms, calls, messages, navigation, settings).",
                        color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onSetup).padding(4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(suggestions) { s ->
                        Text(
                            s, color = Aura.Ink, fontSize = 14.sp, fontFamily = Aura.Sans,
                            modifier = Modifier.glass(RoundedCornerShape(20.dp)).clickable { onSend(s) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(chat.size) { listState.animateScrollToItem(chat.size - 1) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chat) { ChatBubble(it) }
                    if (busy) item {
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
                    .geminiGlow(30.dp, blur = 12.dp, alpha = 0.35f)
                    .glass(shape, fill = Color(0xFF2E2A27), stroke = Aura.GeminiViolet.copy(alpha = 0.55f))
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("What can I do for you?", color = Aura.InkDim, fontSize = 17.sp, fontFamily = Aura.Sans)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans),
                        cursorBrush = SolidColor(Aura.GeminiViolet),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (text.isBlank()) MicButton(mic, 44.dp) else IconCircle(Glyph.Send, "Send", submit, filled = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTextButton("Open Gemini app", onOpenGeminiApp, Glyph.External)
                SmallTextButton("Talk to Gemini", onVoice, Glyph.Mic)
            }
        }
    }
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
private fun SmallTextButton(label: String, onClick: () -> Unit, glyph: Glyph? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let { AuraGlyph(it, size = 15.dp, color = Aura.InkDim); Spacer(Modifier.width(6.dp)) }
        Text(label, color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans)
    }
}
