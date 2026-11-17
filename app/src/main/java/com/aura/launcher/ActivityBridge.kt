package com.aura.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.lang.ref.WeakReference

/**
 * Lets non-UI code (the Gemini agent's phone tools) start activities from the live launcher
 * activity and ask for runtime permissions at the moment a tool needs them.
 */
object ActivityBridge {
    private var ref: WeakReference<MainActivity>? = null

    fun attach(activity: MainActivity) { ref = WeakReference(activity) }
    fun detach(activity: MainActivity) { if (ref?.get() === activity) ref = null }

    val activity: MainActivity? get() = ref?.get()

    /** Starts [intent] from the activity when possible; returns false if nothing handles it. */
    fun start(context: Context, intent: Intent): Boolean {
        val host: Context = activity ?: context
        if (host !is MainActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            host.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun ensurePermission(context: Context, permission: String): Boolean {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        return activity?.requestPermissionAsync(permission) ?: false
    }
}
