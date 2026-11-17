package com.aura.launcher.data

import android.content.pm.ApplicationInfo

/** Short, calm one-line descriptions shown under each app name in the drawer. */
object AppDescriptions {
    private val known = mapOf(
        "com.google.android.apps.maps" to "Navigate, explore, plan",
        "com.google.android.apps.messaging" to "Text, chat, and share",
        "com.google.android.apps.youtube.music" to "Play, browse, and manage",
        "com.google.android.apps.magazines" to "Top stories and your interests",
        "com.google.android.apps.photos" to "Back up, organize, and edit",
        "com.android.vending" to "Discover and install apps",
        "com.google.android.gm" to "Mail, organized",
        "com.android.chrome" to "Browse the web",
        "com.google.android.GoogleCamera" to "Capture photos and video",
        "com.google.android.dialer" to "Calls and voicemail",
        "com.google.android.contacts" to "The people you know",
        "com.google.android.calendar" to "Plan your days",
        "com.google.android.deskclock" to "Alarms, timers, world clock",
        "com.android.settings" to "Tune your device",
        "com.google.android.apps.bard" to "Your AI assistant",
        "com.google.android.youtube" to "Watch and discover",
        "com.google.android.apps.docs" to "Store and share files",
        "com.google.android.keep" to "Notes and lists",
        "com.google.android.googlequicksearchbox" to "Search and discover",
        "com.google.android.apps.nbu.files" to "Browse and free up space",
        "com.google.android.calculator" to "Quick math",
        "com.google.android.apps.walletnfcrel" to "Pay and store passes",
        "com.google.android.apps.recorder" to "Record and transcribe",
        "com.google.android.apps.tachyon" to "Video calls",
        "com.google.android.apps.translate" to "Speak every language",
        "com.google.android.apps.fitness" to "Move, track, and thrive",
        "com.google.android.apps.weather" to "Forecasts at a glance",
        "com.spotify.music" to "Music and podcasts",
        "com.whatsapp" to "Chat and call",
        "com.instagram.android" to "Share moments",
        "com.netflix.mediaclient" to "Stream shows and films",
        "org.thoughtcrime.securesms" to "Private messaging",
        "com.discord" to "Talk with your communities",
        "com.reddit.frontpage" to "Dive into communities",
    )

    fun subtitleFor(pkg: String, category: Int): String = known[pkg] ?: when (category) {
        ApplicationInfo.CATEGORY_GAME -> "Play"
        ApplicationInfo.CATEGORY_AUDIO -> "Listen and play"
        ApplicationInfo.CATEGORY_VIDEO -> "Watch and stream"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos and images"
        ApplicationInfo.CATEGORY_SOCIAL -> "Connect with people"
        ApplicationInfo.CATEGORY_NEWS -> "Read the latest"
        ApplicationInfo.CATEGORY_MAPS -> "Find your way"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Get things done"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility tools"
        else -> ""
    }
}
