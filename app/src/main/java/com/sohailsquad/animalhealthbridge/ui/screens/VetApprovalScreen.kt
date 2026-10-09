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
 * Screen 5 - vet approval.
 *
 * The single most safety-critical screen: it is the only place a treatment can
 * be approved, and therefore the only route to a prescription. Real form fields
 * arrive in Step 7.
 *
 * @param onRequestMoreInfo opens the query loop so the vet can ask a follow-up.
 * @param onTreatmentApproved called with the approved case id.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun VetApprovalScreen(
    onRequestMoreInfo: () -> Unit,
    onTreatmentApproved: (caseId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AhbScreen(
        destination = AhbDestination.VetApproval,
        onBack = onBack,
        modifier = modifier,
        safetyBanner = true,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.VetApproval,
                onStepClick = { /* Backward jumps enabled once cases persist. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        AhbPlaceholderContent(
            destination = AhbDestination.VetApproval,
            plannedFeatures = listOf(
                "Read-only case summary at the top",
                "Vet notes field",
                "Treatment plan: medicine, dosage, frequency, duration, route",
                "Withdrawal period and follow-up date (livestock)",
                "Medicine name and dosage are NEVER auto-filled",
                "Approve Treatment stays disabled while any query is pending",
                "Approve is the only path that can create a prescription",
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}