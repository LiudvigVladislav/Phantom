// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §5.2 layer 4 wiring
 * contract test (2026-06-30). Mirrors [AppContainerS6DebugTriggerWiringTest]
 * for the L1 synthetic-trigger surface.
 *
 * The mini-lock §5.2 four-layer model puts AppContainer in layer 4.
 * This test pins the AppContainer-side wiring as it ships in source so
 * a future refactor that drops a load-bearing wire-up fails here
 * BEFORE the L1 synthetic-trigger surface ships a regression:
 *
 *   1. AppContainer reads `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == "1"`
 *      and stores the Boolean (mirror of the `mode2FastPathEnabled` /
 *      `mode2StickyEnabled` / `s6DebugTriggerEnabled` pattern).
 *   2. AppContainer constructs `KtorRelayTransport` with the Boolean
 *      passed via the named constructor parameter
 *      `debugForceMode2Enabled = <expr>` (not positional, not omitted).
 *   3. AppContainer provides a `triggerDebugForceMode2(durationMs: Long)`
 *      method that re-checks `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION != "1"`
 *      AND the orchestrator's `isStickyOrRecoveryActive` state BEFORE
 *      delegating to `wsTransport.debugForceMode2Synthetic(durationMs)`.
 *   4. The method's return type is the typed
 *      `phantom.core.transport.SyntheticTriggerResult`, not `Boolean`.
 *
 * The test is structural — it reads `AppContainer.kt` as text and
 * asserts the load-bearing strings exist in the correct context. This
 * is the same pattern the S6 wiring test uses; a runtime-level
 * integration test would require standing up the full Android
 * application context which is overkill for pinning a few constructor
 * arguments.
 */
class AppContainerDebugForceMode2WiringTest {

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
    fun appcontainer_reads_buildconfig_debug_force_mode_2_detection() {
        val text = source()
        assertTrue(
            text.contains("phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == \"1\""),
            "AppContainer MUST read " +
                "`phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == \"1\"` and " +
                "store the resulting Boolean. This is the L1 mini-lock §4.6 wiring " +
                "contract: `commonMain` never reads BuildConfig directly; the Android-side " +
                "AppContainer reads it and injects the Boolean down. Mirror of the " +
                "existing `mode2FastPathEnabled` / `mode2StickyEnabled` injection pattern.",
        )
    }

    @Test
    fun ktor_relay_transport_constructor_passes_debug_force_mode_2_enabled_named() {
        val text = source()
        val constructionStart = text.indexOf("KtorRelayTransport(")
        assertNotNull(
            constructionStart.takeIf { it >= 0 },
            "AppContainer must contain a KtorRelayTransport construction site.",
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
        if (depth != 0) {
            fail("Unbalanced parens in AppContainer KtorRelayTransport construction.")
        }
        val args = text.substring(openIdx + 1, i - 1)
        assertTrue(
            args.contains("debugForceMode2Enabled = "),
            "AppContainer's KtorRelayTransport construction MUST pass " +
                "`debugForceMode2Enabled = <expr>` as a NAMED constructor parameter. " +
                "Positional binding would silently drift if the constructor parameter " +
                "order changed. Argument list:\n$args",
        )
    }

    @Test
    fun appcontainer_declares_trigger_debug_force_mode_2_wrapper_method() {
        val text = source()
        assertTrue(
            text.contains("fun triggerDebugForceMode2(durationMs: Long)"),
            "AppContainer MUST declare " +
                "`fun triggerDebugForceMode2(durationMs: Long): SyntheticTriggerResult` " +
                "as the layer-4 wrapper for the L1 synthetic-trigger surface. The " +
                "method is the choke point between the debug-only " +
                "`DebugForceMode2Activity` (which `adb shell am start` lands on) and " +
                "the transport-side `KtorRelayTransport.debugForceMode2Synthetic`.",
        )
        assertTrue(
            text.contains(": phantom.core.transport.SyntheticTriggerResult"),
            "AppContainer's `triggerDebugForceMode2` MUST return the typed " +
                "`phantom.core.transport.SyntheticTriggerResult` sealed class (not " +
                "Boolean) per the L1 mini-lock §6 contract. Diagnosis is structural, " +
                "not log-pattern-match.",
        )
    }

    @Test
    fun trigger_debug_force_mode_2_fails_closed_when_orchestrator_ref_is_null() {
        val text = source()
        val methodStart = text.indexOf("fun triggerDebugForceMode2(durationMs: Long)")
        assertTrue(
            methodStart >= 0,
            "triggerDebugForceMode2 must be declared (covered by another test cell).",
        )
        val bodySlice = text.substring(methodStart, minOf(text.length, methodStart + 3_500))
        assertTrue(
            bodySlice.contains("if (orch == null)"),
            "triggerDebugForceMode2 MUST explicitly check `if (orch == null)` and " +
                "return a typed refusal BEFORE delegating to the transport. Without " +
                "this guard the layer-4 sticky/recovery check is silently skipped " +
                "when `initMessaging` has not run yet, which could let a synthetic " +
                "fire during an in-flight recovery candidate. Mirrors the S6 " +
                "fail-closed precedent (`triggerS6BreakerForDebug` returns `false` " +
                "when `restOrchestratorRef == null`).",
        )
        assertTrue(
            bodySlice.contains(
                "phantom.core.transport.SyntheticTriggerResult.RefusedNotConnected",
            ),
            "The `orch == null` branch MUST return " +
                "`SyntheticTriggerResult.RefusedNotConnected` (reusing the existing " +
                "§6 sealed-class member). Adding a new sealed-class member would " +
                "drift the §6 contract from the L1 mini-lock; reusing the existing " +
                "member keeps the contract stable.",
        )
    }

    @Test
    fun trigger_debug_force_mode_2_rechecks_buildconfig_before_delegating() {
        val text = source()
        val methodStart = text.indexOf("fun triggerDebugForceMode2(durationMs: Long)")
        assertTrue(
            methodStart >= 0,
            "triggerDebugForceMode2 must be declared (covered by another test cell).",
        )
        // Take the body of the method (next ~2000 chars cover its body).
        val bodySlice = text.substring(methodStart, minOf(text.length, methodStart + 2_500))
        assertTrue(
            bodySlice.contains("phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION != \"1\""),
            "triggerDebugForceMode2 MUST re-check " +
                "`phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION != \"1\"` " +
                "as the first gate AND return `SyntheticTriggerResult.RefusedDisabled` " +
                "if so. This is the L1 mini-lock §5.2 layer-4 BuildConfig re-check " +
                "before reaching `KtorRelayTransport.debugForceMode2Synthetic`.",
        )
        assertTrue(
            bodySlice.contains("isStickyOrRecoveryActive"),
            "triggerDebugForceMode2 MUST consult " +
                "`orch.stateMachine.isStickyOrRecoveryActive` AND return " +
                "`SyntheticTriggerResult.RefusedAlreadyArmed` if the sticky window is " +
                "armed or recovery is in flight. This closes the L1 mini-lock §7.2 D-1 " +
                "edge case identified during pre-flight (firing a synthetic trigger " +
                "during recovery would falsely fail the recovery candidate).",
        )
        assertTrue(
            bodySlice.contains("wsTransport.debugForceMode2Synthetic(durationMs)"),
            "triggerDebugForceMode2 MUST delegate to " +
                "`wsTransport.debugForceMode2Synthetic(durationMs)` AFTER both gate " +
                "checks pass. The transport-side method has its own gates (Disabled, " +
                "NotConnected, DurationOutOfRange, AlreadyFired) that finish the chain.",
        )
    }
}
