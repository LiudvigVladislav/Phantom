// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory bus carrying live chunk-progress for voice_v2 upload / download
 * (PR-M2d.1b). Keyed by message row id (sender: localMsgId; receiver: mediaId
 * — since receiver rows use mediaId as PK).
 *
 * Pure UI/state plumbing — no DB writes, no transport, no behaviour change.
 * Cleared on completion or failure; on app restart the map is empty (the
 * underlying message row keeps the durable [AUDIO_DOWNLOADING] / [AUDIO:base64]
 * markers, so the bubble still renders correctly — just without the live N/M
 * counter).
 */
class MediaProgressBus {

    enum class Direction { UPLOAD, DOWNLOAD }

    data class Progress(
        val sent: Int,
        val total: Int,
        val direction: Direction,
    )

    private val _flow = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val flow: StateFlow<Map<String, Progress>> = _flow.asStateFlow()

    fun update(rowId: String, sent: Int, total: Int, direction: Direction) {
        _flow.update { it + (rowId to Progress(sent, total, direction)) }
    }

    fun clear(rowId: String) {
        _flow.update { it - rowId }
    }
}
