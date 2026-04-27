// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

sealed class TransportState {
    object Disconnected : TransportState()
    object Connecting : TransportState()
    object Connected : TransportState()
    data class Error(val cause: Throwable) : TransportState()
}
