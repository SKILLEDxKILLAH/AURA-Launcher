package com.aura.launcher.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ActivityBridge
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.GeminiAuthMode
import com.aura.launcher.data.TopLineMode
import com.aura.launcher.gemini.GoogleAccountAuth
import com.aura.launcher.ui.theme.Aura

@Composable
fun SettingsScreen(
    prefs: AuraPrefs,
    signingIn: Boolean,
    signInError: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRunCheck: () -> Unit,
    requestPermissions: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Color(0xF71D1B19)).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraGlyph(Glyph.Sparkle, size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Text("AURA", color = Aura.Ink, fontSize = 30.sp, fontFamily = Aura.Sans, modifier = Modifier.weight(1f))
            Text("Done", color = Aura.GeminiViolet, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(10.dp))
        }

        Group("Home") {
            ActionRow("Set AURA as your home app", "Required for the home button to open AURA") {
                val rm = context.getSystemService(RoleManager::class.java)
                val intent = if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
                } else Intent(Settings.ACTION_HOME_SETTINGS)
                ActivityBridge.activity?.startActivity(intent)
            }
            Text("Top of the home screen", color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
            ChoiceRow(
                "Your text",
                "“${prefs.topLabel ?: defaultTopLabel()}” — tap it on the home screen to change it",
                prefs.topLineMode == TopLineMode.LABEL,
            ) { prefs.updateTopLineMode(TopLineMode.LABEL) }
            ChoiceRow(
                "Weather (At a Glance)",
                "Current conditions like the Pixel launcher; tap to open your weather app",
                prefs.topLineMode == TopLineMode.WEATHER,
            ) { prefs.updateTopLineMode(TopLineMode.WEATHER) }
            ToggleRow("AURA backdrop", "Off uses your system wallpaper", prefs.useAuraBackdrop, prefs::updateUseAuraBackdrop)
            ToggleRow("Weather under the clock", "Local conditions via Open‑Meteo", prefs.showWeather, prefs::updateShowWeather)
            ToggleRow("Fahrenheit", null, prefs.fahrenheit, prefs::updateFahrenheit)
        }

        Group("App suggestions") {
            Text(
                "The top of the app drawer suggests apps you're likely to want now, learned on this phone from when " +
                    "and how often you open them. Nothing leaves your device.",
                color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            val usageOn = remember { com.aura.launcher.data.AppRepository.hasUsageAccess(context) }
            ActionRow(
                "Learn from all app use",
                (if (usageOn) "On" else "Off") + " · also count apps opened from notifications, links and other apps (Usage access)",
            ) { ActivityBridge.start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

        Group("Gestures") {
            val gesturesOn = remember { com.aura.launcher.system.SystemPanels.isGestureServiceEnabled(context) }
            ActionRow(
                "AURA gestures",
                (if (gesturesOn) "On" else "Off") + " · swipe down with one finger for notifications, two fingers for Quick Settings",
            ) { ActivityBridge.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (!gesturesOn) {
                ActionRow(
                    "Greyed out? Allow restricted settings",
                    "Opens AURA's App info: tap ⋮ → Allow restricted settings, confirm, then turn AURA gestures on",
                ) {
                    ActivityBridge.start(
                        context,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }
            ActionRow("Wallpaper & style", "Or press and hold anywhere on the home screen") {
                ActivityBridge.start(context, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper & style"))
            }
        }

        Group("Gemini") {
            val email = prefs.accountEmail
            if (prefs.authMode == GeminiAuthMode.GOOGLE_ACCOUNT && email != null) {
                AccountRow(prefs.accountName ?: "Google account", email, onSignOut)
            } else if (prefs.authMode == GeminiAuthMode.GOOGLE_ACCOUNT) {
                ActionRow(
                    if (signingIn) "Waiting for Google…" else "Sign in with Google",
                    "Allow AURA to use Gemini with your Google account",
                    onSignIn,
                )
                signInError?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
            }
            ActionRow("Grant phone permissions", "Contacts, calling, microphone and location for Gemini actions and weather") {
                requestPermissions(
                    listOf(
                        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                        Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }

            ActionRow("Run Gemini check", "Tests sign-in, a live reply and phone actions, with a report you can copy", onRunCheck)

            var advanced by remember { mutableStateOf(prefs.authMode == GeminiAuthMode.API_KEY) }
            ActionRow("Advanced", if (advanced) "Hide model and API key options" else "Model, API key instead of Google sign-in") { advanced = !advanced }
            if (advanced) {
                var model by remember { mutableStateOf(prefs.model) }
                Field("Model ID (or pick one from the Gemini panel)", model, { model = it; prefs.updateModel(it) })
                ToggleRow(
                    "Use an API key instead",
                    "Skip Google sign-in and authenticate with an AI Studio key",
                    prefs.authMode == GeminiAuthMode.API_KEY,
                ) { prefs.updateAuthMode(if (it) GeminiAuthMode.API_KEY else GeminiAuthMode.GOOGLE_ACCOUNT) }
                if (prefs.authMode == GeminiAuthMode.API_KEY) {
                    var key by remember { mutableStateOf(prefs.apiKey) }
                    var reveal by remember { mutableStateOf(false) }
                    Field(
                        label = "Gemini API key",
                        value = key,
                        onChange = { key = it; prefs.updateApiKey(it) },
                        transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    )
                    Text(
                        if (reveal) "Hide key" else "Show key", color = Aura.GeminiViolet, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).clickable { reveal = !reveal },
                    )
                }
            }
        }

        Group("Google setup (one time, for the developer)") {
            val sha1 = remember { GoogleAccountAuth.signingSha1(context) }
            Text(
                "Google sign-in needs an Android OAuth client in your Google Cloud project:\n" +
                    "1. Enable “Generative Language API”.\n" +
                    "2. Google Auth Platform → Audience: External, Testing; add your Gmail under Test users.\n" +
                    "3. Clients → Create → Android, with the values below.",
                color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(6.dp),
            )
            CopyRow("Package name", context.packageName)
            CopyRow("SHA-1 certificate fingerprint", sha1)
        }

        Group("About") {
            Text("AURA Launcher 1.9 · Built for Android 17", color = Aura.InkDim, fontSize = 14.sp, modifier = Modifier.padding(6.dp))
            Text("Tips: swipe left for apps · swipe right for your news feed · swipe down for notifications (two fingers: Quick Settings) · press and hold for wallpaper & settings · drag the letters on the right · tap the mic to talk to Gemini · tap the top-left text to edit it, long-press it for these settings.",
                color = Aura.InkFaint, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(22.dp))
    SectionLabel(title, Modifier.padding(start = 6.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(10.dp)) { content() }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
            subtitle?.let { Text(it, color = Aura.InkDim, fontSize = 13.sp) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Aura.GeminiViolet, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp)) {
        Text(title, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
        Text(subtitle, color = Aura.InkDim, fontSize = 13.sp)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, transformation: VisualTransformation = VisualTransformation.None) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = transformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Aura.GeminiViolet,
            unfocusedBorderColor = Aura.Stroke,
            focusedTextColor = Aura.Ink,
            unfocusedTextColor = Aura.Ink,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun AccountRow(name: String, email: String, onSignOut: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
            Text("Gemini is signed in as $email", color = Aura.InkDim, fontSize = 13.sp)
        }
        Text(
            "Sign out", color = Aura.GeminiViolet, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSignOut).padding(10.dp),
        )
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .clickable { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, value)) }
            .padding(10.dp)
    ) {
        Text(label, color = Aura.InkDim, fontSize = 12.sp)
        Text(value, color = Aura.Ink, fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text("Tap to copy", color = Aura.InkFaint, fontSize = 11.sp)
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected, onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Aura.GeminiViolet),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
            Text(subtitle, color = Aura.InkDim, fontSize = 13.sp)
        }
    }
}
