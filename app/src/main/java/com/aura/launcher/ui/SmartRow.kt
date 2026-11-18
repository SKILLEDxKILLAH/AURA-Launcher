package com.aura.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.ui.theme.Aura

/**
 * Top of the app drawer: five apps you're likely to want right now ("Suggested", learned from
 * when and how often you open apps) or the last five you opened ("Recent").
 */
@Composable
fun SmartRow(suggested: List<AppEntry>, recent: List<AppEntry>, repo: AppRepository, onLaunch: (AppEntry) -> Unit) {
    var showRecent by rememberSaveable { mutableStateOf(false) }
    val list = if (showRecent || suggested.isEmpty()) recent else suggested
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Row(Modifier.padding(start = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab("Suggested", selected = !showRecent && suggested.isNotEmpty(), enabled = suggested.isNotEmpty()) { showRecent = false }
            Spacer(Modifier.width(6.dp))
            Tab("Recent", selected = showRecent || suggested.isEmpty(), enabled = recent.isNotEmpty()) { showRecent = true }
        }
        Crossfade(list, label = "smart") { apps ->
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), fill = Color(0x14FFFFFF)).padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                apps.take(5).forEach { app ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .clickable(onClickLabel = "Open ${app.label}", role = Role.Button) { onLaunch(app) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(app, repo, 48.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            app.label, color = Aura.Ink, fontSize = 12.sp, fontFamily = Aura.Sans,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
                // Keep icon spacing stable when fewer than five apps are known yet.
                repeat(5 - apps.take(5).size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = when { selected -> Color.White; enabled -> Aura.InkDim; else -> Aura.InkFaint },
        fontSize = 13.sp, fontFamily = Aura.Sans,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .then(if (selected) Modifier.background(Aura.GlassStrong) else Modifier)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
