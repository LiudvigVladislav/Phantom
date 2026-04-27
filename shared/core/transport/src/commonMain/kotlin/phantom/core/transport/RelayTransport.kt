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

    suspend fun connect(relayUrl: String, identityPublicKeyHex: String, token: String? = null)
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
}
