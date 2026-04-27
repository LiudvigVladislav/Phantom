// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

enum class CallState {
    IDLE,       // no active call
    CALLING,    // we initiated, waiting for answer
    RINGING,    // incoming call, waiting for local answer
    IN_CALL,    // call established
    ENDED,      // call terminated normally
    REJECTED,   // call was rejected by either party
}

data class ActiveCall(
    val callId: String,
    val remotePubKeyHex: String,
    val remoteUsername: String,
    val state: CallState,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val startedAt: Long? = null,
)
