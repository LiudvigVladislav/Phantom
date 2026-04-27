// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

data class OutgoingMessage(
    val id: String,
    val conversationId: String,
    val recipientPublicKeyHex: String,
    val text: String,
)
