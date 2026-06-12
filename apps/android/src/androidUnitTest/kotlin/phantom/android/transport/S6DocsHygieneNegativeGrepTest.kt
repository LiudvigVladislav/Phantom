// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 4 P2.1) — docs-hygiene
 * negative grep over the S6 controllable trigger surface.
 *
 * Round 3 switched the trigger gate from `BuildConfig.DEBUG` to the
 * dedicated `BuildConfig.S6_DEBUG_TRIGGER_ENABLED` String "1"/"0"
 * pin and switched the API 33+ receiver registration from
 * `RECEIVER_NOT_EXPORTED` to `RECEIVER_EXPORTED`. Round 3's commit
 * left stale KDoc and inline comments behind that still described
 * the round-2 model. A future refactor that read the stale comments
 * could plausibly revert the gate back to `BuildConfig.DEBUG` or
 * the export classification back to `NOT_EXPORTED` while the unit
 * tests (which assert the positive tokens) continued to pass — the
 * commit body would look like a "doc fixup" while silently
 * regressing the Tele2 LTE smoke S6 trigger.
 *
 * This test enforces that the two S6-bearing source files contain
 * NO load-bearing references to the stale model. References to the
 * stale tokens are forbidden in:
 *
 *   * `apps/android/src/androidMain/kotlin/phantom/android/dev/
 *     S6BreakerTriggerReceiver.kt`
 *   * The S6 surface inside
 *     `apps/android/src/androidMain/kotlin/phantom/android/di/
 *     AppContainer.kt` (the orchestrator construction site + the
 *     `triggerS6BreakerForDebug` method + the
 *     `S6BreakerTriggerReceiver` registration block).
 *
 * The full `AppContainer.kt` source contains many unrelated debug
 * surfaces (PR-M2f.1, RC-DIRECT-STABILITY1 arms, etc.) so this
 * test extracts the S6 surface explicitly before checking — a
 * `BuildConfig.DEBUG` reference outside the S6 block is legitimate
 * and not flagged here.
 */
class S6DocsHygieneNegativeGrepTest {

    private fun locate(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("apps/android/$relative"),
            File("../apps/android/$relative"),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate `$relative` from the unit-test working directory. " +
                    "Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    @Test
    fun receiver_source_does_not_reference_stale_round2_gate_model() {
        val source = locate("src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerReceiver.kt")
            .readText(Charsets.UTF_8)
        // RECEIVER_NOT_EXPORTED was the round-2 receiver flag. A
        // reference in this file (code OR doc) implies the round-3
        // export-flip was misremembered.
        assertTrue(
            !source.contains("RECEIVER_NOT_EXPORTED"),
            "S6BreakerTriggerReceiver.kt MUST NOT reference `RECEIVER_NOT_EXPORTED` " +
                "(round-2 model). The round-3 model uses `RECEIVER_EXPORTED` on API 33+; a " +
                "stale reference here will mislead a future refactor into reverting the " +
                "wire-up.",
        )
        // `BuildConfig.DEBUG` was the round-2 gate. The round-3
        // gate is `BuildConfig.S6_DEBUG_TRIGGER_ENABLED`. The
        // receiver source MUST NOT reference the round-2 gate
        // anywhere (its docstring and onReceive body alike are
        // round-3-only).
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(source),
            "S6BreakerTriggerReceiver.kt MUST NOT reference `BuildConfig.DEBUG` " +
                "(round-2 gate). The round-3 gate is " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED == \"1\"`.",
        )
        // The log line wording "debug build only" misrepresents
        // the round-3 model — a future beta variant with
        // `isDebuggable=false` MAY enable the trigger via the
        // dedicated flag. Replaced with "S6 trigger flag
        // enabled".
        assertTrue(
            !source.contains("debug build only"),
            "S6BreakerTriggerReceiver.kt MUST NOT contain the phrase `debug build only` — " +
                "the round-3 gate decouples the trigger from `isDebuggable`. Use " +
                "`S6 trigger flag enabled` (or equivalent) instead.",
        )
    }

    @Test
    fun app_container_S6_surface_does_not_reference_stale_round2_gate_model() {
        val text = locate("src/androidMain/kotlin/phantom/android/di/AppContainer.kt")
            .readText(Charsets.UTF_8)
        // Extract the S6 surface: from `restOrchestratorRef`
        // declaration through to the `Registered
        // S6BreakerTriggerReceiver` log call. The file is large
        // and contains unrelated debug surfaces that legitimately
        // reference `BuildConfig.DEBUG`; restricting the grep to
        // the S6 surface avoids false positives outside it.
        val refStart = text.indexOf("@Volatile private var restOrchestratorRef")
        assertTrue(refStart >= 0, "S6 surface anchor `restOrchestratorRef` not found in AppContainer.kt")
        val triggerMethodStart = text.indexOf("suspend fun triggerS6BreakerForDebug")
        assertTrue(triggerMethodStart >= 0, "S6 trigger method not found in AppContainer.kt")
        // Walk forward from the `{` opening the method body to the
        // matching `}` using balanced-brace counting — robust
        // against indentation drift.
        val methodOpenBrace = text.indexOf('{', startIndex = triggerMethodStart)
        var d1 = 1
        var j = methodOpenBrace + 1
        while (j < text.length && d1 > 0) {
            when (text[j]) {
                '{' -> d1++
                '}' -> d1--
            }
            j++
        }
        if (d1 != 0) fail("Unbalanced braces in triggerS6BreakerForDebug body")
        val s6SurfaceA = text.substring(refStart, j)
        val registrationStart = text.indexOf(
            "if (phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED == \"1\")",
        )
        assertTrue(registrationStart >= 0, "S6 receiver registration anchor not found")
        // Walk forward to the matching `}` for the registration block.
        val openBrace = text.indexOf('{', startIndex = registrationStart)
        var depth = 1
        var i = openBrace + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) fail("Unbalanced braces in S6 receiver registration block")
        val s6SurfaceB = text.substring(registrationStart, i)
        // C6 review-fix round 5 P2.tester — anchor the context
        // window on the structural element that DIRECTLY precedes
        // the registration: the `restOrchestratorRef = restOrchestrator`
        // assignment that runs immediately before the trigger flag
        // check. A future refactor that adds a long comment block
        // between the assignment and the `if` would push stale tokens
        // OUT of a fixed 400-char window; anchoring on the structural
        // boundary keeps the grep scope correct under any comment
        // expansion.
        val contextAnchorRegex = Regex("""restOrchestratorRef\s*=\s*restOrchestrator""")
        val contextAnchorMatch = contextAnchorRegex.find(text, startIndex = refStart)
        assertTrue(
            contextAnchorMatch != null && contextAnchorMatch.range.first < registrationStart,
            "S6 surface anchor `restOrchestratorRef = restOrchestrator` not found between " +
                "the field declaration and the receiver registration block.",
        )
        val contextStart = contextAnchorMatch!!.range.first
        val s6SurfaceContext = text.substring(contextStart, registrationStart)
        val combinedS6Surface = s6SurfaceA + "\n" + s6SurfaceContext + "\n" + s6SurfaceB

        assertTrue(
            !combinedS6Surface.contains("RECEIVER_NOT_EXPORTED"),
            "AppContainer S6 surface MUST NOT reference `RECEIVER_NOT_EXPORTED` (round-2 " +
                "model). Round 3 switched to `RECEIVER_EXPORTED` on API 33+.",
        )
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(combinedS6Surface),
            "AppContainer S6 surface MUST NOT reference `BuildConfig.DEBUG` — round 3 " +
                "decoupled the gate onto `BuildConfig.S6_DEBUG_TRIGGER_ENABLED`. Found " +
                "stale reference in the S6 surface block (the file has legitimate " +
                "`BuildConfig.DEBUG` uses elsewhere, but not in the S6 block).",
        )
        assertTrue(
            !combinedS6Surface.contains("debug build only"),
            "AppContainer S6 surface MUST NOT contain `debug build only` — round 3 " +
                "decoupled the trigger from `isDebuggable`. Use `S6 trigger flag enabled` " +
                "instead.",
        )
    }
}
