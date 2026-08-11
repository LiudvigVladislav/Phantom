// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct WSS Yota-First — pin store round-trip through app code
 * (§11 lock 4 test #3).
 *
 * The pin is in-memory only; the "app code" round-trip proves that
 * a debug-only writer (the receiver in production, this test in
 * isolation) can set the guard and the reader observes it. NO ADB
 * file write is possible or supported.
 */
class DiagnosticTransportGuardTest {

    @Before
    fun reset() = DiagnosticTransportGuard.reset()

    @After
    fun tearDown() = DiagnosticTransportGuard.reset()

    @Test
    fun default_state_is_none_and_unset() {
        val state = DiagnosticTransportGuard.current()
        assertEquals(DiagnosticTransportGuard.Pin.NONE, state.pin)
        assertEquals("", state.runId)
        assertEquals("", state.cellId)
        assertEquals(DiagnosticTransportGuard.EmitterId.UNSET, DiagnosticTransportGuard.currentEmitterId())
    }

    @Test
    fun set_pin_wss_is_readable_immediately() {
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(
                pin = DiagnosticTransportGuard.Pin.WSS,
                runId = "run-abc",
                cellId = "wss.phone-to-emu.after-connect.envelope-1",
            ),
        )
        val state = DiagnosticTransportGuard.current()
        assertEquals(DiagnosticTransportGuard.Pin.WSS, state.pin)
        assertEquals("run-abc", state.runId)
        assertEquals("wss.phone-to-emu.after-connect.envelope-1", state.cellId)
    }

    @Test
    fun set_pin_rest_then_pin_none_returns_to_default_pin_but_metadata_preserved_by_caller() {
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(
                pin = DiagnosticTransportGuard.Pin.REST,
                runId = "run-1",
                cellId = "rest.emu-to-phone.control.envelope-3",
            ),
        )
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(
                pin = DiagnosticTransportGuard.Pin.NONE,
                runId = "run-1",
                cellId = "-",
            ),
        )
        assertEquals(DiagnosticTransportGuard.Pin.NONE, DiagnosticTransportGuard.current().pin)
    }

    @Test
    fun emitter_id_set_to_phone_then_emulator_survives_pin_writes() {
        DiagnosticTransportGuard.setEmitterId(DiagnosticTransportGuard.EmitterId.PHONE)
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(DiagnosticTransportGuard.Pin.WSS, "r", "c"),
        )
        assertEquals(DiagnosticTransportGuard.EmitterId.PHONE, DiagnosticTransportGuard.currentEmitterId())
        DiagnosticTransportGuard.setEmitterId(DiagnosticTransportGuard.EmitterId.EMULATOR)
        assertEquals(DiagnosticTransportGuard.EmitterId.EMULATOR, DiagnosticTransportGuard.currentEmitterId())
    }
}
