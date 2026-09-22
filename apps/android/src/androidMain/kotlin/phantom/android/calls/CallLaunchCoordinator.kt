// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

/**
 * Keeps the originating Compose scope alive until call setup has produced an
 * observable call. Navigating first disposes that scope and cancels suspended
 * setup work such as the TURN credential request.
 */
internal suspend fun startCallBeforeNavigation(
    startCall: suspend () -> Unit,
    hasActiveCall: () -> Boolean,
    navigate: () -> Unit,
): Boolean {
    startCall()
    if (!hasActiveCall()) return false
    navigate()
    return true
}
