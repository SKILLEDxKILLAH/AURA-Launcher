package com.aura.launcher

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.aura.launcher.ui.homeGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xhdpi")
class HomeGesturesTest {
    @get:Rule val compose = createComposeRule()

    private var longPressAt: Offset? = null
    private var swipeFingers: Int? = null
    private var horizontalDrag = 0f

    @Before
    fun setUp() {
        compose.setContent {
            Box(
                Modifier.fillMaxSize().testTag("home")
                    .homeGestures(onLongPress = { longPressAt = it }, onSwipeDown = { swipeFingers = it })
                    .draggable(rememberDraggableState { horizontalDrag += it }, Orientation.Horizontal)
            )
        }
    }

    @Test
    fun oneFingerSwipeDownOpensNotifications() {
        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width / 2f, height * 0.3f))
            repeat(8) { moveBy(Offset(0f, 40f)) }
            up()
        }
        assertEquals(1, swipeFingers)
        assertNull(longPressAt)
    }

    @Test
    fun twoFingerSwipeDownOpensQuickSettings() {
        compose.onNodeWithTag("home").performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.3f))
            down(1, Offset(width * 0.6f, height * 0.3f))
            repeat(8) { moveBy(0, Offset(0f, 40f)); moveBy(1, Offset(0f, 40f)) }
            up(0); up(1)
        }
        assertEquals(2, swipeFingers)
    }

    @Test
    fun pressAndHoldOpensMenu() {
        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            advanceEventTime(900)
            up()
        }
        assertTrue(longPressAt != null)
        assertNull(swipeFingers)
    }

    @Test
    fun sidewaysSwipeIsLeftForTheDrawer() {
        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width - 20f, height / 2f))
            repeat(8) { moveBy(Offset(-40f, 4f)) }
            up()
        }
        assertNull(swipeFingers)
        assertNull(longPressAt)
        assertTrue(horizontalDrag < 0f)
    }
}
