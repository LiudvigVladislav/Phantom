// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 77 —
 * `DiagnosticNetworkProfileReporterEgressTimeoutTest`.
 *
 * Pins that when the HTTP client's read timeout fires, the reporter
 * emits `hmac_fp_hex:null`, `http_status:null`, and
 * `timeout_ms_used:<the configured timeout>` — no partial or fabricated
 * fingerprint escapes.
 *
 * The test forces the timeout by driving the reporter at a local
 * HttpServer whose handler blocks for longer than the timeout the
 * reporter runs under. `timeoutMs=250` (short but well above the
 * jitter floor of Robolectric-hosted socket setup) keeps the test
 * fast; the released reporter's public [run] entry point pins the
 * production 5 s timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterEgressTimeoutTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer
    private lateinit var released: CountDownLatch

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        released = CountDownLatch(1)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            // Block far longer than the reporter's timeoutMs so the
            // read side fires SocketTimeoutException. The latch lets
            // the test suite release the handler in @After.
            try {
                released.await(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
            try {
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.close()
            } catch (_: Throwable) {}
        }
        server.start()
    }

    @After
    fun tearDown() {
        released.countDown()
        server.stop(0)
    }

    @Test
    fun timeout_yields_null_hmac_null_status_and_records_timeout_ms_used() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "WIFI"
            override fun hasTransportVpn(): Boolean = false
        }
        val timeoutMs = 250
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "0".repeat(64),
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = timeoutMs,
            probeOverride = probe,
        )
        val json = File(
            File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR),
            DiagnosticNetworkProfileReporter.REPORT_FILENAME,
        ).readText()
        assertTrue(
            json.contains("\"hmac_fp_hex\":null"),
            "expected hmac_fp_hex:null on timeout, got: $json",
        )
        assertTrue(
            json.contains("\"http_status\":null"),
            "expected http_status:null on timeout, got: $json",
        )
        assertTrue(
            json.contains("\"timeout_ms_used\":$timeoutMs"),
            "expected timeout_ms_used:$timeoutMs, got: $json",
        )
    }
}
