// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 9 P1.evidence) — contract
 * test that pins the AppContainer-side wire-up for the S6
 * controllable breaker trigger.
 *
 * Round 9 dropped the dynamic BroadcastReceiver registration and
 * the DUMP sender-permission gate. The replacement entry point is
 * `S6BreakerTriggerActivity` declared in the manifest and launched
 * via `adb shell am start`. AppContainer's wire-up shrinks to:
 *
 *   1. Pass `s6DebugTriggerEnabled = BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`
 *      through to the orchestrator constructor.
 *   2. Assign `restOrchestratorRef = restOrchestrator` so
 *      `triggerS6BreakerForDebug()` can reach the orchestrator from
 *      the activity's onCreate dispatch.
 *   3. Provide `triggerS6BreakerForDebug()` itself with the
 *      `BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1"` short-circuit.
 *
 * The receiver-era registration code path is GONE — the wiring test
 * asserts both the positive surfaces (above) AND the negative
 * surface (no registerReceiver call for the S6 receiver).
 */
class AppContainerS6DebugTriggerWiringTest {

    private val appContainerSource: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AppContainer.kt from the unit-test working directory. " +
                    "Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private fun source(): String = appContainerSource.readText(Charsets.UTF_8)

    @Test
    fun s6DebugTriggerEnabled_is_passed_through_to_orchestrator_constructor() {
        val text = source()
        val constructionStart = text.indexOf(
            "phantom.core.transport.RestFallbackOrchestrator(",
        )
        assertNotNull(
            constructionStart.takeIf { it >= 0 },
            "AppContainer must contain at least one RestFallbackOrchestrator construction site.",
        )
        val openIdx = text.indexOf('(', startIndex = constructionStart)
        var depth = 1
        var i = openIdx + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) fail("Unbalanced parens in AppContainer RestFallbackOrchestrator construction.")
        val args = text.substring(openIdx + 1, i - 1)

        assertTrue(
            args.contains("s6DebugTriggerEnabled = "),
            "AppContainer's RestFallbackOrchestrator construction MUST pass " +
                "`s6DebugTriggerEnabled = <expr>` explicitly. Argument list:\n$args",
        )
        assertTrue(
            text.contains("BuildConfig.S6_DEBUG_TRIGGER_ENABLED"),
            "AppContainer must reference `BuildConfig.S6_DEBUG_TRIGGER_ENABLED` so the " +
                "trigger gate is independent of `BuildConfig.DEBUG`.",
        )
    }

    @Test
    fun rest_orchestrator_ref_is_assigned_for_activity_routing() {
        val text = source()
        assertTrue(
            text.contains("restOrchestratorRef = restOrchestrator"),
            "AppContainer must assign `restOrchestratorRef = restOrchestrator` after the " +
                "orchestrator is constructed so `triggerS6BreakerForDebug()` can reach it " +
                "from S6BreakerTriggerActivity.onCreate.",
        )
    }

    @Test
    fun trigger_method_short_circuits_on_dedicated_flag_when_not_one() {
        val text = source()
        val triggerStart = text.indexOf("suspend fun triggerS6BreakerForDebug(): Boolean {")
        assertNotNull(
            triggerStart.takeIf { it >= 0 },
            "AppContainer must declare `suspend fun triggerS6BreakerForDebug(): Boolean`.",
        )
        val openIdx = text.indexOf('{', startIndex = triggerStart)
        var depth = 1
        var i = openIdx + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) fail("Unbalanced braces in triggerS6BreakerForDebug body.")
        val body = text.substring(openIdx + 1, i - 1)
        val gateRegex = Regex(
            """if\s*\(\s*phantom\.android\.BuildConfig\.S6_DEBUG_TRIGGER_ENABLED\s*!=\s*"1"\s*\)"""
        )
        assertTrue(
            gateRegex.containsMatchIn(body),
            "triggerS6BreakerForDebug() body must contain the early-exit gate " +
                "`if (phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED != \"1\") { return false }`. " +
                "Found body:\n$body",
        )
        assertTrue(
            body.contains("return false"),
            "triggerS6BreakerForDebug() body must `return false` on the gate-fail branch.",
        )
    }

    @Test
    fun receiver_era_wiring_artefacts_are_gone() {
        val text = source()
        assertTrue(
            !text.contains("S6BreakerTriggerReceiver"),
            "AppContainer MUST NOT reference `S6BreakerTriggerReceiver`. Round 9 dropped " +
                "the BroadcastReceiver entry point; the replacement is " +
                "`S6BreakerTriggerActivity` declared in the manifest. A leftover reference " +
                "would compile-fail (the class is gone) but the assertion guards against a " +
                "future re-introduction without the manifest declaration.",
        )
        // The receiver-era registration path used `registerReceiver`
        // for the S6 receiver. Other receivers in the project also
        // call registerReceiver, so scope the check to the S6
        // surface: assert NO `registerReceiver` call mentions
        // `S6_BREAKER_TRIGGER` or `S6BreakerTriggerReceiver` in the
        // same expression.
        val s6RegisterRegex = Regex(
            """registerReceiver\([^)]*?(S6_BREAKER_TRIGGER|S6BreakerTrigger)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            !s6RegisterRegex.containsMatchIn(text),
            "AppContainer MUST NOT call `registerReceiver(...)` for any S6-related class " +
                "or action. The round-9 entry point is the manifest-declared activity.",
        )
    }
}
