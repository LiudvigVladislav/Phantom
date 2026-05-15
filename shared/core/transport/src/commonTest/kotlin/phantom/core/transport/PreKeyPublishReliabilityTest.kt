// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for PR-R0 reliability additions to [PreKeyApiClient.publishBundle]:
 *
 *  1. Mutex debounce — a second concurrent publish is dropped silently.
 *  2. Retry on SocketTimeoutException, gives up after [PreKeyApiClient.PUBLISH_MAX_ATTEMPTS].
 *  3. No retry on 422 Unprocessable Entity (bad request variant).
 *  4. Exponential backoff between retry attempts (virtual time via TestScope).
 *
 * These tests use Ktor's MockEngine and kotlinx-coroutines-test virtual-time
 * dispatcher. No OkHttp, no Android runtime required — runs in commonTest / JVM.
 */
class PreKeyPublishReliabilityTest {

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

    // ── Test 1: mutex deduplicates parallel calls ─────────────────────────────

    /**
     * Debounce test: when publishMutex is already locked (simulating an in-flight
     * publish), a subsequent publishBundle call returns Stored(0) immediately
     * without dispatching any HTTP request.
     *
     * We access publishMutex directly via the `internal` visibility modifier —
     * this is the approved test hook; no reflection needed.
     */
    @Test
    fun publishBundle_mutex_deduplicates_parallel_calls() = runTest {
        var engineCallCount = 0

        val engine = MockEngine { _ ->
            engineCallCount++
            respond(
                content = """{"stored_opks": 5}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client,
        )

        // Simulate a concurrent in-flight publish by acquiring the mutex directly.
        // publishBundle uses tryLock() — if the lock is already held it debounces.
        api.publishMutex.lock()

        val debounced = api.publishBundle(sampleRequest())

        assertTrue(debounced is PublishResult.Stored)
        assertEquals(0, (debounced as PublishResult.Stored).storedOpks,
            "debounced call must return synthetic Stored(0)")
        assertEquals(0, engineCallCount,
            "no HTTP request should fire when mutex is locked (debounce path)")

        // Release the lock and verify a normal call succeeds.
        api.publishMutex.unlock()

        val real = api.publishBundle(sampleRequest())
        assertTrue(real is PublishResult.Stored)
        assertEquals(5, (real as PublishResult.Stored).storedOpks,
            "real call after mutex released must return server response")
        assertEquals(1, engineCallCount, "exactly one HTTP request dispatched for the real call")
    }

    // ── Test 2: retry on SocketTimeoutException, gives up after 3 attempts ───

    @Test
    fun publishBundle_retries_on_SocketTimeoutException_givesUpAfter3() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            // Simulate SocketTimeoutException by name — in test JVM this is
            // java.net.SocketTimeoutException. We throw it by name to match
            // the isRetryable() class-name check in production code.
            throw java.net.SocketTimeoutException("Read timed out")
        }
        val client = HttpClient(engine)
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client,
        )

        // Advance virtual time past all retry delays so the test finishes fast.
        var thrown: Throwable? = null
        try {
            api.publishBundle(sampleRequest())
            fail("expected SocketTimeoutException to propagate after all retries")
        } catch (e: java.net.SocketTimeoutException) {
            thrown = e
        }

        // The loop ran exactly PUBLISH_MAX_ATTEMPTS (3) times before giving up.
        assertEquals(PreKeyApiClient.PUBLISH_MAX_ATTEMPTS, callCount,
            "should attempt exactly ${PreKeyApiClient.PUBLISH_MAX_ATTEMPTS} times total")
        assertTrue(thrown != null, "original exception must propagate")
    }

    // ── Test 3: no retry on 422 ───────────────────────────────────────────────

    @Test
    fun publishBundle_doesNotRetry_on_422() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = """{"error":"invalid signing key format"}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client,
        )

        val result = api.publishBundle(sampleRequest())

        // 422 maps to Unexpected(422) — not a retryable status.
        assertTrue(result is PublishResult.Failure)
        val reason = (result as PublishResult.Failure).reason
        assertTrue(reason is PublishResult.Reason.Unexpected)
        assertEquals(422, (reason as PublishResult.Reason.Unexpected).httpStatus)

        // Only one attempt — no retries.
        assertEquals(1, callCount, "422 must not trigger retries")
    }

    // ── Test 4: exponential backoff between retries (virtual time) ────────────

    /**
     * Verifies that publishBundle completes all retry attempts (spending the
     * inter-retry delays) under kotlinx-coroutines-test's virtual-time
     * dispatcher. Virtual time auto-advances through `delay()` calls, so
     * this test finishes in ~0 real ms regardless of the delay values.
     *
     * We verify:
     *   a) All PUBLISH_MAX_ATTEMPTS were dispatched (retry loop ran fully).
     *   b) The test completed (meaning the delays did not block forever —
     *      confirming the delays are coroutine-friendly `delay()` calls,
     *      not `Thread.sleep()` which would block virtual-time tests).
     *   c) The total virtual time elapsed matches sum of PUBLISH_RETRY_DELAYS_MS.
     */
    @Test
    fun publishBundle_exponentialBackoff_betweenRetries() = runTest {
        var callCount = 0

        val engine = MockEngine { _ ->
            callCount++
            // Always throw SocketTimeoutException so every attempt fails
            // and the retry loop runs through all PUBLISH_MAX_ATTEMPTS.
            throw java.net.SocketTimeoutException("Read timed out")
        }
        val client = HttpClient(engine)
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client,
        )

        val tBefore = testScheduler.currentTime
        try {
            api.publishBundle(sampleRequest())
        } catch (ignored: java.net.SocketTimeoutException) {
            // Expected — all retries exhausted.
        }
        val elapsed = testScheduler.currentTime - tBefore

        assertEquals(
            expected = PreKeyApiClient.PUBLISH_MAX_ATTEMPTS,
            actual = callCount,
            message = "all ${PreKeyApiClient.PUBLISH_MAX_ATTEMPTS} attempts must fire",
        )

        // Total virtual time advanced equals the sum of the inter-retry delays.
        // There are (PUBLISH_MAX_ATTEMPTS - 1) gaps between attempts: after attempt 1
        // we wait PUBLISH_RETRY_DELAYS_MS[0], after attempt 2 we wait [1], and
        // attempt 3 either succeeds or throws immediately (no trailing delay).
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
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            if (callCount == 1) {
                throw java.net.SocketTimeoutException("Read timed out")
            }
            respond(
                content = """{"stored_opks": 10}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client,
        )

        val result = api.publishBundle(sampleRequest())

        assertEquals(2, callCount, "should succeed on the second attempt")
        assertTrue(result is PublishResult.Stored)
        assertEquals(10, (result as PublishResult.Stored).storedOpks)
    }

    // ── Test 6: HTTP 408 triggers retry, HTTP 400 does not ───────────────────

    @Test
    fun publishBundle_retries_on_408_doesNotRetry_on_400() = runTest {
        // --- 408 case: should retry ---
        var count408 = 0
        val engine408 = MockEngine { _ ->
            count408++
            respond(
                content = """{"error":"timeout"}""",
                status = HttpStatusCode.RequestTimeout,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client408 = HttpClient(engine408)
        val api408 = PreKeyApiClient(
            httpClient = client408,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client408,
        )

        val result408 = api408.publishBundle(sampleRequest())

        // After PUBLISH_MAX_ATTEMPTS the 408 is returned as-is (Unexpected reason).
        assertEquals(PreKeyApiClient.PUBLISH_MAX_ATTEMPTS, count408,
            "408 should trigger retries up to PUBLISH_MAX_ATTEMPTS")
        assertTrue(result408 is PublishResult.Failure)

        // --- 400 case: should NOT retry ---
        var count400 = 0
        val engine400 = MockEngine { _ ->
            count400++
            respond(
                content = """{"error":"bad request"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client400 = HttpClient(engine400)
        val api400 = PreKeyApiClient(
            httpClient = client400,
            relayBaseUrl = "https://relay.test",
            publishHttpClient = client400,
        )

        val result400 = api400.publishBundle(sampleRequest())

        assertEquals(1, count400, "400 must not trigger retries")
        assertTrue(result400 is PublishResult.Failure)
        assertEquals(PublishResult.Reason.BadRequest, (result400 as PublishResult.Failure).reason)
    }
}
