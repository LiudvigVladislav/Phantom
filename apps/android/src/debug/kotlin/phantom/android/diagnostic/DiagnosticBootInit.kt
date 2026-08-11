// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.android.PhantomApplication
import phantom.core.messaging.WssDiagBridge
import phantom.core.messaging.WssDiagBridgeHolder
import phantom.core.transport.PrivacyMode

/**
 * Direct WSS Yota-First diagnostic — zero-touch boot init for the
 * debug variant.
 *
 * Registered as a ContentProvider in the debug `AndroidManifest.xml`
 * overlay so the framework instantiates it BEFORE `Application.onCreate()`
 * runs. Responsibilities per §12:
 *
 *  1. Install the [WssDiagBridge] adapter so shared messaging code
 *     can emit `WSS_DIAG` events.
 *  2. Restore [DiagnosticTransportGuard] state from the persisted
 *     [DiagnosticTransportPinStore] (§12 P0-2 fix — a process
 *     restart within a matrix cell must not silently reset to NONE).
 *  3. Emit a structured `diagnostic_session_started` event carrying
 *     the restored pin + run + cell + emitter + a `restored` flag so
 *     the verifier can distinguish a fresh boot from a mid-cell
 *     restart.
 *  4. Install a debug-only outer-arm reader on the guard so
 *     `HybridRelayTransport.send` can OBSERVE the actual outer
 *     transport arm (Direct/Reality/Tor) at send time rather than
 *     hard-coding `direct`. §12 P0-3 fix.
 */
class DiagnosticBootInitProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean {
        // Install the bridge exactly once.
        if (WssDiagBridgeHolder.instance == null) {
            WssDiagBridgeHolder.instance = AndroidWssDiagBridge
        }

        val ctx = context?.applicationContext ?: return true
        val snapshot = DiagnosticTransportPinStore.read(ctx)
        val restored = snapshot.pin != DiagnosticTransportGuard.Pin.NONE ||
            snapshot.runId.isNotEmpty() ||
            snapshot.cellId.isNotEmpty() ||
            snapshot.emitterId != DiagnosticTransportGuard.EmitterId.UNSET
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(
                pin = snapshot.pin,
                runId = snapshot.runId,
                cellId = snapshot.cellId,
            ),
        )
        DiagnosticTransportGuard.setEmitterId(snapshot.emitterId)

        // Install the outer-arm reader once AppContainer becomes
        // available. Runs on a background scope because
        // AppContainer's initialisation depends on
        // Application.onCreate() completing.
        scope.launch {
            val app = ctx as? PhantomApplication ?: return@launch
            runCatching { app.ready.await() }
            val container = runCatching { app.container }.getOrNull() ?: return@launch
            DiagnosticTransportGuard.outerArmReader = {
                when (container.transportPreferences.privacyMode) {
                    PrivacyMode.Standard -> "direct"
                    PrivacyMode.Private -> "tor"
                    PrivacyMode.Ghost -> "reality"
                }
            }
        }

        WssDiag.emit(
            event = "diagnostic_session_started",
            role = WssDiag.Role.MATRIX,
            pin = snapshot.pin.name.lowercase(),
            innerRoute = when (snapshot.pin) {
                DiagnosticTransportGuard.Pin.WSS -> WssDiag.InnerRoute.WSS
                DiagnosticTransportGuard.Pin.REST -> WssDiag.InnerRoute.REST
                DiagnosticTransportGuard.Pin.NONE -> WssDiag.InnerRoute.UNKNOWN
            },
            emitterIdOverride = snapshot.emitterId.name.lowercase(),
            dispatched = restored,
        )
        Log.i(WssDiag.TAG, "diagnostic_boot_init bridge_installed=true restored=$restored")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object AndroidWssDiagBridge : WssDiagBridge {
    override fun emit(
        event: String,
        correlationId: String,
        role: WssDiagBridge.Role,
        outcomeFlag: WssDiagBridge.OutcomeFlag,
        dedupGate: WssDiagBridge.DedupGate?,
    ) {
        WssDiag.emit(
            event = event,
            role = when (role) {
                WssDiagBridge.Role.SENDER -> WssDiag.Role.SENDER
                WssDiagBridge.Role.RECIPIENT -> WssDiag.Role.RECIPIENT
            },
            correlationId = correlationId,
            outcomeFlag = when (outcomeFlag) {
                WssDiagBridge.OutcomeFlag.SENDER_RELAY_ACK_DELIVERED ->
                    WssDiag.OutcomeFlag.SENDER_RELAY_ACK_DELIVERED
                WssDiagBridge.OutcomeFlag.SENDER_RELAY_ACK_RELAYED ->
                    WssDiag.OutcomeFlag.SENDER_RELAY_ACK_RELAYED
                WssDiagBridge.OutcomeFlag.NONE -> WssDiag.OutcomeFlag.NONE
            },
            dedupGate = dedupGate?.let {
                when (it) {
                    WssDiagBridge.DedupGate.FRESH -> WssDiag.DedupGate.FRESH
                    WssDiagBridge.DedupGate.DUPLICATE -> WssDiag.DedupGate.DUPLICATE
                    WssDiagBridge.DedupGate.REACK -> WssDiag.DedupGate.REACK
                    WssDiagBridge.DedupGate.UNKNOWN -> WssDiag.DedupGate.UNKNOWN
                }
            },
        )
    }
}
