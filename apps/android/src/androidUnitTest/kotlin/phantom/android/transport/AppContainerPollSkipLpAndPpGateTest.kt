// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B Round 12 step 3 — AppContainer wiring contract
 * for the `POLL_SKIP_LP_AND_PP` diagnostic toggle's three-conjunct
 * runtime gate.
 *
 * The runtime gate that decides whether to strip BOTH LP and PP
 * headers on each poll iteration MUST combine all three of:
 *
 *   (1) `BuildConfig.DEBUG`               — debug variant only.
 *   (2) `BuildConfig.POLL_SKIP_LP_AND_PP == "1"` — explicit operator opt-in.
 *   (3) `transportPreferences.privacyMode == PrivacyMode.Standard`
 *                                         — Privacy/Ghost are protected
 *                                           by the Vladislav-locked
 *                                           uniform-functionality rule
 *                                           (2026-06-06).
 *
 * Missing ANY conjunct turns the strip off. The council security
 * cross-check (BS-6) identified the absence of the third conjunct
 * (PrivacyMode gate) as a HIGH-severity Privacy/Ghost exposure
 * vector — a debug build with the second conjunct flipped on but
 * the user in Ghost mode would silently degrade the wire shape
 * without warning.
 *
 * This contract test is a source-parse fence: parse
 * `AppContainer.kt`, locate the `pollSkipLpAndPpProvider = { ... }`
 * lambda body inside the `createRestFallbackTransport(...)` call,
 * and assert that the lambda's text contains the three required
 * conjuncts joined by `&&` (in any order, with any whitespace).
 *
 * Why a parse test instead of a behavioural test: the lambda is
 * an inline runtime closure over `transportPreferences`, which
 * requires a full container init to observe at runtime; the source
 * fence catches the regression at a far lower cost.
 */
class AppContainerPollSkipLpAndPpGateTest {

    private fun loadAppContainer(): String {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
        assertNotNull(
            file,
            "Could not locate AppContainer.kt from the unit-test working directory. " +
                "Tried: ${candidates.joinToString { it.absolutePath }}",
        )
        return file.readText(Charsets.UTF_8)
    }

    private fun extractPollSkipProviderLambda(source: String): String? {
        // Locate the `pollSkipLpAndPpProvider = {` parameter assignment, then
        // walk a balanced-brace scanner to extract the lambda body.
        val markerIdx = source.indexOf("pollSkipLpAndPpProvider = {")
        if (markerIdx < 0) return null
        val openIdx = source.indexOf('{', startIndex = markerIdx)
        if (openIdx < 0) return null
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return source.substring(openIdx + 1, i - 1)
    }

    @Test
    fun gate_lambda_contains_BuildConfig_DEBUG_conjunct() {
        val source = loadAppContainer()
        val lambda = extractPollSkipProviderLambda(source)
            ?: fail(
                "Could not locate `pollSkipLpAndPpProvider = { ... }` lambda in AppContainer.kt. " +
                    "Round 12 step 3 wiring is missing; the diagnostic toggle would silently default " +
                    "to `{ false }` and a future operator who flips `BuildConfig.POLL_SKIP_LP_AND_PP=1` " +
                    "would see no effect.",
            )
        assertTrue(
            lambda.contains("BuildConfig.DEBUG"),
            "pollSkipLpAndPpProvider lambda MUST gate on `BuildConfig.DEBUG`. The release variant " +
                "where `BuildConfig.DEBUG` is `false` MUST be unable to activate the strip. " +
                "Lambda body:\n$lambda",
        )
    }

    @Test
    fun gate_lambda_contains_POLL_SKIP_LP_AND_PP_eq_one_conjunct() {
        val source = loadAppContainer()
        val lambda = extractPollSkipProviderLambda(source)
            ?: fail("Could not locate `pollSkipLpAndPpProvider = { ... }` lambda in AppContainer.kt.")
        assertTrue(
            lambda.contains("BuildConfig.POLL_SKIP_LP_AND_PP") &&
                lambda.contains("== \"1\""),
            "pollSkipLpAndPpProvider lambda MUST require `BuildConfig.POLL_SKIP_LP_AND_PP == \"1\"`. " +
                "Without this explicit operator opt-in conjunct, every debug build in Standard mode " +
                "would emit the diagnostic strip by default — privacy regression. " +
                "Lambda body:\n$lambda",
        )
    }

    @Test
    fun gate_lambda_contains_PrivacyMode_Standard_conjunct() {
        val source = loadAppContainer()
        val lambda = extractPollSkipProviderLambda(source)
            ?: fail("Could not locate `pollSkipLpAndPpProvider = { ... }` lambda in AppContainer.kt.")
        assertTrue(
            lambda.contains("PrivacyMode.Standard") &&
                lambda.contains("transportPreferences.privacyMode"),
            "pollSkipLpAndPpProvider lambda MUST require `transportPreferences.privacyMode == " +
                "PrivacyMode.Standard`. Privacy and Ghost sessions MUST NOT activate the diagnostic " +
                "strip — the Vladislav-locked uniform-functionality rule (2026-06-06) requires those " +
                "tiers to receive at least the same wire-shape protections as Standard. The council " +
                "security cross-check (BS-6) identified the absence of this conjunct as a HIGH-severity " +
                "Privacy/Ghost exposure vector. Lambda body:\n$lambda",
        )
    }

    @Test
    fun gate_lambda_uses_logical_AND_conjunction_for_all_three_conditions() {
        // Pin that the gate is `A && B && C`, not `A || B || C`. A
        // future refactor that accidentally flips one `&&` to `||`
        // would silently enable the strip on ANY of the three
        // conditions instead of ALL of them — catastrophic privacy
        // regression. We count the `&&` occurrences inside the
        // lambda; the structural form is two ANDs joining three
        // operands.
        val source = loadAppContainer()
        val lambda = extractPollSkipProviderLambda(source)
            ?: fail("Could not locate `pollSkipLpAndPpProvider = { ... }` lambda in AppContainer.kt.")
        val andCount = Regex("""&&""").findAll(lambda).count()
        assertTrue(
            andCount >= 2,
            "pollSkipLpAndPpProvider lambda MUST conjoin its three required conditions with `&&`. " +
                "Expected at least 2 `&&` operators in the lambda body; found $andCount. " +
                "A `||` joining the conditions would activate the strip on ANY single condition " +
                "instead of ALL three, defeating the privacy-gate. Lambda body:\n$lambda",
        )
        // Pin that the lambda does NOT contain `||` between the
        // three relevant conditions. Plain text scan is enough — a
        // `||` would mean a future maintainer accidentally weakened
        // the gate.
        assertTrue(
            !lambda.contains("||"),
            "pollSkipLpAndPpProvider lambda MUST NOT contain `||` — all three conditions must " +
                "be conjoined by `&&`. A `||` would silently activate the strip on any single " +
                "condition, defeating the privacy-gate. Lambda body:\n$lambda",
        )
    }

    @Test
    fun gate_lambda_does_not_reference_BuildConfig_for_rejected_partial_strip_name() {
        // Narrow fence: the lambda body MUST NOT read a BuildConfig
        // field with the rejected partial-strip name. Reading the
        // full AppContainer.kt source for the literal name would
        // false-positive on this test's own KDoc — same "fence
        // cannot fence its own input" exception that the
        // S6DocsHygieneNegativeGrepTest documents.
        val source = loadAppContainer()
        val lambda = extractPollSkipProviderLambda(source)
            ?: fail("Could not locate `pollSkipLpAndPpProvider = { ... }` lambda in AppContainer.kt.")
        assertTrue(
            !lambda.contains("BuildConfig.POLL_SKIP_PADDED_BODY"),
            "pollSkipLpAndPpProvider lambda MUST NOT read `BuildConfig.POLL_SKIP_PADDED_BODY` " +
                "(the rejected partial-strip name). The accepted name is " +
                "`BuildConfig.POLL_SKIP_LP_AND_PP`. Lambda body:\n$lambda",
        )
    }
}
