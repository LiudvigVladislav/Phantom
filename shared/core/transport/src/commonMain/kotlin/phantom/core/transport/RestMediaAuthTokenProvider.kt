// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * [MediaAuthTokenProvider] adapter backed by [RestFallbackOrchestrator] (PR-M1w).
 *
 * All CAS logic lives in [RestFallbackOrchestrator.acquireOrRefreshMediaToken];
 * this class is intentionally thin — it exists only to satisfy the interface
 * so [VoiceV2Sender] and the download orchestrator do not take a direct
 * dependency on [RestFallbackOrchestrator].
 */
class RestMediaAuthTokenProvider(
    private val orchestrator: RestFallbackOrchestrator,
) : MediaAuthTokenProvider {

    override suspend fun acquireToken(reason: String, staleToken: String?): String? =
        orchestrator.acquireOrRefreshMediaToken(reason, staleToken)
}
