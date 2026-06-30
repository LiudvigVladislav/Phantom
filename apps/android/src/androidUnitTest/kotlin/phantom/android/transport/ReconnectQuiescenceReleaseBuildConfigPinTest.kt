// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §13.1 / §13.4 release-pin
 * contract test (2026-06-30).
 *
 * Pins the release variant of `apps/android/build.gradle.kts` so that
 * `RECONNECT_QUIESCENCE_ENABLED` cannot drift away from `"0"` in the
 * release block by accident. The gate-only carve-out brings the
 * `phantom.core.transport.WsReconnectGate` type-and-interface surface to
 * master AHEAD of the MC implementation PR that will land the gate's
 * state-transition logic on `RestStateMachine`. Until the MC PR's
 * wiring lands there is no production reader of this flag at all;
 * pinning the release literal to `"0"` is the load-bearing rollout
 * knob for that future wiring.
 *
 * The wire-up will mirror the `MODE_2_FAST_PATH_ENABLED` /
 * `MODE_2_STICKY_ENABLED` / `DEBUG_FORCE_MODE_2_DETECTION` patterns:
 * `AppContainer` will read `BuildConfig.RECONNECT_QUIESCENCE_ENABLED`
 * directly WITHOUT a `BuildConfig.DEBUG` conjunction, so the literal
 * here IS the production-default mechanism. Adding `BuildConfig.DEBUG`
 * would make the release pin permanently ineffective for a future flip
 * to `"1"`.
 *
 * Three checks:
 *
 *   1. Release block carries the exact literal pin
 *      `buildConfigField("String", "RECONNECT_QUIESCENCE_ENABLED", "\"0\"")`.
 *   2. Debug block does NOT carry the same literal pin (a mirror-image
 *      regression would silently make the release-pin assert pass on a
 *      wrong-block lookup).
 *   3. Debug block declares `RECONNECT_QUIESCENCE_ENABLED` via
 *      `localOrEnv("reconnectQuiesce", "RECONNECT_QUIESCENCE_ENABLED", "0")`
 *      so an operator can opt in via `-PreconnectQuiesce=1` once the MC
 *      PR lands the orchestrator wiring. The default `"0"` keeps the
 *      canary opt-in (NOT opt-out).
 *
 * Test strategy mirrors [Mode2DebugForceReleaseBuildConfigPinTest] /
 * [Mode2FastPathReleaseBuildConfigPinTest] / [LongPollV2ReleaseBuildConfigPinTest]
 * — parse `build.gradle.kts` as text, find the `release { ... }` /
 * `debug { ... }` blocks under `buildTypes`, and grep for the exact
 * declaration. Text parsing is the right tool because the BuildConfig
 * value is a Gradle-generated `String` constant only meaningful when the
 * release variant is built; standing up a release-variant unit test on
 * every change just to read one constant is overkill.
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
                "The future MC implementation PR will wire `AppContainer` to read " +
                "`BuildConfig.RECONNECT_QUIESCENCE_ENABLED` directly without a " +
                "`BuildConfig.DEBUG` conjunction (consistent with the existing " +
                "`MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` / " +
                "`DEBUG_FORCE_MODE_2_DETECTION` patterns), so this literal IS the " +
                "production-default mechanism. Flipping it to `\"1\"` is a deliberate " +
                "operator action via `-PreconnectQuiesce=1` on a debug build AND a " +
                "deliberate one-line literal flip on this release pin for a " +
                "production rollout — both MUST NOT happen as a side-effect of an " +
                "unrelated edit. The gate component shipped by this carve-out is inert " +
                "in release as long as this pin holds, regardless of whether downstream " +
                "wiring has landed.",
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
                "`RECONNECT_QUIESCENCE_ENABLED`, not the literal release pin. Finding " +
                "the literal pin in the debug block would make the release-pin assert " +
                "pass on a wrong block lookup.",
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
                "so an operator can opt in via `-PreconnectQuiesce=1` once the MC PR's " +
                "orchestrator wiring lands. The default `\"0\"` keeps the canary opt-in " +
                "(NOT opt-out).",
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
