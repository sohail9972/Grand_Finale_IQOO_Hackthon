package com.sohailsquad.animalhealthbridge.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import com.sohailsquad.animalhealthbridge.R

/**
 * Every screen in the app, in the order the owner/vet walks through them.
 *
 * This is a sealed class rather than a bare list of route strings so that:
 *   - the route, title, subtitle and icon for a screen can never drift apart;
 *   - the compiler flags any `when` that forgets a screen;
 *   - a typo in a route is a compile error, not a runtime crash.
 *
 * @param route stable navigation route (used as the Navigation-Compose key).
 * @param titleRes screen heading, shown in the top app bar.
 * @param subtitleRes one-line explanation shown under the heading.
 * @param icon tab / step icon.
 */
sealed class AhbDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
) {
    /** 1. Pick the language the owner speaks. Persisted to DataStore. */
    data object LanguageSelect : AhbDestination(
        route = "language_select",
        titleRes = R.string.screen_language_title,
        subtitleRes = R.string.screen_language_subtitle,
        icon = Icons.Filled.Language,
    )

    /** 2. Identify the animal: NFC tag, QR code, or manual tag number. */
    data object AnimalId : AhbDestination(
        route = "animal_id",
        titleRes = R.string.screen_animal_id_title,
        subtitleRes = R.string.screen_animal_id_subtitle,
        icon = Icons.Filled.Pets,
    )

    /** 3. Owner speaks symptoms freely; on-device ASR produces a transcript. */
    data object VoiceIntake : AhbDestination(
        route = "voice_intake",
        titleRes = R.string.screen_voice_intake_title,
        subtitleRes = R.string.screen_voice_intake_subtitle,
        icon = Icons.Filled.Mic,
    )

    /** 4. Structured case card + triage badge for the owner to review. */
    data object CaseReview : AhbDestination(
        route = "case_review",
        titleRes = R.string.screen_case_review_title,
        subtitleRes = R.string.screen_case_review_subtitle,
        icon = Icons.AutoMirrored.Filled.FactCheck,
    )

    /** 5. Vet reviews the case and is the only actor who can approve treatment. */
    data object VetApproval : AhbDestination(
        route = "vet_approval",
        titleRes = R.string.screen_vet_approval_title,
        subtitleRes = R.string.screen_vet_approval_subtitle,
        icon = Icons.Filled.Description,
    )

    /**
     * 5b. Prescription document. A SUB-SCREEN of [VetApproval]: it is only ever
     * reachable after the vet approves, so it is deliberately not part of the
     * bottom-bar step list.
     */
    data object Prescription : AhbDestination(
        route = "prescription",
        titleRes = R.string.screen_prescription_title,
        subtitleRes = R.string.screen_prescription_subtitle,
        icon = Icons.Filled.Description,
    )

    /** 6. Two-way follow-up loop between vet and owner. */
    data object VetQuery : AhbDestination(
        route = "vet_query",
        titleRes = R.string.screen_vet_query_title,
        subtitleRes = R.string.screen_vet_query_subtitle,
        icon = Icons.Filled.QuestionAnswer,
    )

    /** 7. Longitudinal timeline of everything that happened to this animal. */
    data object HealthRecord : AhbDestination(
        route = "health_record",
        titleRes = R.string.screen_health_record_title,
        subtitleRes = R.string.screen_health_record_subtitle,
        icon = Icons.Filled.Timeline,
    )

    companion object {
        /** Where the app lands on a cold start. */
        val START: AhbDestination = LanguageSelect

        /**
         * The 8 destinations, in spec order. Used by the debug screen switcher
         * and by the "next screen" helper so the happy path is data-driven.
         */
        val ALL: List<AhbDestination> = listOf(
            LanguageSelect,
            AnimalId,
            VoiceIntake,
            CaseReview,
            VetApproval,
            Prescription,
            VetQuery,
            HealthRecord,
        )

        /** The 7 steps shown in the bottom navigation bar (Prescription excluded). */
        val STEPS: List<AhbDestination> = ALL - Prescription

        /**
         * Looks up a destination by route.
         *
         * @return the matching destination, or `null` when [route] is unknown
         *   (e.g. a deep link to a screen that no longer exists). Callers treat
         *   `null` as "stay where you are" rather than crashing.
         */
        fun fromRoute(route: String?): AhbDestination? = ALL.firstOrNull { it.route == route }

        /**
         * The screen that follows [current] on the happy path.
         *
         * @return the next destination, or `null` when [current] is the last
         *   screen - the caller decides whether to loop back to the start.
         */
        fun nextAfter(current: AhbDestination): AhbDestination? =
            ALL.getOrNull(ALL.indexOf(current) + 1)
    }
}