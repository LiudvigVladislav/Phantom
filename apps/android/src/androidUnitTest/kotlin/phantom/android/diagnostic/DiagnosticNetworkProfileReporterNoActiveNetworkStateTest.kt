// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 76 —
 * `DiagnosticNetworkProfileReporterNoActiveNetworkStateTest`.
 *
 * Pins that when `getActiveNetwork() == null` (canonicalised to
 * `activeNetworkKind() == "NONE"` in the reporter's [NetworkStateProbe]),
 * the emitted report carries `active_network_present:false`,
 * `active_network_kind:"OTHER"`, and NO egress attempt was made —
 * proved by attaching a canary HTTP server that fails the test if
 * touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterNoActiveNetworkStateTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer
    private lateinit var hitCount: AtomicInteger

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        hitCount = AtomicInteger(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hitCount.incrementAndGet()
            val body = "must-not-be-hit".toByteArray(Charsets.UTF_8)
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
    fun no_active_network_skips_egress_and_emits_active_network_present_false() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "NONE"
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
        assertEquals(0, hitCount.get(), "reporter must NOT contact egress endpoint when no active network")
        assertTrue(
            json.contains("\"active_network_present\":false"),
            "expected active_network_present:false, got: $json",
        )
        assertTrue(
            json.contains("\"active_network_kind\":\"OTHER\""),
            "expected active_network_kind:\"OTHER\" when active_network_present is false, got: $json",
        )
        assertTrue(
            json.contains("\"hmac_fp_hex\":null"),
            "expected hmac_fp_hex:null when no egress attempted, got: $json",
        )
        assertTrue(
            json.contains("\"http_status\":null"),
            "expected http_status:null when no egress attempted, got: $json",
        )
    }
}
