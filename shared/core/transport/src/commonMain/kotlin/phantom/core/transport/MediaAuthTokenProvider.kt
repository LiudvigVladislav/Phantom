// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * CAS-safe bearer token provider for media chunk upload/download (PR-M1w).
 *
 * Callers pass a [staleToken] when the previous attempt returned a 401 so the
 * provider can decide whether a network round-trip is needed or whether another
 * concurrent caller already refreshed. This eliminates the "pinball" race
 * described in the D1c.1 post-mortem: two concurrent 401s each force a refresh
 * and the second invalidates the first.
 *
 * Auth-refresh logic lives entirely in the caller loop ([VoiceV2Sender] /
 * download orchestrator). [KtorMediaUploadTransport] never calls this — it
 * receives a token verbatim at call time.
 */
interface MediaAuthTokenProvider {

    /**
     * Acquire a valid bearer session token.
     *
     * @param reason  Debug string ("media_upload" / "media_download" / …) for log lines.
     * @param staleToken If non-null, caller reports this token was just rejected with 401.
     *   Provider MUST return a strictly different token (refresh if needed).
     *   If null, caller wants whatever cached token is fresh — cheap path, no network
     *   call unless cache is empty or expired.
     * @return New or cached token, or null on terminal auth failure (exhausted retries).
     */
    suspend fun acquireToken(reason: String, staleToken: String? = null): String?
}
