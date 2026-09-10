// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * §12 Round-1 audit P0-6 caller-boundary: even though `exported="true"`
 * is required for `am broadcast -n` to work from an ADB shell,
 * third-party apps are rejected at the AMS boundary via the debug
 * manifest's `android:permission="android.permission.DUMP"`. Only
 * callers holding DUMP (shell, root, and system dumps) reach
 * [onReceive]. The earlier `Binder.getCallingUid()` gate is removed
 * — it was unreliable inside `onReceive` because delivery is mediated
 * by the framework and the reported UID can be system rather than the
 * original sender.
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
        // §12 Round-1 audit P0-6: caller enforcement moved to the
        // AMS boundary via `android:permission="android.permission.DUMP"`
        // in the debug AndroidManifest.xml overlay. Broadcasts from a
        // caller that does NOT hold DUMP never reach this method.
        // The prior `Binder.getCallingUid()` gate is removed — it was
        // unreliable inside `onReceive` because delivery is mediated
        // by the framework and the reported UID can be system rather
        // than the original sender.
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
            SUB_CHECKPOINT -> handleCheckpoint(context)
            SUB_PAIRED_COUNT_REPORT -> handlePairedCountReport(context)
            SUB_SIGNED_PREKEY_READINESS -> handleSignedPrekeyReadiness(context)
            SUB_NETWORK_PROFILE_REPORT -> handleNetworkProfileReport(context, intent)
            SUB_NETWORK_PROFILE_STATE_REPORT -> handleNetworkProfileStateReport(context)
            else -> Log.w(TAG, "rejected: dispatch fell through for subcommand=$subcommand")
        }
    }

    private fun handleNetworkProfileReport(context: Context, intent: Intent) {
        // WSS-3 §4.5: bounded debug-only additive subcommand. Dispatch
        // to [DiagnosticNetworkProfileReporter]. The reporter writes to
        // a FIXED app-owned path (never a caller-controlled path); the
        // extras whitelist above rejects everything except
        // `checkpoint_key_hex`. This handler intentionally does NOT emit
        // to Log — the reporter's `Contract` clause requires silence
        // (no leakage into logcat) for the diagnostic path.
        val key = intent.getStringExtra(EXTRA_CHECKPOINT_KEY_HEX)
        if (!DiagnosticNetworkProfileReporter.isValidCheckpointKeyHex(key)) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                DiagnosticNetworkProfileReporter.run(context, key!!)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleNetworkProfileStateReport(context: Context) {
        // Audit ROUND-10 P0-1: phone MUST NOT participate in HMAC egress
        // scheme. This subcommand runs the reporter WITHOUT any HTTP
        // fetch or HMAC computation. Emitted JSON drops the
        // `egress_fingerprint` object entirely. No `checkpoint_key_hex`
        // extra is accepted (extras whitelist for this subcommand is
        // empty).
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                DiagnosticNetworkProfileReporter.runStateOnly(context)
            } finally {
                pendingResult.finish()
            }
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
        // the source-of-truth role after a process restart. §12
        // Round-1 audit P0-5: a failed .commit() must NOT silently
        // update the in-memory guard — the two would diverge from
        // the operator's perspective and a subsequent restart would
        // silently revert.
        val committed = DiagnosticTransportPinStore.writePin(context, newPin, runId!!, cellId!!)
        if (!committed) {
            Log.w(TAG, "rejected: SharedPreferences.commit() returned false for pin write — in-memory NOT updated")
            return
        }
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
        // §12 P0-2 + Round-1 audit P0-5 — full fail-closed match:
        //   - persisted pin must NOT be NONE (pin write must have happened)
        //   - persisted runId + cellId must match the caller
        //   - in-memory guard state must equal the persisted snapshot
        //     (a process restart between pin write and send must be
        //     detectable — session_started with restored=true is
        //     the operator's cue to re-write the pin before sending)
        val persisted = DiagnosticTransportPinStore.read(context)
        val inMemory = DiagnosticTransportGuard.current()
        val mismatchReason: String? = when {
            persisted.pin == DiagnosticTransportGuard.Pin.NONE ->
                "persisted pin=NONE — no pin write covered this send"
            persisted.runId != runId ->
                "persisted runId=${persisted.runId} != caller $runId"
            persisted.cellId != cellId ->
                "persisted cellId=${persisted.cellId} != caller $cellId"
            inMemory.pin != persisted.pin ->
                "in-memory pin=${inMemory.pin} != persisted ${persisted.pin} — process restart drift"
            inMemory.runId != persisted.runId ->
                "in-memory runId=${inMemory.runId} != persisted ${persisted.runId} — process restart drift"
            inMemory.cellId != persisted.cellId ->
                "in-memory cellId=${inMemory.cellId} != persisted ${persisted.cellId} — process restart drift"
            else -> null
        }
        if (mismatchReason != null) {
            Log.w(
                TAG,
                "rejected: send fail-closed: $mismatchReason (caller runId=$runId cellId=$cellId) — " +
                    "operator must re-write pin (diag-cmd pin ...) before sending",
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
        // §12 Round-9 audit P0-2: do NOT hold `goAsync()` around a
        // potentially long `sendMessage`. Android's BroadcastReceiver
        // API documents that `goAsync()` extends the broadcast
        // timeout only in bounded ways — using it for arbitrary
        // network work risks ANR or process kill during a diagnosed
        // hang (the exact class of product signal we came to Yota
        // to investigate). Instead: fire the coordinator on its
        // OWN process-local scope
        // (`DiagnosticSendCoordinator.asyncTrigger` uses
        // `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created
        // at coordinator construction time — scope is bound to the
        // coordinator instance, which outlives this receiver
        // invocation; a process kill still loses it, and that
        // manifests as integrity RED / NOT_EVALUABLE at verifier
        // time via the missing matrix_completion.json marker), and
        // return from `onReceive` synchronously — the
        // `diag-cmd.sh send` wrapper additionally passes `--async`
        // so the ADB reply does not wait for the broadcast to
        // finish either. The coordinator's coroutine emits
        // `diagnostic_send_dispatched` immediately and
        // `diagnostic_send_command_completed` after `sendMessage`
        // returns / throws — up to the point of a process kill.
        coordinator.asyncTrigger(cellId!!, sequence) { /* no-op */ }
    }

    private fun handleCheckpoint(context: Context) {
        // Round-1 audit P0-1 (P0-5 baseline): re-emits
        // diagnostic_session_started so an in-stream capture can
        // observe it deterministically. Preflight calls this AFTER
        // capture-logs.sh streams start.
        val snapshot = DiagnosticTransportPinStore.read(context)
        val inMemory = DiagnosticTransportGuard.current()
        val restored = inMemory.pin != snapshot.pin || inMemory.runId != snapshot.runId ||
            inMemory.cellId != snapshot.cellId
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
    }

    private fun handleSignedPrekeyReadiness(context: Context) {
        // §12 Round-7 audit P1-2: non-consuming relay-side prekey
        // publication check. Uses `preKeyApi.fetchStatus(...)` — which
        // GETs `/prekeys/status` and returns `(signed_prekey_age_days,
        // remaining_opks)` WITHOUT consuming an OPK. `fetchBundle`
        // (which does consume an OPK) is deliberately NOT called here;
        // Ct  preflight's job is to prove readiness, not to burn a
        // one-time prekey. Reports back via `WSS_DIAG_CMD` so
        // preflight can parse a single line per device.
        val app = context.applicationContext as? PhantomApplication ?: return
        val container = runCatching { app.container }.getOrNull() ?: run {
            Log.i(TAG, "signed_prekey_readiness ok=false reason=container_not_ready")
            return
        }
        val identity = container.identityState.value ?: run {
            Log.i(TAG, "signed_prekey_readiness ok=false reason=no_identity")
            return
        }
        val api = container.preKeyApi ?: run {
            Log.i(TAG, "signed_prekey_readiness ok=false reason=prekey_api_not_ready")
            return
        }
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val status = runCatching {
                    api.fetchStatus(
                        identityPubkeyHex = identity.publicKeyHex,
                        requesterPubkeyHex = identity.publicKeyHex,
                    )
                }
                if (status.isFailure) {
                    Log.i(
                        TAG,
                        "signed_prekey_readiness ok=false reason=fetch_status_exception " +
                            "exception=${status.exceptionOrNull()?.let { it::class.simpleName } ?: "Unknown"}",
                    )
                    return@launch
                }
                val s = status.getOrThrow()
                val age = s.signed_prekey_age_days
                val opks = s.remaining_opks
                val published = age != null
                Log.i(
                    TAG,
                    "signed_prekey_readiness published=$published " +
                        "signed_prekey_age_days=${age ?: "null"} remaining_opks=$opks",
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handlePairedCountReport(context: Context) {
        // Round-1 audit P1: preflight needs an app-owned count of
        // eligible paired conversations. Returns the count via a
        // structured event so preflight can enforce exactly 1.
        val app = context.applicationContext as? PhantomApplication ?: return
        val container = runCatching { app.container }.getOrNull() ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val active = container.conversationRepo.getActiveConversations()
                    .filterNot { it.blocked || it.archived }
                Log.i(
                    TAG,
                    "paired_count_report count=${active.size}",
                )
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
        val ok = DiagnosticTransportPinStore.writeEmitter(context, newId)
        if (!ok) {
            Log.w(TAG, "rejected: SharedPreferences.commit() returned false for emitter write — in-memory NOT updated")
            return
        }
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
        val ok = DiagnosticTransportPinStore.clear(context)
        if (!ok) {
            Log.w(TAG, "rejected: SharedPreferences.commit() returned false for clear — in-memory NOT updated")
            return
        }
        DiagnosticTransportGuard.set(DiagnosticTransportGuard.PinState.NONE_UNSET)
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
        internal const val EXTRA_CHECKPOINT_KEY_HEX = "checkpoint_key_hex"

        internal const val SUB_PIN = "pin"
        internal const val SUB_SEND = "send"
        internal const val SUB_CANARY = "canary"
        internal const val SUB_SET_EMITTER_ID = "set_emitter_id"
        internal const val SUB_DUAL_SIM_REPORT = "dual_sim_report"
        internal const val SUB_HEALTH = "health"
        internal const val SUB_CLEAR = "clear"
        internal const val SUB_CHECKPOINT = "checkpoint"
        internal const val SUB_PAIRED_COUNT_REPORT = "paired_count_report"
        internal const val SUB_SIGNED_PREKEY_READINESS = "signed_prekey_readiness"
        internal const val SUB_NETWORK_PROFILE_REPORT = "network_profile_report"
        // Audit ROUND-10 P0-1: state-only variant for the phone.
        // Skips HTTP/HMAC entirely; accepts NO extras.
        internal const val SUB_NETWORK_PROFILE_STATE_REPORT = "network_profile_state_report"

        internal val ALLOWED_SUBCOMMANDS = setOf(
            SUB_PIN, SUB_SEND, SUB_CANARY, SUB_SET_EMITTER_ID,
            SUB_DUAL_SIM_REPORT, SUB_HEALTH, SUB_CLEAR,
            SUB_CHECKPOINT, SUB_PAIRED_COUNT_REPORT,
            SUB_SIGNED_PREKEY_READINESS,
            SUB_NETWORK_PROFILE_REPORT,
            SUB_NETWORK_PROFILE_STATE_REPORT,
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
            // §12 Round-2 audit P0-1: both subcommands MUST have an
            // entry (even empty) — the receiver's dispatch is gated
            // on this map lookup and returns silently on `null`.
            SUB_CHECKPOINT to emptySet(),
            SUB_PAIRED_COUNT_REPORT to emptySet(),
            SUB_SIGNED_PREKEY_READINESS to emptySet(),
            // WSS-3 §4.5: `network_profile_report` accepts EXACTLY one
            // extra beyond `subcommand` — the orchestrator-supplied
            // HMAC key. No `report_target`, no `output` path, no other
            // caller-controlled input. Round-2 blocker 5 enforcement.
            SUB_NETWORK_PROFILE_REPORT to setOf(EXTRA_CHECKPOINT_KEY_HEX),
            // Audit ROUND-10 P0-1: state-only subcommand for the phone.
            // Accepts NO extras — the phone never receives an HMAC key.
            SUB_NETWORK_PROFILE_STATE_REPORT to emptySet(),
        )

        private val RUN_ID_EXTRA = setOf('.', '_', '-')
        private val CELL_ID_EXTRA = setOf('.', '_', '-', ':')
    }
}
