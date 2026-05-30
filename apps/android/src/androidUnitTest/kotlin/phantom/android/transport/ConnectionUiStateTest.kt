// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.RestMode
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for the pure derivation function
 * [deriveConnectionUiState] introduced by PR-WS-HEALTH-STATE1 Commit 3.1
 * (2026-05-30). Gate 7 of the design note locked in
 * `docs/tracks/ws-health-state.md` § Commit 3.1 § §4 requires the
 * 7-row priority table to be exhaustively asserted, including the four
 * architect-flagged ambiguous cases that rev1 of the design note would
 * have got wrong:
 *
 *   - (Connected, RestActive)    -> LimitedRealtime   NOT Online
 *   - (Connected, WsCandidate)   -> Recovering        NOT Online
 *   - (Reconnecting, RestActive) -> LimitedRealtime   NOT Reconnecting
 *   - (Error(t), RestActive)     -> LimitedRealtime   NOT Error(t)
 *
 * Tests run on the JVM (androidUnitTest source set). No Android
 * runtime is required; the function is pure.
 */
class ConnectionUiStateTest {

    // ── Row 1: RestActive supersedes every wsState ────────────────────────────

    @Test
    fun restActive_with_Connected_yields_LimitedRealtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(TransportState.Connected, RestMode.RestActive),
        )
    }

    @Test
    fun restActive_with_Connecting_yields_LimitedRealtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(TransportState.Connecting, RestMode.RestActive),
        )
    }

    @Test
    fun restActive_with_Reconnecting_yields_LimitedRealtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(TransportState.Reconnecting, RestMode.RestActive),
        )
    }

    @Test
    fun restActive_with_Disconnected_yields_LimitedRealtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(TransportState.Disconnected, RestMode.RestActive),
        )
    }

    @Test
    fun restActive_with_Error_yields_LimitedRealtime() {
        val cause = RuntimeException("ws error")
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(TransportState.Error(cause), RestMode.RestActive),
        )
    }

    // ── Row 2: WsCandidate supersedes every wsState ───────────────────────────

    @Test
    fun wsCandidate_with_Connected_yields_Recovering() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Connected, RestMode.WsCandidate),
        )
    }

    @Test
    fun wsCandidate_with_Connecting_yields_Recovering() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Connecting, RestMode.WsCandidate),
        )
    }

    @Test
    fun wsCandidate_with_Reconnecting_yields_Recovering() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Reconnecting, RestMode.WsCandidate),
        )
    }

    @Test
    fun wsCandidate_with_Disconnected_yields_Recovering() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Disconnected, RestMode.WsCandidate),
        )
    }

    @Test
    fun wsCandidate_with_Error_yields_Recovering() {
        val cause = RuntimeException("ws error")
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Error(cause), RestMode.WsCandidate),
        )
    }

    // ── Rows 3-7: WsActive falls through to raw wsState ───────────────────────

    @Test
    fun wsActive_with_Connected_yields_Online() {
        assertEquals(
            ConnectionUiState.Online,
            deriveConnectionUiState(TransportState.Connected, RestMode.WsActive),
        )
    }

    @Test
    fun wsActive_with_Connecting_yields_Connecting() {
        assertEquals(
            ConnectionUiState.Connecting,
            deriveConnectionUiState(TransportState.Connecting, RestMode.WsActive),
        )
    }

    @Test
    fun wsActive_with_Reconnecting_yields_Reconnecting() {
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(TransportState.Reconnecting, RestMode.WsActive),
        )
    }

    @Test
    fun wsActive_with_Disconnected_yields_Offline() {
        assertEquals(
            ConnectionUiState.Offline,
            deriveConnectionUiState(TransportState.Disconnected, RestMode.WsActive),
        )
    }

    @Test
    fun wsActive_with_Error_yields_Error_with_preserved_cause() {
        val cause = RuntimeException("terminal ws error")
        val result = deriveConnectionUiState(TransportState.Error(cause), RestMode.WsActive)
        assertIs<ConnectionUiState.Error>(result)
        assertEquals(cause, result.cause)
    }
}
