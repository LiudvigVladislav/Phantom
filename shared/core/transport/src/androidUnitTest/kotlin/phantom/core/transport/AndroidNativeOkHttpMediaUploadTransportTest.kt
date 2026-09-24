// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNativeOkHttpMediaUploadTransportTest {

    @Test
    fun binary_v3_reuses_one_connection_for_consecutive_upload_chunks() = runBlocking {
        TestHttpServer { _, socket ->
            repeat(2) {
                socket.readRequest()
                socket.writeNoContent()
            }
        }.use { server ->
            val transport = transport(server)

            repeat(2) { idx ->
                val result = transport.uploadChunk("token", "media", idx, 2, byteArrayOf(1, 2, 3))
                assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            }

            assertEquals(1, server.acceptedConnections.get())
        }
    }

    @Test
    fun pooled_upload_timeout_sticky_falls_back_to_fresh_connection() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        TestHttpServer { connection, socket ->
            socket.readRequest()
            if (connection == 1) {
                Thread.sleep(500)
            } else {
                socket.writeNoContent()
            }
        }.use { server ->
            val transport = transport(server, logs, timeoutMs = 150)

            assertTrue(transport.uploadChunk("token", "media", 0, 2, byteArrayOf(1)).isFailure)
            assertTrue(transport.uploadChunk("token", "media", 0, 2, byteArrayOf(1)).isSuccess)

            assertTrue(logs.any { it.contains("upload_pool_fallback") })
            assertTrue(logs.any { it.contains("upload_start") && it.contains("mode=fresh") })
            assertEquals(2, server.acceptedConnections.get())
        }
    }

    @Test
    fun binary_v3_reuses_one_connection_for_consecutive_download_chunks() = runBlocking {
        TestHttpServer { _, socket ->
            repeat(2) { idx ->
                socket.readRequest()
                socket.writeChunk(byteArrayOf(idx.toByte()))
            }
        }.use { server ->
            val transport = transport(server)

            repeat(2) { idx ->
                val result = transport.downloadChunk("token", "media", idx)
                assertEquals(byteArrayOf(idx.toByte()).toList(), result.getOrThrow().ciphertext.toList())
            }

            assertEquals(1, server.acceptedConnections.get())
        }
    }

    @Test
    fun pooled_download_timeout_sticky_falls_back_to_fresh_connection() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        TestHttpServer { connection, socket ->
            socket.readRequest()
            if (connection == 1) {
                Thread.sleep(500)
            } else {
                socket.writeChunk(byteArrayOf(7))
            }
        }.use { server ->
            val transport = transport(server, logs, timeoutMs = 150)

            assertTrue(transport.downloadChunk("token", "media", 0).isFailure)
            val recovered = transport.downloadChunk("token", "media", 0).getOrThrow()

            assertEquals(byteArrayOf(7).toList(), recovered.ciphertext.toList())
            assertTrue(logs.any { it.contains("download_pool_fallback") })
            assertTrue(logs.any { it.contains("download_start") && it.contains("mode=fresh") })
            assertEquals(2, server.acceptedConnections.get())
        }
    }

    private fun transport(
        server: TestHttpServer,
        logs: MutableList<String> = CopyOnWriteArrayList(),
        timeoutMs: Long = 2_000,
    ) = AndroidNativeOkHttpMediaUploadTransport(
        relayBaseUrl = "http://127.0.0.1:${server.port}",
        log = logs::add,
        binaryV3Enabled = { true },
        callTimeoutMs = timeoutMs,
        connectTimeoutMs = timeoutMs,
        readTimeoutMs = timeoutMs,
        writeTimeoutMs = timeoutMs,
    )

    private class TestHttpServer(
        private val handler: (connection: Int, socket: Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool()
        private val acceptor = Executors.newSingleThreadExecutor()
        val acceptedConnections = AtomicInteger(0)
        val port: Int = server.localPort

        init {
            acceptor.execute {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        val connection = acceptedConnections.incrementAndGet()
                        workers.execute {
                            socket.use { handler(connection, it) }
                        }
                    } catch (_: Throwable) {
                        if (!server.isClosed) throw IllegalStateException("test server accept failed")
                    }
                }
            }
        }

        override fun close() {
            server.close()
            acceptor.shutdownNow()
            acceptor.awaitTermination(1, TimeUnit.SECONDS)
            workers.shutdown()
            if (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
                workers.shutdownNow()
                workers.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }
}

private fun Socket.readRequest() {
    val input = getInputStream()
    var contentLength = 0
    while (true) {
        val line = input.readAsciiLine()
        if (line.isEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = line.substringAfter(':').trim().toInt()
        }
    }
    repeat(contentLength) {
        check(input.read() >= 0) { "request body truncated" }
    }
}

private fun InputStream.readAsciiLine(): String {
    val bytes = ArrayList<Byte>()
    while (true) {
        val next = read()
        check(next >= 0) { "request headers truncated" }
        if (next == '\n'.code) break
        if (next != '\r'.code) bytes += next.toByte()
    }
    return bytes.toByteArray().toString(Charsets.US_ASCII)
}

private fun Socket.writeNoContent() {
    getOutputStream().apply {
        write((
            "HTTP/1.1 204 No Content\r\n" +
                "Content-Length: 0\r\n" +
                "X-Chunk-Stored: 1\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII))
        flush()
    }
}

private fun Socket.writeChunk(ciphertext: ByteArray) {
    getOutputStream().apply {
        write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${ciphertext.size}\r\n" +
                "X-Chunk-Total: 2\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII))
        write(ciphertext)
        flush()
    }
}
