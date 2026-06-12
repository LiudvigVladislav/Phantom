// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 3 P1.2) — contract test for
 * [phantom.android.dev.S6BreakerTriggerReceiver] that pins:
 *
 *   * The static `ACTION` constant exactly matches the Tele2 LTE
 *     smoke runbook recipe (`phantom.android.dev.S6_BREAKER_TRIGGER`).
 *     A drift in the action name silently breaks the
 *     `adb shell am broadcast -a <action>` recipe — Tecno operator
 *     fires the broadcast, the framework drops it because no
 *     receiver is registered for the new action, and the smoke
 *     reports "natural Mode-2 did not reproduce" with no trigger
 *     evidence.
 *
 *   * `onReceive` short-circuits on
 *     `BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1"` (the round-3
 *     dedicated gate, decoupled from `BuildConfig.DEBUG`).
 *
 *   * `onReceive` checks `intent.action == ACTION` before
 *     dispatching, so an arbitrary intent dispatched to the
 *     dynamically-registered receiver (e.g. a race where another
 *     intent filter matches) is ignored.
 *
 *   * `onReceive` routes through `container.triggerS6BreakerForDebug()`
 *     on `container.appScope` so the dispatch lives on the same
 *     scope as the orchestrator's other suspending work and
 *     respects the AppContainer lifecycle.
 *
 * The test parses the receiver source text — Robolectric is NOT
 * available in this project's `androidUnitTest` source set, so the
 * receiver's runtime behaviour against a real `Intent` is verified
 * manually per the Tele2 runbook (the operator fires the recipe and
 * observes the logcat output). This text-level test is the CI gate
 * that catches silent-rename regressions.
 */
class S6BreakerTriggerReceiverContractTest {

    private val receiverSource: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerReceiver.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerReceiver.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerReceiver.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate S6BreakerTriggerReceiver.kt. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private fun source(): String = receiverSource.readText(Charsets.UTF_8)

    @Test
    fun permission_constant_matches_manifest_signature_scoped_declaration() {
        // C6 review-fix round 5 P1.security/tester — receiver
        // declares a signature-level sender permission so a co-
        // installed third-party app on a debug/beta device cannot
        // broadcast the trigger. The constant must match the
        // permission declared in AndroidManifest.xml exactly; a
        // drift breaks the registerReceiver call site and either
        // crashes at registration OR silently registers without
        // a permission gate (the latter is the load-bearing
        // regression this test prevents).
        val text = source()
        val permissionRegex = Regex(
            """const\s+val\s+PERMISSION\s*:\s*String\s*=\s*"([^"]+)""""
        )
        val match = permissionRegex.find(text)
        assertNotNull(
            match,
            "S6BreakerTriggerReceiver must declare `const val PERMISSION: String = \"...\"` " +
                "for the signature-level sender permission.",
        )
        assertEquals(
            "phantom.android.dev.permission.TRIGGER_S6",
            match.groupValues[1],
            "PERMISSION must equal `phantom.android.dev.permission.TRIGGER_S6` exactly. " +
                "Any other value desynchronises from the manifest declaration and either " +
                "crashes registerReceiver OR drops the sender gate silently.",
        )
    }

    @Test
    fun action_constant_matches_runbook_recipe_exactly() {
        val text = source()
        val actionRegex = Regex(
            """const\s+val\s+ACTION\s*:\s*String\s*=\s*"([^"]+)""""
        )
        val match = actionRegex.find(text)
        assertNotNull(
            match,
            "S6BreakerTriggerReceiver must declare `const val ACTION: String = \"...\"` " +
                "as the Tele2 runbook recipe target.",
        )
        assertEquals(
            "phantom.android.dev.S6_BREAKER_TRIGGER",
            match.groupValues[1],
            "ACTION must equal `phantom.android.dev.S6_BREAKER_TRIGGER` exactly. The Tele2 " +
                "LTE smoke runbook's recipe `adb shell am broadcast -a " +
                "phantom.android.dev.S6_BREAKER_TRIGGER` would silently break under any " +
                "other value because no receiver would be registered for the new action.",
        )
    }

    @Test
    fun on_receive_gate_short_circuits_on_dedicated_flag_when_not_one() {
        val text = source()
        // `BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1"` must be the
        // first gate in the method body so the refusal log fires
        // before any action-class branch logic.
        val gateRegex = Regex(
            """if\s*\(\s*phantom\.android\.BuildConfig\.S6_DEBUG_TRIGGER_ENABLED\s*!=\s*"1"\s*\)"""
        )
        assertTrue(
            gateRegex.containsMatchIn(text),
            "S6BreakerTriggerReceiver.onReceive must short-circuit on " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED != \"1\"`. Found receiver source:\n$text",
        )
    }

    @Test
    fun on_receive_checks_intent_action_equals_ACTION_before_dispatch() {
        val text = source()
        val actionCheckRegex = Regex(
            """intent\?\.action\s*!=\s*ACTION"""
        )
        assertTrue(
            actionCheckRegex.containsMatchIn(text),
            "S6BreakerTriggerReceiver.onReceive must check `intent?.action != ACTION` " +
                "before dispatching so a stray intent with a matching component but a " +
                "different action does not fire the trigger. Found receiver source:\n$text",
        )
    }

    @Test
    fun on_receive_dispatches_through_appScope_launch_to_triggerS6BreakerForDebug() {
        val text = source()
        // The dispatch MUST go through `container.appScope.launch`
        // — running on the broadcast receiver's `onReceive` thread
        // directly would block the framework dispatch thread for
        // the suspend orchestrator helper.
        assertTrue(
            text.contains("container.appScope.launch"),
            "S6BreakerTriggerReceiver must dispatch on `container.appScope.launch { ... }` " +
                "to respect the AppContainer lifecycle.",
        )
        assertTrue(
            text.contains("container.triggerS6BreakerForDebug()"),
            "S6BreakerTriggerReceiver must route the dispatch through " +
                "`container.triggerS6BreakerForDebug()` so the AppContainer-level gate runs " +
                "before the orchestrator-level gate.",
        )
    }
}
