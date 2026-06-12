// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 2 P1.1) — contract test
 * that pins the Android-side wire-up for the S6 controllable breaker
 * trigger.
 *
 * The Tele2 LTE smoke runbook (`docs/tracks/trek2-stage2b-b-tele2-
 * smoke.md`) accepts the helper iff natural Mode-2 does not reproduce
 * inside the 30-minute Tecno+Tele2 LTE time-box. Round 1's first
 * landing claimed in a code comment that the helper was already wired
 * through a debug surface — that was false. Round 2 makes it real,
 * and this test asserts that the wiring actually exists in
 * `AppContainer.kt` so a future refactor cannot quietly unwire it.
 *
 * Three required surfaces:
 *
 *   1. The orchestrator construction passes
 *      `s6DebugTriggerEnabled = phantom.android.BuildConfig.DEBUG`
 *      (or an equivalent reference) so release builds get `false`.
 *   2. The `restOrchestratorRef` field is assigned to the freshly-
 *      constructed orchestrator so the AppContainer-level
 *      `triggerS6BreakerForDebug()` can reach it.
 *   3. The dynamic `BroadcastReceiver` registration block is present
 *      and gated on `phantom.android.BuildConfig.DEBUG` so a release
 *      APK never registers the receiver.
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
                "`s6DebugTriggerEnabled = <expr>` explicitly. The constructor default is " +
                "`false`; relying on the default in production is acceptable BUT " +
                "AppContainer is the documented wire-up — a missing assignment is a " +
                "signal that the round-2 wire-up was reverted. Argument list:\n$args",
        )
        // The expression must reference BuildConfig.DEBUG (directly or
        // through a local variable named `s6DebugEnabled` that we
        // already verify earlier in the AppContainer source).
        assertTrue(
            text.contains("phantom.android.BuildConfig.DEBUG") ||
                text.contains("BuildConfig.DEBUG"),
            "AppContainer must reference `BuildConfig.DEBUG` so the orchestrator-side gate " +
                "is fed by the BuildConfig pin. A literal `true` here would leak the trigger " +
                "into release builds and is a CI gate violation.",
        )
    }

    @Test
    fun rest_orchestrator_ref_is_assigned_for_debug_routing() {
        val text = source()
        assertTrue(
            text.contains("restOrchestratorRef = restOrchestrator"),
            "AppContainer must assign `restOrchestratorRef = restOrchestrator` after the " +
                "orchestrator is constructed so `triggerS6BreakerForDebug()` can reach it. " +
                "Without this assignment the debug surface is unreachable even when the " +
                "BuildConfig gate would allow it.",
        )
    }

    @Test
    fun s6_broadcast_receiver_registration_is_present_and_gated_on_buildconfig_debug() {
        val text = source()
        // The receiver class must be referenced, the action constant
        // referenced, AND the registration must live inside an
        // `if (...BuildConfig.DEBUG)` block (or syntactic equivalent).
        assertTrue(
            text.contains("S6BreakerTriggerReceiver"),
            "AppContainer must reference `S6BreakerTriggerReceiver` so the ADB-broadcast " +
                "intent dispatches into `triggerS6BreakerForDebug()`. A missing reference " +
                "means the Tele2 LTE smoke S6 trigger is unreachable on Tecno.",
        )
        assertTrue(
            text.contains("registerReceiver("),
            "AppContainer must call `registerReceiver(...)` dynamically so the receiver lives " +
                "for the process lifetime without expanding the production manifest. The Tele2 " +
                "smoke S6 scenario relies on the `phantom.android.dev.S6_BREAKER_TRIGGER` " +
                "broadcast being deliverable from `adb shell am broadcast`.",
        )
        // The registration block must be guarded by BuildConfig.DEBUG
        // — even though the receiver itself also gates, an
        // unconditional registration in release would expand the
        // attack surface (release APKs would respond to the broadcast
        // by entering the receiver's onReceive, which then refuses
        // — but the refusal log itself is a tell). Defence in depth:
        // never register the receiver in release.
        val gateRegex = Regex("""if\s*\(\s*phantom\.android\.BuildConfig\.DEBUG\s*\)""")
        assertTrue(
            gateRegex.containsMatchIn(text),
            "AppContainer must gate the `S6BreakerTriggerReceiver` registration on " +
                "`phantom.android.BuildConfig.DEBUG`. Found no such guard.",
        )
    }

    @Test
    fun trigger_method_returns_false_in_release_via_buildconfig_check() {
        val text = source()
        // `triggerS6BreakerForDebug()` must short-circuit on
        // `!BuildConfig.DEBUG`. Pin the method body's exit-early
        // structure so a refactor cannot silently drop the gate.
        val triggerStart = text.indexOf("suspend fun triggerS6BreakerForDebug(): Boolean {")
        assertNotNull(
            triggerStart.takeIf { it >= 0 },
            "AppContainer must declare `suspend fun triggerS6BreakerForDebug(): Boolean`. " +
                "A missing declaration removes the AppContainer-level debug surface that the " +
                "S6 trigger receiver routes to.",
        )
        // Walk forward to the matching `}` to extract the method body.
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
        assertTrue(
            Regex("""if\s*\(\s*!\s*phantom\.android\.BuildConfig\.DEBUG\s*\)""").containsMatchIn(body),
            "triggerS6BreakerForDebug() body must contain the early-exit gate " +
                "`if (!phantom.android.BuildConfig.DEBUG) { ... return false }`. " +
                "Found body:\n$body",
        )
        assertTrue(
            body.contains("return false"),
            "triggerS6BreakerForDebug() body must `return false` on the gate-fail branch.",
        )
    }
}
