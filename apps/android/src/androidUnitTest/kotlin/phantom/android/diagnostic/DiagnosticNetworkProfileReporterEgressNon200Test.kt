// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 78 —
 * `DiagnosticNetworkProfileReporterEgressNon200Test`.
 *
 * Pins that when the HTTP echo endpoint returns a non-200 status
 * (fixture uses 502) the reporter emits `hmac_fp_hex:null` and
 * records the actual status in `http_status`. No partial hash is
 * fabricated from a non-2xx response body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterEgressNon200Test {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "Bad Gateway".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(502, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun non_200_yields_null_hmac_and_records_http_status() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "WIFI"
            override fun hasTransportVpn(): Boolean = false
        }
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "0".repeat(64),
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = 3000,
            probeOverride = probe,
        )
        val json = File(
            File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR),
            DiagnosticNetworkProfileReporter.REPORT_FILENAME,
        ).readText()
        assertTrue(
            json.contains("\"hmac_fp_hex\":null"),
            "expected hmac_fp_hex:null on 502, got: $json",
        )
        assertTrue(
            json.contains("\"http_status\":502"),
            "expected http_status:502, got: $json",
        )
    }
}
