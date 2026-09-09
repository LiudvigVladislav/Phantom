// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 80 —
 * `DiagnosticNetworkProfileReporterDenylistedFieldAbsenceTest`.
 *
 * Fixture writes a report where the local canary HTTP server hands out
 * a well-known raw IP `192.0.2.1` (RFC 5737 documentation range) and
 * asserts the emitted JSON:
 *
 *   - contains NEITHER the raw IP string `192.0.2.1`;
 *   - contains no field NAME matching `ip_address` / `address` /
 *     `ipv4` / `ipv6` (the denylist §5 field-name enumeration);
 *   - contains no substring matching `checkpoint_key`,
 *     `checkpoint_key_hex`, or `checkpointKey*` (Round-3 blocker 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterDenylistedFieldAbsenceTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "192.0.2.1".toByteArray(Charsets.UTF_8)
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
    fun emitted_json_contains_no_raw_ip_no_ip_field_names_no_key_substrings() {
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "WIFI"
            override fun hasTransportVpn(): Boolean = false
        }
        val key = "ee".repeat(32)
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = key,
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = 3000,
            probeOverride = probe,
        )
        val json = File(
            File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR),
            DiagnosticNetworkProfileReporter.REPORT_FILENAME,
        ).readText()

        // Raw IP body must never survive to disk.
        assertFalse(
            json.contains("192.0.2.1"),
            "emitted JSON must not contain the raw IP `192.0.2.1`. JSON: $json",
        )

        // §5 denylist field names must not appear anywhere in the JSON.
        for (name in listOf(
            "ip_address", "\"address\"", "\"ipv4\"", "\"ipv6\"", "\"ip\"",
        )) {
            assertFalse(
                json.contains(name),
                "emitted JSON must not contain the field name `$name`. JSON: $json",
            )
        }

        // No form of the checkpoint key.
        for (forbidden in listOf(
            "checkpoint_key", "checkpoint_key_hex", "checkpoint-key",
            "checkpointKey", "checkpointKeyHex",
        )) {
            assertFalse(
                json.contains(forbidden),
                "emitted JSON must not contain `$forbidden`. JSON: $json",
            )
        }
        assertFalse(
            json.contains(key),
            "emitted JSON must not contain the raw key hex.",
        )
    }
}
