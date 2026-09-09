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
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 §9 focused fixture 73 — `DiagnosticNetworkProfileReporterFixedOutputPathTest`.
 *
 * Pins that the reporter ALWAYS writes to
 * `Context.filesDir/wss3/network_profile.json` and no other path. The
 * public API surface has NO parameter that could be interpreted as an
 * operator-supplied output path (Round-2 blocker 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterFixedOutputPathTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        // Local HTTP server that hands out a canonical fake IP.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.1".toByteArray(Charsets.UTF_8)
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

    @Test
    fun report_writes_to_fixed_context_filesdir_wss3_network_profile_json() {
        val endpoint = "http://127.0.0.1:${server.address.port}/"
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "0".repeat(64),
            endpoint = endpoint,
            timeoutMs = 3000,
        )
        val target = File(
            File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR),
            DiagnosticNetworkProfileReporter.REPORT_FILENAME,
        )
        assertTrue(target.exists(), "expected report at ${target.path}")
        // Additional sanity: no other file was created in filesDir/wss3/.
        val siblings = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
            .listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf(DiagnosticNetworkProfileReporter.REPORT_FILENAME), siblings)
    }

    @Test
    fun reporter_public_run_api_has_no_output_path_parameter() {
        // Reflective check: the public `run` entry point takes exactly
        // (Context, String) — no third parameter that could be an
        // operator-controlled path. Any regression that adds a
        // caller-controlled path would trip this assertion.
        val runMethod = DiagnosticNetworkProfileReporter::class.java.methods
            .firstOrNull { it.name == "run" && !it.isBridge }
        assertTrue(runMethod != null, "public run() method missing")
        assertEquals(2, runMethod!!.parameterTypes.size, "public run() must take exactly (Context, String)")
        assertEquals("android.content.Context", runMethod.parameterTypes[0].name)
        assertEquals("java.lang.String", runMethod.parameterTypes[1].name)
        // Also ensure no method surface accepts a `File` or `Path`
        // parameter — a compile-time seam that would suggest an
        // operator-supplied output.
        val hasFileParam = DiagnosticNetworkProfileReporter::class.java.methods.any { m ->
            m.parameterTypes.any {
                it.name == "java.io.File" || it.name == "java.nio.file.Path"
            }
        }
        assertFalse(
            hasFileParam,
            "reporter must not accept a File or Path parameter — would suggest operator-controlled output",
        )
    }
}
