package com.sohailsquad.animalhealthbridge

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStartupTest {
    @Test
    fun coldStartRendersLanguageSelectionWithCompleteSteps() {
        val firstDestination = AhbDestination.LanguageSelect
        assertSame(firstDestination, AhbDestination.START)
        assertEquals(
            listOf(
                firstDestination,
                AhbDestination.AnimalId,
                AhbDestination.VoiceIntake,
                AhbDestination.CaseReview,
                AhbDestination.VetApproval,
                AhbDestination.Prescription,
                AhbDestination.VetQuery,
                AhbDestination.HealthRecord,
            ),
            AhbDestination.ALL,
        )
        assertEquals(
            AhbDestination.ALL.filterNot { it == AhbDestination.Prescription },
            AhbDestination.STEPS,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val title = instrumentation.targetContext.getString(R.string.screen_language_title)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            var rendered = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (containsText(instrumentation.uiAutomation.rootInActiveWindow, title)) {
                    rendered = true
                    break
                }
                SystemClock.sleep(100L)
            }
            assertTrue("Language selection did not render", rendered)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    private fun containsText(node: AccessibilityNodeInfo?, expected: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == expected) return true
        for (childIndex in 0 until node.childCount) {
            if (containsText(node.getChild(childIndex), expected)) return true
        }
        return false
    }
}
