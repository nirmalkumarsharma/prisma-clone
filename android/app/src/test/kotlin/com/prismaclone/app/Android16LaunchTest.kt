package com.prismaclone.app

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Launches the real MainActivity on an Android 16 runtime and drives a filter render end to end. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi") // a typical modern phone
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real Bitmap pixels, so the filters actually run
class Android16LaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(compose.activity)[PrismaViewModel::class.java]

    private fun awaitRender(filterId: String) {
        try {
            compose.waitUntil(timeoutMillis = 120_000) {
                val s = vm.state.value
                s.filterId == filterId && !s.busy && s.styled != null
            }
        } catch (e: Throwable) {
            val s = vm.state.value
            throw AssertionError("No $filterId render: filter=${s.filterId} busy=${s.busy} styled=${s.styled != null}", e)
        }
    }

    @Test
    fun launchesOnAndroid16AndAppliesFilters() {
        assertEquals(36, Build.VERSION.SDK_INT)

        compose.onNodeWithText("Prisma").assertExists()
        compose.onNodeWithText("Prompt builder").assertExists()
        awaitRender("udnie")

        // Scroll down to the filter strip with slow swipes (no fling), as a user would, then tap a card.
        val waveCard = compose.onNodeWithContentDescription("Wave filter")
        fun fullyVisible() = waveCard.fetchSemanticsNode().let { it.boundsInRoot.height >= it.size.height * 0.95f }
        repeat(12) {
            if (fullyVisible()) return@repeat
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.5f, durationMillis = 600) }
            compose.waitForIdle()
        }
        assertTrue("Wave card should be on screen after scrolling", fullyVisible())
        compose.onNodeWithContentDescription("Wave filter").performClick()
        awaitRender("wave")

        val s = vm.state.value
        val original = checkNotNull(s.original)
        val styled = checkNotNull(s.styled)
        assertEquals(original.width, styled.width)
        assertFalse("styled image should differ from the original", styled.sameAs(original))
        compose.onNodeWithText("A dramatic Wave-style", substring = true).assertExists()
    }
}
