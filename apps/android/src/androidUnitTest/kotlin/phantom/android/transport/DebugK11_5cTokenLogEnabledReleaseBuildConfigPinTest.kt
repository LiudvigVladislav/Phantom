// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B2-K11 §5C debug-only session-token observer — release-block pin
 * contract (2026-07-09).
 *
 * Pins the `release { ... }` block in `apps/android/build.gradle.kts` so
 * that `DEBUG_K11_5C_TOKEN_LOG_ENABLED` cannot drift away from the
 * literal `"0"` by accident. The release pin is the load-bearing
 * defence-in-depth alongside the outer `BuildConfig.DEBUG` gate at the
 * `AppContainer` wire-up: with this pin at `"0"`, the AppContainer's
 * double-gate short-circuits to `null` observer even if a rogue upstream
 * subverted the `BuildConfig.DEBUG` half — the `String` equality check
 * against `"1"` becomes constant-false at compile time.
 *
 * Mirrors the discipline of
 * [LongPollV2ReleaseBuildConfigPinTest],
 * [Mode2FastPathReleaseBuildConfigPinTest],
 * [Mode2StickyReleaseBuildConfigPinTest],
 * [ReconnectQuiescenceReleaseBuildConfigPinTest], and
 * [PollSkipLpAndPpReleaseBuildConfigPinTest] — one dedicated
 * release-pin test per debug-only diagnostic seam.
 *
 * Rollback contract: reverting this pin and the release-block literal to
 * anything other than `"0"` is a deliberate deviation from the 5C
 * diagnostic-only contract and must NOT happen as a side-effect of an
 * unrelated edit. The debug-block value uses `localOrEnv(...)` so a
 * developer can opt in per-build without touching the release literal.
 *
 * Test strategy: parse `apps/android/build.gradle.kts` as text, extract
 * the `release { ... }` block via balanced-brace scan, and assert the
 * exact literal `buildConfigField(...)` declaration is present. Text
 * parsing is the right tool because the field is a Gradle-generated
 * `String` constant meaningful only in the release variant.
 */
class DebugK11_5cTokenLogEnabledReleaseBuildConfigPinTest {

    /**
     * The exact line we expect inside the release block. Kept on one
     * line so an extra space or re-flow in the .kts file trips this test
     * deliberately — the pin's value is in not drifting at all.
     */
    private val expectedPinLine: String =
        "buildConfigField(\"String\", \"DEBUG_K11_5C_TOKEN_LOG_ENABLED\", \"\\\"0\\\"\")"

    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_debug_k11_5c_token_log_enabled_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedPinLine),
            "Release variant must pin `DEBUG_K11_5C_TOKEN_LOG_ENABLED` to `\"0\"` " +
                "so the AppContainer's double-gate short-circuits to a null " +
                "`debugSessionTokenObserver` on every release build regardless " +
                "of the outer `BuildConfig.DEBUG` gate. Expected to find the literal\n" +
                "  $expectedPinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "Reverting this pin to `\"1\"` would ship a release APK that dumps " +
                "raw bearer tokens to logcat on every successful token refresh — " +
                "a hard security incident by any reading. This test is the last " +
                "compile-time backstop against that regression.",
        )
    }

    /**
     * Sanity check: the same literal MUST NOT be present in the debug
     * block, because the debug block uses `localOrEnv(...)` for the
     * runtime-computed value. Finding the literal in the debug block
     * would make the release-pin assertion pass on a wrong block lookup.
     */
    @Test
    fun debug_block_does_not_carry_the_literal_zero_pin() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            !debugBlock.contains(expectedPinLine),
            "Debug variant must use the `localOrEnv(...)` runtime value for " +
                "`DEBUG_K11_5C_TOKEN_LOG_ENABLED`, not the literal release pin. " +
                "Finding the literal pin in the debug block would make the " +
                "release-pin assertion pass on a wrong block lookup.",
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
