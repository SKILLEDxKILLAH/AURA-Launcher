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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.gemini.NanoStatus
import com.aura.launcher.ui.theme.Aura

/** Everything the "how should Gemini run?" chooser shows. */
data class GeminiSetupState(
    val nanoStatus: NanoStatus = NanoStatus.CHECKING,
    val nanoWorking: Boolean = false,
    val nanoError: String? = null,
    val keyChecking: Boolean = false,
    val keyError: String? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
)

data class GeminiSetupActions(
    val useNano: () -> Unit = {},
    val openKeyPage: () -> Unit = {},
    val pasteKey: () -> Unit = {},
    val signIn: () -> Unit = {},
    val openSettings: () -> Unit = {},
)

/**
 * First-time Gemini setup, easiest first:
 * 1. On this phone (Gemini Nano): one tap, no account, when the phone supports it.
 * 2. Free API key: open AI Studio, tap Create, copy, come back and tap Paste. No Cloud console.
 * 3. Continue with Google: for people the developer added to their Google Cloud project.
 */
@Composable
fun GeminiSetupCard(state: GeminiSetupState, actions: GeminiSetupActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 2.dp, vertical = 4.dp).testTag("gemini_setup"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Choose how Gemini runs", color = Aura.Ink, fontSize = 21.sp, fontFamily = Aura.Sans)
        Text(
            "No Google Cloud setup needed. You can change this later in AURA settings.",
            color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans,
        )

        if (state.nanoStatus != NanoStatus.UNAVAILABLE) NanoOption(state, actions)
        KeyOption(state, actions, recommended = state.nanoStatus == NanoStatus.UNAVAILABLE)
        GoogleOption(state, actions)
    }
}

@Composable
private fun Option(
    title: String,
    badge: String?,
    body: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Aura.Ink, fontSize = 17.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            badge?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it, color = Aura.OnAccent, fontSize = 11.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Aura.Accent).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(body, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Aura.Accent)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Aura.OnAccent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = Aura.OnAccent, fontSize = 15.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryButton(label: String, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Aura.Accent, RoundedCornerShape(24.dp))
            .clickable(enabled = !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Aura.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = Aura.Accent, fontSize = 15.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorText(text: String?) {
    text?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Aura.Error, fontSize = 13.sp, fontFamily = Aura.Sans)
    }
}

@Composable
private fun NanoOption(state: GeminiSetupState, actions: GeminiSetupActions) {
    Option(
        title = "On this phone",
        badge = "Easiest",
        body = "Gemini Nano runs on your phone: no account, no key, and nothing you ask leaves the device. " +
            "Answers are shorter than cloud Gemini. AURA itself handles everyday actions like timers, alarms, " +
            "the flashlight, calls, texts, directions and opening apps.",
    ) {
        when (state.nanoStatus) {
            NanoStatus.CHECKING -> Text("Checking this phone…", color = Aura.InkFaint, fontSize = 13.sp)
            else -> PrimaryButton(
                when {
                    state.nanoWorking && state.nanoStatus != NanoStatus.AVAILABLE -> "Downloading Gemini Nano…"
                    state.nanoStatus == NanoStatus.AVAILABLE -> "Use Gemini on this phone"
                    state.nanoStatus == NanoStatus.DOWNLOADING -> "Finish download and use"
                    else -> "Download and use (one time)"
                },
                busy = state.nanoWorking,
                onClick = actions.useNano,
            )
        }
        ErrorText(state.nanoError)
    }
}

@Composable
private fun KeyOption(state: GeminiSetupState, actions: GeminiSetupActions, recommended: Boolean) {
    var opened by rememberSaveable { mutableStateOf(false) }
    Option(
        title = "Free Gemini key",
        badge = if (recommended) "Recommended" else null,
        body = "Full cloud Gemini, free for everyday use. Takes about a minute and needs no Google Cloud console or card.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Step(1, "Open Google AI Studio and sign in with your Google account.")
            Step(2, "Tap “Create API key”, then “Copy”.")
            Step(3, "Come back here and tap “Paste my key”.")
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Open Google AI Studio") { opened = true; actions.openKeyPage() }
        Spacer(Modifier.height(8.dp))
        val paste = "Paste my key"
        if (opened || state.keyChecking || state.keyError != null) {
            PrimaryButton(if (state.keyChecking) "Checking key…" else paste, busy = state.keyChecking, onClick = actions.pasteKey)
        } else {
            Text(
                "Already copied a key? Paste it", color = Aura.Accent, fontSize = 14.sp, fontFamily = Aura.Sans,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = actions.pasteKey).padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
        ErrorText(state.keyError)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(Aura.GlassStrong),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Aura.Ink, fontSize = 11.sp, fontFamily = Aura.Sans) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, lineHeight = 18.sp)
    }
}

@Composable
private fun GoogleOption(state: GeminiSetupState, actions: GeminiSetupActions) {
    Option(
        title = "Continue with Google",
        badge = null,
        body = "Sign in with your Google account instead of a key. Works for accounts the app's developer has added; " +
            "otherwise Google shows “Access blocked”.",
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)
                .border(1.dp, Aura.Stroke, RoundedCornerShape(24.dp))
                .clickable(enabled = !state.signingIn, onClick = actions.signIn),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (state.signingIn) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
            } else {
                GoogleG(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (state.signingIn) "Waiting for Google…" else "Continue with Google",
                color = Color(0xFF1F1F1F), fontSize = 15.sp, fontFamily = Aura.Sans, fontWeight = FontWeight.Medium,
            )
        }
        ErrorText(state.signInError)
    }
}
