package com.aura.launcher.gemini

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.aura.launcher.ActivityBridge

/**
 * Hand-off to the system Google Gemini app. When Gemini is the device's default digital
 * assistant it can act on the phone directly (apps, calls, messages, settings, on-screen
 * context), so AURA routes prompts and voice straight into it.
 */
object GeminiBridge {
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    fun isInstalled(context: Context, pkg: String = GEMINI_PACKAGE) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Opens the Gemini overlay / app. */
    fun open(context: Context): Boolean {
        val pm = context.packageManager
        val candidates = listOfNotNull(
            Intent(Intent.ACTION_ASSIST).setPackage(GOOGLE_PACKAGE).takeIf { isInstalled(context, GOOGLE_PACKAGE) },
            pm.getLaunchIntentForPackage(GEMINI_PACKAGE),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_VIEW, "https://gemini.google.com/app".toUri()),
        )
        return candidates.any { ActivityBridge.start(context, it) }
    }

    /** Opens the full Gemini app (not the overlay). */
    fun openApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE)
        return (launch != null && ActivityBridge.start(context, launch)) || open(context)
    }

    /** Starts the default assistant in voice mode (Gemini Live / "Hey Google"). */
    fun voice(context: Context): Boolean =
        ActivityBridge.start(context, Intent(Intent.ACTION_VOICE_COMMAND)) || open(context)

    /** Sends a typed prompt into Gemini. */
    fun ask(context: Context, prompt: String): Boolean {
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, prompt)
            .setPackage(GEMINI_PACKAGE)
        if (isInstalled(context) && ActivityBridge.start(context, share)) return true

        // Fallback: keep the prompt on the clipboard and open whatever assistant exists.
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Gemini prompt", prompt))
        Toast.makeText(context, "Prompt copied — paste it into Gemini", Toast.LENGTH_SHORT).show()
        return open(context)
    }
}
