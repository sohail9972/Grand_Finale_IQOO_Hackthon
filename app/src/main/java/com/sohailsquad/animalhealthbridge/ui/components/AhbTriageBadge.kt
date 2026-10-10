package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors

/**
 * The three urgency levels the deterministic rules engine can assign.
 *
 * Declared here - not in the rules-engine package - because the enum is first
 * and foremost a presentation concern: it decides colour and icon. Step 6 will
 * add the rule engine that produces these values; keeping the shared vocabulary
 * in one place avoids a cyclic dependency between engine and UI.
 *
 * Ordering is meaningful: `ordinal` runs from most to least urgent, which lets
 * callers compare severity with `<` / `>` instead of a lookup table.
 */
typealias TriageLevel = com.sohailsquad.animalhealthbridge.triage.TriageLevel

/**
 * Colour + icon + label for a [TriageLevel], resolved from the theme.
 *
 * Kept as a private extension so the mapping lives next to the badge that uses
 * it, rather than being duplicated on every screen that shows urgency.
 */
private data class TriageStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
)

@Composable
private fun styleFor(level: TriageLevel): TriageStyle {
    val extras = LocalAhbColors.current
    return when (level) {
        TriageLevel.EMERGENCY_NOW -> TriageStyle(
            container = extras.emergency.copy(alpha = 0.18f),
            content = extras.emergency,
            icon = Icons.Filled.Error,
        )
        TriageLevel.SEE_VET_TODAY -> TriageStyle(
            container = extras.warning.copy(alpha = 0.18f),
            content = extras.warning,
            icon = Icons.Filled.Warning,
        )
        TriageLevel.MONITOR -> TriageStyle(
            container = extras.success.copy(alpha = 0.18f),
            content = extras.success,
            icon = Icons.Filled.CheckCircle,
        )
    }
}

/**
 * Human-readable label for a triage level.
 *
 * The strings are deliberately spelled out rather than derived from the enum
 * name so the wording can change without touching the data model.
 */
fun TriageLevel.displayLabel(): String = when (this) {
    TriageLevel.EMERGENCY_NOW -> "EMERGENCY NOW"
    TriageLevel.SEE_VET_TODAY -> "SEE VET TODAY"
    TriageLevel.MONITOR -> "MONITOR"
}

/**
 * Pill-shaped urgency badge.
 *
 * Colour is never the only signal: the badge always pairs a colour with a
 * distinct icon and a text label, so it stays readable for colour-blind users
 * and in bright sunlight on a cheap screen.
 *
 * @param level the urgency to display.
 * @param modifier layout modifier.
 */
@Composable
fun AhbTriageBadge(
    level: TriageLevel,
    modifier: Modifier = Modifier,
) {
    val style = styleFor(level)
    Row(
        modifier = modifier
            .background(
                color = style.container,
                shape = RoundedCornerShape(LocalAhbDimens.current.radiusChip),
            )
            .padding(horizontal = LocalAhbDimens.current.gap, vertical = LocalAhbDimens.current.gapTight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null, // the label below carries the meaning
            tint = style.content,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = level.displayLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = style.content,
        )
    }
}
