package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.R
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors

/**
 * Step-1 scaffold body shared by all eight screens.
 *
 * Each screen renders its real heading (from [AhbDestination]) plus a bulleted
 * list of the behaviour it will gain in its implementation step. That makes the
 * scaffold demonstrably wired - the headings change as you navigate - while
 * keeping Step 1 honestly a scaffold rather than a half-built feature.
 *
 * This composable is deleted in Step 11 once every screen has real content.
 *
 * @param destination the screen rendering this placeholder.
 * @param plannedFeatures the behaviours this screen will implement, one per bullet.
 */
@Composable
fun AhbPlaceholderContent(
    destination: AhbDestination,
    plannedFeatures: List<String>,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAhbColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(LocalAhbDimens.current.padding),
        verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Construction,
            contentDescription = null,
            tint = extras.textDim,
            modifier = Modifier.size(48.dp),
        )

        Text(
            text = stringResource(R.string.placeholder_step_banner),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        AhbCard(
            title = stringResource(R.string.placeholder_planned_heading),
            subtitle = stringResource(destination.titleRes),
        ) {
            plannedFeatures.forEach { feature ->
                Text(
                    text = "•  $feature",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}