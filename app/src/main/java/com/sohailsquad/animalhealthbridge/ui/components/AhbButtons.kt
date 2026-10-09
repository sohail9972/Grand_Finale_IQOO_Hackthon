package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens

/**
 * Primary action button (Continue, Speak, Approve Treatment).
 *
 * The design-system rules are baked in here so no screen can get them wrong:
 *   - 24dp corner radius;
 *   - 48dp minimum height (the accessibility floor from the spec);
 *   - while [loading] is true the button is disabled and shows a spinner,
 *     satisfying "every async operation has a loading state";
 *   - the label stays visible while loading so the layout does not jump.
 *
 * @param text button label.
 * @param onClick invoked on tap.
 * @param modifier layout modifier.
 * @param enabled when false the button is inert (e.g. a form is incomplete).
 * @param loading when true a spinner replaces the icon and taps are ignored.
 * @param icon optional leading icon.
 */
@Composable
fun AhbPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        // A loading button must not fire again: double-tapping "Approve" would
        // otherwise create two treatments for a single case.
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = LocalAhbDimens.current.touchTarget),
        shape = RoundedCornerShape(LocalAhbDimens.current.radiusButton),
        contentPadding = PaddingValues(
            horizontal = LocalAhbDimens.current.gapLoose,
            vertical = LocalAhbDimens.current.gap,
        ),
    ) {
        ButtonLabel(
            text = text,
            loading = loading,
            icon = icon,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Secondary action button (View History, Type Instead, Request More Info).
 *
 * Outlined rather than filled so it never competes with the primary action on
 * the same screen.
 */
@Composable
fun AhbSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = LocalAhbDimens.current.touchTarget),
        shape = RoundedCornerShape(LocalAhbDimens.current.radiusButton),
        contentPadding = PaddingValues(
            horizontal = LocalAhbDimens.current.gapLoose,
            vertical = LocalAhbDimens.current.gap,
        ),
    ) {
        ButtonLabel(
            text = text,
            loading = loading,
            icon = icon,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Full-width primary action for the bottom of a screen's content column.
 *
 * A separate composable because "the main button spans the screen" is a layout
 * decision, not a styling one; folding it into [AhbPrimaryButton] would make
 * the inline call sites read as if they were also full width.
 */
@Composable
fun AhbPrimaryButtonWide(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    AhbPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        loading = loading,
        icon = icon,
    )
}

/**
 * Low-emphasis text button (Skip, Not now).
 *
 * @param onClick invoked on tap.
 */
@Composable
fun AhbTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = LocalAhbDimens.current.touchTarget),
        shape = RoundedCornerShape(LocalAhbDimens.current.radiusButton),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Shared inner content for the filled and outlined buttons: an optional spinner
 * or icon, then the label.
 *
 * Private because it is an implementation detail of the two button styles; a
 * screen that wants a bespoke button should compose its own rather than reach
 * in here.
 */
@Composable
private fun ButtonLabel(
    text: String,
    loading: Boolean,
    icon: ImageVector?,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: the adjacent label already carries the meaning,
                // so announcing the icon too would double up in TalkBack.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                start = if (loading || icon != null) LocalAhbDimens.current.gapTight else 0.dp,
            ),
        )
    }
}