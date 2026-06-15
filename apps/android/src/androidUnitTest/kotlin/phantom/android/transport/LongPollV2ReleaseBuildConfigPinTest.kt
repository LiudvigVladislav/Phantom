// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-D rollout — M4 contract test (pin-direction flipped
 * 2026-06-16 from `"0"` to `"1"` in the dedicated Stage 2B-D rollout PR).
 *
 * Pins the release variant of the `apps/android/build.gradle.kts` file
 * so that `LONGPOLL_V2_ENABLED` cannot drift away from `"1"` in the
 * release block by accident. The release pin is the load-bearing safety
 * invariant of Stage 2B-D rollout: a release-mode APK with the flag at
 * `"1"` emits `X-Phantom-Long-Poll: 1` + `X-Phantom-Padded-Poll: 1`
 * opt-in headers on every `/relay/poll` request, activating the Round
 * 14 paced chunked-flush path on the production relay (deployed via
 * PR #310 squash `345d9761` with `RELAY_POLL_CHUNKED_FLUSH=1` set on
 * the VPS).
 *
 * Pre-Stage-2B-D history (for context). Stage 2B-A originally pinned
 * the release block to `"0"` so a release APK was byte-for-byte
 * runtime-equivalent to a pre-2B-A build regardless of the new wiring
 * — defence-in-depth on top of `BuildConfig.DEBUG`. The pin survived
 * Stage 2B-A (PR #306), Stage 2B-B (PR #309), Sprint 2a (PR #311),
 * Sprint 2b-A/B/C (PRs #315/#316/#317), and Round 14 (PR #310). The
 * 2026-06-16 Stage 2B-D rollout PR is the deliberate "single named
 * PR" that the prior comment promised would flip this pin.
 *
 * Rollback contract. Reverting the build.gradle.kts pin and this
 * test's expected literal back to `"0"` returns release builds to the
 * pre-Stage-2B-D wire shape (no LP/PP opt-in, legacy mono padded
 * poll). The relay-side `RELAY_POLL_CHUNKED_FLUSH=1` flag can stay on
 * the VPS independently — without the LP/PP opt-in headers from the
 * client, the relay falls back to the mono padded poll for those
 * clients, so the server flag is a safe no-op for any non-opted-in
 * client. The decoupling is intentional.
 *
 * Test strategy: parse `apps/android/build.gradle.kts` as text, find
 * the `release { ... }` block under `buildTypes`, and grep for the
 * exact `buildConfigField("String", "LONGPOLL_V2_ENABLED", "\"1\"")`
 * declaration. Text parsing is the right tool here because the
 * BuildConfig value is a Gradle-generated `String` constant that is
 * only meaningful when the release variant is actually built, and
 * standing up a release-variant unit test on every change just to
 * read one constant is overkill.
 */
class LongPollV2ReleaseBuildConfigPinTest {

    /**
     * The exact line we expect inside the release block. Kept on one
     * line so an extra space or a re-flow in the .kts file trips this
     * test deliberately — the pin's value is in not drifting at all.
     */
    private val expectedPinLine: String =
        "buildConfigField(\"String\", \"LONGPOLL_V2_ENABLED\", \"\\\"1\\\"\")"

    /** The header literal we look for to locate the release block. */
    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_longpoll_v2_enabled_to_one() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedPinLine),
            "Release variant must pin `LONGPOLL_V2_ENABLED` to `\"1\"` for the " +
                "Stage 2B-D rollout invariant. Expected to find the literal\n" +
                "  $expectedPinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "Reverting this pin to `\"0\"` is the dedicated Stage 2B-D rollback " +
                "ceremony and must NOT happen as a side-effect of an unrelated edit.",
        )
    }

    /**
     * Sanity check that the pin line we look for in the test above is
     * NOT already present in the debug block — otherwise the M4 assert
     * would pass even if the release block were deleted, because
     * `extractReleaseBlock` would find the wrong block. The debug block
     * uses a runtime-computed `localOrEnv(...)` value, not a literal
     * `"1"`, so the same literal MUST NOT appear there.
     *
     * (Note: even after the Stage 2B-D rollout flipped the release pin
     * from `"0"` to `"1"`, this sanity test still applies — the debug
     * block's value comes from `localOrEnv("longPollV2Enabled",
     * "LONGPOLL_V2_ENABLED", "1")` which expands to
     * `buildConfigField("String", "LONGPOLL_V2_ENABLED",
     * "\"$longPollV2Enabled\"")`, NOT the literal release-pin shape.)
     */
    @Test
    fun debug_block_does_not_carry_the_literal_one_pin() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            !debugBlock.contains(expectedPinLine),
            "Debug variant must use the `localOrEnv(...)` runtime value for " +
                "`LONGPOLL_V2_ENABLED`, not the literal release pin. Finding the " +
                "literal pin in the debug block would make the release pin assert " +
                "pass on a wrong block lookup.",
        )
    }

    /**
     * Load `apps/android/build.gradle.kts` relative to the JVM unit-test
     * working directory. Gradle's `:apps:android:testDebugUnitTest` task
     * runs with the module directory as the cwd, so the relative path
     * is stable across local dev and CI.
     */
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

    /** Extract the contents of the first `release { ... }` block, balanced braces. */
    private fun extractReleaseBlock(source: String): String? =
        extractTopLevelBuildTypeBlock(source, releaseBlockHeader)

    /** Extract the contents of the first `debug { ... }` block, balanced braces. */
    private fun extractDebugBlock(source: String): String? =
        extractTopLevelBuildTypeBlock(source, "debug {")

    /**
     * Locate the first occurrence of `header` and return the substring
     * between its `{` and the matching closing `}`. Returns `null` if
     * the header is not found or the braces never balance.
     */
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
