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

// Source-level source-of-truth for the reporter production file.
private val REPORTER_SOURCE_TEXT_WIFI: String by lazy {
    var candidate = java.io.File(System.getProperty("user.dir") ?: ".")
    repeat(4) {
        val probe = java.io.File(candidate, "apps/android/src/debug/kotlin/phantom/android/diagnostic/DiagnosticNetworkProfileReporter.kt")
        if (probe.exists()) return@lazy probe.readText()
        candidate = candidate.parentFile ?: candidate
    }
    error("DiagnosticNetworkProfileReporter.kt not found from cwd=${System.getProperty("user.dir")}")
}

/**
 * WSS-3 §9 focused fixture 82 —
 * `DiagnosticNetworkProfileReporterWifiMobileDataTest`.
 *
 * Pins REDLINE-3 blocker 6 through the reporter's testable
 * [SystemStateProbe] seam: `wifi_enabled` and `mobile_data_enabled`
 * emitted from the injected probe's values, two-way pinning across
 * on/off/mixed combinations. The production seam
 * ([DiagnosticNetworkProfileReporter.RealSystemStateProbe]) reads from
 * `WifiManager.isWifiEnabled()` + `TelephonyManager.isDataEnabled()` on
 * the active-data sub; the API path is source-level-asserted below to
 * cover Robolectric's per-sub-shadow limitation independently of
 * runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterWifiMobileDataTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.9".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun runReporter(wifi: Boolean, data: Boolean): String {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        val netProbe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "CELLULAR"
            override fun hasTransportVpn(): Boolean = false
        }
        val sysProbe = object : DiagnosticNetworkProfileReporter.SystemStateProbe {
            override val simSubscriptionId: Int = 3
            override val simOperatorNumeric: String = "25020"
            override val wifiEnabled: Boolean? = wifi
            override val wifiReadError: String? = null
            override val mobileDataEnabled: Boolean? = data
        }
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "1".repeat(64),
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = 3000,
            probeOverride = netProbe,
            systemStateOverride = sysProbe,
        )
        return File(dir, DiagnosticNetworkProfileReporter.REPORT_FILENAME).readText()
    }

    @Test
    fun wifi_true_and_mobile_data_true_both_emitted() {
        val json = runReporter(wifi = true, data = true)
        assertTrue(json.contains("\"wifi_enabled\":true"), "got: $json")
        assertTrue(json.contains("\"mobile_data_enabled\":true"), "got: $json")
    }

    @Test
    fun wifi_false_and_mobile_data_false_both_emitted() {
        val json = runReporter(wifi = false, data = false)
        assertTrue(json.contains("\"wifi_enabled\":false"), "got: $json")
        assertTrue(json.contains("\"mobile_data_enabled\":false"), "got: $json")
    }

    @Test
    fun wifi_off_mobile_data_on_two_way_pin() {
        val json = runReporter(wifi = false, data = true)
        assertTrue(json.contains("\"wifi_enabled\":false"), "got: $json")
        assertTrue(json.contains("\"mobile_data_enabled\":true"), "got: $json")
    }

    /**
     * Source-level pin for the production API path (analogous to
     * fixture 81's source assertion): the reporter's production
     * probe MUST use the API contract in REDLINE-3 blocker 6.
     */
    @Test
    fun reporter_production_probe_reads_via_isWifiEnabled_and_isDataEnabled() {
        val src = REPORTER_SOURCE_TEXT_WIFI
        assertTrue(
            src.contains("WifiManager::class.java"),
            "reporter production source MUST look up WifiManager service (REDLINE-3 blocker 6)",
        )
        assertTrue(
            src.contains(".isWifiEnabled"),
            "reporter production source MUST call WifiManager.isWifiEnabled() (REDLINE-3 blocker 6)",
        )
        assertTrue(
            src.contains(".isDataEnabled"),
            "reporter production source MUST call TelephonyManager.isDataEnabled() on the active-data sub (REDLINE-3 blocker 6)",
        )
    }
}
