package com.sohailsquad.animalhealthbridge

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.ClinicalField
import com.sohailsquad.animalhealthbridge.ui.components.OfflineContent
import com.sohailsquad.animalhealthbridge.ui.screens.LanguageSelectScreen
import com.sohailsquad.animalhealthbridge.ui.screens.CaseReviewScreen
import com.sohailsquad.animalhealthbridge.ui.screens.presentClinicalList
import com.sohailsquad.animalhealthbridge.ui.theme.AnimalHealthBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class UiPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun languageSelectionKeepsContinueVisibleAndPassesChosenCode() {
        val selected = AtomicReference<String>()
        withCompactContent({ LanguageSelectScreen(onContinue = { selected.set(it) }) }) { scenario ->
            val beforeSelection = clickableAncestor(awaitText("Continue"))
            assertFalse(beforeSelection.isEnabled)
            assertAboveNavigationBar(beforeSelection, scenario)
            val hindi = clickableAncestor(awaitText("Hindi"))
            assertTrue(hindi.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            val continueButton = clickableAncestor(awaitText("Continue") { clickableAncestor(it).isEnabled })
            assertTrue(continueButton.isEnabled)
            assertAboveNavigationBar(continueButton, scenario)
            assertTrue(continueButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            awaitCondition("Chosen language was not delivered") { selected.get() == "hi" }
            assertEquals("hi", selected.get())
        }
    }

    @Test
    fun recordFooterAndCurrentStepRemainVisibleWithLargeText() {
        val clicked = AtomicBoolean(false)
        withCompactContent({
            OfflineContent(AhbDestination.HealthRecord, onBack = {}, actions = {
                AhbPrimaryButtonWide("Start New Case", onClick = { clicked.set(true) },
                    modifier = Modifier.padding(16.dp))
            }) {
                repeat(20) { ClinicalField("Observation ${it + 1}", "Presentation test only") }
            }
        }) { scenario ->
            val action = clickableAncestor(awaitText("Start New Case"))
            assertAboveNavigationBar(action, scenario)
            val currentStep = stateAncestor(awaitText("7 · Records"))
            assertEquals("Current step", AccessibilityNodeInfoCompat.wrap(currentStep).stateDescription?.toString())
            assertAboveNavigationBar(currentStep, scenario)
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            awaitCondition("Footer action was not delivered") { clicked.get() }
        }
    }

    @Test
    fun clinicalListsAreReadableWithoutLosingInvalidOriginals() {
        assertEquals("swelling · limping", presentClinicalList("[\"swelling\",\"limping\"]"))
        assertEquals("None recorded", presentClinicalList("[]"))
        assertEquals("Original observation", presentClinicalList("Original observation"))
    }

    @Test
    fun missingReviewCaseShowsErrorAndKeepsShareDisabled() {
        withCompactContent({
            CaseReviewScreen(Long.MAX_VALUE, onShareWithVet = {}, onOpenQueries = { _, _ -> },
                onBack = {}, transcript = "Presentation test only")
        }) { scenario ->
            awaitText("Case not found on this phone.")
            val share = clickableAncestor(awaitText("Share with Vet"))
            assertFalse(share.isEnabled)
            assertAboveNavigationBar(share, scenario)
        }
    }

    private fun withCompactContent(content: @Composable () -> Unit,
        assertions: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        AnimalHealthBridgeTheme {
                            Box(Modifier.fillMaxSize().semantics {
                                contentDescription = "Compact presentation test"
                            }, contentAlignment = Alignment.Center) {
                                Box(Modifier.requiredWidth(320.dp).fillMaxHeight()) { content() }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            awaitCondition("Compact fixture did not render") {
                findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString() == "Compact presentation test"
                } != null
            }
            assertions(scenario)
        }
    }

    private fun awaitText(expected: String,
        ready: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString()?.lines()?.contains(expected) == true && ready(it)
            }?.let { return it }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Visible text not found: $expected")
    }

    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && matches(node)) return node
        for (childIndex in 0 until node.childCount) {
            findNode(node.getChild(childIndex), matches)?.let { return it }
        }
        return null
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent
        }
        throw AssertionError("No clickable ancestor for ${node.text}")
    }

    private fun stateAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (AccessibilityNodeInfoCompat.wrap(candidate).stateDescription != null) return candidate
            candidate = candidate.parent
        }
        throw AssertionError("No step state exposed for ${node.text}")
    }

    private fun assertAboveNavigationBar(node: AccessibilityNodeInfo,
        scenario: ActivityScenario<MainActivity>) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("Action has empty bounds", bounds.isEmpty)
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val location = IntArray(2).also(view::getLocationOnScreen)
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                .getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue("Action overlaps status bar", bounds.top >= location[1] + insets.top)
            assertTrue("Action overlaps navigation bar", bounds.bottom <= location[1] + view.height - insets.bottom)
            assertTrue("Action is clipped horizontally", bounds.left >= location[0] && bounds.right <= location[0] + view.width)
        }
    }
}
