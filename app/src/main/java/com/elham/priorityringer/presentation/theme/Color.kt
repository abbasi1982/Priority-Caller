package com.elham.priorityringer.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Architecture.md § 12: "keep contrast high (family/accessibility)".
 *
 * Dynamic colour is deliberately **not** used. § 12 lists it as optional and
 * makes contrast the actual requirement; a wallpaper-derived palette can put a
 * pale amber on a pale surface, which is exactly the failure mode that matters
 * here — the Dashboard readiness banner is the one piece of UI that must be
 * unmistakable at a glance.
 */

// ---- Brand / primary -------------------------------------------------------

internal val BrandBlue40 = Color(0xFF00468C)
internal val BrandBlue80 = Color(0xFFA9C7FF)
internal val BrandBlueContainerLight = Color(0xFFD7E3FF)
internal val BrandBlueContainerDark = Color(0xFF00316B)

internal val OnBrandBlueContainerLight = Color(0xFF0A2547)
internal val OnAccentTealContainerLight = Color(0xFF002022)
internal val OnErrorDark = Color(0xFF690007)

internal val AccentTeal40 = Color(0xFF00696E)
internal val AccentTeal80 = Color(0xFF80D5DA)
internal val AccentTealContainerLight = Color(0xFFB0ECF1)
internal val AccentTealContainerDark = Color(0xFF004F53)

// ---- Neutrals: near-black on near-white, not the M3 defaults ---------------

internal val SurfaceLight = Color(0xFFFCFCFF)
internal val SurfaceContainerLight = Color(0xFFECEFF5)
internal val OnSurfaceLight = Color(0xFF101418)
internal val OutlineLight = Color(0xFF44474E)

internal val SurfaceDark = Color(0xFF0E1116)
internal val SurfaceContainerDark = Color(0xFF1C2026)
internal val OnSurfaceDark = Color(0xFFF1F3F7)
internal val OutlineDark = Color(0xFFB6BAC2)

// ---- Error -----------------------------------------------------------------

internal val ErrorLight = Color(0xFF9E0016)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)

internal val ErrorDark = Color(0xFFFFB4AB)
internal val ErrorContainerDark = Color(0xFF7A0010)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

/**
 * The three readiness states and the three audit severities.
 *
 * Kept outside [androidx.compose.material3.ColorScheme] because M3 has no slot
 * for "warning" or "success", and Architecture.md § A.5 requires ARMED /
 * DEGRADED / INERT to be visually unmistakable from one another — colour is one
 * of three signals (icon and wording are the others; colour alone would fail
 * for a colour-blind user).
 */
data class StatusPalette(
    val armed: Color,
    val onArmed: Color,
    val armedContainer: Color,
    val onArmedContainer: Color,
    val degraded: Color,
    val onDegraded: Color,
    val degradedContainer: Color,
    val onDegradedContainer: Color,
    val inert: Color,
    val onInert: Color,
    val inertContainer: Color,
    val onInertContainer: Color,
)

internal val LightStatusPalette = StatusPalette(
    armed = Color(0xFF14602C),
    onArmed = Color(0xFFFFFFFF),
    armedContainer = Color(0xFFBFF0C8),
    onArmedContainer = Color(0xFF05200D),
    degraded = Color(0xFF7A4B00),
    onDegraded = Color(0xFFFFFFFF),
    degradedContainer = Color(0xFFFFDFB0),
    onDegradedContainer = Color(0xFF2A1700),
    inert = Color(0xFF9E0016),
    onInert = Color(0xFFFFFFFF),
    inertContainer = Color(0xFFFFDAD6),
    onInertContainer = Color(0xFF410002),
)

internal val DarkStatusPalette = StatusPalette(
    armed = Color(0xFF7ADB94),
    onArmed = Color(0xFF00390F),
    armedContainer = Color(0xFF00531C),
    onArmedContainer = Color(0xFFBFF0C8),
    degraded = Color(0xFFFFC36B),
    onDegraded = Color(0xFF3F2500),
    degradedContainer = Color(0xFF5C3700),
    onDegradedContainer = Color(0xFFFFDFB0),
    inert = Color(0xFFFFB4AB),
    onInert = Color(0xFF690007),
    inertContainer = Color(0xFF7A0010),
    onInertContainer = Color(0xFFFFDAD6),
)
