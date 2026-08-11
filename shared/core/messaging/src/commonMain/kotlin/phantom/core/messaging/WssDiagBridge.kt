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
     * @param event one of the schema event names (see contract §4).
     * @param correlationId envelope UUID.
     * @param role SENDER or RECIPIENT — never coupled to device.
     * @param outcomeFlag terminal-event outcome; NONE for non-terminal events.
     * @param dedupGate only on `recipient_deliver_received`.
     */
    fun emit(
        event: String,
        correlationId: String,
        role: Role,
        outcomeFlag: OutcomeFlag = OutcomeFlag.NONE,
        dedupGate: DedupGate? = null,
    )
}

object WssDiagBridgeHolder {
    /** Set by Android debug boot init; null everywhere else. */
    @Volatile
    var instance: WssDiagBridge? = null
}
