// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.Context
import android.content.SharedPreferences

/**
 * Direct WSS Yota-First — debug-only persistence for the
 * DiagnosticTransportGuard state.
 *
 * §12 P0-2 fix — replaces the pure-in-memory design that silently
 * reset to `NONE` on process death. Persisted layout:
 *
 *   pin        = "none" | "wss" | "rest"     (Pin enum name lowercased)
 *   run_id     = <opaque string>
 *   cell_id    = <opaque string>
 *   emitter_id = "unset" | "phone" | "emulator"
 *
 * Backing file: `diagnostic_transport_pin.xml` (SharedPreferences
 * `MODE_PRIVATE`) — plain XML under `context.filesDir/../shared_prefs/`.
 * File is ONLY written by [DiagnosticCommandReceiver] (in this same
 * debug source set). The release APK does not include either file,
 * because this Kotlin class + the receiver + the boot-init provider
 * all live in `src/debug/` and are absent from the release manifest
 * + release dex.
 *
 * Concurrency: SharedPreferences is thread-safe for
 * `getString`/`edit().apply()`; commit is used where callers require
 * a synchronous barrier before returning to the ADB caller.
 */
internal object DiagnosticTransportPinStore {

    private const val PREFS_NAME = "diagnostic_transport_pin"
    private const val KEY_PIN = "pin"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_CELL_ID = "cell_id"
    private const val KEY_EMITTER_ID = "emitter_id"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val pin: DiagnosticTransportGuard.Pin,
        val runId: String,
        val cellId: String,
        val emitterId: DiagnosticTransportGuard.EmitterId,
    )

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        val pinStr = p.getString(KEY_PIN, null) ?: "none"
        val runId = p.getString(KEY_RUN_ID, null) ?: ""
        val cellId = p.getString(KEY_CELL_ID, null) ?: ""
        val emitterStr = p.getString(KEY_EMITTER_ID, null) ?: "unset"
        return Snapshot(
            pin = when (pinStr) {
                "wss" -> DiagnosticTransportGuard.Pin.WSS
                "rest" -> DiagnosticTransportGuard.Pin.REST
                else -> DiagnosticTransportGuard.Pin.NONE
            },
            runId = runId,
            cellId = cellId,
            emitterId = when (emitterStr) {
                "phone" -> DiagnosticTransportGuard.EmitterId.PHONE
                "emulator" -> DiagnosticTransportGuard.EmitterId.EMULATOR
                else -> DiagnosticTransportGuard.EmitterId.UNSET
            },
        )
    }

    fun writePin(context: Context, pin: DiagnosticTransportGuard.Pin, runId: String, cellId: String) {
        prefs(context).edit()
            .putString(KEY_PIN, pin.name.lowercase())
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_CELL_ID, cellId)
            .commit()
    }

    fun writeEmitter(context: Context, id: DiagnosticTransportGuard.EmitterId) {
        prefs(context).edit()
            .putString(KEY_EMITTER_ID, id.name.lowercase())
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .putString(KEY_PIN, "none")
            .putString(KEY_RUN_ID, "")
            .putString(KEY_CELL_ID, "")
            .commit()
        // emitter_id survives clear-run intentionally — the device role
        // is a bootstrap-time property, not a per-run property.
    }
}
