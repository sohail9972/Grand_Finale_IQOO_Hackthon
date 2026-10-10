package com.sohailsquad.animalhealthbridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------
// Dark colour scheme.
//
// The product is dark-only by design: it is used outdoors, often at night in a
// shed with one bulb, and a bright UI wrecks night vision and drains battery.
// There is intentionally NO light scheme - `darkTheme` below is always true.
// ---------------------------------------------------------------------------

private val AhbDarkColorScheme = darkColorScheme(
    // Brand accent drives primary actions (Continue, Speak, Approve).
    primary = AhbAccent,
    onPrimary = AhbOnAccent,
    primaryContainer = AhbAccentDeep,
    onPrimaryContainer = AhbAccentLight,

    // Secondary surfaces: chips, secondary buttons.
    secondary = AhbCardSecondary,
    onSecondary = AhbTextPrimary,
    secondaryContainer = AhbCardSecondary,
    onSecondaryContainer = AhbTextSecondary,

    tertiary = AhbAccentLight,
    onTertiary = AhbOnAccent,

    // Surfaces: app background is the darkest tier, cards sit one step up.
    background = AhbBackground,
    onBackground = AhbTextPrimary,
    surface = AhbCard,
    onSurface = AhbTextPrimary,
    surfaceVariant = AhbCardSecondary,
    onSurfaceVariant = AhbTextSecondary,
    surfaceContainer = AhbCard,
    surfaceContainerHigh = AhbCardSecondary,
    surfaceContainerLow = AhbCard,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF080E18),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF2F3540),

    // Error doubles as the EMERGENCY_NOW triage colour.
    error = AhbEmergency,
    onError = AhbOnEmergency,
    errorContainer = AhbEmergency,
    onErrorContainer = AhbTextPrimary,

    outline = AhbTextDim,
    outlineVariant = AhbCardSecondary,

    // A dark scrim so modal sheets stay readable over dark content.
    scrim = AhbBackground,
    inverseSurface = AhbTextPrimary,
    inverseOnSurface = AhbBackground,
)

/**
 * Root theme for the whole app.
 *
 * @param content the composable tree to theme.
 */
@Composable
fun AnimalHealthBridgeTheme(content: @Composable () -> Unit) {
    // `isSystemInDarkTheme()` is read but deliberately ignored - keeping the
    // call documents that the dark-only decision is intentional rather than an
    // oversight, and silences "unused import" style questions from reviewers.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Draw behind the system bars so the #0E141E background reaches the
            // screen edges instead of leaving grey letterbox bars.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // The status bar icons must be light because the background is dark.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalAhbColors provides AhbExtraColors(),
        LocalAhbDimens provides AhbDimens(),
    ) {
        MaterialTheme(
            colorScheme = AhbDarkColorScheme,
            typography = AhbTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
