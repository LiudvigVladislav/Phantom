// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.transport.CallDisabledReason
import phantom.core.transport.TransportCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the package-level [checkCallCapability] guard in
 * `phantom.android.calls` (PR-C1, 2026-05-17).
 *
 * [checkCallCapability] is the defence-in-depth guard extracted from
 * [CallManager.startCall]. It checks [TransportCapabilities.canStartCalls]
 * and logs a structured `CALL_TX blocked_*` line when a call is rejected.
 *
 * L1 baseline-landing Round-6 (2026-08-13, architect direction): this
 * test class transitively invokes `android.util.Log.i` via production
 * `CallManagerKt.checkCallCapability`. On L1's post-onboarding-baseline
 * `androidUnitTest` classpath the AGP null-mock stub for `Log` no longer
 * supersedes the real `android.util.Log`, so `Log.i` reaches
 * `println_native` at runtime. Explicit Robolectric runner + bare
 * `Application` install `ShadowLog` (proper stub) without booting the
 * production `PhantomApplication` this test does not need. See §L1
 * compatibility invariant in `docs/tracks/android-onboarding/c6-onboarding-baseline-landing-contract.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallManagerGuardTest {

    // ── Blocked cases ────────────────────────────────────────────────────────

    @Test
    fun limitedRealtime_blocked() {
        val caps = blockedCaps(CallDisabledReason.LIMITED_REALTIME, "RestActive")
        assertFalse(
            checkCallCapability(caps),
            "LIMITED_REALTIME must block the call and return false",
        )
    }

    @Test
    fun torTransport_blocked() {
        val caps = blockedCaps(CallDisabledReason.TOR_TRANSPORT, "WsActive")
        assertFalse(
            checkCallCapability(caps),
            "TOR_TRANSPORT must block the call and return false",
        )
    }

    @Test
    fun noTransport_blocked() {
        val caps = blockedCaps(CallDisabledReason.NO_TRANSPORT, null)
        assertFalse(
            checkCallCapability(caps),
            "NO_TRANSPORT must block the call and return false",
        )
    }

    // ── Pass case ────────────────────────────────────────────────────────────

    @Test
    fun wsActive_passes() {
        val caps = TransportCapabilities(
            canSendText = true,
            canSendVoice = true,
            canStartCalls = true,
            realtimeStable = true,
            callDisabledReason = null,
            restModeLabel = "WsActive",
        )
        assertTrue(
            checkCallCapability(caps),
            "WsActive without Tor must allow the call (return true)",
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun blockedCaps(reason: CallDisabledReason, modeLabel: String?) =
        TransportCapabilities(
            canSendText = true,
            canSendVoice = false,
            canStartCalls = false,
            realtimeStable = false,
            callDisabledReason = reason,
            restModeLabel = modeLabel,
        )
}
