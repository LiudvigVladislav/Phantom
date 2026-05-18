// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract tests for the [MediaAuthTokenProvider] CAS interface (PR-M1w, Q3).
 *
 * Exercises [FakeMediaAuthTokenProvider] — the in-memory stub used by
 * messaging-module tests. The real [RestMediaAuthTokenProvider] delegates
 * directly to [RestFallbackOrchestrator.acquireOrRefreshMediaToken] whose
 * CAS semantics are already covered by [RestFallbackOrchestratorTest].
 */
class RestMediaAuthTokenProviderTest {

    @Test
    fun nullStaleToken_returnsCachedToken() = runTest {
        val provider = FakeMediaAuthTokenProvider(token = "tok-1")
        val result = provider.acquireToken(reason = "media_upload", staleToken = null)
        assertEquals("tok-1", result)
    }

    @Test
    fun staleToken_matchesCached_triggersRefresh_returnsNewToken() = runTest {
        val provider = FakeMediaAuthTokenProvider(token = "tok-1")
        // Simulate 401: caller passes the stale token; provider must return something different.
        provider.nextToken = "tok-2"
        val result = provider.acquireToken(reason = "media_upload", staleToken = "tok-1")
        assertEquals("tok-2", result)
    }

    @Test
    fun staleToken_differFromCached_casPath_returnsCachedWithoutRefresh() = runTest {
        // Another concurrent caller already refreshed: cached != staleToken.
        // Provider returns cached without issuing another refresh.
        val provider = FakeMediaAuthTokenProvider(token = "tok-fresh")
        var refreshCalled = false
        provider.onRefresh = { refreshCalled = true; "tok-newer" }
        val result = provider.acquireToken(reason = "media_upload", staleToken = "tok-dead-old")
        assertEquals("tok-fresh", result)  // cached — not the stale caller's token
        // Refresh must NOT have been triggered (CAS-reuse path).
        assertEquals(false, refreshCalled)
    }

    @Test
    fun terminalAuthFailure_returnsNull() = runTest {
        val provider = FakeMediaAuthTokenProvider(token = null)
        val result = provider.acquireToken(reason = "media_upload")
        assertNull(result)
    }
}

// ── Fake implementation for tests ─────────────────────────────────────────────

/**
 * In-memory [MediaAuthTokenProvider] for messaging-module and transport-module
 * tests. Exposed at module level so [VoiceV2Sender] tests can import it.
 *
 * CAS semantics:
 * - If [staleToken] == null → return [token] (cached path).
 * - If [staleToken] != null && [staleToken] != [token] → CAS reuse: return [token]
 *   without calling [onRefresh].
 * - If [staleToken] != null && [staleToken] == [token] → refresh: call [onRefresh]
 *   (or return [nextToken] if set), then update [token].
 */
class FakeMediaAuthTokenProvider(
    var token: String?,
) : MediaAuthTokenProvider {

    /** Pre-set next token returned after a refresh. Falls back to [token] if null. */
    var nextToken: String? = null

    /** Optional side-effecting hook called when a refresh is triggered. */
    var onRefresh: (() -> String?)? = null

    override suspend fun acquireToken(reason: String, staleToken: String?): String? {
        if (staleToken == null) return token
        // CAS: stale differs from cached → another caller already refreshed; return cached.
        if (staleToken != token) return token
        // Refresh path.
        val refreshed = onRefresh?.invoke() ?: nextToken ?: return null
        token = refreshed
        return token
    }
}
