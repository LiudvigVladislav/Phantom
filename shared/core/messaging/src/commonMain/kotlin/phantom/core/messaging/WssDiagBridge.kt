// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/**
 * Direct WSS Yota-First diagnostic — bridge from shared messaging
 * code to Android-side structured logging without a KMP dependency
 * inversion.
 *
 * `DefaultMessagingService` (commonMain) calls
 * `WssDiagBridgeHolder.instance?.emit(...)`. In production and on
 * every non-Android target, `instance` is `null` and the call is a
 * cheap null-check — zero overhead, no behaviour change.
 *
 * The Android debug variant installs an implementation at process
 * start (see `apps/android/src/debug/kotlin/.../DiagnosticBootInit.kt`)
 * that routes each call to `phantom.android.diagnostic.WssDiag`.
 *
 * Fields intentionally mirror the WssDiag schema (§4 contract). No
 * PII, no key material, no message text can flow through this
 * interface — the enum-only parameter surface enforces the schema.
 */
interface WssDiagBridge {

    enum class Role { SENDER, RECIPIENT }

    enum class OutcomeFlag {
        SENDER_RELAY_ACK_DELIVERED,
        SENDER_RELAY_ACK_RELAYED,
        NONE,
    }

    enum class DedupGate { FRESH, DUPLICATE, REACK, UNKNOWN }

    /**
     * Audit ROUND-30.16 — why a delivery ended without settling the
     * envelope, on `recipient_deliver_failed`.
     *
     * The 2026-08-26 physical smoke produced two
     * `recipient_deliver_received dedup_gate=fresh` for one envelope and
     * nothing else: no persist, no ack, twice. The reason WAS written at
     * run time, under the Android tag `PhantomMessaging`, and was
     * discarded twice over — first by the smoke capture filter, then by
     * ring-buffer eviction. Nothing in the shipped evidence stream said
     * a delivery had failed at all.
     *
     * These members are deliberately coarse. They say what is knowable
     * at the handler BOUNDARY without inspecting a decrypt result, a
     * session or a ratchet, so classifying a failure never requires
     * reading cryptographic state and this enum can never carry it.
     */
    enum class DeliverFailure {
        /** An exception escaped the handler. */
        THREW,

        /**
         * The handler returned normally, the envelope is unsettled, and
         * a held row for it exists in the decrypt-failed store. That is
         * the signature the hold path leaves: it returns without
         * throwing, without acking and without `markProcessed`.
         */
        HELD,

        /**
         * Unsettled, nothing threw, and no held row confirms why.
         * Fail-closed: an unexplained delivery is reported as a failure
         * rather than assumed benign, because the state that would
         * explain it may simply not have been written.
         */
        UNKNOWN_PROCESSING_FAILURE,
    }

    /**
     * Audit ROUND-30.16 — the furthest stage a delivery reached, on
     * `recipient_deliver_failed`.
     *
     * Ordered. Each member means every earlier one also completed, so
     * the reader learns where the path stopped without any branch having
     * to report itself.
     */
    enum class DeliverStage {
        /** Claimed and past the dedup gate; no plaintext yet. */
        RECEIVED,

        /** Plaintext was produced. */
        DECRYPTED,

        /** The message row exists. */
        PERSISTED,

        /** The processed-envelope ledger records it. */
        LEDGER_MARKED,

        /** The delivery ack was handed to the transport. */
        ACK_SENT,
    }

    /**
     * @param event one of the schema event names (see contract §4).
     * @param correlationId envelope UUID.
     * @param role SENDER or RECIPIENT — never coupled to device.
     * @param outcomeFlag terminal-event outcome; NONE for non-terminal events.
     * @param dedupGate only on `recipient_deliver_received`.
     * @param deliverFailure only on `recipient_deliver_failed`.
     * @param deliverStage only on `recipient_deliver_failed`.
     * @param attempt Audit ROUND-30.17 — which processing ATTEMPT for
     *   this envelope the event belongs to.
     *
     *   R30.16 counted records per envelope and so could not describe
     *   its own motivating run: a relay redelivery produces a SECOND
     *   fresh attempt, and the verifier read the second terminal record
     *   as evidence corruption. An envelope is not the unit of outcome;
     *   an attempt is. This ordinal is what binds a terminal record to
     *   the delivery it terminates.
     *
     *   It is a small integer and carries nothing else — no time, no
     *   identity, no content — so it does not widen what this interface
     *   can leak.
     */
    fun emit(
        event: String,
        correlationId: String,
        role: Role,
        outcomeFlag: OutcomeFlag = OutcomeFlag.NONE,
        dedupGate: DedupGate? = null,
        deliverFailure: DeliverFailure? = null,
        deliverStage: DeliverStage? = null,
        attempt: Int? = null,
    )
}

/**
 * Audit ROUND-30.18 — the attempt ordinal's range, named once.
 *
 * R30.17 left the producer unbounded while the verifier rejected
 * anything above a limit it had invented for itself, so the two ends of
 * one field disagreed about its domain. This is that domain, and the
 * schema authority `schema_wss3.DELIVER_ATTEMPT_ORDINAL_MAX` carries
 * the same number for the verifier.
 *
 * Reaching it means one envelope was redelivered this many times
 * without ever settling — a redelivery loop, not a delivery. Both ends
 * report it rather than wrapping or clamping.
 */
const val DELIVER_ATTEMPT_ORDINAL_MAX: Int = 1000

object WssDiagBridgeHolder {
    /** Set by Android debug boot init; null everywhere else. */
    @Volatile
    var instance: WssDiagBridge? = null
}
