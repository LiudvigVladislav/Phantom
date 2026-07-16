// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CLIENT-PREKEY-SELFHEAL transport-layer acceptance tests
 * (docs/tracks/client-prekey-selfheal.md §7 rows T1, T2-per-trigger,
 * T7a, T7b, T7c, T9-status, T9-bundle, T10, T13, T14, T15, T16,
 * T-F3, T-F4, T-F11a, T-F11b).
 *
 * Layer: `shared/core/transport/src/commonTest/` — exercises the real
 * [PreKeyApiClient.fetchStatus] / [PreKeyApiClient.publishBundle]
 * retry loops via Ktor [MockEngine] and a locally-defined
 * [PreKeyPublishHttpTransport] fake. Deterministic virtual-time
 * assertions use `runTest`; happy-path fetchStatus tests use
 * `runBlocking` (see `PreKeyApiClientTest.fetchStatus_*` KDoc for the
 * `withTimeoutOrNull` / test-scheduler interaction that makes runTest
 * fire the per-attempt deadline prematurely on MockEngine-backed
 * fetchStatus paths).
 */
class PreKeyApiClientFetchStatusRetryTest {

    private val baseUrl = "https://relay.test"
    private val goodStatusJson = """{"remaining_opks":42,"signed_prekey_age_days":3}"""

    // ── Local fake publish transport used by publish-oriented tests. ───────
    // Kept local (not shared with `PreKeyPublishReliabilityTest`) to keep
    // this file self-contained and preserve per-test-class isolation.

    private class StaticFakePublishTransport(
        private val statusCode: Int = 201,
        private val body: String = "",
    ) : PreKeyPublishHttpTransport {
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse =
            PreKeyPublishHttpResponse(statusCode, body, elapsedMs = 0L)
    }

    private class ThrowingFakePublishTransport(
        private val script: suspend (attempt: Int) -> PreKeyPublishHttpResponse,
    ) : PreKeyPublishHttpTransport {
        var callCount: Int = 0
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse {
            callCount++
            return script(callCount)
        }
    }

    private class ScriptedFakePublishTransport(
        private val script: suspend (attempt: Int) -> PreKeyPublishHttpResponse,
    ) : PreKeyPublishHttpTransport {
        var callCount: Int = 0
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse {
            callCount++
            return script(callCount)
        }
    }

    private fun samplePublishRequest() = PublishRequest(
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

    // ═══════════════════════════════════════════════════════════════════════
    // T1 — 2 transient SSL throws recover on attempt 3.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_recoversAfterTransientSslWithinBoundedAttempts_returnsPreKeyStatus() =
        runBlocking {
            var call = 0
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        call++
                        when (call) {
                            1, 2 -> throw javax.net.ssl.SSLException(
                                "ssl wrapper",
                                java.net.SocketTimeoutException("read timed out"),
                            )
                            else -> respond(
                                content = goodStatusJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.Json.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            val markers = mutableListOf<String>()
            val api = PreKeyApiClient(
                httpClient = client,
                relayBaseUrl = baseUrl,
                jitter = { 0L }, // eliminate real-time delay under runBlocking
                logObserver = { markers.add(it) },
            )

            val result = api.fetchStatus("aa".repeat(32))
            assertEquals(42, result.remaining_opks)
            assertEquals(3L, result.signed_prekey_age_days)
            assertEquals(3, call, "exactly 3 GETs must reach the engine")

            assertTrue(
                markers.any {
                    it.contains("verify_retry_scheduled") && it.contains("attempt=1/3")
                },
                "expected verify_retry_scheduled attempt=1/3 marker in $markers",
            )
            assertTrue(
                markers.any {
                    it.contains("verify_retry_scheduled") && it.contains("attempt=2/3")
                },
                "expected verify_retry_scheduled attempt=2/3 marker in $markers",
            )
            assertTrue(
                markers.any {
                    it.contains("verify_retry_converged") && it.contains("attempt=3")
                },
                "expected verify_retry_converged attempt=3 marker in $markers",
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // T2-per-trigger — 3 transient throws stop at cap, exhausted marker fires
    // exactly once.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_persistentTransientStopsAtCap() = runBlocking {
        var call = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    throw java.net.SocketTimeoutException("read timed out")
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
            logObserver = { markers.add(it) },
        )

        try {
            api.fetchStatus("aa".repeat(32))
            fail("expected SocketTimeoutException after cap")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        }

        assertEquals(
            PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS, call,
            "must attempt exactly ${PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS} times — no 4th GET",
        )
        val exhaustedCount = markers.count {
            it.contains("verify_retry_exhausted") && it.contains("total_attempts=3")
        }
        assertEquals(
            1, exhaustedCount,
            "verify_retry_exhausted total_attempts=3 must appear exactly once; markers=$markers",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T7a — empty body attempt 1, valid attempt 2.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_emptyBody_retriesAndSucceeds() = runBlocking {
        var call = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    if (call == 1) {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                        )
                    } else {
                        respond(
                            content = goodStatusJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                        )
                    }
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
            logObserver = { markers.add(it) },
        )

        val result = api.fetchStatus("aa".repeat(32))
        assertEquals(42, result.remaining_opks)
        assertEquals(2, call, "exactly 2 GETs — empty body triggers one retry")

        assertTrue(
            markers.any {
                it.contains("verify_retry_scheduled") && it.contains("reason=body_truncated")
            },
            "expected verify_retry_scheduled reason=body_truncated in $markers",
        )
        assertTrue(
            markers.any {
                it.contains("verify_retry_converged") && it.contains("attempt=2")
            },
            "expected verify_retry_converged attempt=2 in $markers",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T7b — Content-Length declares more bytes than delivered on attempt 1;
    // valid body on attempt 2. Assert 2 GETs and successful decoded status.
    //
    // Ktor 3.0.3 raises this specific `IllegalStateException` from
    // `io.ktor.client.call.SavedHttpCall.<init>` via
    // `io.ktor.client.call.UtilsKt.checkContentLength` when the declared
    // `Content-Length` header does not match the delivered body size. The
    // throw fires BEFORE our `bodyAsText()` invocation because Ktor saves
    // the entire non-streaming body during `httpClient.get(url)`.
    //
    // Production `fetchStatus` translates ONLY exceptions matching
    // `IllegalStateException` + `message.startsWith("Content-Length
    // mismatch:")` into the `body_truncated` retry arm — the same arm the
    // `BodyClass.Empty` / `BodyClass.TruncatedByLength` branch uses when
    // Ktor happens NOT to enforce Content-Length (e.g. `Content-Length: -1`
    // / chunked / no header). This regression pins the E2E round-trip via
    // MockEngine.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_truncatedBodyDetectedByContentLength_retriesAndSucceeds() = runBlocking {
        var call = 0
        // Ktor 3.0.3 MockEngine copies the declared `Content-Length` header
        // through verbatim, then `SavedHttpCall.<init>` compares it against
        // the actual body byte count. A declared-100 / delivered-`goodStatusJson`
        // pair triggers `checkContentLength`'s bare `IllegalStateException`
        // ("Content-Length mismatch: expected 100 bytes, but received N bytes").
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    if (call == 1) {
                        respond(
                            content = goodStatusJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(
                                    ContentType.Application.Json.toString(),
                                ),
                                HttpHeaders.ContentLength to listOf("100"),
                            ),
                        )
                    } else {
                        respond(
                            content = goodStatusJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(
                                    ContentType.Application.Json.toString(),
                                ),
                                // Correct length: MockEngine's default when
                                // omitted computes to the body byte length.
                            ),
                        )
                    }
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
            logObserver = { markers.add(it) },
        )

        val status = api.fetchStatus("aa".repeat(32))
        assertEquals(42, status.remaining_opks)
        assertEquals(
            2, call,
            "exactly 2 GETs — truncated Content-Length on attempt 1 triggers ONE retry",
        )

        assertTrue(
            markers.any {
                it.contains("verify_retry_scheduled") &&
                    it.contains("reason=body_truncated")
            },
            "expected verify_retry_scheduled reason=body_truncated in $markers",
        )
        assertTrue(
            markers.any {
                it.contains("verify_retry_converged") && it.contains("attempt=2")
            },
            "expected verify_retry_converged attempt=2 in $markers",
        )
    }

    // T7b-support — unit-level assertion that classifyBodyTruncation itself
    // recognises the declared > received size mismatch. Complements the E2E
    // regression above.
    @Test
    fun classifyBodyTruncation_declaredGreaterThanReceived_returnsTruncatedByLength() =
        runBlocking {
            val payload = "x".repeat(100)
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(
                                    ContentType.Application.Json.toString(),
                                ),
                                HttpHeaders.ContentLength to listOf("100"),
                            ),
                        )
                    }
                }
            }
            val response = client.get("https://relay.test/prekeys/status/probe")
            assertEquals(
                BodyClass.TruncatedByLength,
                classifyBodyTruncation(response, "x".repeat(50)),
                "declared 100 with received 50 must classify as TruncatedByLength",
            )
            assertEquals(
                BodyClass.Complete,
                classifyBodyTruncation(response, payload),
                "declared 100 with received 100 must classify as Complete",
            )
            assertEquals(
                BodyClass.Empty,
                classifyBodyTruncation(response, ""),
                "empty body classifies as Empty regardless of Content-Length header",
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // T7c — Content-Length matches body length but JSON is malformed →
    // terminal (SerializationException, no retry).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_nonEmptyMalformedJsonNoTruncation_terminatesImmediately() = runBlocking {
        var call = 0
        val malformed = """{"remaining_opks":""" // 18 bytes, not valid JSON
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    respond(
                        content = malformed,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(
                                ContentType.Application.Json.toString(),
                            ),
                            HttpHeaders.ContentLength to listOf(
                                malformed.encodeToByteArray().size.toString(),
                            ),
                        ),
                    )
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
            logObserver = { markers.add(it) },
        )

        try {
            api.fetchStatus("aa".repeat(32))
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            // expected
        }

        assertEquals(1, call, "malformed non-truncated JSON must terminate on attempt 1")
        assertTrue(
            markers.any {
                it.contains("verify_terminal_shortcut") &&
                    it.contains("attempt=1") &&
                    it.contains("reason=decode_")
            },
            "expected verify_terminal_shortcut attempt=1 reason=decode_… in $markers",
        )
        assertFalse(
            markers.any { it.contains("verify_retry_exhausted") },
            "no verify_retry_exhausted marker allowed on terminal decode",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T9-status — fetchStatus markers must never contain full 64-hex identity.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun httpStatusStart_doesNotLogFullIdentityHex() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = goodStatusJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            logObserver = { markers.add(it) },
        )
        val identity = "ab".repeat(32) // 64 hex chars
        val requester = "cd".repeat(32) // 64 hex chars

        api.fetchStatus(identity, requester)

        val hex64 = Regex("[0-9a-f]{64}")
        for (m in markers) {
            assertFalse(
                hex64.containsMatchIn(m),
                "marker leaks 64-hex identity — D8/D9 violation: $m",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T9-bundle — fetchBundle markers must never contain full 64-hex identity.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun httpBundleFetchStart_doesNotLogFullIdentityHex() = runBlocking {
        val identity = "ab".repeat(32) // 64 hex chars
        val signing = "cd".repeat(32)
        val bundleJson = """
            {
              "identity_pubkey_hex": "$identity",
              "signing_pubkey_hex":  "$signing",
              "signed_pre_key": {
                "key_id": 1,
                "public_key_hex": "${"33".repeat(32)}",
                "created_at_ms": 100,
                "signature_hex": "${"44".repeat(64)}"
              },
              "one_time_pre_key": null
            }
        """.trimIndent()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = bundleJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            logObserver = { markers.add(it) },
        )
        val requester = "ef".repeat(32)

        api.fetchBundle(identity, requester)

        val hex64 = Regex("[0-9a-f]{64}")
        for (m in markers) {
            assertFalse(
                hex64.containsMatchIn(m),
                "marker leaks 64-hex identity — D8/D9 violation: $m",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T10 — exactly 3 GETs per trigger; no hidden outer retry burst.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun noHidden3x3Retry_perTriggerMaxRequestCountBounded() = runBlocking {
        var call = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    throw java.net.SocketTimeoutException("read timed out")
                }
            }
        }
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
        )

        try {
            api.fetchStatus("aa".repeat(32))
            fail("expected SocketTimeoutException")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        }

        assertEquals(
            3, call,
            "must dispatch exactly 3 GETs (FETCH_STATUS_MAX_ATTEMPTS) — no outer 3x3 burst",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T13 — publishWithRetry retries on retryable transient, then succeeds.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun publishWithRetry_retriesOnRetryableTransient_thenSucceeds() = runTest {
        val transport = ThrowingFakePublishTransport { attempt ->
            if (attempt == 1) throw java.net.SocketTimeoutException("read timed out")
            PreKeyPublishHttpResponse(201, """{"stored_opks":4}""", elapsedMs = 0L)
        }
        val api = PreKeyApiClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("", HttpStatusCode.OK) } }
            },
            relayBaseUrl = baseUrl,
            publishTransport = transport,
            jitter = { it },
        )

        val result = api.publishBundle { samplePublishRequest() }
        assertEquals(2, transport.callCount, "T13 expects 2 attempts")
        assertTrue(result is PublishResult.Stored, "expected Stored, got $result")
        assertEquals(4, (result as PublishResult.Stored).storedOpks)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T14 — publishWithRetry retries on ConnectException, then succeeds.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun publishWithRetry_retriesOnConnectException_thenSucceeds() = runTest {
        val transport = ThrowingFakePublishTransport { attempt ->
            if (attempt == 1) throw java.net.ConnectException("refused")
            PreKeyPublishHttpResponse(201, """{"stored_opks":7}""", elapsedMs = 0L)
        }
        val api = PreKeyApiClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("", HttpStatusCode.OK) } }
            },
            relayBaseUrl = baseUrl,
            publishTransport = transport,
            jitter = { it },
        )

        val result = api.publishBundle { samplePublishRequest() }
        assertEquals(2, transport.callCount, "T14 expects 2 attempts")
        assertTrue(result is PublishResult.Stored, "expected Stored, got $result")
        assertEquals(7, (result as PublishResult.Stored).storedOpks)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T15 — HTTP 429 retries with jittered RATE_LIMIT_FALLBACK_MS fallback.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun publishWithRetry_429_retriesWithFallback() = runTest {
        val transport = ScriptedFakePublishTransport { attempt ->
            if (attempt == 1) {
                PreKeyPublishHttpResponse(429, """{"error":"slow down"}""", elapsedMs = 0L)
            } else {
                PreKeyPublishHttpResponse(201, """{"stored_opks":3}""", elapsedMs = 0L)
            }
        }
        val api = PreKeyApiClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("", HttpStatusCode.OK) } }
            },
            relayBaseUrl = baseUrl,
            publishTransport = transport,
            jitter = { it },
        )

        val tBefore = testScheduler.currentTime
        val result = api.publishBundle { samplePublishRequest() }
        val elapsed = testScheduler.currentTime - tBefore

        assertEquals(2, transport.callCount, "T15 expects 2 attempts (429 then 201)")
        assertTrue(result is PublishResult.Stored, "expected Stored, got $result")
        assertEquals(3, (result as PublishResult.Stored).storedOpks)
        assertTrue(
            elapsed >= PreKeyApiClient.RATE_LIMIT_FALLBACK_MS,
            "elapsed=$elapsed must be >= RATE_LIMIT_FALLBACK_MS=${PreKeyApiClient.RATE_LIMIT_FALLBACK_MS}",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T16 — identity jitter for deterministic virtual-time delay assertions.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun jitterInjection_returnsIdentityInTests_forDeterministicAssertions() = runTest {
        val transport = ThrowingFakePublishTransport { _ ->
            throw java.net.SocketTimeoutException("read timed out")
        }
        val api = PreKeyApiClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("", HttpStatusCode.OK) } }
            },
            relayBaseUrl = baseUrl,
            publishTransport = transport,
            jitter = { it },
        )

        val tBefore = testScheduler.currentTime
        try {
            api.publishBundle { samplePublishRequest() }
        } catch (ignored: java.net.SocketTimeoutException) {
            // expected — cap reached
        }
        val elapsed = testScheduler.currentTime - tBefore

        val expected: Long = (0 until PreKeyApiClient.PUBLISH_MAX_ATTEMPTS - 1).sumOf { i ->
            PreKeyApiClient.PUBLISH_RETRY_DELAYS_MS[i]
        }
        assertEquals(
            expected, elapsed,
            "identity jitter must produce elapsed=$expected (raw delay sum); got $elapsed",
        )
        assertEquals(PreKeyApiClient.PUBLISH_MAX_ATTEMPTS, transport.callCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T-F3 — TerminalOther on attempt 1 emits verify_terminal_shortcut and
    // does NOT emit verify_retry_exhausted.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun terminalFirstAttempt_emitsTerminalShortcut_neverExhausted() = runBlocking {
        var call = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    call++
                    // PreKeyBodyTruncatedException classifies as TerminalOther.
                    throw PreKeyBodyTruncatedException(1)
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { 0L },
            logObserver = { markers.add(it) },
        )

        try {
            api.fetchStatus("aa".repeat(32))
            fail("expected PreKeyBodyTruncatedException")
        } catch (e: PreKeyBodyTruncatedException) {
            // expected
        }

        assertEquals(1, call, "TerminalOther must NOT retry — exactly 1 attempt")
        assertTrue(
            markers.any {
                it.contains("verify_terminal_shortcut") && it.contains("attempt=1")
            },
            "expected verify_terminal_shortcut attempt=1 marker in $markers",
        )
        assertFalse(
            markers.any { it.contains("verify_retry_exhausted") },
            "TerminalOther must not emit verify_retry_exhausted",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T-F4 — request-read and body-read paths hit the SAME retry decision
    // through the SAME classifier. Ktor 3.0.3 wraps body-read failures in
    // IOException(cause=IOException(cause=<original>)) via CloseToken +
    // ByteChannelReplay + SavedCall.save. The classifier's cause-chain walk
    // (depth 5) sees the original transient class regardless of wrapping,
    // so both origins produce identical RetryDecision → identical retry
    // trajectory.
    //
    // Evidence captured 2026-07-16 diagnostic (see PR body):
    //   top-level: java.io.IOException("simulated body-read timeout")
    //   cause[0]:  java.io.IOException("simulated body-read timeout")
    //   cause[1]:  java.io.IOException("simulated body-read timeout")
    //   cause[2]:  java.net.SocketTimeoutException("simulated body-read timeout")
    // The extended classifier walks the chain and returns
    // RetryableTransient from cause[2] — same result as if the top-level
    // had been the bare SocketTimeoutException (request path).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun requestAndBodyReadPaths_produceIdenticalRetryDecision_viaSharedClassifier() =
        runBlocking<Unit> {
            // ── Variant 1: request-read path — engine handler THROWS the
            // transient class directly at request time. Top-level throwable
            // is the bare SocketTimeoutException.
            var reqCall = 0
            val reqMarkers = mutableListOf<String>()
            val reqClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        reqCall++
                        throw java.net.SocketTimeoutException("request-read timeout")
                    }
                }
            }
            val reqApi = PreKeyApiClient(
                httpClient = reqClient,
                relayBaseUrl = baseUrl,
                jitter = { 0L },
                logObserver = { reqMarkers.add(it) },
            )
            var reqException: Throwable? = null
            try {
                reqApi.fetchStatus("aa".repeat(32))
                fail("variant 1: expected exception after retry cap")
            } catch (t: Throwable) {
                reqException = t
            }

            // ── Variant 2: body-read path — engine handler returns a
            // ByteReadChannel that raises the SAME transient class via
            // cancel(cause). Ktor wraps the cause in IOException-of-
            // IOException, but the classifier's cause-chain walk sees
            // the original SocketTimeoutException at cause depth 2.
            var bodyCall = 0
            val bodyMarkers = mutableListOf<String>()
            val bodyClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        bodyCall++
                        val throwingChannel = ByteChannel(autoFlush = true).apply {
                            cancel(java.net.SocketTimeoutException("body-read timeout"))
                        }
                        respond(
                            content = throwingChannel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                        )
                    }
                }
            }
            val bodyApi = PreKeyApiClient(
                httpClient = bodyClient,
                relayBaseUrl = baseUrl,
                jitter = { 0L },
                logObserver = { bodyMarkers.add(it) },
            )
            var bodyException: Throwable? = null
            try {
                bodyApi.fetchStatus("aa".repeat(32))
                fail("variant 2: expected exception after retry cap")
            } catch (t: Throwable) {
                bodyException = t
            }

            // ── Identical retry trajectory: both variants exhaust exactly
            // FETCH_STATUS_MAX_ATTEMPTS calls to the engine.
            assertEquals(
                PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS, reqCall,
                "request-read must retry to cap",
            )
            assertEquals(
                PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS, bodyCall,
                "body-read must retry to cap through same classifier",
            )
            // Both variants emit the same retry-marker shape: N-1 scheduled
            // + 1 exhausted. Distinct reason strings reflect the surfaced
            // class name but the retry decision is identical.
            assertEquals(
                PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS - 1,
                reqMarkers.count { it.contains("verify_retry_scheduled") },
                "request-read must schedule exactly MAX-1 retries",
            )
            assertEquals(
                PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS - 1,
                bodyMarkers.count { it.contains("verify_retry_scheduled") },
                "body-read must schedule exactly MAX-1 retries",
            )
            assertTrue(
                reqMarkers.any { it.contains("verify_retry_exhausted") },
                "request-read must fire verify_retry_exhausted after cap",
            )
            assertTrue(
                bodyMarkers.any { it.contains("verify_retry_exhausted") },
                "body-read must fire verify_retry_exhausted after cap",
            )
            // Both variants throw AFTER the retry loop exhausts (rather
            // than terminating early on attempt 1). Concrete top-level
            // classes differ (Ktor wrapping is a version artefact) — what
            // matters is the classifier's decision is symmetric.
            assertNotNull(reqException, "request-read must throw after cap")
            assertNotNull(bodyException, "body-read must throw after cap")
        }

    // ═══════════════════════════════════════════════════════════════════════
    // T-F11a — hung request; per-attempt withTimeoutOrNull deadline fires
    // per attempt, retries to the cap, throws FetchStatusDeadlineExceededException
    // (NOT a CancellationException).
    // ═══════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fetchStatus_hungRequest_attemptDeadlineFires_retriesUpToMax_throwsFetchStatusDeadlineExceededException() =
        runTest {
            var attempts = 0
            val client = HttpClient(MockEngine) {
                engine {
                    // Pin MockEngine to the test dispatcher so
                    // `runTest`'s virtual clock owns every suspension the
                    // handler creates — without this, the handler's
                    // `suspendCancellableCoroutine` runs on Dispatchers.IO
                    // (MockEngine's default) which is opaque to the test
                    // scheduler, so the withTimeoutOrNull deadline never
                    // fires under runTest and the test hangs.
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        attempts++
                        // Suspend forever — the fetchStatus per-attempt
                        // withTimeoutOrNull is the ONLY way this attempt
                        // completes. Cancellation from the timeout resumes
                        // this coroutine with CancellationException.
                        suspendCancellableCoroutine<HttpResponseData> {
                            // never resume
                        }
                    }
                }
            }
            val markers = mutableListOf<String>()
            val api = PreKeyApiClient(
                httpClient = client,
                relayBaseUrl = baseUrl,
                jitter = { it },
                logObserver = { markers.add(it) },
            )

            var thrown: Throwable? = null
            try {
                api.fetchStatus("aa".repeat(32))
                fail("expected FetchStatusDeadlineExceededException")
            } catch (e: FetchStatusDeadlineExceededException) {
                thrown = e
            }

            assertNotNull(thrown, "must throw FetchStatusDeadlineExceededException")
            assertFalse(
                thrown is CancellationException,
                "must NOT be a CancellationException — verify layer must not treat as external cancel",
            )
            assertEquals(
                PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS, attempts,
                "must fire ${PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS} attempts on hung request",
            )
            val scheduledDeadlineMarkers = markers.count {
                it.contains("verify_retry_scheduled") && it.contains("attempt_deadline")
            }
            val exhaustedDeadlineMarkers = markers.count {
                it.contains("verify_retry_exhausted") && it.contains("attempt_deadline")
            }
            assertEquals(
                2, scheduledDeadlineMarkers,
                "expected 2 verify_retry_scheduled markers with attempt_deadline; markers=$markers",
            )
            assertEquals(
                1, exhaustedDeadlineMarkers,
                "expected 1 verify_retry_exhausted marker with attempt_deadline; markers=$markers",
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // T-F11b — trickle body read; headers return but bodyAsText hangs; per-
    // attempt deadline fires per attempt.
    // ═══════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fetchStatus_trickleBodyRead_attemptDeadlineFires() = runTest {
        var attempts = 0
        val client = HttpClient(MockEngine) {
            engine {
                // Pin MockEngine to the test dispatcher so the hanging
                // body-read suspension is observable by the test scheduler
                // (see T-F11a comment).
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    attempts++
                    // Never-written, never-closed channel — bodyAsText
                    // suspends waiting for bytes / EOF that never arrive.
                    val hangingChannel = ByteChannel(autoFlush = false)
                    respond(
                        content = hangingChannel,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                }
            }
        }
        val markers = mutableListOf<String>()
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = baseUrl,
            jitter = { it },
            logObserver = { markers.add(it) },
        )

        var thrown: Throwable? = null
        try {
            api.fetchStatus("aa".repeat(32))
            fail("expected FetchStatusDeadlineExceededException")
        } catch (e: FetchStatusDeadlineExceededException) {
            thrown = e
        }

        assertNotNull(thrown)
        assertFalse(thrown is CancellationException)
        assertEquals(
            PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS, attempts,
            "must fire ${PreKeyApiClient.FETCH_STATUS_MAX_ATTEMPTS} attempts on trickle-body hang",
        )
        val scheduledDeadlineMarkers = markers.count {
            it.contains("verify_retry_scheduled") && it.contains("attempt_deadline")
        }
        val exhaustedDeadlineMarkers = markers.count {
            it.contains("verify_retry_exhausted") && it.contains("attempt_deadline")
        }
        assertEquals(
            2, scheduledDeadlineMarkers,
            "expected 2 verify_retry_scheduled markers with attempt_deadline; markers=$markers",
        )
        assertEquals(
            1, exhaustedDeadlineMarkers,
            "expected 1 verify_retry_exhausted marker with attempt_deadline; markers=$markers",
        )
    }
}
