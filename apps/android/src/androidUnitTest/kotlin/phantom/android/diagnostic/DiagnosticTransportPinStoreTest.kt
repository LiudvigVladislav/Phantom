// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct WSS Yota-First — §12 P0-2 pin-store persistence tests.
 *
 * Backing store is a debug-only SharedPreferences file. Round-trip
 * tests + clear semantics + emitter separation from run/cell state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticTransportPinStoreTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        DiagnosticTransportPinStore.clear(ctx)
        // The `clear()` method deliberately does NOT reset emitter_id;
        // wipe it manually per test to give a clean slate.
        DiagnosticTransportPinStore.writeEmitter(ctx, DiagnosticTransportGuard.EmitterId.UNSET)
    }

    @After
    fun tearDown() {
        DiagnosticTransportPinStore.clear(ctx)
        DiagnosticTransportPinStore.writeEmitter(ctx, DiagnosticTransportGuard.EmitterId.UNSET)
    }

    @Test
    fun default_state_reads_as_pin_none_empty_run_empty_cell_unset_emitter() {
        val s = DiagnosticTransportPinStore.read(ctx)
        assertEquals(DiagnosticTransportGuard.Pin.NONE, s.pin)
        assertEquals("", s.runId)
        assertEquals("", s.cellId)
        assertEquals(DiagnosticTransportGuard.EmitterId.UNSET, s.emitterId)
    }

    @Test
    fun write_pin_wss_persists_across_read() {
        DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.WSS,
            runId = "run-abc", cellId = "wss.p2e.after-connect",
        )
        val s = DiagnosticTransportPinStore.read(ctx)
        assertEquals(DiagnosticTransportGuard.Pin.WSS, s.pin)
        assertEquals("run-abc", s.runId)
        assertEquals("wss.p2e.after-connect", s.cellId)
    }

    @Test
    fun write_emitter_id_persists_and_is_independent_of_pin() {
        DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.REST,
            runId = "r", cellId = "c",
        )
        DiagnosticTransportPinStore.writeEmitter(
            ctx, DiagnosticTransportGuard.EmitterId.PHONE,
        )
        val s = DiagnosticTransportPinStore.read(ctx)
        assertEquals(DiagnosticTransportGuard.Pin.REST, s.pin)
        assertEquals(DiagnosticTransportGuard.EmitterId.PHONE, s.emitterId)
    }

    @Test
    fun clear_resets_pin_run_cell_but_preserves_emitter_id() {
        DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.WSS,
            runId = "r-1", cellId = "c-1",
        )
        DiagnosticTransportPinStore.writeEmitter(
            ctx, DiagnosticTransportGuard.EmitterId.EMULATOR,
        )
        DiagnosticTransportPinStore.clear(ctx)
        val s = DiagnosticTransportPinStore.read(ctx)
        assertEquals(DiagnosticTransportGuard.Pin.NONE, s.pin)
        assertEquals("", s.runId)
        assertEquals("", s.cellId)
        // Emitter is a bootstrap-time property; NOT cleared by end-of-run.
        assertEquals(DiagnosticTransportGuard.EmitterId.EMULATOR, s.emitterId)
    }

    // §12 Round-1 audit P0-5: commit result is returned; caller
    // reacts to a false result. In Robolectric SharedPreferences the
    // real commit always succeeds; we lock the return-shape here so
    // any refactor that reverts to Unit fails-red.

    @Test
    fun write_apis_return_boolean_from_commit() {
        val a: Boolean = DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.WSS, runId = "r", cellId = "c",
        )
        val b: Boolean = DiagnosticTransportPinStore.writeEmitter(
            ctx, DiagnosticTransportGuard.EmitterId.PHONE,
        )
        val c: Boolean = DiagnosticTransportPinStore.clear(ctx)
        assertEquals(true, a)
        assertEquals(true, b)
        assertEquals(true, c)
    }

    @Test
    fun subsequent_write_overwrites_previous_pin_value() {
        DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.WSS,
            runId = "r-1", cellId = "c-1",
        )
        DiagnosticTransportPinStore.writePin(
            ctx, DiagnosticTransportGuard.Pin.REST,
            runId = "r-2", cellId = "c-2",
        )
        val s = DiagnosticTransportPinStore.read(ctx)
        assertEquals(DiagnosticTransportGuard.Pin.REST, s.pin)
        assertEquals("r-2", s.runId)
        assertEquals("c-2", s.cellId)
    }
}
