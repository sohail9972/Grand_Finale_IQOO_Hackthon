package com.sohailsquad.animalhealthbridge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.AhbPlaceholderContent
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen
import com.sohailsquad.animalhealthbridge.ui.components.AhbStepBar

/**
 * Screen 4 - case review.
 *
 * Shows the structured case card plus the triage badge. The pipeline behind it
 * arrives in Steps 4-6: translation (IndicTrans2), LLM structuring (Gemma-3 1B
 * via MediaPipe), then the deterministic rules engine.
 *
 * @param onShareWithVet hand the case to the vet.
 * @param onOpenQueries jump to the vet query loop.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun CaseReviewScreen(
    onShareWithVet: () -> Unit,
    onOpenQueries: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AhbScreen(
        destination = AhbDestination.CaseReview,
        onBack = onBack,
        modifier = modifier,
        // This screen displays clinical content, so the safety notice is pinned.
        safetyBanner = true,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.CaseReview,
                onStepClick = { /* Backward jumps enabled once cases persist. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        AhbPlaceholderContent(
            destination = AhbDestination.CaseReview,
            plannedFeatures = listOf(
                "Staged loading: Translating... then Structuring... then Evaluating...",
                "Triage badge: EMERGENCY_NOW / SEE_VET_TODAY / MONITOR",
                "Extracted fields with icons: complaint, duration, area, severity",
                "Captured photo thumbnail",
                "Collapsible original transcript, never hidden entirely",
                "Pending-query badge when the vet has asked something",
                "\"Share with Vet\" primary action",
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}