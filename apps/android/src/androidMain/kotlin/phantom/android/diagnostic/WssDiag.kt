// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.os.SystemClock
import android.util.Log

/**
 * Direct WSS Yota-First diagnostic — structured `WSS_DIAG` log helper.
 * Every event flows through [emit] to guarantee the field schema is
 * consistent across production sites + focused tests.
 *
 * Contract sheet §4 defines the field set and forbids arbitrary
 * `extra` tokens. This helper enforces the schema by accepting only
 * named fields.
 *
 * The tag `WSS_DIAG` is the SOLE tag the operator's capture script
 * reads (§9.2). Nothing else on this tag is expected.
 */
object WssDiag {

    const val TAG: String = "WSS_DIAG"

    /**
     * §PR-review-round-1 P0 — release-inert-by-default gate.
     *
     * Every [emit] first checks this flag. When `false` (the
     * default), emit returns immediately BEFORE building the
     * schema string or calling `Log.i`. Release APKs never flip
     * this bit: only `DiagnosticBootInitProvider.onCreate`
     * (debug source set) sets it to `true`, and that provider is
     * physically absent from release manifests.
     *
     * The `testSink` branch stays active regardless of `isActive`
     * so a JVM unit test can drive [emit] and observe payloads
     * without registering the debug provider.
     */
    @Volatile
    var isActive: Boolean = false

    /**
     * §12 Round-8 audit P1 test seam. When non-null, each emit
     * invokes this to capture the exact `key=value key=value …`
     * payload for ORDER assertions (e.g. proving
     * `diagnostic_send_dispatched` fires BEFORE a suspending
     * `sendMessage` returns). Production paths leave it null →
     * zero overhead. Reset to `null` after every test.
     */
    @Volatile
    var testSink: ((String) -> Unit)? = null

    /**
     * `role` field values — derived per event site, NOT per device
     * direction. `phone` and `emulator` are `emitter_id` values,
     * NEVER `role` values.
     */
    enum class Role { SENDER, RECIPIENT, RELAY, MATRIX }

    /**
     * `outer_transport` — outer arm actually selected by
     * `TransportManager`. Emitted on `sender_transport_decision`
     * per §11 lock 2 (Method-b fail-closed).
     */
    enum class OuterTransport { DIRECT, REALITY, TOR, UNKNOWN }

    /**
     * `inner_route` — inner send path within the outer arm.
     * Emitted on `sender_transport_decision`,
     * `sender_wss_frame_written`, `sender_rest_post_completed`,
     * `sender_relay_ack_received`.
     */
    enum class InnerRoute { WSS, REST, UNKNOWN }

    /** Terminal event outcome flag. `unresolved_120s_marker` is DELIBERATELY absent — that classification is verifier-side only per §2. */
    enum class OutcomeFlag {
        SENDER_RELAY_ACK_DELIVERED,
        SENDER_RELAY_ACK_RELAYED,
        QUEUED_FOR_RECONNECT,
        DROPPED_BY_CAPABILITY,
        SEND_ERROR,
        NONE,
    }

    /** REST-side acceptance semantics on `sender_rest_post_completed`. */
    enum class RelayAcceptance {
        ACCEPTED,
        DUPLICATE,
        FAILED,
        DISABLED_BY_CAPABILITY,
        UNKNOWN,
    }

    /** Recipient dedup gate outcome on `recipient_deliver_received`. */
    enum class DedupGate { FRESH, DUPLICATE, REACK, UNKNOWN }

    /**
     * Audit ROUND-30.16 — why a delivery ended without settling the
     * envelope, on `recipient_deliver_failed`. Closed set; never carries
     * an exception message or any decrypted content.
     */
    enum class DeliverFailure { THREW, HELD, UNKNOWN_PROCESSING_FAILURE }

    /** Audit ROUND-30.16 — furthest stage reached. Ordered. */
    enum class DeliverStage { RECEIVED, DECRYPTED, PERSISTED, LEDGER_MARKED, ACK_SENT }

    /**
     * Emit a `WSS_DIAG` event. `Log.i` for machine parseability;
     * production severity so operator captures don't drop it.
     *
     * All null-optional fields are omitted from the emitted line to
     * keep the parse deterministic — verifiers key on presence.
     */
    fun emit(
        event: String,
        role: Role,
        correlationId: String? = null,
        attempt: Int? = null,
        outerTransport: OuterTransport? = null,
        innerRoute: InnerRoute? = null,
        dedupGate: DedupGate? = null,
        deliverFailure: DeliverFailure? = null,
        deliverStage: DeliverStage? = null,
        outcomeFlag: OutcomeFlag? = null,
        relayAcceptance: RelayAcceptance? = null,
        sessionEpoch: Long? = null,
        dispatched: Boolean? = null,
        pin: String? = null,
        emitterIdOverride: String? = null,
        restored: Boolean? = null,
        sequence: Int? = null,
        result: String? = null,
    ) {
        // §PR-review-round-1 P0 — release-inert-by-default. In
        // release APKs neither the debug boot-init provider nor a
        // test sink is registered, so this early return fires
        // BEFORE we assemble the schema string or touch `Log.i`.
        // Every caller (including `HybridRelayTransport.send` on
        // production paths under `Pin.NONE`) becomes a
        // read-two-@Volatile no-op in release.
        val sink = testSink
        if (!isActive && sink == null) return
        val state = DiagnosticTransportGuard.current()
        val emitterId = emitterIdOverride ?: DiagnosticTransportGuard.currentEmitterId().name.lowercase()
        val fields = buildString {
            append("event=").append(event)
            append(' ').append("role=").append(role.name.lowercase())
            append(' ').append("emitter_id=").append(emitterId)
            append(' ').append("run_id=").append(state.runId.ifEmpty { "-" })
            append(' ').append("cell_id=").append(state.cellId.ifEmpty { "-" })
            append(' ').append("wall_utc_ms=").append(System.currentTimeMillis())
            append(' ').append("monotonic_ms=").append(SystemClock.elapsedRealtime())
            if (correlationId != null) append(' ').append("correlation_id=").append(correlationId)
            if (attempt != null) append(' ').append("attempt=").append(attempt)
            if (outerTransport != null) append(' ').append("outer_transport=").append(outerTransport.name.lowercase())
            if (innerRoute != null) append(' ').append("inner_route=").append(innerRoute.name.lowercase())
            if (dedupGate != null) append(' ').append("dedup_gate=").append(dedupGate.name.lowercase())
            if (deliverFailure != null)
                append(' ').append("deliver_failure=").append(deliverFailure.name.lowercase())
            if (deliverStage != null)
                append(' ').append("deliver_stage=").append(deliverStage.name.lowercase())
            if (outcomeFlag != null && outcomeFlag != OutcomeFlag.NONE)
                append(' ').append("outcome_flag=").append(outcomeFlag.name.lowercase())
            if (relayAcceptance != null) append(' ').append("relay_acceptance=").append(relayAcceptance.name.lowercase())
            if (sessionEpoch != null) append(' ').append("session_epoch=").append(sessionEpoch)
            if (dispatched != null) append(' ').append("dispatched=").append(dispatched)
            if (pin != null) append(' ').append("pin=").append(pin)
            if (restored != null) append(' ').append("restored=").append(restored)
            if (sequence != null) append(' ').append("sequence=").append(sequence)
            if (result != null) append(' ').append("result=").append(result)
        }
        if (sink != null) {
            // Test path — bypass Android's Log entirely so JVM unit
            // tests (no Robolectric) do not hit
            // `Log.println_native` UnsatisfiedLinkError.
            sink.invoke(fields)
        } else {
            // Only reachable when isActive == true (see early
            // return above), i.e. inside a debug APK whose
            // DiagnosticBootInitProvider ran.
            Log.i(TAG, fields)
        }
        BackgroundTrace.observe("wss", fields)
    }
}
