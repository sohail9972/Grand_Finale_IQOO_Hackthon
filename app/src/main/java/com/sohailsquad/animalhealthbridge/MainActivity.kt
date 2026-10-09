package com.sohailsquad.animalhealthbridge

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.navigation.AhbNavHost
import com.sohailsquad.animalhealthbridge.navigation.NavArgs
import com.sohailsquad.animalhealthbridge.navigation.ScreenCallbacks
import com.sohailsquad.animalhealthbridge.navigation.route
import com.sohailsquad.animalhealthbridge.ui.theme.AnimalHealthBridgeTheme

/**
 * The app's single Activity.
 *
 * Everything is Compose - there are no XML layouts anywhere in the project, per
 * the spec's hard constraint. This class does three things and nothing else:
 *
 *   1. enable edge-to-edge so the dark background reaches the screen edges;
 *   2. install the theme;
 *   3. build the navigation callbacks and hand them to [AhbNavHost].
 *
 * Keeping navigation logic out of the Activity means Step 2+ can move the
 * callbacks into a ViewModel without touching this file's structure.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: draw behind the status and navigation bars. The theme's
        // SideEffect flips the icon colours to light to match the dark surface.
        enableEdgeToEdge()

        setContent {
            AnimalHealthBridgeTheme {
                // Surface (rather than a bare Box) so any composable that forgets
                // its own background still lands on the product's dark colour
                // instead of the platform default.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    val callbacks = rememberScreenCallbacks(navController)

                    AhbNavHost(
                        navController = navController,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }

    /**
     * Builds the navigation intents once per Activity.
     *
     * Declared as an extension on ComponentActivity so it can read the current
     * context without capturing `this` in a lambda that outlives the Activity.
     *
     * Step 1 navigation is deliberately simple: every callback maps to a
     * `navigate` or `popBackStack`. Step 2 onwards will persist the language and
     * the resolved animal before navigating.
     */
    private fun rememberScreenCallbacks(
        navController: NavHostController,
    ): ScreenCallbacks = ScreenCallbacks(
        onLanguageSelected = { languageCode ->
            // TODO(Step 2): persist languageCode to DataStore before navigating.
            Log.i(TAG, "Language selected: $languageCode")
            navController.navigate(AhbDestination.AnimalId.route) {
                // Popping the language screen keeps the back stack shallow; the
                // user should not land back on a chooser they already answered.
                popUpTo(AhbDestination.LanguageSelect.route) { inclusive = true }
            }
        },
        onAnimalResolved = { tagNumber ->
            Log.i(TAG, "Animal resolved: $tagNumber")
            navController.navigate(
                route(AhbDestination.VoiceIntake, tagNumber),
            )
        },
        onOpenHealthRecord = {
            navController.navigate(AhbDestination.HealthRecord.route)
        },
        onTranscriptReady = { transcript ->
            Log.i(TAG, "Transcript ready (${transcript.length} chars)")
            navController.navigate(AhbDestination.CaseReview.route)
        },
        onShareWithVet = {
            navController.navigate(AhbDestination.VetApproval.route)
        },
        onOpenQueries = {
            navController.navigate(AhbDestination.VetQuery.route)
        },
        onTreatmentApproved = { caseId ->
            // The ONLY navigation that reaches the prescription screen. Nothing
            // else in the app can construct this route, which is what makes
            // "no prescription without vet approval" structural rather than a
            // convention someone could forget.
            navController.navigate(route(AhbDestination.Prescription, caseId))
        },
        onStartNewCase = {
            navController.navigate(AhbDestination.AnimalId.route) {
                // Clear the case flow so back does not re-enter a finished case.
                popUpTo(AhbDestination.AnimalId.route) { inclusive = true }
            }
        },
        onBack = {
            // popBackStack returns false when there is nothing to pop; falling
            // back to the start destination avoids trapping the user on a
            // screen with a dead back arrow.
            if (!navController.popBackStack()) {
                navController.navigate(AhbDestination.START.route)
            }
        },
    )

    private companion object {
        /** Logcat tag. Filter with: adb logcat -s AnimalHealthBridge */
        const val TAG = "AnimalHealthBridge"
    }
}