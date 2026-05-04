// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RelayTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<RelayMessage.Deliver>
    val acks: Flow<RelayMessage.Ack>
    val readReceipts: Flow<RelayMessage.ReadReceipt>

    /**
     * Emits the sender's pubKeyHex each time a "typing" event arrives from the relay.
     * These events are ephemeral — never stored or encrypted.
     */
    val typingEvents: SharedFlow<String>

    /**
     * Open the WebSocket and run the reconnect loop until [disconnect] is
     * called. Idempotent at the call-site level — calling `connect` twice
     * cancels the prior reconnect job before starting a new one.
     *
     * @param relayUrl the WebSocket URL to dial. For direct WSS this is the
     *   public hostname (e.g. `wss://relay.phntm.pro/ws`); when [socksProxyPort]
     *   is non-null, callers usually pass the relay's onion address (plain
     *   `ws://…onion/ws`, plaintext over Tor by ADR-016 design).
     * @param identityPublicKeyHex the local identity, appended as `?id=…`
     *   so the relay can dispatch envelopes to the right queue.
     * @param token optional auth token, appended as `&token=…` after id.
     * @param socksProxyPort if non-null, route the WebSocket TCP connection
     *   through `127.0.0.1:<port>` over SOCKS5 (typically the embedded
     *   Tor's auto-bound port from [TorService.state]). Null = direct.
     *   Privacy-Mode changes translate to disconnect → reconnect with a
     *   new value here.
     */
    suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        token: String? = null,
        socksProxyPort: Int? = null,
    )
    suspend fun disconnect()
    suspend fun send(message: RelayMessage.Send): Boolean
    suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean

    /**
     * Tells the relay that [messageId] has been fully processed on the recipient.
     * The relay removes that envelope from its per-recipient store. Best-effort —
     * if the WS is not connected the call is enqueued and retried on reconnect.
     */
    suspend fun sendDeliveryAck(messageId: String): Boolean

    /**
     * Sends an ephemeral typing notification to [toPubKeyHex].
     * The relay forwards it live if the recipient is online, drops it silently otherwise.
     * Returns false if not connected.
     */
    suspend fun sendTyping(toPubKeyHex: String): Boolean

    fun isConnected(): Boolean

    /**
     * Milliseconds elapsed since the last Pong frame was received from the
     * relay. ADR-011 — used by the AlarmManager-driven wakeup receiver to
     * detect a parked-radio dead WebSocket. A fresh value (< ~25 s) means
     * the connection is healthy; a stale value means the OS has parked the
     * Wi-Fi radio and the receiver should call [forceReconnect].
     *
     * Initial value (before the first connect) is conventionally large
     * (effectively "infinitely stale") so the first scheduled alarm fire
     * after cold start triggers a reconnect attempt.
     */
    val lastPongElapsedMs: Long

    /**
     * Force the current reconnect generation to tear down and start over.
     * ADR-011 — invoked by [phantom.android.service.PhantomWakeupReceiver]
     * (Android only) when AlarmManager fires and detects a stale pong.
     *
     * Idempotent: cancelling an already-cancelled per-generation scope is
     * a no-op in coroutines, so concurrent calls (e.g. ack watchdog and
     * alarm receiver firing within the same second) collapse safely into
     * a single reconnect.
     */
    suspend fun forceReconnect()
}
