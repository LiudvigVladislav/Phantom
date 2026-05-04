// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class GroupMemberInfo(val pubkeyHex: String, val username: String)

@Serializable
data class MessagePayload(
    val text: String = "",
    val sentAt: Long = 0L,
    val senderUsername: String = "",
    val type: String = TYPE_MESSAGE,        // see TYPE_* constants below
    val targetMessageId: String = "",       // delete | edit | reaction | pin
    val disappearingTimerSecs: Long? = null, // disappearing_timer
    val emoji: String? = null,              // reaction; empty string = remove
    val pinned: Boolean? = null,            // pin; true = pin, false = unpin

    // ── Group / Channel fields ────────────────────────────────────────────────
    val groupId: String? = null,
    val groupName: String? = null,
    val isChannel: Boolean? = null,
    val groupMembers: List<GroupMemberInfo>? = null,   // group_invite

    // ── SenderKey distribution ────────────────────────────────────────────────
    val senderKeyChainHex: String? = null,             // sender_key_distribution
    val senderKeyIter: Int? = null,
    val senderKeySignPubHex: String? = null,
    val groupCiphertextB64: String? = null,            // base64 SenderKey ciphertext in group_message

    // ── Voice / Audio ─────────────────────────────────────────────────────────
    val audioDataB64: String? = null,                  // base64 OGG audio for audio type
    val audioDurationMs: Long? = null,

    // ── Chunked audio (TYPE_AUDIO_CHUNK) ──────────────────────────────────────
    // Voice notes are split into 64 KB slices so each envelope finishes
    // uploading within the ~30 s Tecno HiOS reconnect window (ISSUE-013).
    val audioChunkId: String? = null,       // UUID grouping all chunks of one voice note
    val audioChunkIndex: Int? = null,       // 0-based position of this slice
    val audioChunkTotal: Int? = null,       // total number of slices for this note
    val audioChunkB64: String? = null,      // base64-encoded raw audio bytes for this slice
    val audioMimeType: String? = null,      // "audio/ogg" or "audio/m4a"

    // ── Voice Calls (WebRTC signalling over relay) ────────────────────────────
    val sdp: String? = null,                           // SDP offer or answer
    val iceCandidateJson: String? = null,              // JSON {"sdpMid":"...","sdpMLineIndex":0,"candidate":"..."}
    val callId: String? = null,                        // UUID for the call session

    // ── Key Rotation ──────────────────────────────────────────────────────────
    val newPublicKeyHex: String? = null,               // key_rotation: sender's new identity key
) {
    companion object {
        // 1:1 message types (pre-existing)
        const val TYPE_MESSAGE            = "message"
        const val TYPE_DELETE             = "delete"
        const val TYPE_EDIT               = "edit"
        const val TYPE_DISAPPEARING_TIMER = "disappearing_timer"
        const val TYPE_REACTION           = "reaction"
        const val TYPE_PIN                = "pin"

        // Group / Channel types (new)
        const val TYPE_GROUP_INVITE            = "group_invite"
        const val TYPE_SENDER_KEY_DISTRIBUTION = "sender_key_distribution"
        const val TYPE_GROUP_MESSAGE           = "group_message"
        const val TYPE_GROUP_ADD_MEMBER        = "group_add_member"
        const val TYPE_GROUP_LEAVE             = "group_leave"
        const val TYPE_CHANNEL_POST            = "channel_post"

        // Media types (new)
        const val TYPE_AUDIO = "audio"
        const val TYPE_AUDIO_CHUNK = "audio_chunk"

        // Key rotation — sender announces a new identity key
        const val TYPE_KEY_ROTATION = "key_rotation"

        // Voice call signalling — these are never stored as chat messages
        const val TYPE_CALL_OFFER  = "call_offer"
        const val TYPE_CALL_ANSWER = "call_answer"
        const val TYPE_CALL_ICE    = "call_ice"
        const val TYPE_CALL_HANGUP = "call_hangup"
        const val TYPE_CALL_REJECT = "call_reject"

        /** All call-signalling types that must be routed to CallManager, not stored. */
        val CALL_TYPES = setOf(
            TYPE_CALL_OFFER,
            TYPE_CALL_ANSWER,
            TYPE_CALL_ICE,
            TYPE_CALL_HANGUP,
            TYPE_CALL_REJECT,
        )

        /** All group-related types that must be routed to GroupMessagingService. */
        val GROUP_TYPES = setOf(
            TYPE_GROUP_INVITE,
            TYPE_SENDER_KEY_DISTRIBUTION,
            TYPE_GROUP_MESSAGE,
            TYPE_CHANNEL_POST,
            TYPE_GROUP_ADD_MEMBER,
            TYPE_GROUP_LEAVE,
        )
    }
}
