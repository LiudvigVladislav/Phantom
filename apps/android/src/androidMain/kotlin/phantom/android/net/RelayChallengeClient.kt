// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * N1-F2 R-N1.3 — the relay auth-challenge request, moved out of
 * `AppContainer`.
 *
 * The challenge GET carries the long-term identity in its query string.
 * R-N1.2 gated only `/auth/session`, so this request escaped the
 * authority entirely; the fix wraps the call site in
 * `RestEgressGate.dispatch("auth_challenge")` inside
 * [phantom.core.transport.RestFallbackOrchestrator], which is the one
 * place every platform's challenge lambda funnels through.
 *
 * The HTTP itself lives here rather than in `AppContainer` so the
 * dependency-injection container imports no HTTP client type at all —
 * `ProductHttpSinkInventoryTest` enforces that, with no per-file
 * exception to erode.
 *
 * The client is the long-lived Ktor REST client (HTTP/1.1 pinned by
 * PR-G4), which is suspend-based: unlike the native OkHttp adapters, a
 * cancelled coroutine does abort the request, so this path needs no
 * separate cancellation handle.
 */
class RelayChallengeClient(
    private val httpClient: HttpClient,
    private val relayHttpBase: String,
) {

    /**
     * Fetch a challenge nonce for [identityHex].
     *
     * Throws on a non-2xx response or a malformed body so the
     * orchestrator surfaces it as `session_challenge_fail` and the next
     * bootstrap attempt tries again.
     */
    suspend fun fetchNonceHex(identityHex: String): String {
        val resp = httpClient.get("$relayHttpBase/auth/challenge?identity=$identityHex")
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            error("auth/challenge non-2xx: ${resp.status.value} body=${text.take(120)}")
        }
        val nonceMatch = NONCE_REGEX.find(text)
            ?: error("auth/challenge response missing nonce_hex: ${text.take(120)}")
        return nonceMatch.groupValues[1]
    }

    private companion object {
        val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
    }
}
