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
 * Screen 7 - the animal's longitudinal health record.
 *
 * @param onStartNewCase loops back to animal identification.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun HealthRecordScreen(
    onStartNewCase: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AhbScreen(
        destination = AhbDestination.HealthRecord,
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.HealthRecord,
                onStepClick = { /* Backward jumps enabled once cases persist. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        AhbPlaceholderContent(
            destination = AhbDestination.HealthRecord,
            plannedFeatures = listOf(
                "Animal header: photo, name, tag, species, age, owner",
                "Active withdrawal-period banner",
                "Filter chips: All / Cases / Treatments / Vaccinations / Withdrawals",
                "Vertical timeline with date and triage badges",
                "Chief complaint and vet decision per entry",
                "Empty state: \"No history yet\"",
                "\"Export Record\" and \"Start New Case\"",
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}