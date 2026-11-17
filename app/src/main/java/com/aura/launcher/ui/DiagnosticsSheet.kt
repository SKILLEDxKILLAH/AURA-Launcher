package com.aura.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.aura.launcher.gemini.CheckResult
import com.aura.launcher.gemini.GeminiDiagnostics
import com.aura.launcher.ui.theme.Aura

/** Live results of the Gemini check, with a copyable report to share for troubleshooting. */
@Composable
fun DiagnosticsSheet(results: List<CheckResult>, running: Boolean, onRunAgain: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Color(0xF71D1B19)).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraGlyph(Glyph.Sparkle, size = 28.dp)
            Spacer(Modifier.width(12.dp))
            Text("Gemini check", color = Aura.Ink, fontSize = 26.sp, fontFamily = Aura.Sans, modifier = Modifier.weight(1f))
            Text(
                "Done", color = Aura.GeminiViolet, fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(10.dp),
            )
        }
        Text(
            "Tests sign-in, a real Gemini reply and a real phone-action decision. Nothing on your phone is changed.",
            color = Aura.InkDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            results.forEach { r ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (r.ok) { true -> "✓"; false -> "✗"; null -> "•" },
                        color = when (r.ok) { true -> Aura.GeminiBlue; false -> Color(0xFFFFB4A9); null -> Aura.InkDim },
                        fontSize = 16.sp, modifier = Modifier.width(22.dp),
                    )
                    Column {
                        Text(r.name, color = Aura.Ink, fontSize = 15.sp, fontFamily = Aura.Sans)
                        Text(r.detail, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans)
                    }
                }
            }
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Aura.GeminiViolet, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…", color = Aura.InkDim, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Copy report", color = Color(0xFF1F1F1F), fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White)
                    .clickable(enabled = !running && results.isNotEmpty()) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("AURA Gemini check", GeminiDiagnostics.report(results)))
                        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                "Run again", color = Aura.Ink, fontSize = 15.sp,
                modifier = Modifier.glass(RoundedCornerShape(22.dp)).clickable(enabled = !running, onClick = onRunAgain)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
