package com.sohailsquad.animalhealthbridge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing / radius / size tokens from the design system spec.
 *
 * Held in a data class rather than loose top-level constants so call sites read
 * as `LocalAhbDimens.current.padding` instead of a bare `16.dp`, which keeps
 * the layout intent obvious and makes a global rhythm change a one-line edit.
 */
@Immutable
data class AhbDimens(
    /** Standard screen padding. */
    val padding: Dp = 16.dp,
    /** Vertical/horizontal gap between sibling elements. */
    val gap: Dp = 12.dp,
    /** Tighter gap used inside a card. */
    val gapTight: Dp = 8.dp,
    /** Generous gap separating major sections. */
    val gapLoose: Dp = 24.dp,
    /** Card corner radius. */
    val radiusCard: Dp = 16.dp,
    /** Button corner radius. */
    val radiusButton: Dp = 24.dp,
    /** Small chips / badges. */
    val radiusChip: Dp = 12.dp,
    /** Hairline border on outlined surfaces. */
    val borderWidth: Dp = 1.dp,
    /**
     * Minimum touch target. 48dp is the Android accessibility floor; the spec
     * repeats it as a hard requirement, so it is enforced by the shared button
     * and list-item components rather than by each screen.
     */
    val touchTarget: Dp = 48.dp,
    /** Large circular mic button on the voice-intake screen. */
    val micButton: Dp = 120.dp,
)

/**
 * Composition-local holder for [AhbDimens].
 *
 * `staticCompositionLocalOf` is the right choice here: the value never changes
 * during a session (there is no runtime dimension theming), and static locals
 * skip the per-read recomposition bookkeeping that `compositionLocalOf` would
 * add.
 *
 * Read it as `LocalAhbDimens.current.padding`. The defaults on [AhbDimens] mean
 * this is always safe to read, even if a caller forgets to provide a value.
 */
val LocalAhbDimens = staticCompositionLocalOf { AhbDimens() }