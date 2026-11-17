package com.aura.launcher.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.DockItem
import com.aura.launcher.gemini.GeminiBridge
import com.aura.launcher.ui.theme.Aura

@Composable
fun AppIcon(entry: AppEntry, repo: AppRepository, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState(repo.cachedIcon(entry.key), entry.key) { value = repo.icon(entry) }
    IconImage(bmp, size, modifier)
}

@Composable
fun PackageIcon(pkg: String, repo: AppRepository, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState(repo.cachedIcon("pkg:$pkg"), pkg) { value = repo.packageIcon(pkg) }
    IconImage(bmp, size, modifier)
}

@Composable
private fun IconImage(bmp: ImageBitmap?, size: Dp, modifier: Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Aura.Glass)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

/** "〜 Android 17" on the left, Google search on the right — shared by home and drawer. */
@Composable
fun AuraTopBar(repo: AppRepository, onGoogle: () -> Unit, onLongPressLabel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = {}, onLongClick = onLongPressLabel).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuraGlyph(Glyph.Pulse, size = 16.dp, color = Aura.Ink)
            Spacer(Modifier.width(10.dp))
            Text("Android ${Build.VERSION.RELEASE}", color = Aura.Ink, fontSize = 16.sp, fontFamily = Aura.Sans)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Google search", role = Role.Button, onClick = onGoogle),
            contentAlignment = Alignment.Center,
        ) {
            PackageIcon(GeminiBridge.GOOGLE_PACKAGE, repo, 28.dp)
        }
    }
}

/** The large "Google Gemini — What can I do for you?" card from the home mockup. */
@Composable
fun GeminiCard(onOpen: () -> Unit, onLongPress: () -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(44.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .geminiGlow(44.dp, blur = 20.dp, alpha = 0.5f)
            .glass(shape, fill = Aura.GlassDark, stroke = Aura.Stroke)
            .background(Aura.geminiBrush, shape, alpha = 0.06f)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress, onClickLabel = "Ask Gemini")
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Sparkle, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Google Gemini", color = Aura.InkDim, fontSize = 14.sp, fontFamily = Aura.Sans)
            Spacer(Modifier.height(2.dp))
            Text(
                "What can I do for you?", color = Aura.Ink, fontSize = 21.sp, fontFamily = Aura.Sans,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        MicButton(onMic, 52.dp)
    }
}

/** Compact single-line Gemini pill used at the bottom of the app drawer. */
@Composable
fun GeminiPill(onOpen: () -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(70.dp)
            .geminiGlow(36.dp, blur = 16.dp, alpha = 0.45f)
            .glass(shape, fill = Aura.GlassDark)
            .clickable(onClickLabel = "Ask Gemini", onClick = onOpen)
            .padding(start = 24.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraGlyph(Glyph.Sparkle, size = 36.dp)
        Spacer(Modifier.width(18.dp))
        Text("What can I do for you?", color = Aura.InkDim, fontSize = 18.sp, fontFamily = Aura.Sans, modifier = Modifier.weight(1f))
        MicButton(onMic, 46.dp)
    }
}

@Composable
fun MicButton(onClick: () -> Unit, size: Dp) {
    Box(
        Modifier.size(size).glass(CircleShape, fill = Aura.GlassStrong)
            .clickable(onClickLabel = "Talk to Gemini", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AuraGlyph(Glyph.Mic, size = size * 0.46f) }
}

@Composable
fun SparkleFab(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(64.dp)
            .geminiGlow(32.dp, blur = 14.dp, alpha = 0.6f)
            .glass(CircleShape, fill = Aura.GlassDark, stroke = Aura.GeminiViolet.copy(alpha = 0.7f), strokeWidth = 1.5.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Open Gemini"),
        contentAlignment = Alignment.Center,
    ) { AuraGlyph(Glyph.Sparkle, size = 34.dp) }
}

@Composable
fun Dock(items: List<DockItem>, repo: AppRepository, onLaunch: (DockItem) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Box(
                Modifier.size(62.dp).clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(bounded = true),
                        onClickLabel = item.label,
                        role = Role.Button,
                        onClick = { onLaunch(item) },
                    ),
                contentAlignment = Alignment.Center,
            ) { PackageIcon(item.packageName, repo, 58.dp) }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Aura.InkFaint, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp, modifier = modifier)
}
