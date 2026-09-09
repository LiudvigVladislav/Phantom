// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import phantom.android.net.GatedAppHttp
import phantom.android.net.PreviewUrlPolicy
import phantom.core.transport.EgressCallRegistry
import phantom.core.transport.PrivacyMode
import phantom.core.transport.PrivacyModeRestEgressPolicy
import phantom.core.transport.RestEgressGate
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.3 P1-1 — abuse report and link preview, the two product
 * HTTP paths that used to bypass the authority entirely.
 *
 * "Zero HTTP" is asserted at the socket: a real [ServerSocket] counts
 * accepted TCP connections. A test that only counted method calls could
 * not tell a refused request from one that reached the network and
 * failed for some other reason.
 *
 * The link-preview target is chosen by whoever sent the message, so the
 * Private/Ghost assertions here are what stop a remote peer from
 * learning the reader's real network origin.
 *
 * Every fail-closed assertion is paired with a Standard control that
 * proves the same call DOES reach the socket, so none of them can pass
 * because the request was broken rather than refused.
 */
class GatedAppHttpFailClosedTest {

    private lateinit var server: ServerSocket
    private val connections = AtomicInteger(0)

    @Volatile
    private var accepting = true

    private val body =
        "<html><head><title>Hi</title></head><body>" + "x".repeat(500) + "</body></html>"

    @Before
    fun startCountingServer() {
        server = ServerSocket(0)
        thread(isDaemon = true, name = "counting-server") {
            while (accepting) {
                try {
                    val socket = server.accept()
                    connections.incrementAndGet()
                    // Drain the request head (and body, if any) so the
                    // client's write completes, then answer with a
                    // well-formed minimal response.
                    val input = socket.getInputStream().bufferedReader()
                    var line: String? = input.readLine()
                    var contentLength = 0
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        line = input.readLine()
                    }
                    repeat(contentLength) { input.read() }

                    val bytes = body.toByteArray()
                    val head = buildString {
                        append("HTTP/1.1 200 OK").append(CRLF)
                        append("Content-Type: text/html").append(CRLF)
                        append("Content-Length: ").append(bytes.size).append(CRLF)
                        append("Connection: close").append(CRLF)
                        append(CRLF)
                    }
                    val out = socket.getOutputStream()
                    out.write(head.toByteArray())
                    out.write(bytes)
                    out.flush()
                    socket.close()
                } catch (_: Throwable) {
                    return@thread
                }
            }
        }
    }

    @After
    fun stopServer() {
        accepting = false
        runCatching { server.close() }
    }

    private fun http(mode: PrivacyMode?): GatedAppHttp {
        val registry = EgressCallRegistry()
        return GatedAppHttp(
            egressGate = RestEgressGate(
                policy = PrivacyModeRestEgressPolicy { mode },
                callRegistry = registry,
            ),
            callRegistry = registry,
        )
    }

    private fun url(path: String) = "http://127.0.0.1:" + server.localPort + path

    private val reportBody = """{"reporter_key":"aa","reported_key":"bb","category":"SPAM"}"""

    // ── Abuse report ──────────────────────────────────────────────────

    @Test
    fun abuse_report_opens_zero_connections_in_private() = runBlocking {
        val outcome = http(PrivacyMode.Private).postJson(url("/report"), reportBody)
        assertEquals(
            GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome,
            "an abuse report in Private must be refused, not attempted",
        )
        assertEquals(0, connections.get(), "no TCP connection may be opened in Private")
    }

    @Test
    fun abuse_report_opens_zero_connections_in_ghost() = runBlocking {
        val outcome = http(PrivacyMode.Ghost).postJson(url("/report"), reportBody)
        assertEquals(GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome)
        assertEquals(0, connections.get(), "no TCP connection may be opened in Ghost")
    }

    @Test
    fun abuse_report_opens_zero_connections_when_the_mode_is_unreadable() = runBlocking {
        // A malformed persisted mode reads as null and must fail closed.
        val outcome = http(null).postJson(url("/report"), reportBody)
        assertEquals(GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome)
        assertEquals(0, connections.get())
    }

    @Test
    fun abuse_report_reaches_the_network_in_standard() = runBlocking {
        // Non-vacuity control for the three assertions above.
        val outcome = http(PrivacyMode.Standard).postJson(url("/report"), reportBody)
        assertTrue(
            outcome is GatedAppHttp.Outcome.Ok,
            "Standard must still be able to report abuse, got " + outcome,
        )
        assertEquals(200, outcome.value)
        assertEquals(1, connections.get(), "Standard must open exactly one connection")
    }

    // ── Link preview ──────────────────────────────────────────────────

    @Test
    fun link_preview_opens_zero_connections_in_private() = runBlocking {
        val outcome = http(PrivacyMode.Private).getText(
            url = url("/page"),
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            // These cases exercise the EGRESS GATE, not the address
            // policy: the loopback test server would otherwise be
            // refused by PreviewUrlPolicy before the gate is consulted,
            // and the assertion would pass for the wrong reason.
            // PreviewUrlPolicySsrfTest covers the real policy.
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertEquals(
            GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome,
            "a link preview in Private must not touch a sender-chosen host",
        )
        assertEquals(0, connections.get())
    }

    @Test
    fun link_preview_opens_zero_connections_in_ghost() = runBlocking {
        val outcome = http(PrivacyMode.Ghost).getText(
            url = url("/page"),
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            // These cases exercise the EGRESS GATE, not the address
            // policy: the loopback test server would otherwise be
            // refused by PreviewUrlPolicy before the gate is consulted,
            // and the assertion would pass for the wrong reason.
            // PreviewUrlPolicySsrfTest covers the real policy.
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertEquals(GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome)
        assertEquals(0, connections.get())
    }

    @Test
    fun link_preview_reaches_the_network_in_standard() = runBlocking {
        val outcome = http(PrivacyMode.Standard).getText(
            url = url("/page"),
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            // These cases exercise the EGRESS GATE, not the address
            // policy: the loopback test server would otherwise be
            // refused by PreviewUrlPolicy before the gate is consulted,
            // and the assertion would pass for the wrong reason.
            // PreviewUrlPolicySsrfTest covers the real policy.
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertTrue(outcome is GatedAppHttp.Outcome.Ok, "expected Ok, got " + outcome)
        assertTrue("<title>Hi</title>" in outcome.value, "body must be readable in Standard")
        assertEquals(1, connections.get())
    }

    @Test
    fun link_preview_refuses_the_loopback_server_under_the_real_policy() = runBlocking {
        // Same Standard mode, same reachable server, real address policy:
        // the request must be refused before any connection is opened.
        val outcome = http(PrivacyMode.Standard).getText(
            url = url("/page"),
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
        )
        assertTrue(
            outcome is GatedAppHttp.Outcome.RefusedDestination,
            "a loopback preview target must be refused by the address policy, got " + outcome,
        )
        assertEquals("loopback_address", outcome.reason)
        assertEquals(0, connections.get(), "no TCP connection may be opened to loopback")
    }

    @Test
    fun link_preview_body_is_bounded() = runBlocking {
        val outcome = http(PrivacyMode.Standard).getText(
            url = url("/page"),
            maxChars = 16,
            userAgent = "PHANTOM/1.0",
            // These cases exercise the EGRESS GATE, not the address
            // policy: the loopback test server would otherwise be
            // refused by PreviewUrlPolicy before the gate is consulted,
            // and the assertion would pass for the wrong reason.
            // PreviewUrlPolicySsrfTest covers the real policy.
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertTrue(outcome is GatedAppHttp.Outcome.Ok, "expected Ok, got " + outcome)
        assertTrue(
            outcome.value.length <= 16,
            "a sender-chosen host must not be able to stream an unbounded body: got " +
                outcome.value.length + " chars",
        )
        assertTrue(
            body.length > 16,
            "the control body must be longer than the cap or the bound proves nothing",
        )
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}
