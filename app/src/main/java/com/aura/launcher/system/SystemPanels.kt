package com.aura.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/** Pulls down the notification shade or Quick Settings from the home screen. */
object SystemPanels {

    enum class Result { OPENED, NEEDS_SETUP }

    fun notifications(context: Context): Result =
        open(context, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "expandNotificationsPanel")

    fun quickSettings(context: Context): Result =
        open(context, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "expandSettingsPanel")

    private fun open(context: Context, globalAction: Int, statusBarMethod: String): Result {
        // 1) AURA gestures accessibility service: the supported, always-working route.
        AuraGestureService.instance?.let { if (it.performGlobalAction(globalAction)) return Result.OPENED }
        // 2) StatusBarManager, the route most launchers use (EXPAND_STATUS_BAR permission).
        val ok = runCatching {
            val sbm = context.getSystemService("statusbar") ?: return@runCatching false
            sbm.javaClass.getMethod(statusBarMethod).invoke(sbm)
            true
        }.getOrDefault(false)
        return if (ok) Result.OPENED else Result.NEEDS_SETUP
    }

    fun isGestureServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, AuraGestureService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/**
 * Optional accessibility service whose only job is opening the notification shade and Quick
 * Settings on request. It reads no screen content and handles no events.
 */
class AuraGestureService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: AuraGestureService? = null
            private set
    }
}
