// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §5.1 + §4.6 release-pin
 * contract test (2026-06-30).
 *
 * Pins the release variant of `apps/android/build.gradle.kts` so that
 * `DEBUG_FORCE_MODE_2_DETECTION` cannot drift away from `"0"` in the
 * release block by accident. The release-pin literal is the load-bearing
 * production-side mechanism for keeping the L1 synthetic-trigger surface
 * inert in release builds because `AppContainer` reads
 * `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION` directly WITHOUT a
 * `BuildConfig.DEBUG` conjunction (consistent with the
 * `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` /
 * `S6_DEBUG_TRIGGER_ENABLED` patterns). Adding `BuildConfig.DEBUG`
 * would make the release pin permanently ineffective for any future
 * variant that wanted to flip it.
 *
 * Three checks:
 *
 *   1. Release block carries the exact literal pin
 *      `buildConfigField("String", "DEBUG_FORCE_MODE_2_DETECTION", "\"0\"")`.
 *   2. Debug block does NOT carry the same literal pin (a mirror-image
 *      regression would silently make the release pin assert pass on a
 *      wrong-block lookup).
 *   3. Debug block declares `DEBUG_FORCE_MODE_2_DETECTION` via
 *      `localOrEnv("debugForceMode2", "DEBUG_FORCE_MODE_2_DETECTION", "0")`
 *      so an operator can opt in to the synthetic-trigger surface on a
 *      debug build via `-PdebugForceMode2=1`. The default `"0"` keeps
 *      the canary opt-in (NOT opt-out).
 *
 * Test strategy mirrors [Mode2FastPathReleaseBuildConfigPinTest] /
 * [LongPollV2ReleaseBuildConfigPinTest] — parse `build.gradle.kts` as
 * text, find the `release { ... }` / `debug { ... }` blocks under
 * `buildTypes`, and grep for the exact declaration. Text parsing is the
 * right tool because the BuildConfig value is a Gradle-generated
 * `String` constant only meaningful when the release variant is built;
 * standing up a release-variant unit test on every change just to read
 * one constant is overkill.
 */
class Mode2DebugForceReleaseBuildConfigPinTest {

    /**
     * The release-pin literal we look for. Mirrors the existing
     * `expectedReleasePinLine` pattern from [Mode2FastPathReleaseBuildConfigPinTest]
     * — whitespace-sensitive so an extra space or a re-flow in the
     * `.kts` file trips this test deliberately. The pin's value is in
     * not drifting at all.
     */
    private val expectedReleasePinLine: String =
        "buildConfigField(\"String\", \"DEBUG_FORCE_MODE_2_DETECTION\", \"\\\"0\\\"\")"

    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_debug_force_mode_2_detection_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedReleasePinLine),
            "Release variant must pin `DEBUG_FORCE_MODE_2_DETECTION` to `\"0\"` as the " +
                "load-bearing rollout knob. Expected to find the literal\n" +
                "  $expectedReleasePinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "AppContainer reads `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION` directly " +
                "without a `BuildConfig.DEBUG` conjunction (consistent with the existing " +
                "`MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` patterns), so this " +
                "literal IS the production-default mechanism. Flipping it to `\"1\"` is " +
                "a deliberate operator action via `-PdebugForceMode2=1` on a debug build " +
                "AND MUST NOT happen as a side-effect of an unrelated edit. The L1 " +
                "synthetic-trigger surface is inert in release as long as this pin holds.",
        )
    }

    @Test
    fun debug_block_does_not_carry_the_literal_zero_pin() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            !debugBlock.contains(expectedReleasePinLine),
            "Debug variant must use the `localOrEnv(...)` runtime value for " +
                "`DEBUG_FORCE_MODE_2_DETECTION`, not the literal release pin. Finding " +
                "the literal pin in the debug block would make the release-pin assert " +
                "pass on a wrong block lookup.",
        )
    }

    @Test
    fun debug_block_declares_debug_force_mode_2_via_localOrEnv() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        val regex = Regex(
            pattern = "localOrEnv\\(\\s*\"debugForceMode2\"\\s*,\\s*\"DEBUG_FORCE_MODE_2_DETECTION\"\\s*,\\s*\"0\"\\s*,?\\s*\\)",
        )
        assertTrue(
            regex.containsMatchIn(debugBlock),
            "Debug variant must declare `DEBUG_FORCE_MODE_2_DETECTION` via " +
                "`localOrEnv(\"debugForceMode2\", \"DEBUG_FORCE_MODE_2_DETECTION\", \"0\")` " +
                "so an operator can opt in to the L1 synthetic-trigger surface on a " +
                "debug build via `-PdebugForceMode2=1`. The default `\"0\"` keeps the " +
                "canary opt-in (NOT opt-out).",
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

    private fun extractReleaseBlock(source: String): String? =
        extractTopLevelBuildTypeBlock(source, releaseBlockHeader)

    private fun extractDebugBlock(source: String): String? =
        extractTopLevelBuildTypeBlock(source, "debug {")

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
