// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import phantom.core.transport.AndroidNativeOkHttpMediaUploadTransport
import phantom.core.transport.EgressCallRegistry
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.3 P1-3 — revocation against a REAL blocking OkHttp call.
 *
 * R-N1.2's revocation tests used cooperative fakes: a
 * `CompletableDeferred` that a coroutine cancellation could interrupt at
 * a suspension point. Production has no such suspension point. Every
 * Android transport blocks in `okhttp3.Call.execute()`, which does not
 * observe coroutine cancellation at all, so those tests proved the gate
 * mechanism and nothing about the network.
 *
 * This test uses the production
 * [AndroidNativeOkHttpMediaUploadTransport] against a local server that
 * accepts the connection and then never answers — the shape of a stalled
 * transfer on a hostile network. It asserts that
 * [EgressCallRegistry.cancelAll] aborts the transfer promptly.
 *
 * The control test is the point of the exercise: without the abort, the
 * same request is still running when the deadline passes. Without that
 * control, a fast return would prove nothing about cancellation.
 *
 * Timeouts are set far above the assertion deadline so that a natural
 * timeout cannot be mistaken for a successful abort.
 */
class RealOkHttpRevocationTest {

    private lateinit var server: ServerSocket
    private val held = mutableListOf<Socket>()
    @Volatile private var accepting = true

    /** Deadline for "the abort worked". Well below the transport timeouts. */
    private val abortDeadlineMs = 5_000L

    @Before
    fun startHangingServer() {
        server = ServerSocket(0)
        thread(isDaemon = true, name = "hanging-relay") {
            while (accepting) {
                try {
                    val s = server.accept()
                    synchronized(held) { held += s }
                    // Accept, read nothing, answer nothing: the client
                    // blocks in Call.execute() waiting for a response.
                } catch (_: Throwable) {
                    return@thread
                }
            }
        }
    }

    @After
    fun stopServer() {
        accepting = false
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        runCatching { server.close() }
    }

    private fun transport(registry: EgressCallRegistry?) =
        AndroidNativeOkHttpMediaUploadTransport(
            relayBaseUrl = "http://127.0.0.1:${server.localPort}",
            log = {},
            binaryV3Enabled = { true },
            // Deliberately far above abortDeadlineMs: a natural timeout
            // must not be able to masquerade as a successful abort.
            callTimeoutMs = 120_000L,
            connectTimeoutMs = 120_000L,
            readTimeoutMs = 120_000L,
            writeTimeoutMs = 120_000L,
            callRegistry = registry,
        )

    private suspend fun awaitRegistered(registry: EgressCallRegistry): Boolean =
        withTimeoutOrNull(10_000L) {
            while (registry.liveCount() == 0) delay(20)
            true
        } ?: false

    @Test
    fun cancelAll_aborts_a_blocking_okhttp_upload() = runBlocking {
        val registry = EgressCallRegistry()
        val transport = transport(registry)

        val upload = async(Dispatchers.IO) {
            transport.uploadChunk(
                token = "t",
                mediaId = "m".repeat(16),
                idx = 0,
                total = 1,
                ciphertext = ByteArray(64),
            )
        }

        assertTrue(
            awaitRegistered(registry),
            "the production transport must register its Call before blocking — if this fails, " +
                "the adapter is not wired to the registry at all",
        )
        assertEquals(1, registry.liveCount())

        val startMs = System.currentTimeMillis()
        val cancelled = registry.cancelAll("privacy_mode_change")
        assertEquals(1, cancelled, "cancelAll must report the call it aborted")

        val result = withTimeoutOrNull(abortDeadlineMs) { upload.await() }
        val elapsed = System.currentTimeMillis() - startMs

        assertNotNull(
            result,
            "a revoked upload must abort within ${abortDeadlineMs}ms; it was still running. " +
                "Cancelling the coroutine Job alone does NOT stop okhttp3.Call.execute().",
        )
        assertTrue(
            result.isFailure,
            "an aborted upload must surface as a failure, never as success",
        )
        assertTrue(
            elapsed < abortDeadlineMs,
            "abort took ${elapsed}ms, deadline ${abortDeadlineMs}ms",
        )
        assertEquals(
            0, registry.liveCount(),
            "the aborted call must de-register itself",
        )
    }

    /**
     * Non-vacuity control. Same server, same transport, NO abort: the
     * request must still be in flight when the deadline passes. If this
     * test ever goes green quickly, the test above proves nothing.
     */
    @Test
    fun without_revocation_the_same_upload_is_still_blocked_at_the_deadline() = runBlocking {
        val registry = EgressCallRegistry()
        val transport = transport(registry)

        val upload = async(Dispatchers.IO) {
            transport.uploadChunk(
                token = "t",
                mediaId = "m".repeat(16),
                idx = 0,
                total = 1,
                ciphertext = ByteArray(64),
            )
        }
        assertTrue(awaitRegistered(registry), "the Call must be registered")

        val result = withTimeoutOrNull(abortDeadlineMs) { upload.await() }
        assertNull(
            result,
            "control: with no abort the upload must still be blocked after ${abortDeadlineMs}ms — " +
                "otherwise the abort test above is measuring a natural completion",
        )

        // Clean up so the suite does not leak a blocked thread.
        registry.cancelAll("teardown")
        upload.await()
        Unit
    }

    /**
     * The registry must survive a handle that throws: one misbehaving
     * transport cannot be allowed to prevent the others from aborting.
     */
    @Test
    fun a_throwing_handle_does_not_stop_the_others() = runBlocking {
        val registry = EgressCallRegistry()
        var secondCancelled = false
        registry.register { error("boom") }
        registry.register { secondCancelled = true }

        val n = registry.cancelAll("test")
        assertEquals(2, n)
        assertTrue(secondCancelled, "a throwing handle must not abort the cancellation sweep")
    }
}
