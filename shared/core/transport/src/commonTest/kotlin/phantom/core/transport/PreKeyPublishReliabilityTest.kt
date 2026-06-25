// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for PR-R0 / PR-R0.1 reliability additions to
 * [PreKeyApiClient.publishBundle]:
 *
 *  1. Mutex debounce — a second concurrent publish is dropped silently.
 *  2. Retry on SocketTimeoutException, gives up after [PreKeyApiClient.PUBLISH_MAX_ATTEMPTS].
 *  3. No retry on 422 Unprocessable Entity (bad request variant).
 *  4. Exponential backoff between retry attempts (virtual time via TestScope).
 *  5. Success on second attempt after one timeout.
 *  6. HTTP 408 triggers retry, HTTP 400 does not.
 *
 * PR-R0.1: tests now use [FakePreKeyPublishHttpTransport] instead of Ktor
 * MockEngine — the publish path no longer goes through Ktor at all.
 * No OkHttp, no Android runtime required — runs in commonTest / JVM.
 */
class PreKeyPublishReliabilityTest {

    // ── Fake transports ───────────────────────────────────────────────────────

    /**
     * Scriptable fake transport that calls [script] on each publish attempt.
     * The script receives the 1-based attempt index and returns a response
     * or throws. Tracks total call count for assertions.
     */
    private class FakePreKeyPublishHttpTransport(
        private val script: suspend (attempt: Int) -> PreKeyPublishHttpResponse,
    ) : PreKeyPublishHttpTransport {
        var callCount: Int = 0
        var lastRequestId: String = ""
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse {
            callCount++
            lastRequestId = requestId
            return script(callCount)
        }
    }

    /**
     * Fake transport whose script may throw instead of returning a response.
     * Used for SocketTimeoutException scenarios.
     */
    private class ThrowingFakePublishTransport(
        private val script: suspend (attempt: Int) -> PreKeyPublishHttpResponse,
    ) : PreKeyPublishHttpTransport {
        var callCount: Int = 0
        var lastRequestId: String = ""
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse {
            callCount++
            lastRequestId = requestId
            return script(callCount) // script may throw
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sampleRequest() = PublishRequest(
        identity_pubkey_hex = "aa".repeat(32),
        signing_pubkey_hex = "bb".repeat(32),
        signed_pre_key = WireSignedPreKey(
            key_id = 1L,
            public_key_hex = "cc".repeat(32),
            created_at_ms = 100L,
            signature_hex = "dd".repeat(64),
        ),
        one_time_pre_keys = emptyList(),
    )

    /** Minimal Ktor client for the non-publish paths (fetchBundle, fetchStatus).
     *  None of the reliability tests exercise those paths, so a never-called
     *  mock is fine here. */
    private val unusedKtorClient: HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { _ ->
                respond("", HttpStatusCode.OK)
            }
        }
    }

    // ── Test 0: T2 diag client-side trace gate (2026-06-16 Option A Item 3) ──

    /**
     * The `t2DiagPublishTraceEnabled` constructor flag controls whether
     * the `T2_DIAG_PUBLISH_TRACE` log lines are emitted at attempt
     * start / response / exception. The default is `false` so unit tests
     * and any non-Android consumer see no new trace surface.
     *
     * This test verifies the contract at the constructor + behaviour
     * level: with either flag value the publish flow completes
     * successfully and the underlying transport receives exactly one
     * call. The actual log emission shape is platform-specific (the
     * `relayLog` expect function dispatches to `android.util.Log` on
     * Android and stdout on JVM), so verifying the exact log text from
     * commonTest would require a custom log sink the production code
     * doesn't carry. The operator-facing contract that this gate works
     * correctly is verified at deploy time by inspecting logcat for the
     * `T2_DIAG_PUBLISH_TRACE` prefix.
     */
    @Test
    fun publishBundle_succeeds_with_t2DiagPublishTraceEnabled_true() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = """{"stored_opks": 5}""",
                elapsedMs = 1L,
                protocol = "h2",
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
            t2DiagPublishTraceEnabled = true,
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(result is PublishResult.Stored)
        assertEquals(5, (result as PublishResult.Stored).storedOpks)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun publishBundle_plumbs_t2DiagRequestId_through_to_transport_when_trace_enabled() = runTest {
        // T2 diagnostic round 2 (2026-06-16) — verify the `requestId`
        // generated inside `publishWithRetry` is plumbed through to
        // the `PreKeyPublishHttpTransport.publish(requestId = …)`
        // call, so the Android impl's five `T2_PUBLISH_PHASE` log
        // lines can correlate with `T2_DIAG_PUBLISH_TRACE` lines by
        // exact id (not by timestamp).
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = """{"stored_opks": 5}""",
                elapsedMs = 1L,
                protocol = "h2",
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
            t2DiagPublishTraceEnabled = true,
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(result is PublishResult.Stored)
        assertEquals(5, (result as PublishResult.Stored).storedOpks)
        // The id format is 16 hex chars (epoch-ms low 32 + Random low
        // 32). Concrete value is non-deterministic; assert the SHAPE.
        assertEquals(
            16, transport.lastRequestId.length,
            "T2 diag request_id MUST be 16 hex chars when t2DiagPublishTraceEnabled = true; got '${transport.lastRequestId}'",
        )
        assertTrue(
            transport.lastRequestId.all { it.isDigit() || it in 'a'..'f' },
            "T2 diag request_id MUST be lowercase hex; got '${transport.lastRequestId}'",
        )
    }

    @Test
    fun publishBundle_plumbs_empty_t2DiagRequestId_when_trace_disabled() = runTest {
        // Inverse contract: with the gate off, the transport receives
        // an empty string for `requestId` and emits no phase trace.
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = """{"stored_opks": 3}""",
                elapsedMs = 1L,
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
            // t2DiagPublishTraceEnabled omitted — default false.
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(result is PublishResult.Stored)
        assertEquals(
            "", transport.lastRequestId,
            "T2 diag request_id MUST be empty when t2DiagPublishTraceEnabled = false (default); got '${transport.lastRequestId}'",
        )
    }

    @Test
    fun publishBundle_succeeds_with_t2DiagPublishTraceEnabled_default_false() = runTest {
        // Default-constructed PreKeyApiClient does NOT receive the trace
        // flag — verify the existing publish path is unchanged.
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = """{"stored_opks": 7}""",
                elapsedMs = 1L,
                // No protocol field — null. Default constructor of
                // PreKeyPublishHttpResponse leaves it null, mirroring
                // the JVM stub and pre-Item-3 behaviour.
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
            // t2DiagPublishTraceEnabled omitted — default `false`.
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(result is PublishResult.Stored)
        assertEquals(7, (result as PublishResult.Stored).storedOpks)
        assertEquals(1, transport.callCount)
    }

    // ── Test 1: mutex deduplicates parallel calls ─────────────────────────────

    /**
     * Debounce test (RC-PREKEY-PUBLISH-DEBOUNCE-RACE 2026-06-25):
     * when publishMutex is already locked (simulating an in-flight
     * publish), a subsequent `publishBundle` call with the default
     * `forceJoinInFlight = false` returns [PublishResult.Deferred]
     * without dispatching any transport call.
     *
     * Pre-fix the implementation returned a synthetic `Stored(0)` here,
     * which downstream consumers logged as `upload_ok stored_opks=0`.
     * The mini-lock invariant Inv-NoFalseSuccess REJECTS that shape —
     * the debounce result must be honest, not synthesised as a storage
     * success.
     */
    @Test
    fun publishBundle_mutex_default_returnsDeferred_not_Stored() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(201, """{"stored_opks": 5}""", elapsedMs = 1L)
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        // Simulate a concurrent in-flight publish by acquiring the mutex directly.
        api.publishMutex.lock()

        val debounced = api.publishBundle { sampleRequest() }

        assertTrue(
            debounced is PublishResult.Deferred,
            "default-debounce result must be PublishResult.Deferred " +
                "(Inv-NoFalseSuccess) — got $debounced",
        )
        assertEquals(0, transport.callCount,
            "no transport call should fire when mutex is locked (debounce path)")

        // Release the lock and verify a normal call succeeds.
        api.publishMutex.unlock()

        val real = api.publishBundle { sampleRequest() }
        assertTrue(real is PublishResult.Stored)
        assertEquals(5, (real as PublishResult.Stored).storedOpks,
            "real call after mutex released must return server response")
        assertEquals(1, transport.callCount, "exactly one transport call dispatched for the real call")
    }

    /**
     * Force-join path (RC-PREKEY-PUBLISH-DEBOUNCE-RACE 2026-06-25):
     * when called with `forceJoinInFlight = true`, a publishMutex hit
     * MUST NOT short-circuit with [PublishResult.Deferred]. The call
     * blocks on `publishMutex.lock()`, waits for the in-flight publish
     * to release the mutex, and then runs its own publish attempt.
     *
     * This is the load-bearing path for the verify-driven republish in
     * [PreKeyLifecycleService.verifyBundleOnRelay]: the relay reported
     * zero record, so the republish MUST reach the relay regardless of
     * the in-flight publish's outcome.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun publishBundle_mutex_forceJoinInFlight_waitsAndPublishes() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(201, """{"stored_opks": 9}""", elapsedMs = 1L)
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        // Hold the mutex to simulate an in-flight publish, then start
        // the force-join call concurrently. The call must NOT return
        // synchronously — it must suspend on `publishMutex.lock()`
        // until we release the mutex below.
        api.publishMutex.lock()

        val deferred = async {
            api.publishBundle(forceJoinInFlight = true) { sampleRequest() }
        }

        // Let the launched coroutine run up to the suspension point on
        // `publishMutex.lock()`. The force-join branch is supposed to
        // suspend here — the test scheduler will go idle once it does.
        advanceUntilIdle()
        assertTrue(
            deferred.isActive,
            "force-join publishBundle must SUSPEND on publishMutex while another " +
                "publish holds the mutex (not short-circuit with Deferred)",
        )
        assertEquals(
            0, transport.callCount,
            "no transport call may fire while the mutex is still held",
        )

        // Release the in-flight publish — the force-join call now
        // acquires the mutex and runs its own publish attempt.
        api.publishMutex.unlock()
        val result = deferred.await()
        assertTrue(
            result is PublishResult.Stored,
            "force-join must complete with a real Stored result; got $result",
        )
        assertEquals(
            9, (result as PublishResult.Stored).storedOpks,
            "force-join must reflect the server response (storedOpks=9)",
        )
        assertEquals(
            1, transport.callCount,
            "force-join must dispatch exactly one transport call AFTER the " +
                "mutex was released",
        )
    }

    // ── Test 2: retry on SocketTimeoutException, gives up after 3 attempts ───

    @Test
    fun publishBundle_retries_on_SocketTimeoutException_givesUpAfter3() = runTest {
        val transport = ThrowingFakePublishTransport { _ ->
            throw java.net.SocketTimeoutException("Read timed out")
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        var thrown: Throwable? = null
        try {
            api.publishBundle { sampleRequest() }
            fail("expected SocketTimeoutException to propagate after all retries")
        } catch (e: java.net.SocketTimeoutException) {
            thrown = e
        }

        assertEquals(PreKeyApiClient.PUBLISH_MAX_ATTEMPTS, transport.callCount,
            "should attempt exactly ${PreKeyApiClient.PUBLISH_MAX_ATTEMPTS} times total")
        assertTrue(thrown != null, "original exception must propagate")
    }

    // ── Test 3: no retry on 422 ───────────────────────────────────────────────

    @Test
    fun publishBundle_doesNotRetry_on_422() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(422, """{"error":"invalid signing key format"}""", elapsedMs = 1L)
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val result = api.publishBundle { sampleRequest() }

        // 422 maps to Unexpected(422) — not a retryable status.
        assertTrue(result is PublishResult.Failure)
        val reason = (result as PublishResult.Failure).reason
        assertTrue(reason is PublishResult.Reason.Unexpected)
        assertEquals(422, (reason as PublishResult.Reason.Unexpected).httpStatus)

        assertEquals(1, transport.callCount, "422 must not trigger retries")
    }

    // ── Test 4: exponential backoff between retries (virtual time) ────────────

    /**
     * Verifies that publishBundle completes all retry attempts (spending the
     * inter-retry delays) under kotlinx-coroutines-test's virtual-time
     * dispatcher. Virtual time auto-advances through `delay()` calls, so
     * this test finishes in ~0 real ms regardless of the delay values.
     *
     * Verifies:
     *   a) All PUBLISH_MAX_ATTEMPTS were dispatched (retry loop ran fully).
     *   b) The test completed (meaning the delays are coroutine-friendly
     *      `delay()` calls, not `Thread.sleep()` which blocks virtual time).
     *   c) The total virtual time elapsed matches sum of PUBLISH_RETRY_DELAYS_MS.
     */
    @Test
    fun publishBundle_exponentialBackoff_betweenRetries() = runTest {
        val transport = ThrowingFakePublishTransport { _ ->
            throw java.net.SocketTimeoutException("Read timed out")
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val tBefore = testScheduler.currentTime
        try {
            api.publishBundle { sampleRequest() }
        } catch (ignored: java.net.SocketTimeoutException) {
            // Expected — all retries exhausted.
        }
        val elapsed = testScheduler.currentTime - tBefore

        assertEquals(
            expected = PreKeyApiClient.PUBLISH_MAX_ATTEMPTS,
            actual = transport.callCount,
            message = "all ${PreKeyApiClient.PUBLISH_MAX_ATTEMPTS} attempts must fire",
        )

        // Total virtual time = sum of (PUBLISH_MAX_ATTEMPTS - 1) inter-retry delays.
        val retryCount = PreKeyApiClient.PUBLISH_MAX_ATTEMPTS - 1
        val expectedTotalDelay: Long = (0 until retryCount).sumOf { i ->
            PreKeyApiClient.PUBLISH_RETRY_DELAYS_MS[i]
        }
        assertEquals(
            expected = expectedTotalDelay,
            actual = elapsed,
            message = "virtual time elapsed ($elapsed ms) should equal sum of $retryCount inter-retry delays ($expectedTotalDelay ms)",
        )
    }

    // ── Test 5: success on second attempt after one timeout ──────────────────

    @Test
    fun publishBundle_succeedsOnSecondAttempt_afterOneTimeout() = runTest {
        val transport = ThrowingFakePublishTransport { attempt ->
            if (attempt == 1) {
                throw java.net.SocketTimeoutException("Read timed out")
            }
            PreKeyPublishHttpResponse(201, """{"stored_opks": 10}""", elapsedMs = 1L)
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val result = api.publishBundle { sampleRequest() }

        assertEquals(2, transport.callCount, "should succeed on the second attempt")
        assertTrue(result is PublishResult.Stored)
        assertEquals(10, (result as PublishResult.Stored).storedOpks)
    }

    // ── Test 6: HTTP 408 triggers retry, HTTP 400 does not ───────────────────

    @Test
    fun publishBundle_retries_on_408_doesNotRetry_on_400() = runTest {
        // --- 408 case: should retry to PUBLISH_MAX_ATTEMPTS ---
        val transport408 = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(408, """{"error":"timeout"}""", elapsedMs = 1L)
        }
        val api408 = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport408,
        )

        val result408 = api408.publishBundle { sampleRequest() }

        assertEquals(PreKeyApiClient.PUBLISH_MAX_ATTEMPTS, transport408.callCount,
            "408 should trigger retries up to PUBLISH_MAX_ATTEMPTS")
        assertTrue(result408 is PublishResult.Failure)

        // --- 400 case: should NOT retry ---
        val transport400 = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(400, """{"error":"bad request"}""", elapsedMs = 1L)
        }
        val api400 = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport400,
        )

        val result400 = api400.publishBundle { sampleRequest() }

        assertEquals(1, transport400.callCount, "400 must not trigger retries")
        assertTrue(result400 is PublishResult.Failure)
        assertEquals(PublishResult.Reason.BadRequest, (result400 as PublishResult.Failure).reason)
    }

    // ── T2 carrier-ceiling two-layer fix (2026-06-16, post Phase 1+2 field evidence) ──

    /**
     * Layer 2 contract: when the transport returns 2xx with an empty
     * `bodyText` (the production Android shape after the Layer 1
     * unconditional-skip-on-2xx change), `publishWithRetry` must
     * synthesise `PublishResult.Stored(storedOpks = 0)` instead of
     * attempting `json.decodeFromString(PublishResponse, "")` which
     * would throw `JsonDecodingException: Expected JSON, got ""`.
     *
     * Phase 2 field run on Tele2 LTE 2026-06-16 caught this lifecycle
     * layer: the transport itself succeeded (publish elapsedMs = 545
     * vs the 186 000 ms baseline timeout) but the decode threw and
     * triggered the retry budget anyway. This test covers the
     * lifecycle fix in isolation from the transport.
     */
    @Test
    fun publishBundle_succeeds_with_2xx_and_empty_body_synthesizing_zero_storedOpks() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = "",
                elapsedMs = 1L,
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(
            result is PublishResult.Stored,
            "2xx + empty body must synthesise PublishResult.Stored (no JsonDecodingException); got $result",
        )
        assertEquals(
            0, (result as PublishResult.Stored).storedOpks,
            "synthesised Stored must carry storedOpks = 0 (count lost when body skipped)",
        )
        assertEquals(
            1, transport.callCount,
            "single attempt must succeed — no retry on a successful publish",
        )
    }

    /**
     * Backward-compatibility contract: a 2xx response that DOES carry a
     * JSON body (the iOS / JVM stub path, or any future transport that
     * does not skip the body read) continues to decode normally and
     * carries the server's `stored_opks` count through to the result.
     */
    @Test
    fun publishBundle_decodes_2xx_with_JSON_body_normally() = runTest {
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 201,
                bodyText = """{"stored_opks": 42}""",
                elapsedMs = 1L,
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(
            result is PublishResult.Stored,
            "2xx + JSON body must decode normally; got $result",
        )
        assertEquals(
            42, (result as PublishResult.Stored).storedOpks,
            "JSON-decoded storedOpks must reach the result unchanged",
        )
        assertEquals(1, transport.callCount)
    }

    /**
     * Error-context contract: non-2xx responses still propagate the
     * server's body verbatim into `PublishResult.Failure.serverMessage`
     * for log visibility. The Layer 1 transport change reads the body
     * on non-2xx exactly as before (the skip-body-on-2xx branch is
     * conditional on `response.code in 200..299`), and the Layer 2
     * lifecycle change is gated on the 201 branch, so neither layer
     * should disturb 4xx / 5xx handling.
     */
    @Test
    fun publishBundle_reads_body_on_non_2xx_for_error_context() = runTest {
        val errorBody = """{"error":"signing_key_mismatch","detail":"identity already bound"}"""
        val transport = FakePreKeyPublishHttpTransport { _ ->
            PreKeyPublishHttpResponse(
                statusCode = 409,
                bodyText = errorBody,
                elapsedMs = 1L,
            )
        }
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        val result = api.publishBundle { sampleRequest() }

        assertTrue(
            result is PublishResult.Failure,
            "409 must surface as PublishResult.Failure; got $result",
        )
        assertEquals(
            PublishResult.Reason.SigningKeyMismatch,
            (result as PublishResult.Failure).reason,
        )
        assertEquals(
            errorBody, result.serverMessage,
            "non-2xx body must reach Failure.serverMessage verbatim — no Layer 1 / Layer 2 interference",
        )
        assertEquals(1, transport.callCount, "409 must not trigger retries")
    }
}
