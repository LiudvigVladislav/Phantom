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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.2 (2026-08-29) — the sibling REST boundaries (pre-key and
 * media) are covered by the SAME fail-closed, revocable authority.
 *
 * Private/Ghost must produce zero pre-key and zero media HTTP; Standard
 * must remain functional; and a transfer suspended at the network
 * boundary must be cancelled when the user leaves Standard.
 */
class SiblingRestEgressFailClosedTest {

    private class Mode(var value: PrivacyMode?)
    private fun gate(m: Mode) = RestEgressGate(PrivacyModeRestEgressPolicy { m.value })

    // ── Pre-key: a counting MockEngine proves zero HTTP under Private ──
    private class CountingEngineHolder {
        var httpCalls = 0
    }

    private fun countingHttpClient(holder: CountingEngineHolder): HttpClient =
        HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            engine {
                addHandler {
                    holder.httpCalls++
                    respond(
                        content = """{"identity_pubkey_hex":"%s",""".format("aa".repeat(32)) +
                            """"signing_pubkey_hex":"%s",""".format("bb".repeat(32)) +
                            """"signed_pre_key":{"key_id":1,""" +
                            """"public_key_hex":"%s",""".format("cc".repeat(32)) +
                            """"created_at_ms":100,"signature_hex":"%s"},""".format("dd".repeat(64)) +
                            """"one_time_pre_keys":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

    @Test
    fun prekey_fetch_bundle_makes_zero_http_in_private() = runTest {
        val holder = CountingEngineHolder()
        val api = PreKeyApiClient(
            httpClient = countingHttpClient(holder),
            relayBaseUrl = "https://relay.test",
            egressGate = gate(Mode(PrivacyMode.Private)),
        )
        val result = runCatching { api.fetchBundle("aa".repeat(32)) }
        assertEquals(0, holder.httpCalls, "no pre-key HTTP may leave in Private")
        // The fetch either returns null or surfaces the block as failure;
        // either way it did not reach the network.
        if (result.isSuccess) {
            assertEquals(null, result.getOrNull(), "blocked fetch must not yield a bundle")
        }
    }

    @Test
    fun prekey_fetch_bundle_makes_zero_http_in_ghost() = runTest {
        val holder = CountingEngineHolder()
        val api = PreKeyApiClient(
            httpClient = countingHttpClient(holder),
            relayBaseUrl = "https://relay.test",
            egressGate = gate(Mode(PrivacyMode.Ghost)),
        )
        runCatching { api.fetchBundle("aa".repeat(32)) }
        assertEquals(0, holder.httpCalls, "no pre-key HTTP may leave in Ghost")
    }

    @Test
    fun prekey_fetch_bundle_reaches_http_in_standard() = runTest {
        val holder = CountingEngineHolder()
        val api = PreKeyApiClient(
            httpClient = countingHttpClient(holder),
            relayBaseUrl = "https://relay.test",
            egressGate = gate(Mode(PrivacyMode.Standard)),
        )
        val bundle = api.fetchBundle("aa".repeat(32))
        assertTrue(holder.httpCalls >= 1, "Standard pre-key fetch must reach HTTP")
        assertTrue(bundle != null)
    }

    // ── Media: the gated decorator proves zero delegate calls ─────────
    private class RecordingMediaTransport : MediaUploadTransport {
        var uploadCalls = 0
        var downloadCalls = 0
        val uploadEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var uploadSuspends = false

        override suspend fun uploadChunk(
            token: String, mediaId: String, idx: Int, total: Int, ciphertext: ByteArray,
        ): Result<MediaUploadTransport.UploadStatus> {
            uploadCalls++
            if (uploadSuspends) {
                uploadEntered.complete(Unit)
                release.await()
            }
            return Result.success(MediaUploadTransport.UploadStatus.STORED)
        }

        override suspend fun downloadChunk(
            token: String, mediaId: String, idx: Int,
        ): Result<MediaUploadTransport.DownloadResult> {
            downloadCalls++
            return Result.success(MediaUploadTransport.DownloadResult(ByteArray(0), 1))
        }
    }

    @Test
    fun media_upload_and_download_make_zero_delegate_calls_in_private() = runTest {
        val delegate = RecordingMediaTransport()
        val gated = GatedMediaUploadTransport(delegate, gate(Mode(PrivacyMode.Private)))
        val up = gated.uploadChunk("t", "m", 0, 1, ByteArray(4))
        val down = gated.downloadChunk("t", "m", 0)
        assertEquals(0, delegate.uploadCalls, "no media upload HTTP in Private")
        assertEquals(0, delegate.downloadCalls, "no media download HTTP in Private")
        assertTrue(up.isFailure && down.isFailure)
        assertIs<MediaTransportException>(up.exceptionOrNull())
    }

    @Test
    fun media_upload_and_download_make_zero_delegate_calls_in_ghost() = runTest {
        val delegate = RecordingMediaTransport()
        val gated = GatedMediaUploadTransport(delegate, gate(Mode(PrivacyMode.Ghost)))
        gated.uploadChunk("t", "m", 0, 1, ByteArray(4))
        gated.downloadChunk("t", "m", 0)
        assertEquals(0, delegate.uploadCalls)
        assertEquals(0, delegate.downloadCalls)
    }

    @Test
    fun media_upload_reaches_delegate_in_standard() = runTest {
        val delegate = RecordingMediaTransport()
        val gated = GatedMediaUploadTransport(delegate, gate(Mode(PrivacyMode.Standard)))
        val up = gated.uploadChunk("t", "m", 0, 1, ByteArray(4))
        assertEquals(1, delegate.uploadCalls)
        assertEquals(MediaUploadTransport.UploadStatus.STORED, up.getOrNull())
    }

    // ── Media: a suspended upload is revoked on mode switch ───────────
    @Test
    fun suspended_media_upload_is_cancelled_on_revocation() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val g = gate(mode)
        val delegate = RecordingMediaTransport().apply { uploadSuspends = true }
        val gated = GatedMediaUploadTransport(delegate, g)

        var result: Result<MediaUploadTransport.UploadStatus>? = null
        var cancelled = false
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            try {
                result = gated.uploadChunk("t", "m", 0, 1, ByteArray(4))
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(delegate.uploadEntered.isCompleted, "upload must have entered the delegate")

        mode.value = PrivacyMode.Private
        g.revokeAndJoin("switch")
        runCurrent()
        worker.join()

        assertFalse(result?.isSuccess == true, "a revoked upload must not report success")
        assertTrue(cancelled || result?.isFailure == true, "revoked upload must fail or cancel")
        assertEquals(0, g.activeDispatchCount())
    }
}
