// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher

/**
 * Shared semantics matchers for the OnboardingV2 test suites.
 *
 * Extracted from `OnboardingV2SemanticsTest.kt` in round-4 REDLINE
 * §P2-1 test-file split — matchers were duplicated between the
 * split test files; moving them here keeps each test-file focused
 * on its assertions and gives one canonical source for the matcher
 * shape.
 */

/** Match a semantics node whose config exposes `Role.Button`. */
internal fun hasButtonRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Button") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Button"
    }

/** Match a semantics node whose config exposes `Role.Tab`. */
internal fun hasTabRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Tab") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Tab"
    }

/** Match a semantics node that carries any Error semantic. */
internal fun hasAnyErrorSemantic(): SemanticsMatcher =
    SemanticsMatcher("Has any Error semantic") { node ->
        node.config.getOrNull(SemanticsProperties.Error) != null
    }

/** Match a semantics node that is focusable (has Focused property). */
internal fun hasFocusedSemantic(): SemanticsMatcher =
    SemanticsMatcher("Has Focused semantic (focusable node)") { node ->
        node.config.getOrNull(SemanticsProperties.Focused) != null
    }

/** Match a semantics node whose OnClick action label is "Close pricing". */
internal fun hasClosePricingClickLabel(): SemanticsMatcher =
    SemanticsMatcher("Has click action + Close pricing label") { node ->
        val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
        label == "Close pricing"
    }

/**
 * Match a semantics node marked `invisibleToUser`.
 *
 * `InvisibleToUser` is set via `Modifier.semantics { invisibleToUser() }`
 * — checked via `contains` on the raw SemanticsProperties key rather
 * than the typed getter (the typed getter isn't always public across
 * Compose versions).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun hasInvisibleToUserSemantic(): SemanticsMatcher =
    SemanticsMatcher("Has InvisibleToUser semantic") { node ->
        node.config.contains(SemanticsProperties.InvisibleToUser)
    }

/** Match a semantics node with an exact contentDescription. */
internal fun hasContentDescriptionExact(expected: String): SemanticsMatcher =
    SemanticsMatcher("contentDescription == '$expected'") { node ->
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
        desc?.any { it == expected } == true
    }

/** Match a semantics node with a specific Selected state. */
internal fun hasSelectedState(expected: Boolean): SemanticsMatcher =
    SemanticsMatcher("Selected == $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Selected) == expected
    }

/**
 * Nullable-safe `SemanticsConfiguration` accessor — returns null
 * for absent keys instead of throwing `IllegalStateException`.
 * Used by every matcher above; extracted here so the extension is
 * available to any semantic-inspection code in the module.
 */
internal fun <T> SemanticsConfiguration.getOrNull(
    key: SemanticsPropertyKey<T>,
): T? = try {
    this[key]
} catch (_: IllegalStateException) {
    null
}
