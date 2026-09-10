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
 * WSS-3 §9 focused fixture 75 —
 * `DiagnosticNetworkProfileReporterVpnActiveStateTest`.
 *
 * Pins that when the injected [DiagnosticNetworkProfileReporter.NetworkStateProbe]
 * reports `hasTransport(TRANSPORT_VPN) == true` (via the canonical
 * `activeNetworkKind() == "VPN"`), the reporter's emitted JSON carries
 * `has_transport_vpn:true` and `active_network_kind:"VPN"`.
 *
 * A canned probe is used rather than a Robolectric shadow because the
 * shadow surface for `ConnectivityManager.getActiveNetwork()` in
 * Robolectric 4.14.1 does not expose a stable setter — the reporter
 * defines an injectable seam for exactly this reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterVpnActiveStateTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.7".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun reports_has_transport_vpn_true_when_probe_reports_VPN() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "VPN"
            override fun hasTransportVpn(): Boolean = true
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
            json.contains("\"has_transport_vpn\":true"),
            "expected has_transport_vpn:true in JSON, got: $json",
        )
        assertTrue(
            json.contains("\"active_network_kind\":\"VPN\""),
            "expected active_network_kind:\"VPN\" in JSON, got: $json",
        )
        assertTrue(
            json.contains("\"active_network_present\":true"),
            "expected active_network_present:true when kind is VPN, got: $json",
        )
    }
}
