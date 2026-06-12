// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 2 P1.1; hardened round 3) —
 * contract test that pins the Android-side wire-up for the S6
 * controllable breaker trigger.
 *
 * Round 2 wired the helper through `BuildConfig.DEBUG`. Round 3
 * decouples the trigger gate from the DEBUG flag onto a dedicated
 * `BuildConfig.S6_DEBUG_TRIGGER_ENABLED` String "1"/"0" pin so a
 * future beta variant (which may run with `isDebuggable=false`) can
 * still opt into the trigger by flipping the gradle property. The
 * Tele2 runbook ("debug or beta APK") is now honoured regardless of
 * how the future beta variant is configured.
 *
 * Required surfaces:
 *
 *   1. The orchestrator construction passes
 *      `s6DebugTriggerEnabled = ...S6_DEBUG_TRIGGER_ENABLED == "1"`.
 *   2. The `restOrchestratorRef` field is assigned to the freshly-
 *      constructed orchestrator.
 *   3. The dynamic `BroadcastReceiver` registration block is present
 *      and gated on `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`.
 *   4. `triggerS6BreakerForDebug()` body short-circuits on the same
 *      `S6_DEBUG_TRIGGER_ENABLED != "1"` gate.
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
        // Locate the SOLE RestFallbackOrchestrator(...) construction
        // — the cursor-repo wiring test already pins that exactly
        // one exists. Extract its balanced-paren argument list.
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
        // The expression must derive from the dedicated
        // S6_DEBUG_TRIGGER_ENABLED BuildConfig pin (NOT
        // BuildConfig.DEBUG — that round-2 gate is decommissioned).
        assertTrue(
            text.contains("BuildConfig.S6_DEBUG_TRIGGER_ENABLED"),
            "AppContainer must reference `BuildConfig.S6_DEBUG_TRIGGER_ENABLED` so the " +
                "trigger gate is independent of `BuildConfig.DEBUG`. A future beta variant " +
                "with `isDebuggable=false` would otherwise lose the trigger surface despite " +
                "the runbook allowing `debug OR beta` APKs.",
        )
    }

    @Test
    fun rest_orchestrator_ref_is_assigned_for_debug_routing() {
        val text = source()
        assertTrue(
            text.contains("restOrchestratorRef = restOrchestrator"),
            "AppContainer must assign `restOrchestratorRef = restOrchestrator` after the " +
                "orchestrator is constructed so `triggerS6BreakerForDebug()` can reach it.",
        )
    }

    @Test
    fun s6_broadcast_receiver_registration_is_present_and_gated_on_dedicated_flag() {
        val text = source()
        assertTrue(
            text.contains("S6BreakerTriggerReceiver"),
            "AppContainer must reference `S6BreakerTriggerReceiver` so the ADB-broadcast " +
                "intent dispatches into `triggerS6BreakerForDebug()`. A missing reference " +
                "means the Tele2 LTE smoke S6 trigger is unreachable on Tecno.",
        )
        assertTrue(
            text.contains("registerReceiver("),
            "AppContainer must call `registerReceiver(...)` dynamically. The Tele2 smoke " +
                "S6 scenario relies on the `phantom.android.dev.S6_BREAKER_TRIGGER` " +
                "broadcast being deliverable from `adb shell am broadcast`.",
        )
        val gateRegex = Regex(
            """if\s*\(\s*phantom\.android\.BuildConfig\.S6_DEBUG_TRIGGER_ENABLED\s*==\s*"1"\s*\)"""
        )
        assertTrue(
            gateRegex.containsMatchIn(text),
            "AppContainer must gate the `S6BreakerTriggerReceiver` registration on " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED == \"1\"`. Found no such guard.",
        )
        // The registration must also use RECEIVER_EXPORTED on API
        // 33+ so `adb shell am broadcast` (system shell user, NOT
        // the registering app) can deliver the intent. The
        // NOT_EXPORTED round-2 wiring silently dropped the
        // broadcast on API 33+ devices like the Tecno.
        assertTrue(
            text.contains("RECEIVER_EXPORTED"),
            "AppContainer must register the receiver with `RECEIVER_EXPORTED` on API 33+ so " +
                "`adb shell am broadcast` can deliver the intent. `RECEIVER_NOT_EXPORTED` " +
                "(round 2) was load-bearing for the production safety story but silently " +
                "dropped debug broadcasts dispatched from outside the app.",
        )
        // Round-5 P1.security/tester — the registration must also
        // pass a signature-level sender permission so a co-installed
        // third-party app on a debug/beta device cannot broadcast
        // the trigger. The PERMISSION constant on
        // S6BreakerTriggerReceiver is the documented source; the
        // AppContainer wire-up must reference it.
        assertTrue(
            text.contains("S6BreakerTriggerReceiver.PERMISSION"),
            "AppContainer must pass `S6BreakerTriggerReceiver.PERMISSION` as the " +
                "broadcast-permission argument to `registerReceiver`. Without it, any " +
                "app installed on the same device could broadcast the trigger and " +
                "open the breaker; the signature-level permission scopes the gate to " +
                "the app's own signing certificate (and the system shell on a debug " +
                "build).",
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
}
