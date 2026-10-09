package com.sohailsquad.animalhealthbridge.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sohailsquad.animalhealthbridge.ui.screens.AnimalIdScreen
import com.sohailsquad.animalhealthbridge.ui.screens.CaseReviewScreen
import com.sohailsquad.animalhealthbridge.ui.screens.HealthRecordScreen
import com.sohailsquad.animalhealthbridge.ui.screens.LanguageSelectScreen
import com.sohailsquad.animalhealthbridge.ui.screens.PrescriptionScreen
import com.sohailsquad.animalhealthbridge.ui.screens.VetApprovalScreen
import com.sohailsquad.animalhealthbridge.ui.screens.VetQueryScreen
import com.sohailsquad.animalhealthbridge.ui.screens.VoiceIntakeScreen

/** Forward-navigation animation duration. Short enough to feel instant. */
private const val NAV_ANIM_MS = 220

/**
 * Argument key for the animal id passed between screens.
 *
 * The case, vet-query and prescription screens all need to know WHICH animal
 * they are working on, so the id travels in the route rather than in a global
 * singleton. That keeps deep links meaningful and makes process death
 * survivable.
 */
object NavArgs {
    const val ANIMAL_ID = "animalId"
    const val CASE_ID = "caseId"
}

/**
 * Builds a route string with its arguments filled in.
 *
 * @param destination the screen to navigate to.
 * @param args path-segment arguments, in the order the route declares them.
 */
fun route(destination: AhbDestination, vararg args: Any): String =
    buildString {
        append(destination.route)
        args.forEach { append("/").append(it) }
    }

/**
 * The single navigation graph for the app.
 *
 * Eight destinations are registered - the seven [AhbDestination.STEPS] plus the
 * [AhbDestination.Prescription] sub-screen. Every screen receives a
 * [ScreenCallbacks] bundle instead of eight individual lambdas, which keeps the
 * call sites readable as the app grows.
 *
 * @param modifier layout modifier applied to the host.
 * @param navController controller to drive; injected so tests and the debug
 *   screen switcher can supply their own.
 * @param callbacks navigation intents supplied by the caller.
 */
@Composable
fun AhbNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    callbacks: ScreenCallbacks,
) {
    // Slide left/right on push/pop so the step-by-step flow reads as forward
    // motion. Fades are used for the sub-screen, which is a lateral move.
    val forwardEnter = slideInHorizontally(
        animationSpec = tween(NAV_ANIM_MS),
        initialOffsetX = { fullWidth -> fullWidth / 6 },
    ) + fadeIn(animationSpec = tween(NAV_ANIM_MS))
    val forwardExit = slideOutHorizontally(
        animationSpec = tween(NAV_ANIM_MS),
        targetOffsetX = { fullWidth -> -fullWidth / 6 },
    ) + fadeOut(animationSpec = tween(NAV_ANIM_MS))
    val backEnter = slideInHorizontally(
        animationSpec = tween(NAV_ANIM_MS),
        initialOffsetX = { fullWidth -> -fullWidth / 6 },
    ) + fadeIn(animationSpec = tween(NAV_ANIM_MS))
    val backExit = slideOutHorizontally(
        animationSpec = tween(NAV_ANIM_MS),
        targetOffsetX = { fullWidth -> fullWidth / 6 },
    ) + fadeOut(animationSpec = tween(NAV_ANIM_MS))
    val crossFadeIn = fadeIn(animationSpec = tween(NAV_ANIM_MS))
    val crossFadeOut = fadeOut(animationSpec = tween(NAV_ANIM_MS))

    NavHost(
        navController = navController,
        startDestination = AhbDestination.START.route,
        modifier = modifier,
        enterTransition = { forwardEnter },
        exitTransition = { forwardExit },
        popEnterTransition = { backEnter },
        popExitTransition = { backExit },
    ) {
        // --- 1. Language select -------------------------------------------------
        composable(
            route = AhbDestination.LanguageSelect.route,
            enterTransition = { crossFadeIn },
            exitTransition = { crossFadeOut },
        ) {
            LanguageSelectScreen(
                onContinue = callbacks.onLanguageSelected,
            )
        }

        // --- 2. Animal identification -------------------------------------------
        composable(route = AhbDestination.AnimalId.route) {
            AnimalIdScreen(
                onAnimalResolved = callbacks.onAnimalResolved,
                onOpenHistory = callbacks.onOpenHealthRecord,
                onBack = callbacks.onBack,
            )
        }

        // --- 3. Voice intake ----------------------------------------------------
        composable(
            route = "${AhbDestination.VoiceIntake.route}/{${NavArgs.ANIMAL_ID}}",
            arguments = listOf(
                navArgument(NavArgs.ANIMAL_ID) {
                    type = NavType.StringType
                    // Manual entry is always possible, so a missing id must not
                    // crash the screen - it degrades to an unsaved draft case.
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            VoiceIntakeScreen(
                animalId = entry.arguments?.getString(NavArgs.ANIMAL_ID),
                onTranscriptReady = callbacks.onTranscriptReady,
                onBack = callbacks.onBack,
            )
        }

        // --- 4. Case review -----------------------------------------------------
        composable(route = AhbDestination.CaseReview.route) {
            CaseReviewScreen(
                onShareWithVet = callbacks.onShareWithVet,
                onOpenQueries = callbacks.onOpenQueries,
                onBack = callbacks.onBack,
            )
        }

        // --- 5. Vet approval ----------------------------------------------------
        composable(route = AhbDestination.VetApproval.route) {
            VetApprovalScreen(
                onRequestMoreInfo = callbacks.onOpenQueries,
                onTreatmentApproved = callbacks.onTreatmentApproved,
                onBack = callbacks.onBack,
            )
        }

        // --- 5b. Prescription (sub-screen, reachable only after approval) -------
        composable(
            route = "${AhbDestination.Prescription.route}/{${NavArgs.CASE_ID}}",
            arguments = listOf(
                navArgument(NavArgs.CASE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            PrescriptionScreen(
                caseId = entry.arguments?.getString(NavArgs.CASE_ID),
                onSaveToRecord = callbacks.onOpenHealthRecord,
                onBack = callbacks.onBack,
            )
        }

        // --- 6. Vet query loop --------------------------------------------------
        composable(route = AhbDestination.VetQuery.route) {
            VetQueryScreen(
                onBack = callbacks.onBack,
            )
        }

        // --- 7. Health record ---------------------------------------------------
        composable(route = AhbDestination.HealthRecord.route) {
            HealthRecordScreen(
                onStartNewCase = callbacks.onStartNewCase,
                onBack = callbacks.onBack,
            )
        }
    }
}