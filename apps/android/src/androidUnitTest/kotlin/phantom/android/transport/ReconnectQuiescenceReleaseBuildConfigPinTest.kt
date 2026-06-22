// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2d (2026-06-22) — release-pin contract test.
 *
 * Mirrors [Mode2StickyReleaseBuildConfigPinTest]. The release-pin literal
 * `"0"` is the SOLE production-side mechanism for keeping reconnect
 * quiescence actuation off, because `AppContainer` reads
 * `BuildConfig.RECONNECT_QUIESCENCE_ENABLED` directly WITHOUT a
 * `BuildConfig.DEBUG` conjunction. Promotion is a deliberate one-line flip
 * in a separate named rollout PR (alongside `MODE_2_FAST_PATH_ENABLED` and
 * `MODE_2_STICKY_ENABLED`) AFTER Tecno Tele2 LTE smoke PASS.
 *
 * Additionally validates that the debug block uses `localOrEnv(...)` (not a
 * literal pin) so operators can opt-in via `-PreconnectQuiesce=1`.
 */
class ReconnectQuiescenceReleaseBuildConfigPinTest {

    private val expectedReleasePinLine: String =
        "buildConfigField(\"String\", \"RECONNECT_QUIESCENCE_ENABLED\", \"\\\"0\\\"\")"

    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_reconnect_quiescence_enabled_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedReleasePinLine),
            "Release variant must pin `RECONNECT_QUIESCENCE_ENABLED` to `\"0\"` as the " +
                "load-bearing rollout knob. Expected to find the literal\n" +
                "  $expectedReleasePinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "AppContainer reads BuildConfig.RECONNECT_QUIESCENCE_ENABLED directly " +
                "without a BuildConfig.DEBUG conjunction, so this literal IS the " +
                "production-default mechanism. Flipping it to `\"1\"` is the dedicated " +
                "promotion ceremony in a separate named PR after Tecno Tele2 LTE smoke PASS.",
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
                "`RECONNECT_QUIESCENCE_ENABLED`, not the literal release pin.",
        )
    }

    @Test
    fun debug_block_declares_reconnect_quiescence_via_localOrEnv() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        val regex = Regex(
            pattern = "localOrEnv\\(\\s*\"reconnectQuiesce\"\\s*,\\s*\"RECONNECT_QUIESCENCE_ENABLED\"\\s*,\\s*\"0\"\\s*,?\\s*\\)",
        )
        assertTrue(
            regex.containsMatchIn(debugBlock),
            "Debug variant must declare `RECONNECT_QUIESCENCE_ENABLED` via " +
                "`localOrEnv(\"reconnectQuiesce\", \"RECONNECT_QUIESCENCE_ENABLED\", \"0\")` " +
                "so an operator can opt in via `-PreconnectQuiesce=1`.",
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
