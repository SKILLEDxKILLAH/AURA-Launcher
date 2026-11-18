package com.aura.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Closing the Gemini panel ends the conversation; the next time it opens, the chat is new. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GeminiChatLifecycleTest {
    private val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun talk() {
        vm.chat += ChatItem(ChatRole.USER, "What's the weather?")
        vm.chat += ChatItem(ChatRole.GEMINI, "Sunny, 39°F.")
    }

    @Test
    fun reopeningStartsANewChat() {
        vm.openGemini()
        talk()
        vm.geminiSheetOpen = false          // close (X, back, or swipe away)
        vm.openGemini()
        assertTrue(vm.chat.isEmpty())
    }

    @Test
    fun goingHomeAlsoEndsTheChat() {
        vm.openGemini()
        talk()
        vm.onHomeIntent()                   // home button while the panel is up
        vm.openGemini(listen = true)        // reopened by holding the mic
        assertTrue(vm.chat.isEmpty())
    }

    @Test
    fun chatStaysWhileThePanelIsOpen() {
        vm.openGemini()
        talk()
        vm.openGemini()                     // already open: nothing is cleared
        vm.geminiSheetOpen = true
        assertEquals(2, vm.chat.size)
    }
}
