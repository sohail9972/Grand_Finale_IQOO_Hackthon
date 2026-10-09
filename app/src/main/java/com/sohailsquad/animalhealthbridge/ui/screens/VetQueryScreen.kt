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
 * Screen 6 - the two-way vet query loop.
 *
 * One screen, two views. The owner sees questions to answer; the vet sees a
 * question composer. Step 8 splits the body on the viewer's role.
 *
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun VetQueryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AhbScreen(
        destination = AhbDestination.VetQuery,
        onBack = onBack,
        modifier = modifier,
        safetyBanner = true,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.VetQuery,
                onStepClick = { /* Backward jumps enabled once cases persist. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        AhbPlaceholderContent(
            destination = AhbDestination.VetQuery,
            plannedFeatures = listOf(
                "OWNER VIEW: questions in the owner's language with answer controls",
                "PHOTO answer: open camera, thumbnail, retake",
                "VIDEO answer: 30s maximum, preview before sending",
                "YES_NO answer: three pre-translated buttons (Yes / No / Not sure)",
                "TEXT answer: type, or speak the answer with the mic",
                "Up to 5 media files per question",
                "VET VIEW: free-text question composer",
                "Template library offered as suggestions only, never as the only choice",
                "Every query type has a fallback (PHOTO falls back to TEXT)",
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}