// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.android.PhantomApplication

/**
 * Direct WSS Yota-First diagnostic — debug-only broadcast receiver.
 *
 * §11 lock 1: single [BroadcastReceiver] declared ONLY in the debug
 * `AndroidManifest.xml` overlay. Physically absent from the release
 * APK's merged manifest.
 *
 * §12 P1 caller-boundary fix: even though `exported="true"` is required
 * for `am broadcast -n` to work from an ADB shell, [onReceive] gates on
 * `Binder.getCallingUid() ∈ {SHELL_UID, ROOT_UID}`. A third-party app
 * that discovers the component name and fires an explicit broadcast is
 * silently rejected.
 *
 * §12 P0-2 persistence fix: every `pin` and `set_emitter_id` write is
 * persisted to [DiagnosticTransportPinStore] AND to
 * [DiagnosticTransportGuard]. Process death is transparent to the
 * matrix: the debug boot-init provider restores the persisted state
 * before `Application.onCreate()`.
 *
 * §12 P0-2 fail-closed send: the `send` subcommand requires the
 * caller-supplied `cell_id` to match the persisted `cell_id` — a
 * mid-cell process restart WITHOUT a matching `diagnostic_pin` write
 * is rejected.
 */
class DiagnosticCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // §12 P1 — caller UID gate. `Binder.getCallingUid()` returns
        // the UID of the IPC caller; SHELL (2000) or ROOT (0) are the
        // only legitimate origins for a matrix broadcast. Rejects
        // third-party apps that guess the explicit component name.
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.SHELL_UID && callerUid != Process.ROOT_UID) {
            Log.w(TAG, "rejected: caller uid=$callerUid not shell/root")
            return
        }

        val extras = intent.extras
        val subcommand = intent.getStringExtra(EXTRA_SUBCOMMAND)

        if (subcommand == null || subcommand !in ALLOWED_SUBCOMMANDS) {
            Log.w(TAG, "rejected: subcommand invalid or missing: $subcommand")
            return
        }

        val allowedForSub = ALLOWED_EXTRAS_BY_SUBCOMMAND[subcommand] ?: run {
            Log.w(TAG, "rejected: no whitelist configured for subcommand=$subcommand")
            return
        }
        val extraKeys = extras?.keySet().orEmpty()
        val actual = extraKeys - EXTRA_SUBCOMMAND
        val unknown = actual - allowedForSub
        if (unknown.isNotEmpty()) {
            Log.w(TAG, "rejected: unknown extras=$unknown for subcommand=$subcommand")
            return
        }

        when (subcommand) {
            SUB_PIN -> handlePin(context, intent)
            SUB_SEND -> handleSend(context, intent)
            SUB_CANARY -> handleCanary()
            SUB_SET_EMITTER_ID -> handleSetEmitterId(context, intent)
            SUB_DUAL_SIM_REPORT -> handleDualSimReport(context)
            SUB_HEALTH -> handleHealth()
            SUB_CLEAR -> handleClear(context)
            else -> Log.w(TAG, "rejected: dispatch fell through for subcommand=$subcommand")
        }
    }

    private fun handlePin(context: Context, intent: Intent) {
        val pin = intent.getStringExtra(EXTRA_PIN)
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        val cellId = intent.getStringExtra(EXTRA_CELL_ID)
        if (!validRunId(runId) || !validCellId(cellId) || pin == null || pin !in ALLOWED_PINS) {
            Log.w(TAG, "rejected: pin subcommand invalid input (pin=$pin runId=$runId cellId=$cellId)")
            return
        }
        val newPin = when (pin) {
            "none" -> DiagnosticTransportGuard.Pin.NONE
            "wss" -> DiagnosticTransportGuard.Pin.WSS
            "rest" -> DiagnosticTransportGuard.Pin.REST
            else -> return
        }
        // §12 P0-2 — persist THEN update in-memory. Persistence has
        // the source-of-truth role after a process restart.
        DiagnosticTransportPinStore.writePin(context, newPin, runId!!, cellId!!)
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(pin = newPin, runId = runId, cellId = cellId),
        )
        WssDiag.emit(
            event = "diagnostic_pin_active",
            role = WssDiag.Role.MATRIX,
            outerTransport = WssDiag.OuterTransport.UNKNOWN,
            innerRoute = when (newPin) {
                DiagnosticTransportGuard.Pin.WSS -> WssDiag.InnerRoute.WSS
                DiagnosticTransportGuard.Pin.REST -> WssDiag.InnerRoute.REST
                DiagnosticTransportGuard.Pin.NONE -> WssDiag.InnerRoute.UNKNOWN
            },
            pin = newPin.name.lowercase(),
        )
    }

    private fun handleSend(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        val cellId = intent.getStringExtra(EXTRA_CELL_ID)
        val sequence = intent.getIntExtra(EXTRA_SEQUENCE, -1)
        if (!validRunId(runId) || !validCellId(cellId) || sequence < 1) {
            Log.w(TAG, "rejected: send subcommand invalid input (runId=$runId cellId=$cellId seq=$sequence)")
            return
        }
        // §12 P0-2 — fail-closed if the persisted (pin, run, cell)
        // does not match the caller. A mid-cell process restart that
        // discarded the pin surfaces here.
        val persisted = DiagnosticTransportPinStore.read(context)
        if (persisted.runId != runId || persisted.cellId != cellId) {
            Log.w(
                TAG,
                "rejected: send extras mismatch persisted state " +
                    "(caller runId=$runId cellId=$cellId; persisted runId=${persisted.runId} cellId=${persisted.cellId}) — " +
                    "either the pin was never written for this cell OR the process was restarted; " +
                    "operator must re-write pin before sending",
            )
            return
        }
        val app = context.applicationContext as? PhantomApplication ?: run {
            Log.w(TAG, "rejected: PhantomApplication not available")
            return
        }
        val container = runCatching { app.container }.getOrNull() ?: run {
            Log.w(TAG, "rejected: AppContainer not initialized")
            return
        }
        val messaging = container.messagingService ?: run {
            Log.w(TAG, "rejected: MessagingService not ready")
            return
        }
        val coordinator = DiagnosticSendCoordinator(
            conversationRepository = container.conversationRepo,
            messagingService = messaging,
        )
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val outcome = coordinator.resolveAndSend(cellId!!, sequence)
                when (outcome) {
                    is DiagnosticSendCoordinator.Outcome.Sent -> Log.i(
                        TAG,
                        "send ok correlation_id=${outcome.correlationId} cell_id=$cellId seq=$sequence",
                    )
                    is DiagnosticSendCoordinator.Outcome.NoPairedConversation -> Log.w(
                        TAG,
                        "send rejected: no paired conversation (active=${outcome.activeCount})",
                    )
                    is DiagnosticSendCoordinator.Outcome.MultiplePairedConversations -> Log.w(
                        TAG,
                        "send rejected: multiple paired conversations (count=${outcome.ids.size})",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleCanary() {
        // §9.2 + §11 additional test — canary does NOT enqueue any
        // envelope. It only proves the WSS_DIAG tag emits on this
        // device.
        WssDiag.emit(event = "diagnostic_canary", role = WssDiag.Role.MATRIX)
    }

    private fun handleSetEmitterId(context: Context, intent: Intent) {
        val emitterId = intent.getStringExtra(EXTRA_EMITTER_ID)
        val newId = when (emitterId) {
            "phone" -> DiagnosticTransportGuard.EmitterId.PHONE
            "emulator" -> DiagnosticTransportGuard.EmitterId.EMULATOR
            else -> {
                Log.w(TAG, "rejected: set_emitter_id invalid value=$emitterId")
                return
            }
        }
        DiagnosticTransportPinStore.writeEmitter(context, newId)
        DiagnosticTransportGuard.setEmitterId(newId)
        Log.i(TAG, "emitter_id set to=${newId.name.lowercase()}")
    }

    private fun handleDualSimReport(context: Context) {
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(defaultDataSubId)
        val operator = telephony.simOperator.orEmpty()
        Log.i(
            TAG,
            "dual_sim_report default_data_sub_id=$defaultDataSubId operator_numeric=$operator",
        )
    }

    private fun handleHealth() {
        val guard = DiagnosticTransportGuard.current()
        val id = DiagnosticTransportGuard.currentEmitterId()
        Log.i(
            TAG,
            "health emitter_id=${id.name.lowercase()} pin=${guard.pin.name.lowercase()} " +
                "run_id=${guard.runId.ifEmpty { "-" }} cell_id=${guard.cellId.ifEmpty { "-" }} " +
                "outer_transport=${DiagnosticTransportGuard.currentOuterArm()}",
        )
    }

    private fun handleClear(context: Context) {
        DiagnosticTransportPinStore.clear(context)
        DiagnosticTransportGuard.set(DiagnosticTransportGuard.PinState.NONE_UNSET)
        Log.i(TAG, "diagnostic_state_cleared")
        WssDiag.emit(
            event = "diagnostic_state_cleared",
            role = WssDiag.Role.MATRIX,
        )
    }

    private fun validRunId(v: String?): Boolean =
        v != null && v.isNotEmpty() && v.length <= 64 && v.all { it.isLetterOrDigit() || it in RUN_ID_EXTRA }

    private fun validCellId(v: String?): Boolean =
        v != null && v.isNotEmpty() && v.length <= 128 && v.all { it.isLetterOrDigit() || it in CELL_ID_EXTRA }

    companion object {
        private const val TAG = "WSS_DIAG_CMD"

        internal const val EXTRA_SUBCOMMAND = "subcommand"
        internal const val EXTRA_PIN = "pin"
        internal const val EXTRA_RUN_ID = "run_id"
        internal const val EXTRA_CELL_ID = "cell_id"
        internal const val EXTRA_SEQUENCE = "sequence"
        internal const val EXTRA_EMITTER_ID = "emitter_id"

        internal const val SUB_PIN = "pin"
        internal const val SUB_SEND = "send"
        internal const val SUB_CANARY = "canary"
        internal const val SUB_SET_EMITTER_ID = "set_emitter_id"
        internal const val SUB_DUAL_SIM_REPORT = "dual_sim_report"
        internal const val SUB_HEALTH = "health"
        internal const val SUB_CLEAR = "clear"

        internal val ALLOWED_SUBCOMMANDS = setOf(
            SUB_PIN, SUB_SEND, SUB_CANARY, SUB_SET_EMITTER_ID,
            SUB_DUAL_SIM_REPORT, SUB_HEALTH, SUB_CLEAR,
        )

        internal val ALLOWED_PINS = setOf("none", "wss", "rest")

        internal val ALLOWED_EXTRAS_BY_SUBCOMMAND: Map<String, Set<String>> = mapOf(
            SUB_PIN to setOf(EXTRA_PIN, EXTRA_RUN_ID, EXTRA_CELL_ID),
            SUB_SEND to setOf(EXTRA_RUN_ID, EXTRA_CELL_ID, EXTRA_SEQUENCE),
            SUB_CANARY to emptySet(),
            SUB_SET_EMITTER_ID to setOf(EXTRA_EMITTER_ID),
            SUB_DUAL_SIM_REPORT to emptySet(),
            SUB_HEALTH to emptySet(),
            SUB_CLEAR to emptySet(),
        )

        private val RUN_ID_EXTRA = setOf('.', '_', '-')
        private val CELL_ID_EXTRA = setOf('.', '_', '-', ':')
    }
}
