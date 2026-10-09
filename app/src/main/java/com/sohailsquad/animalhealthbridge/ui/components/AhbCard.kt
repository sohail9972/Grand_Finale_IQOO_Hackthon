package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors

/**
 * The standard content card: 16dp radius, 16dp internal padding, one surface
 * tier above the app background.
 *
 * @param modifier layout modifier.
 * @param title optional bold heading rendered above the content.
 * @param subtitle optional low-contrast line under the title.
 * @param accentBorder when true the card is outlined in teal, which is the
 *   "this one is selected" treatment from the language grid spec.
 * @param containerColor surface colour; defaults to the standard card tier.
 * @param content the card body.
 */
@Composable
fun AhbCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    accentBorder: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(LocalAhbDimens.current.radiusCard)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (accentBorder) {
                    Modifier.border(
                        border = BorderStroke(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            ),
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAhbColors.current.textDim,
                )
            }
            content()
        }
    }
}

/**
 * A card whose whole surface is tappable, used by the language grid and the
 * recent-animal list.
 *
 * @param onClick invoked on tap.
 * @param selected when true the card gets the teal accent border.
 */
@Composable
fun AhbSelectableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Surface's onClick overload supplies the ripple and the Button role, so
    // the card is reachable by TalkBack without extra semantics wiring.
    val shape = RoundedCornerShape(LocalAhbDimens.current.radiusCard)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier.padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAhbColors.current.textDim,
                )
            }
            content()
        }
    }
}