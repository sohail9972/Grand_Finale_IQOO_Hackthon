package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors

/**
 * Horizontal progress bar showing where the user is in the 7-step flow.
 *
 * This is intentionally NOT a Material [androidx.compose.material3.NavigationBar].
 * The flow is strictly sequential - a farmer cannot jump to "Vet approval"
 * before describing symptoms - so a tab bar would advertise navigation the app
 * must refuse. A step rail communicates order and progress instead.
 *
 * The rail is horizontally scrollable because seven labels do not fit on a
 * 360dp-wide screen without truncating them into uselessness.
 *
 * @param current the screen being displayed.
 * @param modifier layout modifier.
 * @param onStepClick invoked when a user taps a step; used by the Step-1 debug
 *   switcher and, later, to jump backwards to an already-completed step.
 */
@Composable
fun AhbStepBar(
    current: AhbDestination,
    modifier: Modifier = Modifier,
    onStepClick: ((AhbDestination) -> Unit)? = null,
) {
    val steps = AhbDestination.STEPS
    val currentStep = if (current == AhbDestination.Prescription) AhbDestination.VetApproval else current
    val currentIndex = steps.indexOf(currentStep).let { if (it < 0) 0 else it }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = LocalAhbDimens.current.padding,
                    vertical = LocalAhbDimens.current.gapTight,
                ),
            horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { index, step ->
                val state = when {
                    index < currentIndex -> StepState.DONE
                    index == currentIndex -> StepState.CURRENT
                    else -> StepState.UPCOMING
                }
                StepChip(
                    step = step,
                    index = index + 1,
                    state = state,
                    onClick = onStepClick?.let { callback -> { callback(step) } },
                )
            }
        }
    }
}

/** Visual state of a single step in the rail. */
private enum class StepState { DONE, CURRENT, UPCOMING }

/**
 * One step pill: number, icon, and short label.
 *
 * @param index 1-based step number shown in the leading circle.
 */
@Composable
private fun StepChip(
    step: AhbDestination,
    index: Int,
    state: StepState,
    onClick: (() -> Unit)?,
) {
    val extras = LocalAhbColors.current
    val (container, content) = when (state) {
        StepState.DONE -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.primary
        StepState.CURRENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.primary
        StepState.UPCOMING -> MaterialTheme.colorScheme.surface to extras.textDim
    }

    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(state) { if (state == StepState.CURRENT) requester.bringIntoView() }
    val label = when (step) {
        AhbDestination.LanguageSelect -> "Language"
        AhbDestination.AnimalId -> "Identify"
        AhbDestination.VoiceIntake -> "Intake"
        AhbDestination.CaseReview -> "Review"
        AhbDestination.VetApproval, AhbDestination.Prescription -> "Approval"
        AhbDestination.VetQuery -> "Questions"
        AhbDestination.HealthRecord -> "Records"
    }
    Surface(
        shape = RoundedCornerShape(LocalAhbDimens.current.radiusChip),
        color = container,
        contentColor = content,
        // Every chip is a real 48dp-tall target even though it looks compact.
        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 80.dp).bringIntoViewRequester(requester)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                selected = state == StepState.CURRENT
                stateDescription = when (state) {
                    StepState.CURRENT -> "Current step"
                    StepState.DONE -> "Previous step"
                    StepState.UPCOMING -> "Upcoming step"
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = step.icon,
                contentDescription = null, // the label below is the accessible name
                tint = content,
                modifier = Modifier.size(24.dp),
            )
            Text(
                // "3 · Describe symptoms" reads as a step, not as a button.
                text = "$index · $label",
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
