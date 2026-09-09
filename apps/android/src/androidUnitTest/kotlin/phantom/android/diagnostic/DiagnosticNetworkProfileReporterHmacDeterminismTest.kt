// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 79 —
 * `DiagnosticNetworkProfileReporterHmacDeterminismTest`.
 *
 * Pins the observable, verifiable HMAC guarantees inside the Kotlin/JVM
 * boundary (per REDLINE-5 blocker 3):
 *
 *   - two runs with the same `checkpoint_key` + same canonical IP bytes
 *     produce the same `hmac_fp_hex`;
 *   - two runs with different `checkpoint_key`s but the same IP bytes
 *     produce different `hmac_fp_hex`s;
 *   - the emitted JSON contains NO substring matching `checkpoint_key`,
 *     `checkpoint_key_hex`, `checkpointKey`, `checkpointKeyHex`, or
 *     `checkpoint-key` — the key never lands on disk;
 *   - the fixed app-owned reporter file is the ONLY artefact created,
 *     so the emitted JSON is the entire in-JVM surface the test can
 *     inspect (JVM string immutability + GC opacity acknowledged; the
 *     shell-owned `run-as rm` invariant is proved by shell fixture 51
 *     per REDLINE-5 blocker 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterHmacDeterminismTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.42".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun runOnce(keyHex: String): String {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "WIFI"
            override fun hasTransportVpn(): Boolean = false
        }
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = keyHex,
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = 3000,
            probeOverride = probe,
        )
        return File(dir, DiagnosticNetworkProfileReporter.REPORT_FILENAME).readText()
    }

    private fun extractHmac(json: String): String? {
        val m = Regex("\"hmac_fp_hex\":\"([0-9a-f]{32})\"").find(json) ?: return null
        return m.groupValues[1]
    }

    @Test
    fun same_key_same_ip_bytes_yields_same_hmac() {
        val key = "aa".repeat(32)
        val json1 = runOnce(key)
        val json2 = runOnce(key)
        val h1 = extractHmac(json1)
        val h2 = extractHmac(json2)
        assertNotNull(h1, "hmac missing in first run: $json1")
        assertNotNull(h2, "hmac missing in second run: $json2")
        assertEquals(h1, h2, "same key + same IP bytes must yield same HMAC (determinism)")
    }

    @Test
    fun different_key_same_ip_bytes_yields_different_hmac() {
        val hA = extractHmac(runOnce("aa".repeat(32)))
        val hB = extractHmac(runOnce("bb".repeat(32)))
        assertNotNull(hA)
        assertNotNull(hB)
        assertTrue(hA != hB, "different keys must yield different HMACs; both were $hA")
    }

    @Test
    fun emitted_json_contains_no_checkpoint_key_substring() {
        val key = "cc".repeat(32)
        val json = runOnce(key)
        for (forbidden in listOf(
            "checkpoint_key", "checkpoint_key_hex", "checkpoint-key",
            "checkpointKey", "checkpointKeyHex",
        )) {
            assertFalse(
                json.contains(forbidden),
                "emitted JSON must not contain `$forbidden` substring — key never lands on disk (Round-3 blocker 2). JSON: $json",
            )
        }
        // Belt-and-braces: the raw key value itself must not appear.
        assertFalse(
            json.contains(key),
            "emitted JSON must not contain the raw key hex `$key`",
        )
    }

    @Test
    fun report_file_is_the_only_artefact_in_wss3_subdir() {
        runOnce("dd".repeat(32))
        val siblings = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
            .listFiles()?.map { it.name } ?: emptyList()
        assertEquals(
            listOf(DiagnosticNetworkProfileReporter.REPORT_FILENAME),
            siblings,
            "only the network_profile.json file may be created — no scratch/key/tmp file",
        )
    }
}
