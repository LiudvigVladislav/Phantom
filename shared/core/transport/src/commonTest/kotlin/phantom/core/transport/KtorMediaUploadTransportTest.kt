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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [KtorMediaUploadTransport] using Ktor's [MockEngine].
 *
 * Each test builds its own engine so no state leaks between tests.
 * The bearer token is passed explicitly per call; tests verify the
 * `Authorization: Bearer <token>` header reaches the relay.
 */
@OptIn(ExperimentalEncodingApi::class)
class KtorMediaUploadTransportTest {

    private val baseUrl = "https://relay.test"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val testToken = "test-bearer-token-abc123"

    private fun client(
        status: HttpStatusCode,
        body: String,
        contentType: String = ContentType.Application.Json.toString(),
        urlAssert: (String) -> Unit = {},
        headerAssert: (Map<String, List<String>>) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine) {
        install(ContentNegotiation) { json(json) }
        engine {
            addHandler { request ->
                urlAssert(request.url.toString())
                headerAssert(request.headers.entries().associate { it.key to it.value })
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, contentType),
                )
            }
        }
    }

    private fun smallCiphertext(): ByteArray = ByteArray(200) { it.toByte() }

    // ── uploadChunk happy paths ────────────────────────────────────────────────

    @Test
    fun uploadChunk_201_returnsStored() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.Created, """{"status":"stored"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(
            token = testToken,
            mediaId = "testmediaid",
            idx = 0,
            total = 5,
            ciphertext = smallCiphertext(),
        )
        assertTrue(result.isSuccess)
        assertEquals(MediaUploadTransport.UploadStatus.STORED, result.getOrThrow())
    }

    @Test
    fun uploadChunk_200_returnsDuplicate() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.OK, """{"status":"duplicate"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(
            token = testToken,
            mediaId = "testmediaid",
            idx = 0,
            total = 5,
            ciphertext = smallCiphertext(),
        )
        assertTrue(result.isSuccess)
        assertEquals(MediaUploadTransport.UploadStatus.DUPLICATE, result.getOrThrow())
    }

    // ── uploadChunk error paths ────────────────────────────────────────────────

    @Test
    fun uploadChunk_409_ciphertextMismatch_returnsMediaConflictException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.Conflict, """{"error":"ciphertext_mismatch"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is MediaConflictException, "expected MediaConflictException, got $ex")
        assertEquals("ciphertext_mismatch", (ex as MediaConflictException).reason)
    }

    @Test
    fun uploadChunk_409_totalMismatch_returnsMediaConflictException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.Conflict, """{"error":"total_mismatch"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is MediaConflictException)
        assertEquals("total_mismatch", (ex as MediaConflictException).reason)
    }

    @Test
    fun uploadChunk_413_returnsMediaQuotaException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.PayloadTooLarge, """{"error":"body_too_large"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is MediaQuotaException, "expected MediaQuotaException, got $ex")
        assertEquals("body_too_large", (ex as MediaQuotaException).reason)
    }

    @Test
    fun uploadChunk_401_returnsMediaAuthException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaAuthException)
    }

    // ── downloadChunk happy path ───────────────────────────────────────────────

    @Test
    fun downloadChunk_200_returnsCiphertextAndTotal() = runTest {
        val rawCiphertext = ByteArray(300) { it.toByte() }
        val b64 = Base64.encode(rawCiphertext)
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.OK,
                """{"ciphertext_b64":"$b64","total":7}""",
            ),
            relayBaseUrl = baseUrl,
        )
        val result = transport.downloadChunk(testToken, "mediaId", 0)
        assertTrue(result.isSuccess)
        val dl = result.getOrThrow()
        assertEquals(7, dl.total)
        assertTrue(rawCiphertext.contentEquals(dl.ciphertext))
    }

    // ── downloadChunk error paths ──────────────────────────────────────────────

    @Test
    fun downloadChunk_404_returnsNotFoundException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.NotFound, """{"error":"not_found"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.downloadChunk(testToken, "missing", 0)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() === NotFoundException)
    }

    @Test
    fun downloadChunk_401_returnsMediaAuthException() = runTest {
        val transport = KtorMediaUploadTransport(
            httpClient = client(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}"""),
            relayBaseUrl = baseUrl,
        )
        val result = transport.downloadChunk(testToken, "id", 0)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaAuthException)
    }

    // ── client-side oversize precheck ─────────────────────────────────────────

    @Test
    fun uploadChunk_oversizeCiphertext_rejectsWithoutHttpCall() = runTest {
        var httpCallMade = false
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.Created,
                """{"status":"stored"}""",
                urlAssert = { httpCallMade = true },
            ),
            relayBaseUrl = baseUrl,
        )

        // A ciphertext of 1900 bytes encodes to ~2534 base64 chars + 120 overhead = ~2654 > 2600
        val oversizeCiphertext = ByteArray(1900) { 0x42 }
        val result = transport.uploadChunk(testToken, "id", 0, 1, oversizeCiphertext)

        assertFalse(httpCallMade, "HTTP call should not be made for oversized chunk")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException, got $ex")
        assertTrue(
            ex.message?.contains("ciphertext_too_large_for_chunk") == true,
            "message should contain ciphertext_too_large_for_chunk: ${ex.message}",
        )
    }

    // ── URL shape ─────────────────────────────────────────────────────────────

    @Test
    fun downloadChunk_url_includesMediaIdAndIdx() = runTest {
        val capturedUrl = mutableListOf<String>()
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.NotFound,
                """{"error":"not_found"}""",
                urlAssert = { capturedUrl += it },
            ),
            relayBaseUrl = baseUrl,
        )
        transport.downloadChunk(testToken, "mymediaid", 3)
        assertEquals(1, capturedUrl.size)
        assertTrue(
            capturedUrl.first().endsWith("/media/chunk/mymediaid/3"),
            "URL shape mismatch: ${capturedUrl.first()}",
        )
    }

    @Test
    fun uploadChunk_url_pointsToUploadChunkEndpoint() = runTest {
        val capturedUrl = mutableListOf<String>()
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.Created,
                """{"status":"stored"}""",
                urlAssert = { capturedUrl += it },
            ),
            relayBaseUrl = baseUrl,
        )
        transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertEquals(1, capturedUrl.size)
        assertTrue(
            capturedUrl.first().endsWith("/media/upload-chunk"),
            "URL shape mismatch: ${capturedUrl.first()}",
        )
    }

    // ── Authorization header is sent ───────────────────────────────────────────

    @Test
    fun uploadChunk_sendsBearerAuthorizationHeader() = runTest {
        var observedAuth: String? = null
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.Created,
                """{"status":"stored"}""",
                headerAssert = { headers -> observedAuth = headers[HttpHeaders.Authorization]?.firstOrNull() },
            ),
            relayBaseUrl = baseUrl,
        )
        transport.uploadChunk(testToken, "id", 0, 1, smallCiphertext())
        assertEquals("Bearer $testToken", observedAuth)
    }

    @Test
    fun downloadChunk_sendsBearerAuthorizationHeader() = runTest {
        var observedAuth: String? = null
        val transport = KtorMediaUploadTransport(
            httpClient = client(
                HttpStatusCode.NotFound,
                """{"error":"not_found"}""",
                headerAssert = { headers -> observedAuth = headers[HttpHeaders.Authorization]?.firstOrNull() },
            ),
            relayBaseUrl = baseUrl,
        )
        transport.downloadChunk(testToken, "id", 0)
        assertEquals("Bearer $testToken", observedAuth)
    }
}
