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
 * §11 lock 1 (architect FINAL GREEN): a single [BroadcastReceiver]
 * declared ONLY in the debug `AndroidManifest.xml` overlay. Physically
 * absent from the release APK's merged manifest. Invoked via explicit
 * component:
 *
 * ```
 * adb shell am broadcast -n <APP_ID>/phantom.android.diagnostic.DiagnosticCommandReceiver \
 *     --es subcommand pin --es pin wss --es run_id <UUID> --es cell_id <ID>
 * ```
 *
 * Strict-whitelist enforcement of extras (§9.3) + strict-enum
 * enforcement of subcommands (§11 additional test 1). Any deviation
 * exits the receiver red WITHOUT touching `sendMessage`, the pin
 * store, or the chat store.
 *
 * The receiver is the SOLE writer of [DiagnosticTransportGuard] +
 * the SOLE trigger of [DiagnosticSendCoordinator]. Its outputs are
 * `WSS_DIAG` events emitted via [WssDiag].
 */
class DiagnosticCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val subcommand = intent.getStringExtra(EXTRA_SUBCOMMAND)

        if (subcommand == null || subcommand !in ALLOWED_SUBCOMMANDS) {
            Log.w(TAG, "rejected: subcommand invalid or missing: $subcommand")
            return
        }

        // §11 additional test 1: unknown extras are rejected outright.
        val allowedForSub = ALLOWED_EXTRAS_BY_SUBCOMMAND[subcommand] ?: run {
            Log.w(TAG, "rejected: no whitelist configured for subcommand=$subcommand")
            return
        }
        val extraKeys = extras?.keySet().orEmpty()
        // Kotlin's Bundle always includes `subcommand` itself; strip it.
        val actual = extraKeys - EXTRA_SUBCOMMAND
        val unknown = actual - allowedForSub - ALLOWED_META_EXTRAS
        if (unknown.isNotEmpty()) {
            Log.w(TAG, "rejected: unknown extras=$unknown for subcommand=$subcommand")
            return
        }

        when (subcommand) {
            SUB_PIN -> handlePin(intent)
            SUB_SEND -> handleSend(context, intent)
            SUB_CANARY -> handleCanary()
            SUB_SET_EMITTER_ID -> handleSetEmitterId(intent)
            SUB_DUAL_SIM_REPORT -> handleDualSimReport(context)
            SUB_REST_CAPABILITY_PROBE -> handleRestCapabilityProbe()
            SUB_HEALTH -> handleHealth()
            else -> Log.w(TAG, "rejected: dispatch fell through for subcommand=$subcommand")
        }
    }

    private fun handlePin(intent: Intent) {
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
        DiagnosticTransportGuard.set(
            DiagnosticTransportGuard.PinState(pin = newPin, runId = runId!!, cellId = cellId!!),
        )
        // Rebuild pin_active event with the guard now populated so the
        // wall_utc/monotonic fields are strictly after the write.
        WssDiag.emit(
            event = "diagnostic_pin_active",
            role = WssDiag.Role.MATRIX,
            outerTransport = WssDiag.OuterTransport.UNKNOWN, // filled by first sender_transport_decision
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
        // device. Never touches sendMessage, MessageRepository,
        // ConversationRepository, or KtorRelayTransport.
        WssDiag.emit(event = "diagnostic_canary", role = WssDiag.Role.MATRIX)
    }

    private fun handleSetEmitterId(intent: Intent) {
        val emitterId = intent.getStringExtra(EXTRA_EMITTER_ID)
        val newId = when (emitterId) {
            "phone" -> DiagnosticTransportGuard.EmitterId.PHONE
            "emulator" -> DiagnosticTransportGuard.EmitterId.EMULATOR
            else -> {
                Log.w(TAG, "rejected: set_emitter_id invalid value=$emitterId")
                return
            }
        }
        DiagnosticTransportGuard.setEmitterId(newId)
        Log.i(TAG, "emitter_id set to=${newId.name.lowercase()}")
    }

    private fun handleDualSimReport(context: Context) {
        // Reports default-data subscription's operator numeric per §8-Q9.
        // Reads via SubscriptionManager + per-subscription
        // TelephonyManager. No PII, no phone number, no ICCID.
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(defaultDataSubId)
        val operator = telephony.simOperator.orEmpty()
        Log.i(
            TAG,
            "dual_sim_report default_data_sub_id=$defaultDataSubId operator_numeric=$operator",
        )
    }

    private fun handleRestCapabilityProbe() {
        // Emit a marker event. The actual REST probe is a controlled
        // fail-closed matrix envelope, driven by the operator script
        // and observed via sender_rest_post_completed (see §9.6).
        // This subcommand only prints the intent so the operator
        // script can synchronise.
        Log.i(TAG, "rest_capability_probe_marker")
    }

    private fun handleHealth() {
        val guard = DiagnosticTransportGuard.current()
        val id = DiagnosticTransportGuard.currentEmitterId()
        Log.i(
            TAG,
            "health emitter_id=${id.name.lowercase()} pin=${guard.pin.name.lowercase()} " +
                "run_id=${guard.runId.ifEmpty { "-" }} cell_id=${guard.cellId.ifEmpty { "-" }}",
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
        internal const val SUB_REST_CAPABILITY_PROBE = "rest_capability_probe"
        internal const val SUB_HEALTH = "health"

        internal val ALLOWED_SUBCOMMANDS = setOf(
            SUB_PIN, SUB_SEND, SUB_CANARY, SUB_SET_EMITTER_ID,
            SUB_DUAL_SIM_REPORT, SUB_REST_CAPABILITY_PROBE, SUB_HEALTH,
        )

        internal val ALLOWED_PINS = setOf("none", "wss", "rest")

        // Every Bundle carries these two system keys; whitelisting
        // them prevents false rejections.
        private val ALLOWED_META_EXTRAS = setOf(
            "phantom_diagnostic_dummy_never_used", // reserved
        )

        internal val ALLOWED_EXTRAS_BY_SUBCOMMAND: Map<String, Set<String>> = mapOf(
            SUB_PIN to setOf(EXTRA_PIN, EXTRA_RUN_ID, EXTRA_CELL_ID),
            SUB_SEND to setOf(EXTRA_RUN_ID, EXTRA_CELL_ID, EXTRA_SEQUENCE),
            SUB_CANARY to emptySet(),
            SUB_SET_EMITTER_ID to setOf(EXTRA_EMITTER_ID),
            SUB_DUAL_SIM_REPORT to emptySet(),
            SUB_REST_CAPABILITY_PROBE to emptySet(),
            SUB_HEALTH to emptySet(),
        )

        private val RUN_ID_EXTRA = setOf('.', '_', '-')
        private val CELL_ID_EXTRA = setOf('.', '_', '-', ':')
    }
}
