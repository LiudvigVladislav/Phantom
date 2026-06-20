// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 3.6 Fast REST degradation (2026-06-18) — release-pin contract test.
 *
 * Pins the release variant of `apps/android/build.gradle.kts` so that
 * `MODE_2_FAST_PATH_ENABLED` cannot drift away from `"0"` in the release
 * block by accident. The release-pin literal is the SOLE production-side
 * mechanism for keeping Mode-2 fast-path actuation off, because
 * `AppContainer` reads `BuildConfig.MODE_2_FAST_PATH_ENABLED` directly
 * WITHOUT a `BuildConfig.DEBUG` conjunction. Promotion of the production
 * default is a deliberate one-line flip in this release block to `"1"`,
 * shipped in a separate named PR after the Tele2 + Wi-Fi smoke PASS.
 *
 * If a future edit accidentally drops the release literal (e.g. someone
 * deletes the line, or someone over-writes it via `localOrEnv` because
 * the build.gradle.kts gets refactored), this test fails loudly. Without
 * the literal, the rollout contract collapses — a release build would
 * inherit the debug-block default (`"0"`), so the promotion mechanism
 * would still work today, but the next person assuming "release pin is
 * the truth" would be reading non-existent code.
 *
 * Debug-block check. The debug block MUST use the `localOrEnv(...)`
 * runtime value, NOT a literal pin — otherwise an operator cannot opt
 * in via `-PfastRestMode2=1`. The companion test guards against the
 * mirror-image regression where someone moves the literal pin into the
 * debug block by accident.
 *
 * Test strategy mirrors [LongPollV2ReleaseBuildConfigPinTest] — parse
 * `apps/android/build.gradle.kts` as text, find the `release { ... }`
 * block under `buildTypes`, and grep for the exact
 * `buildConfigField("String", "MODE_2_FAST_PATH_ENABLED", "\"0\"")`
 * declaration. Text parsing is the right tool here because the
 * BuildConfig value is a Gradle-generated `String` constant that is
 * only meaningful when the release variant is actually built, and
 * standing up a release-variant unit test on every change just to
 * read one constant is overkill.
 */
class Mode2FastPathReleaseBuildConfigPinTest {

    /**
     * The exact line we expect inside the release block. Kept on one
     * line so an extra space or a re-flow in the .kts file trips this
     * test deliberately — the pin's value is in not drifting at all.
     */
    private val expectedReleasePinLine: String =
        "buildConfigField(\"String\", \"MODE_2_FAST_PATH_ENABLED\", \"\\\"0\\\"\")"

    private val releaseBlockHeader: String = "release {"

    @Test
    fun release_block_pins_mode_2_fast_path_enabled_to_zero() {
        val source = loadBuildGradle()
        val releaseBlock = extractReleaseBlock(source)
            ?: fail("Could not locate `release { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            releaseBlock.contains(expectedReleasePinLine),
            "Release variant must pin `MODE_2_FAST_PATH_ENABLED` to `\"0\"` as the " +
                "load-bearing rollout knob. Expected to find the literal\n" +
                "  $expectedReleasePinLine\n" +
                "inside the `release { ... }` block of `apps/android/build.gradle.kts`. " +
                "AppContainer reads BuildConfig.MODE_2_FAST_PATH_ENABLED directly " +
                "without a BuildConfig.DEBUG conjunction, so this literal IS the " +
                "production-default mechanism. Flipping it to `\"1\"` is the dedicated " +
                "promotion ceremony in a separate named PR after smoke PASS and must " +
                "NOT happen as a side-effect of an unrelated edit.",
        )
    }

    /**
     * Sanity check that the literal `"0"` pin we look for in the test
     * above is NOT already present in the debug block — otherwise the
     * release-pin assert above would pass even if the release block were
     * deleted, because `extractReleaseBlock` would find the wrong block.
     * The debug block uses a runtime-computed `localOrEnv(...)` value so
     * the same literal MUST NOT appear there.
     */
    @Test
    fun debug_block_does_not_carry_the_literal_zero_pin() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        assertTrue(
            !debugBlock.contains(expectedReleasePinLine),
            "Debug variant must use the `localOrEnv(...)` runtime value for " +
                "`MODE_2_FAST_PATH_ENABLED`, not the literal release pin. Finding " +
                "the literal pin in the debug block would make the release pin " +
                "assert pass on a wrong block lookup.",
        )
    }

    /**
     * Forward-looking guard: the debug block MUST also declare the
     * `MODE_2_FAST_PATH_ENABLED` field via `localOrEnv(...)` so an
     * operator can opt in via `-PfastRestMode2=1`. The opt-in
     * affordance is part of the promotion mechanism — without it, a
     * canary smoke could not enable actuation on a debug build.
     */
    @Test
    fun debug_block_declares_mode_2_fast_path_via_localOrEnv() {
        val source = loadBuildGradle()
        val debugBlock = extractDebugBlock(source)
            ?: fail("Could not locate `debug { ... }` block inside `apps/android/build.gradle.kts`.")
        val regex = Regex(
            pattern = "localOrEnv\\(\\s*\"fastRestMode2\"\\s*,\\s*\"MODE_2_FAST_PATH_ENABLED\"\\s*,\\s*\"0\"\\s*,?\\s*\\)",
        )
        assertTrue(
            regex.containsMatchIn(debugBlock),
            "Debug variant must declare `MODE_2_FAST_PATH_ENABLED` via " +
                "`localOrEnv(\"fastRestMode2\", \"MODE_2_FAST_PATH_ENABLED\", \"0\")` " +
                "so an operator can opt in to the fast-path actuation on a debug " +
                "build via `-PfastRestMode2=1`. The default `\"0\"` keeps the canary " +
                "opt-in (NOT opt-out).",
        )
    }

    /**
     * Load `apps/android/build.gradle.kts` relative to the JVM unit-test
     * working directory. Gradle's `:apps:android:testDebugUnitTest` task
     * runs with the module directory as the cwd.
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
