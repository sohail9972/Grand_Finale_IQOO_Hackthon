package com.sohailsquad.animalhealthbridge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand palette - exact hex values from the design system spec.
// These raw tokens are private to this file. UI code should read colours from
// MaterialTheme.colorScheme or LocalAhbColors, never from these constants
// directly, so the palette can be swapped in one place.
// ---------------------------------------------------------------------------

// Surfaces
internal val AhbBackground = Color(0xFF0E141E)
internal val AhbCard = Color(0xFF161C26)
internal val AhbCardSecondary = Color(0xFF242A35)

// Accent
internal val AhbAccent = Color(0xFF1FC8A8)
internal val AhbAccentLight = Color(0xFF4DE4C3)
internal val AhbAccentDeep = Color(0xFF0B7F69)

// Text
internal val AhbTextPrimary = Color(0xFFDDE2F1)
internal val AhbTextSecondary = Color(0xFFBBCAC4)
internal val AhbTextDim = Color(0xFF85948E)

// Semantic
internal val AhbWarning = Color(0xFFFFB46B)
internal val AhbEmergency = Color(0xFFFF5C5C)
internal val AhbSuccess = Color(0xFF1FC8A8)

// Derived helpers (not in the spec, but needed for readable contrast).
// On a teal fill we use a very dark green so text stays legible at 4.5:1+.
internal val AhbOnAccent = Color(0xFF04231C)
// Emergency fill needs a dark foreground for the same reason.
internal val AhbOnEmergency = Color(0xFF2B0707)

/**
 * Colours the Material 3 [androidx.compose.material3.ColorScheme] has no slot for.
 *
 * Material 3 exposes primary/secondary/tertiary/error but has no notion of a
 * "triage urgency" ramp or a "dim" text tier. Rather than overloading the M3
 * slots (which makes the mapping unreadable), the extras live here and are
 * reached through [LocalAhbColors].
 */
@Immutable
data class AhbExtraColors(
    val cardSecondary: Color = AhbCardSecondary,
    val textDim: Color = AhbTextDim,
    val warning: Color = AhbWarning,
    val emergency: Color = AhbEmergency,
    val success: Color = AhbSuccess,
    val accentLight: Color = AhbAccentLight,
    val onAccent: Color = AhbOnAccent,
    val onEmergency: Color = AhbOnEmergency,
)

/**
 * Composition-local holder for [AhbExtraColors].
 *
 * `staticCompositionLocalOf` is the right choice here: the value never changes
 * during a session (there is no runtime theming), and static locals skip the
 * per-read recomposition bookkeeping that `compositionLocalOf` would add.
 */
val LocalAhbColors = staticCompositionLocalOf { AhbExtraColors() }
