// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B Round 12 step 3 — release pin contract test.
 *
 * Pins the release variant of `apps/android/build.gradle.kts` so
 * that the `POLL_SKIP_LP_AND_PP` diagnostic toggle cannot be
 * promoted to `"1"` in the release block by accident. The
 * Round-12 council security cross-check (BS-1, BS-4, BS-6)
 * identified any active diagnostic LP+PP strip in release as a
 * privacy-mode metadata regression vector and a traffic-analysis
 * fingerprint hazard, so the release pin is a guardrail
 * independent of the runtime `BuildConfig.DEBUG && PrivacyMode ==
 * Standard` chain inside `AppContainer`.
 *
 * Mirrors the `LongPollV2ReleaseBuildConfigPinTest` (Stage 2B-A
 * L6) text-parse pattern — the BuildConfig value is a
 * Gradle-generated constant and a release-variant unit test is
 * overkill for reading one literal.
 */
class PollSkipLpAndPpReleaseBuildConfigPinTest {

    /**
     * The exact line we expect inside the release block. Kept on
     * one line so an extra space or a re-flow in the .kts file
     * trips this test deliberately — the pin's value is in not
     * drifting at all.
     */
    private val expectedPinLine: String =
        "buildConfigField(\"String\", \"POLL_SKIP_LP_AND_PP\", \"\\\"0\\\"\")"

    @Test
    fun release_block_pins_poll_skip_lp_and_pp_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractTopLevelBuildTypeBlock(source, "release {")
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedPinLine),
            "Release variant MUST pin `POLL_SKIP_LP_AND_PP` to `\"0\"` per the Round 12 council " +
                "security cross-check release-pin invariant. Expected to find the literal\n" +
                "  $expectedPinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "The diagnostic LP+PP-strip MUST NOT be active in any release variant shipped " +
                "to users — flipping this pin to `\"1\"` would silently degrade the wire shape " +
                "of all production users.",
        )
    }

    @Test
    fun debug_block_does_not_carry_the_literal_zero_pin() {
        // Sanity check that the literal release pin line is not
        // already present in the debug block — otherwise the release
        // pin test would pass even if the release block were deleted,
        // because `extractTopLevelBuildTypeBlock` would find the
        // wrong block. The debug block uses a runtime-computed
        // `localOrEnv(...)` value, not a literal `"0"`, so the same
        // exact literal MUST NOT appear in the debug block.
        val source = loadBuildGradle()
        val debugBlock = extractTopLevelBuildTypeBlock(source, "debug {")
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            !debugBlock.contains(expectedPinLine),
            "Debug variant MUST use the `localOrEnv(...)` runtime value for " +
                "`POLL_SKIP_LP_AND_PP`, not the literal release pin. Finding the literal pin " +
                "in the debug block would make the release pin assert pass on a wrong block " +
                "lookup.",
        )
    }

    @Test
    fun build_gradle_does_not_declare_rejected_partial_strip_buildConfigField() {
        // The Round 12 council REJECTED the original partial-strip
        // diagnostic name because it implies a PP-only strip
        // (LP retained), which violates the L1 LP+PP coupling lock
        // and produces a traffic-analysis fingerprint regression.
        // The accepted name signals that BOTH headers are dropped
        // together. This narrow negative-grep checks only for the
        // rejected name appearing as a `buildConfigField` key — it
        // intentionally does NOT match the rejected name appearing
        // in this file's own documentation comments (which must
        // reference it to explain why it was rejected; that is the
        // same "fence cannot fence its own input" exception
        // documented for the S6 negative-grep test).
        val source = loadBuildGradle()
        val rejectedBuildConfigFieldRegex = Regex(
            """buildConfigField\s*\(\s*"String"\s*,\s*"POLL_SKIP_PADDED_BODY"""",
        )
        assertTrue(
            !rejectedBuildConfigFieldRegex.containsMatchIn(source),
            "`apps/android/build.gradle.kts` MUST NOT declare a `buildConfigField` named " +
                "with the rejected partial-strip moniker. The accepted name is " +
                "`POLL_SKIP_LP_AND_PP`, signalling that BOTH `X-Phantom-Long-Poll` and " +
                "`X-Phantom-Padded-Poll` headers are dropped atomically.",
        )
    }

    private fun loadBuildGradle(): String {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("apps/android/build.gradle.kts"),
            File("../build.gradle.kts"),
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
        assertNotNull(
            file,
            "Could not locate the apps/android `build.gradle.kts` file from the " +
                "unit-test working directory. Tried: ${candidates.joinToString { it.absolutePath }}",
        )
        return file.readText(Charsets.UTF_8)
    }

    private fun extractTopLevelBuildTypeBlock(source: String, header: String): String? {
        val headerIdx = source.indexOf(header)
        if (headerIdx < 0) return null
        val openIdx = source.indexOf('{', startIndex = headerIdx)
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
}
