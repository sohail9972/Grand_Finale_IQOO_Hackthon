package com.sohailsquad.animalhealthbridge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.AhbPlaceholderContent
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen

/**
 * Screen 5b - prescription document.
 *
 * A SUB-SCREEN of vet approval, reachable only after the vet has approved, and
 * deliberately absent from the step rail so it never looks like a step the
 * owner can navigate to directly.
 *
 * @param caseId the approved case this prescription belongs to, or null when
 *   the screen is opened without a case (which renders an explanatory empty
 *   state rather than crashing).
 * @param onSaveToRecord save the prescription into the animal's health record.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun PrescriptionScreen(
    caseId: String?,
    onSaveToRecord: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AhbScreen(
        destination = AhbDestination.Prescription,
        onBack = onBack,
        modifier = modifier,
        safetyBanner = true,
    ) { contentPadding: PaddingValues ->
        AhbPlaceholderContent(
            destination = AhbDestination.Prescription,
            plannedFeatures = listOf(
                "Formatted prescription document",
                "Vet name and registration number on every page",
                "Clinic name and address, date of issue",
                "Animal id, species, owner",
                "Chief complaint and vet's own diagnosis text",
                "Rx: medicine, dose, frequency, duration, route",
                "Withdrawal period and follow-up date",
                "Vet signature placeholder",
                "\"Share PDF\" and \"Save to Record\" actions",
                "Case: ${caseId ?: "no case selected"}",
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}