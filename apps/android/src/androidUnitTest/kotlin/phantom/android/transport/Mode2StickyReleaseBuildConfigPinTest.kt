// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R3.6 Sticky-per-route Fast REST degradation (2026-06-20) — release-pin contract test.
 *
 * Mirrors [Mode2FastPathReleaseBuildConfigPinTest] for the new
 * `MODE_2_STICKY_ENABLED` flag. The release-pin literal `"0"` is the SOLE
 * production-side mechanism for keeping sticky actuation off, because
 * `AppContainer` reads `BuildConfig.MODE_2_STICKY_ENABLED` directly WITHOUT
 * a `BuildConfig.DEBUG` conjunction. Promotion is a deliberate one-line flip
 * in a separate named PR after smoke PASS.
 *
 * Additionally validates that the debug block uses `localOrEnv(...)` (not a
 * literal pin) so operators can opt-in via `-PmodeSticky=1`.
 */
class Mode2StickyReleaseBuildConfigPinTest {

    private val expectedReleasePinLine: String =
        "buildConfigField(\"String\", \"MODE_2_STICKY_ENABLED\", \"\\\"0\\\"\")"

    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_mode_2_sticky_enabled_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedReleasePinLine),
            "Release variant must pin `MODE_2_STICKY_ENABLED` to `\"0\"` as the " +
                "load-bearing rollout knob. Expected to find the literal\n" +
                "  $expectedReleasePinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "AppContainer reads BuildConfig.MODE_2_STICKY_ENABLED directly " +
                "without a BuildConfig.DEBUG conjunction, so this literal IS the " +
                "production-default mechanism. Flipping it to `\"1\"` is the dedicated " +
                "promotion ceremony in a separate named PR after smoke PASS.",
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
                "`MODE_2_STICKY_ENABLED`, not the literal release pin.",
        )
    }

    @Test
    fun debug_block_declares_mode_2_sticky_via_localOrEnv() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        val regex = Regex(
            pattern = "localOrEnv\\(\\s*\"modeSticky\"\\s*,\\s*\"MODE_2_STICKY_ENABLED\"\\s*,\\s*\"0\"\\s*,?\\s*\\)",
        )
        assertTrue(
            regex.containsMatchIn(debugBlock),
            "Debug variant must declare `MODE_2_STICKY_ENABLED` via " +
                "`localOrEnv(\"modeSticky\", \"MODE_2_STICKY_ENABLED\", \"0\")` " +
                "so an operator can opt in via `-PmodeSticky=1`.",
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
