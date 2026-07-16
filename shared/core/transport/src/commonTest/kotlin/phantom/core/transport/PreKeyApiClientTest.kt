// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Minimal fake [PreKeyPublishHttpTransport] for [PreKeyApiClientTest].
 *
 * Returns a fixed status and body on every call, with zero delay.
 * Tests that need more complex scripting (retry, backoff, throw) use
 * the richer fakes in [PreKeyPublishReliabilityTest].
 */
private class StaticFakePublishTransport(
    private val statusCode: Int,
    private val body: String,
) : PreKeyPublishHttpTransport {
    override suspend fun publish(
        url: String,
        bodyBytes: ByteArray,
        contentType: String,
        requestId: String,
    ): PreKeyPublishHttpResponse = PreKeyPublishHttpResponse(statusCode, body, elapsedMs = 0L)
}

/**
 * Drives [PreKeyApiClient] against a Ktor [MockEngine] so request shape
 * and response handling are exercised without a running relay. Each
 * test wires its own engine handler so capture state doesn't leak.
 *
 * The wire-format struct equality (PublishRequest serialise round-trip)
 * is covered by [WireSerializationRoundTripTest] separately — this file
 * focuses on the HTTP semantics: status code → result type mapping.
 */
class PreKeyApiClientTest {

    private val baseUrl = "https://relay.test"

    private fun clientThatReturns(
        status: HttpStatusCode,
        body: String,
        urlAssert: (String) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        engine {
            addHandler { request ->
                urlAssert(request.url.toString())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json.toString(),
                    ),
                )
            }
        }
    }

    private fun samplePublishRequest() = PublishRequest(
        identity_pubkey_hex = "aa".repeat(32),
        signing_pubkey_hex = "bb".repeat(32),
        signed_pre_key = WireSignedPreKey(
            key_id = 1,
            public_key_hex = "cc".repeat(32),
            created_at_ms = 100,
            signature_hex = "dd".repeat(64),
        ),
        one_time_pre_keys = emptyList(),
    )

    // ── Publish tests use StaticFakePublishTransport (native transport path) ────
    // The publish path no longer goes through Ktor (PR-R0.1); use the fake
    // transport instead of a MockEngine for the POST /prekeys/publish tests.
    // Retry/backoff/mutex tests live in PreKeyPublishReliabilityTest.

    @Test
    fun publishBundle_201_returnsStored() = runTest {
        val api = PreKeyApiClient(
            httpClient = clientThatReturns(HttpStatusCode.OK, ""),
            relayBaseUrl = baseUrl,
            publishTransport = StaticFakePublishTransport(201, """{"stored_opks":2}"""),
        )

        val result = api.publishBundle { samplePublishRequest() }
        when (result) {
            is PublishResult.Stored -> assertEquals(2, result.storedOpks)
            else -> fail("expected Stored, got $result")
        }
    }

    @Test
    fun publishBundle_409_returnsSigningKeyMismatch() = runTest {
        val api = PreKeyApiClient(
            httpClient = clientThatReturns(HttpStatusCode.OK, ""),
            relayBaseUrl = baseUrl,
            publishTransport = StaticFakePublishTransport(409, """{"error":"signing_pubkey_hex does not match"}"""),
        )

        val result = api.publishBundle { samplePublishRequest() }
        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishResult.Reason.SigningKeyMismatch, result.reason)
    }

    @Test
    fun publishBundle_429_returnsRateLimited() = runTest {
        val api = PreKeyApiClient(
            httpClient = clientThatReturns(HttpStatusCode.OK, ""),
            relayBaseUrl = baseUrl,
            publishTransport = StaticFakePublishTransport(429, """{"error":"publish rate limit exceeded"}"""),
        )

        val result = api.publishBundle { samplePublishRequest() }
        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishResult.Reason.RateLimited, result.reason)
    }

    @Test
    fun publishBundle_400_returnsBadRequest() = runTest {
        val api = PreKeyApiClient(
            httpClient = clientThatReturns(HttpStatusCode.OK, ""),
            relayBaseUrl = baseUrl,
            publishTransport = StaticFakePublishTransport(400, """{"error":"signing_pubkey_hex must be 64 hex chars"}"""),
        )

        val result = api.publishBundle { samplePublishRequest() }
        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishResult.Reason.BadRequest, result.reason)
    }

    @Test
    fun fetchBundle_200_returnsBundle() = runTest {
        val identity = "11".repeat(32)
        val signing = "22".repeat(32)
        val responseBody = """
            {
              "identity_pubkey_hex": "$identity",
              "signing_pubkey_hex":  "$signing",
              "signed_pre_key": {
                "key_id": 7,
                "public_key_hex": "${"33".repeat(32)}",
                "created_at_ms": 12345,
                "signature_hex": "${"44".repeat(64)}"
              },
              "one_time_pre_key": {
                "key_id_hex": "${"55".repeat(16)}",
                "public_key_hex": "${"66".repeat(32)}"
              }
            }
        """.trimIndent()
        val client = clientThatReturns(
            HttpStatusCode.OK,
            responseBody,
            urlAssert = { url ->
                assertEquals("https://relay.test/prekeys/bundle/$identity", url)
            },
        )
        val api = PreKeyApiClient(client, baseUrl)

        val bundle = api.fetchBundle(identity)
        assertNotNull(bundle)
        assertEquals(identity, bundle.identity_pubkey_hex)
        assertEquals(signing, bundle.signing_pubkey_hex)
        assertEquals(7, bundle.signed_pre_key.key_id)
        assertNotNull(bundle.one_time_pre_key)
        assertEquals("55".repeat(16), bundle.one_time_pre_key!!.key_id_hex)
    }

    @Test
    fun fetchBundle_200_handlesEmptyOpkSlot() = runTest {
        val identity = "11".repeat(32)
        val responseBody = """
            {
              "identity_pubkey_hex": "$identity",
              "signing_pubkey_hex":  "${"22".repeat(32)}",
              "signed_pre_key": {
                "key_id": 1,
                "public_key_hex": "${"33".repeat(32)}",
                "created_at_ms": 12345,
                "signature_hex": "${"44".repeat(64)}"
              },
              "one_time_pre_key": null
            }
        """.trimIndent()
        val client = clientThatReturns(HttpStatusCode.OK, responseBody)
        val api = PreKeyApiClient(client, baseUrl)

        val bundle = api.fetchBundle(identity)
        assertNotNull(bundle)
        assertNull(bundle.one_time_pre_key)
    }

    @Test
    fun fetchBundle_404_returnsNull() = runTest {
        val client = clientThatReturns(
            HttpStatusCode.NotFound,
            """{"error":"no published prekeys for this identity"}""",
        )
        val api = PreKeyApiClient(client, baseUrl)

        val bundle = api.fetchBundle("aa".repeat(32))
        assertNull(bundle)
    }

    @Test
    fun fetchBundle_429_throwsRateLimited() = runTest {
        val client = clientThatReturns(
            HttpStatusCode.TooManyRequests,
            """{"error":"bundle rate limit exceeded"}""",
        )
        val api = PreKeyApiClient(client, baseUrl)

        try {
            api.fetchBundle("aa".repeat(32))
            fail("expected BundleFetchException.RateLimited")
        } catch (e: BundleFetchException.RateLimited) {
            // ok
        }
    }

    @Test
    fun fetchBundle_includesRequesterQueryParam_whenProvided() = runTest {
        val capturedUrls = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedUrls += request.url.toString()
                    respond(
                        content = """{"error":"not found"}""",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                }
            }
        }
        val api = PreKeyApiClient(client, baseUrl)

        val identity = "aa".repeat(32)
        val requester = "bb".repeat(32)
        api.fetchBundle(identity, requesterPubkeyHex = requester)
        assertEquals(1, capturedUrls.size)
        assertTrue(
            capturedUrls.first().contains("?requester=$requester"),
            "URL should include requester query param: ${capturedUrls.first()}",
        )
    }

    // fetchStatus tests use `runBlocking` because CLIENT-PREKEY-SELFHEAL
    // introduced a `withTimeoutOrNull` per-attempt deadline. Under
    // `runTest` the virtual-time scheduler advances past that deadline
    // whenever the inner block suspends on a real dispatcher (Ktor's
    // MockEngine internals), firing the timeout even though the mock
    // response returns instantly on the wall clock. `runBlocking` uses
    // the real clock so the deadline behaves as production would.
    @Test
    fun fetchStatus_200_decodesStatus() = runBlocking {
        val client = clientThatReturns(
            HttpStatusCode.OK,
            """{"remaining_opks":42,"signed_prekey_age_days":3}""",
        )
        val api = PreKeyApiClient(client, baseUrl)

        val status = api.fetchStatus("aa".repeat(32))
        assertEquals(42, status.remaining_opks)
        assertEquals(3L, status.signed_prekey_age_days)
    }

    @Test
    fun fetchStatus_handlesNullSpkAge_forEmptyIdentity() = runBlocking {
        val client = clientThatReturns(
            HttpStatusCode.OK,
            """{"remaining_opks":0}""",
        )
        val api = PreKeyApiClient(client, baseUrl)

        val status = api.fetchStatus("aa".repeat(32))
        assertEquals(0, status.remaining_opks)
        assertNull(status.signed_prekey_age_days)
    }
}

/**
 * Round-trip serialization smoke test — guards against an accidental
 * @Serializable break when wire types are extended in future PRs.
 */
class WireSerializationRoundTripTest {

    @Test
    fun publishRequest_roundTripsThroughJson() {
        val original = PublishRequest(
            identity_pubkey_hex = "aa".repeat(32),
            signing_pubkey_hex = "bb".repeat(32),
            signed_pre_key = WireSignedPreKey(
                key_id = 7,
                public_key_hex = "cc".repeat(32),
                created_at_ms = 1_700_000_000_000L,
                signature_hex = "dd".repeat(64),
            ),
            one_time_pre_keys = listOf(
                WireOneTimePreKey("11".repeat(16), "22".repeat(32)),
                WireOneTimePreKey("33".repeat(16), "44".repeat(32)),
            ),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(PublishRequest.serializer(), original)
        val decoded = json.decodeFromString(PublishRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun preKeyBundle_roundTripsThroughJson_withAndWithoutOpk() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val withOpk = PreKeyBundle(
            identity_pubkey_hex = "aa".repeat(32),
            signing_pubkey_hex = "bb".repeat(32),
            signed_pre_key = WireSignedPreKey(1, "cc".repeat(32), 0L, "dd".repeat(64)),
            one_time_pre_key = WireOneTimePreKey("11".repeat(16), "22".repeat(32)),
        )
        val withoutOpk = withOpk.copy(one_time_pre_key = null)

        for (b in listOf(withOpk, withoutOpk)) {
            val encoded = json.encodeToString(PreKeyBundle.serializer(), b)
            val decoded = json.decodeFromString(PreKeyBundle.serializer(), encoded)
            assertEquals(b, decoded)
        }
    }
}
