// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * WSS-3 §9 focused fixture 85 —
 * `DiagnosticNetworkProfileReporterSecurityExceptionFailsClosedTest`.
 *
 * Per REDLINE-4 blocker 3: if a future toolchain change ever drops
 * `ACCESS_WIFI_STATE` from the debug merged manifest, the reporter
 * MUST fail-closed by catching `SecurityException` and emitting
 * `wifi_enabled:null` + `wifi_read_error:"SECURITY_EXCEPTION"`. It
 * MUST NEVER substitute a fabricated boolean.
 *
 * The fixture installs a custom Robolectric shadow that overrides
 * `WifiManager.isWifiEnabled()` to throw. Verifies both the
 * `wifi_enabled:null` marker and the `wifi_read_error` string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [DiagnosticNetworkProfileReporterSecurityExceptionFailsClosedTest.ThrowingWifiManager::class],
)
class DiagnosticNetworkProfileReporterSecurityExceptionFailsClosedTest {

    /**
     * Custom Robolectric shadow that intercepts `isWifiEnabled` and
     * throws `SecurityException`. Only active for this test class
     * via the `shadows = [...]` @Config attribute; every other
     * Robolectric-hosted test in the module continues to use the
     * default `ShadowWifiManager`.
     */
    @Implements(WifiManager::class)
    class ThrowingWifiManager : ShadowWifiManager() {
        @Implementation
        override fun isWifiEnabled(): Boolean {
            throw SecurityException("simulated: ACCESS_WIFI_STATE missing")
        }
    }

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.3".toByteArray(Charsets.UTF_8)
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
    fun wifi_read_security_exception_yields_null_wifi_and_typed_read_error() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "WIFI"
            override fun hasTransportVpn(): Boolean = false
        }
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "2".repeat(64),
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = 3000,
            probeOverride = probe,
        )
        val json = File(
            File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR),
            DiagnosticNetworkProfileReporter.REPORT_FILENAME,
        ).readText()
        assertTrue(
            json.contains("\"wifi_enabled\":null"),
            "expected wifi_enabled:null on SecurityException; got: $json",
        )
        assertTrue(
            json.contains("\"wifi_read_error\":\"SECURITY_EXCEPTION\""),
            "expected wifi_read_error:\"SECURITY_EXCEPTION\"; got: $json",
        )
        // Fail-closed discipline: no fabricated boolean.
        assertFalse(
            json.contains("\"wifi_enabled\":true"),
            "reporter must never fabricate wifi_enabled=true; got: $json",
        )
        assertFalse(
            json.contains("\"wifi_enabled\":false"),
            "reporter must never fabricate wifi_enabled=false; got: $json",
        )
    }
}
