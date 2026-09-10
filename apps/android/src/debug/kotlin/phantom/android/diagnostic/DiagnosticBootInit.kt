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
import phantom.core.transport.ManagerState
import phantom.core.transport.TransportKind

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
        try { context?.applicationContext?.let { BackgroundRecorder.start(it) } }
        catch (_: Exception) { Log.w("BackgroundDiagnostic", "observer_start_failed") }
        // §PR-review-round-1 P0: activate WssDiag. `WssDiag.emit`
        // is release-inert-by-default; every call is a no-op until
        // this line runs. This provider is declared ONLY in the
        // debug AndroidManifest overlay so release APKs never
        // reach here — production diagnostic emits stay silent.
        WssDiag.isActive = true
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
        // available. Reads the LIVE TransportManager state — the
        // actually-selected outer arm after chain-walk completes.
        // A Standard-policy device that fell through to Reality/Tor
        // returns those values, NOT "direct".
        scope.launch {
            val app = ctx as? PhantomApplication ?: return@launch
            runCatching { app.ready.await() }
            val container = runCatching { app.container }.getOrNull() ?: return@launch
            DiagnosticTransportGuard.outerArmReader = {
                when (val s = container.transportManager.state.value) {
                    is ManagerState.Connected -> when (s.kind) {
                        TransportKind.Direct -> "direct"
                        TransportKind.Reality -> "reality"
                        TransportKind.Tor -> "tor"
                    }
                    is ManagerState.Probing -> "probing"
                    is ManagerState.AllFailed -> "failed"
                    ManagerState.Idle -> "idle"
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
            restored = restored,
        )
        // §12 Round-1 audit: remove the previous raw Log.i line
        // — the sole WSS_DIAG tag must carry ONLY structured events.
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
        deliverFailure: WssDiagBridge.DeliverFailure?,
        deliverStage: WssDiagBridge.DeliverStage?,
        attempt: Int?,
    ) {
        WssDiag.emit(
            event = event,
            attempt = attempt,
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
            deliverFailure = deliverFailure?.let {
                when (it) {
                    WssDiagBridge.DeliverFailure.THREW -> WssDiag.DeliverFailure.THREW
                    WssDiagBridge.DeliverFailure.HELD -> WssDiag.DeliverFailure.HELD
                    WssDiagBridge.DeliverFailure.UNKNOWN_PROCESSING_FAILURE ->
                        WssDiag.DeliverFailure.UNKNOWN_PROCESSING_FAILURE
                }
            },
            deliverStage = deliverStage?.let {
                when (it) {
                    WssDiagBridge.DeliverStage.RECEIVED -> WssDiag.DeliverStage.RECEIVED
                    WssDiagBridge.DeliverStage.DECRYPTED -> WssDiag.DeliverStage.DECRYPTED
                    WssDiagBridge.DeliverStage.PERSISTED -> WssDiag.DeliverStage.PERSISTED
                    WssDiagBridge.DeliverStage.LEDGER_MARKED -> WssDiag.DeliverStage.LEDGER_MARKED
                    WssDiagBridge.DeliverStage.ACK_SENT -> WssDiag.DeliverStage.ACK_SENT
                }
            },
        )
    }
}
