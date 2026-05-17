// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TransportCapabilitiesResolver.resolve].
 *
 * Covers every row of the PR-C1 lock table (see [TransportCapabilitiesResolver] KDoc).
 * Pure function — no coroutines, no I/O, no fakes needed.
 *
 * Test naming convention: `<input description>_<expected outcome key field>`.
 */
class TransportCapabilitiesResolverTest {

    // ── Row 1: WsActive, no Tor — all green ─────────────────────────────────

    @Test
    fun wsActive_noTor_canStartCalls_true() {
        val caps = TransportCapabilitiesResolver.resolve(
            restMode = RestMode.WsActive,
            torActive = false,
        )
        assertTrue(caps.canStartCalls, "canStartCalls must be true on WsActive without Tor")
    }

    @Test
    fun wsActive_noTor_canSendVoice_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        assertTrue(caps.canSendVoice)
    }

    @Test
    fun wsActive_noTor_canSendText_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        assertTrue(caps.canSendText)
    }

    @Test
    fun wsActive_noTor_realtimeStable_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        assertTrue(caps.realtimeStable)
    }

    @Test
    fun wsActive_noTor_callDisabledReason_null() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        assertNull(caps.callDisabledReason)
    }

    // ── Row 2: WsCandidate, no Tor — voice ok, calls blocked ────────────────

    @Test
    fun wsCandidate_noTor_canStartCalls_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = false)
        assertFalse(caps.canStartCalls)
    }

    @Test
    fun wsCandidate_noTor_canSendVoice_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = false)
        assertTrue(caps.canSendVoice)
    }

    @Test
    fun wsCandidate_noTor_realtimeStable_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = false)
        assertFalse(caps.realtimeStable)
    }

    @Test
    fun wsCandidate_noTor_reason_limitedRealtime() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = false)
        assertEquals(CallDisabledReason.LIMITED_REALTIME, caps.callDisabledReason)
    }

    // ── Row 3: RestActive, no Tor — voice ok, calls blocked ─────────────────

    @Test
    fun restActive_noTor_canStartCalls_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertFalse(caps.canStartCalls)
    }

    @Test
    fun restActive_noTor_canSendVoice_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertTrue(caps.canSendVoice)
    }

    @Test
    fun restActive_noTor_realtimeStable_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertFalse(caps.realtimeStable)
    }

    @Test
    fun restActive_noTor_reason_limitedRealtime() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertEquals(CallDisabledReason.LIMITED_REALTIME, caps.callDisabledReason)
    }

    // ── Row 4: any restMode + torActive=true — calls AND voice blocked ───────

    @Test
    fun wsActive_torActive_canStartCalls_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertFalse(caps.canStartCalls)
    }

    @Test
    fun wsActive_torActive_canSendVoice_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertFalse(caps.canSendVoice)
    }

    @Test
    fun wsActive_torActive_canSendText_true() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertTrue(caps.canSendText)
    }

    @Test
    fun wsActive_torActive_realtimeStable_false() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertFalse(caps.realtimeStable)
    }

    @Test
    fun wsActive_torActive_reason_torTransport() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertEquals(CallDisabledReason.TOR_TRANSPORT, caps.callDisabledReason)
    }

    @Test
    fun restActive_torActive_reason_torTransport() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = true)
        assertEquals(CallDisabledReason.TOR_TRANSPORT, caps.callDisabledReason)
    }

    @Test
    fun wsCandidate_torActive_reason_torTransport() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = true)
        assertEquals(CallDisabledReason.TOR_TRANSPORT, caps.callDisabledReason)
    }

    @Test
    fun nullMode_torActive_reason_torTransport() {
        // torActive trumps null restMode
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = true)
        assertEquals(CallDisabledReason.TOR_TRANSPORT, caps.callDisabledReason)
    }

    // ── Row 5: restMode=null, no Tor — pre-bootstrap / no transport ──────────

    @Test
    fun nullMode_noTor_canStartCalls_false() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertFalse(caps.canStartCalls)
    }

    @Test
    fun nullMode_noTor_canSendVoice_false() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertFalse(caps.canSendVoice)
    }

    @Test
    fun nullMode_noTor_canSendText_true() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertTrue(caps.canSendText)
    }

    @Test
    fun nullMode_noTor_realtimeStable_false() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertFalse(caps.realtimeStable)
    }

    @Test
    fun nullMode_noTor_reason_noTransport() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertEquals(CallDisabledReason.NO_TRANSPORT, caps.callDisabledReason)
    }

    // ── Invariant: callDisabledReason is null iff canStartCalls is true ──────

    @Test
    fun invariant_callDisabledReason_null_iff_canStartCalls_true_wsActive() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        // The only combination where both hold
        assertTrue(caps.canStartCalls)
        assertNull(caps.callDisabledReason)
    }

    @Test
    fun invariant_callDisabledReason_nonNull_when_canStartCalls_false_restActive() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertFalse(caps.canStartCalls)
        assertTrue(caps.callDisabledReason != null)
    }

    // ── PR-C1: restModeLabel field — one assertion per distinct resolve path ──
    // restModeLabel is used by CallManager's CALL_TX guard log (mode=<label>).
    // It must faithfully echo RestMode.name so the log is machine-parseable.

    @Test
    fun wsActive_noTor_restModeLabel_isWsActive() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = false)
        assertEquals("WsActive", caps.restModeLabel)
    }

    @Test
    fun wsCandidate_noTor_restModeLabel_isWsCandidate() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.WsCandidate, torActive = false)
        assertEquals("WsCandidate", caps.restModeLabel)
    }

    @Test
    fun restActive_noTor_restModeLabel_isRestActive() {
        val caps = TransportCapabilitiesResolver.resolve(RestMode.RestActive, torActive = false)
        assertEquals("RestActive", caps.restModeLabel)
    }

    @Test
    fun nullMode_noTor_restModeLabel_isNull() {
        val caps = TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
        assertNull(caps.restModeLabel)
    }

    @Test
    fun torActive_restModeLabel_echoesRestMode() {
        // Tor path: restModeLabel still echoes the restMode argument (may be null or a mode name).
        val capsWithMode = TransportCapabilitiesResolver.resolve(RestMode.WsActive, torActive = true)
        assertEquals("WsActive", capsWithMode.restModeLabel)
        val capsNullMode = TransportCapabilitiesResolver.resolve(restMode = null, torActive = true)
        assertNull(capsNullMode.restModeLabel)
    }
}
