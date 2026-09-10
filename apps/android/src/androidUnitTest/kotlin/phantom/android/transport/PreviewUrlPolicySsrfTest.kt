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
import phantom.core.transport.RestEgressDecision
import phantom.core.transport.RestEgressGate
import phantom.core.transport.RestEgressPolicy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.4 P2 — SSRF through link preview.
 *
 * The preview URL is chosen by whoever SENT the message. R-N1.3 removed
 * the automatic fetch and put the request behind the egress gate, but in
 * Standard a tap still performed a GET against an arbitrary host, with
 * platform redirect following enabled. `http://127.0.0.1:<port>/…`, a
 * router at `http://192.168.1.1/…`, or a public URL that 302-redirects
 * to either, all worked.
 *
 * Two layers are tested here: the address predicate itself, and the
 * redirect loop that must re-apply it to every hop. A filter applied
 * only to the first URL is no filter at all.
 */
class PreviewUrlPolicySsrfTest {

    // ── The address predicate ─────────────────────────────────────────

    private fun refusalFor(literal: String): String? =
        PreviewUrlPolicy.refusalReason(InetAddress.getByName(literal))

    @Test
    fun non_global_ipv4_addresses_are_refused() {
        // IANA IPv4 Special-Purpose Registry, Globally Reachable = False,
        // plus multicast and limited broadcast. The expected REASON is
        // asserted, not merely "refused", so a predicate that refused the
        // right address for the wrong rule would be caught.
        val cases = mapOf(
            "0.0.0.0" to "this_network",
            "0.1.2.3" to "this_network",
            "10.0.0.1" to "private_address",
            "100.64.0.1" to "cgnat_address",
            "100.127.255.254" to "cgnat_address",
            "127.0.0.1" to "loopback_address",
            "127.1.2.3" to "loopback_address",
            "169.254.1.1" to "link_local_address",
            "172.16.5.4" to "private_address",
            "172.31.255.255" to "private_address",
            "192.0.0.8" to "ipv4_dummy_address",
            "192.0.2.1" to "test_net_1",
            "192.88.99.1" to "deprecated_6to4_relay",
            "192.168.1.1" to "private_address",
            "198.18.0.1" to "benchmark_address",
            "198.19.255.255" to "benchmark_address",
            "198.51.100.1" to "test_net_2",
            "203.0.113.1" to "test_net_3",
            "224.0.0.1" to "multicast_address",
            "239.255.255.250" to "multicast_address",
            "240.0.0.1" to "reserved_address",
            "255.255.255.255" to "reserved_address",
        )
        for ((literal, expected) in cases) {
            assertEquals(expected, refusalFor(literal), "wrong verdict for $literal")
        }
    }

    @Test
    fun globally_reachable_special_purpose_ipv4_is_not_refused() {
        // These sit in the special-purpose registry but are marked
        // Globally Reachable = TRUE: real anycast services on the public
        // Internet. Refusing them would block ordinary destinations for
        // no security benefit, so the policy must let them through.
        val allowed = listOf(
            "192.31.196.1",   // AS112-v4
            "192.175.48.1",   // direct delegation AS112
            "192.52.193.1",   // AMT
            "172.32.0.1",     // just outside 172.16/12
            "100.128.0.1",    // just outside 100.64/10
            "192.0.1.1",      // just outside 192.0.0.0/24
            "198.20.0.1",     // just outside 198.18/15
            "203.0.114.1",    // just outside TEST-NET-3
        )
        for (literal in allowed) {
            assertNull(refusalFor(literal), "$literal is globally reachable and must be allowed")
        }
    }

    @Test
    fun non_global_ipv6_addresses_are_refused() {
        val cases = mapOf(
            "::" to "unspecified_address",
            "::1" to "loopback_address",
            "fe80::1" to "link_local_address",
            "fc00::1" to "unique_local_address",
            "fd12:3456:789a::1" to "unique_local_address",
            "ff02::1" to "multicast_address",
            "100::1" to "discard_only_address",
            "100:0:0:1::1" to "dummy_prefix_address",
            "64:ff9b:1::1" to "local_use_nat64",
            "2001::1" to "teredo_address",
            "2001:2::1" to "benchmark_address",
            "2001:10::1" to "deprecated_orchid",
            "2001:db8::1" to "documentation_address",
            "3fff::1" to "documentation_address",
            "5f00::1" to "srv6_sid_address",
        )
        for ((literal, expected) in cases) {
            assertEquals(expected, refusalFor(literal), "wrong verdict for $literal")
        }
    }

    @Test
    fun globally_reachable_ipv6_anycast_is_not_refused() {
        // Globally Reachable = True entries in the IPv6 special-purpose
        // registry must not be swept up by the prefix matching above.
        val allowed = listOf(
            "2001:1::1",        // PCP anycast
            "2001:1::2",        // TURN anycast
            "2001:4:112::1",    // AS112-v6
            "2620:4f:8000::1",  // direct delegation AS112
            "2001:db9::1",      // just outside 2001:db8::/32
            "2001:20::1",       // ORCHIDv2 — Globally Reachable = True
            "2001:2f:ffff::1",  // upper end of 2001:20::/28
            "2001:30::1",       // Drone Remote ID DETs — Globally Reachable = True
            "2001:3f:ffff::1",  // upper end of 2001:30::/28
            "100:0:0:2::1",     // outside both 100::/64 and 100:0:0:1::/64
        )
        for (literal in allowed) {
            assertNull(refusalFor(literal), "$literal is globally reachable and must be allowed")
        }
    }

    @Test
    fun the_four_prefixes_r_n1_5_got_wrong_have_explicit_parity_fixtures() {
        // R-N1.5 shipped an IPv6 table that diverged from IANA in both
        // directions. These four are pinned individually so a future edit
        // cannot silently reintroduce either error.
        //
        // Globally Reachable = False -> must be REFUSED:
        assertEquals(
            "benchmark_address", refusalFor("2001:2::1"),
            "2001:2::/48 is Benchmarking, Globally Reachable = False. R-N1.5 allowed it.",
        )
        assertEquals(
            "dummy_prefix_address", refusalFor("100:0:0:1::1"),
            "100:0:0:1::/64 is the Dummy Prefix (RFC 9780), Globally Reachable = False. " +
                "R-N1.5 matched only the adjacent 100::/64 and left this open.",
        )
        // Globally Reachable = True -> must be ALLOWED:
        assertNull(
            refusalFor("2001:20::1"),
            "2001:20::/28 is ORCHIDv2, Globally Reachable = True. R-N1.5 blocked it, which " +
                "is over-blocking a reachable prefix.",
        )
        assertNull(
            refusalFor("2001:30::1"),
            "2001:30::/28 is Drone Remote ID Protocol Entity Tags, Globally Reachable = " +
                "True. R-N1.5 blocked it.",
        )
    }

    @Test
    fun nested_registry_entries_use_longest_prefix_semantics() {
        // The registries are NESTED, and R-N1.4..R-N1.6 expressed them as
        // flat `when` branches that could not represent that. Each pair
        // below is a broad FALSE entry with a more-specific TRUE entry
        // inside it: the specific one must win.
        //
        // 192.0.0.0/24 is IETF Protocol Assignments (False), but
        // 192.0.0.9 and .10 inside it are PCP/TURN anycast (True).
        assertEquals("ds_lite", refusalFor("192.0.0.0"))
        assertEquals("ipv4_dummy_address", refusalFor("192.0.0.8"))
        assertNull(refusalFor("192.0.0.9"), "192.0.0.9/32 PCP anycast is Globally Reachable")
        assertNull(refusalFor("192.0.0.10"), "192.0.0.10/32 TURN anycast is Globally Reachable")
        assertEquals("nat64_discovery", refusalFor("192.0.0.170"))
        assertEquals("nat64_discovery", refusalFor("192.0.0.171"))
        assertEquals("ietf_protocol_assignment", refusalFor("192.0.0.11"))
        assertEquals("ietf_protocol_assignment", refusalFor("192.0.0.255"))
    }

    @Test
    fun documentation_prefix_3fff_is_a_slash_20_not_a_slash_16() {
        // R-N1.6 implemented 3fff::/20 as `g0 == 0x3fff`, blocking the
        // whole /16. The /20 ends at 3fff:0fff.
        assertEquals("documentation_address", refusalFor("3fff::1"))
        assertEquals("documentation_address", refusalFor("3fff:0fff::1"))
        assertEquals(
            "documentation_address", refusalFor("3fff:0fff:ffff:ffff:ffff:ffff:ffff:ffff"),
        )
        assertNull(
            refusalFor("3fff:1000::1"),
            "3fff:1000:: is outside the registered /20 and must not be refused",
        )
        assertNull(refusalFor("3fff:ffff::1"), "also outside the /20")
    }

    @Test
    fun the_broad_2001_slash_23_entry_is_refused_by_default() {
        // 2001::/23 is IETF Protocol Assignments, Globally Reachable =
        // False. R-N1.6 omitted it entirely, so unallocated space inside
        // it was allowed.
        assertEquals(
            "ietf_protocol_assignment", refusalFor("2001:5::1"),
            "unallocated space inside 2001::/23 must be refused, not allowed",
        )
        assertEquals("ietf_protocol_assignment", refusalFor("2001:1ff::1"))
        assertEquals(
            "ietf_protocol_assignment", refusalFor("2001:2:1::1"),
            "inside 2001::/23 but outside the 2001:2::/48 benchmarking entry",
        )
        // The TRUE carve-outs inside it must survive.
        assertNull(refusalFor("2001:1::1"), "PCP anycast")
        assertNull(refusalFor("2001:1::2"), "TURN anycast")
        assertNull(refusalFor("2001:1::3"), "DNS-SD anycast")
        assertNull(refusalFor("2001:3::1"), "AMT")
        assertNull(refusalFor("2001:4:112::1"), "AS112-v6")
        assertNull(refusalFor("2001:20::1"), "ORCHIDv2")
        assertNull(refusalFor("2001:30::1"), "Drone Remote ID DETs")
        // A /128 carve-out must not widen to its neighbours.
        assertEquals(
            "ietf_protocol_assignment", refusalFor("2001:1::4"),
            "only 2001:1::1/2/3 are carved out, not the whole 2001:1::/32",
        )
        // Just outside the /23 upper bound.
        assertNull(refusalFor("2001:200::1"), "2001:200:: is outside 2001::/23")
    }

    @Test
    fun embedded_ipv4_is_judged_as_ipv4_whatever_wrapper_carries_it() {
        // Judging the wrapper alone is a bypass. 64:ff9b::/96 is itself
        // Globally Reachable = True, but a NAT64 gateway translates
        // 64:ff9b::7f00:1 to 127.0.0.1; 6to4 and the deprecated
        // IPv4-compatible form embed an address the same way.
        val cases = mapOf(
            "64:ff9b::7f00:1" to "loopback_address",      // NAT64 -> 127.0.0.1
            "64:ff9b::a00:1" to "private_address",        // NAT64 -> 10.0.0.1
            "64:ff9b::c0a8:101" to "private_address",     // NAT64 -> 192.168.1.1
            "2002:7f00:1::1" to "loopback_address",       // 6to4  -> 127.0.0.1
            "2002:c0a8:101::1" to "private_address",      // 6to4  -> 192.168.1.1
            "::127.0.0.1" to "loopback_address",          // IPv4-compatible
            "::10.0.0.1" to "private_address",            // IPv4-compatible
        )
        for ((literal, expected) in cases) {
            assertEquals(expected, refusalFor(literal), "wrong verdict for $literal")
        }
    }

    @Test
    fun an_embedded_public_ipv4_is_allowed() {
        // Non-vacuity control for the unwrapping: a translation prefix
        // carrying a PUBLIC address is a legitimate destination.
        for (literal in listOf("64:ff9b::5db8:d822", "2002:5db8:d822::1")) {
            assertNull(refusalFor(literal), "$literal wraps a public address and must be allowed")
        }
    }

    @Test
    fun ipv4_mapped_ipv6_loopback_is_refused() {
        // ::ffff:127.0.0.1 must be judged as the IPv4 address it wraps.
        val mapped = InetAddress.getByAddress(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0xFF.toByte(), 0xFF.toByte(),
                127, 0, 0, 1,
            ),
        )
        assertEquals("loopback_address", PreviewUrlPolicy.refusalReason(mapped))
    }

    @Test
    fun global_unicast_addresses_are_allowed() {
        // Non-vacuity control: if everything were refused the tests above
        // would pass while the feature was simply broken.
        for (literal in listOf("93.184.216.34", "8.8.8.8", "1.1.1.1", "2606:2800:220:1::1")) {
            assertNull(refusalFor(literal), "$literal must be allowed")
        }
    }

    // ── The URL-level check ───────────────────────────────────────────

    private fun check(url: String, resolvesTo: String? = null): PreviewUrlPolicy.Verdict =
        PreviewUrlPolicy.check(url) { host ->
            listOf(InetAddress.getByName(resolvesTo ?: host))
        }

    @Test
    fun non_http_schemes_are_refused() {
        for (u in listOf("file:///etc/passwd", "ftp://example.com/x", "gopher://x/", "jar:x!/")) {
            val v = check(u)
            assertTrue(v is PreviewUrlPolicy.Verdict.Refused, "$u must be refused")
        }
    }

    @Test
    fun userinfo_in_the_authority_is_refused() {
        val v = check("http://evil.example@93.184.216.34/", resolvesTo = "93.184.216.34")
        assertTrue(v is PreviewUrlPolicy.Verdict.Refused)
        assertEquals("userinfo_present", v.reason)
    }

    @Test
    fun a_public_hostname_resolving_to_loopback_is_refused() {
        // DNS-level bypass: the name looks public, the address is not.
        val v = check("http://totally-public.example/x", resolvesTo = "127.0.0.1")
        assertTrue(v is PreviewUrlPolicy.Verdict.Refused)
        assertEquals("loopback_address", v.reason)
    }

    @Test
    fun a_host_with_any_non_global_address_is_refused() {
        // One public and one private answer must refuse, not partially allow.
        val v = PreviewUrlPolicy.check("http://mixed.example/x") {
            listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5"))
        }
        assertTrue(v is PreviewUrlPolicy.Verdict.Refused)
        assertEquals("private_address", v.reason)
    }

    @Test
    fun a_public_url_is_allowed() {
        assertEquals(
            PreviewUrlPolicy.Verdict.Allowed,
            check("https://example.com/page", resolvesTo = "93.184.216.34"),
        )
    }

    @Test
    fun an_unresolvable_host_is_refused() {
        val v = PreviewUrlPolicy.check("http://nope.invalid/x") { error("no such host") }
        assertTrue(v is PreviewUrlPolicy.Verdict.Refused)
        assertEquals("unresolvable_host", v.reason)
    }

    // ── The redirect loop ─────────────────────────────────────────────

    private lateinit var server: ServerSocket
    private val connections = AtomicInteger(0)
    private val hits = mutableListOf<String>()

    @Volatile
    private var accepting = true

    /** Answers every request with a 302 to the loopback secret path. */
    @Before
    fun startRedirectingServer() {
        server = ServerSocket(0)
        thread(isDaemon = true, name = "redirector") {
            while (accepting) {
                try {
                    val socket = server.accept()
                    connections.incrementAndGet()
                    val input = socket.getInputStream().bufferedReader()
                    val requestLine = input.readLine() ?: ""
                    synchronized(hits) { hits += requestLine }
                    var line: String? = input.readLine()
                    while (!line.isNullOrEmpty()) line = input.readLine()

                    val body = "secret"
                    val out = socket.getOutputStream()
                    if (requestLine.contains("/loop")) {
                        // Redirects to itself forever: the hop cap must
                        // terminate this, not the server.
                        out.write(
                            (
                                "HTTP/1.1 302 Found" + CRLF +
                                    "Location: http://127.0.0.1:" + server.localPort +
                                    "/loop" + CRLF +
                                    "Content-Length: 0" + CRLF +
                                    "Connection: close" + CRLF + CRLF
                                ).toByteArray()
                        )
                    } else if (requestLine.contains("/open")) {
                        out.write(
                            (
                                "HTTP/1.1 302 Found" + CRLF +
                                    "Location: http://127.0.0.1:" + server.localPort +
                                    "/secret" + CRLF +
                                    "Content-Length: 0" + CRLF +
                                    "Connection: close" + CRLF + CRLF
                                ).toByteArray()
                        )
                    } else {
                        out.write(
                            (
                                "HTTP/1.1 200 OK" + CRLF +
                                    "Content-Type: text/html" + CRLF +
                                    "Content-Length: " + body.length + CRLF +
                                    "Connection: close" + CRLF + CRLF + body
                                ).toByteArray()
                        )
                    }
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

    private fun http(): GatedAppHttp {
        val registry = EgressCallRegistry()
        return GatedAppHttp(
            egressGate = RestEgressGate(
                policy = PrivacyModeRestEgressPolicy { PrivacyMode.Standard },
                callRegistry = registry,
            ),
            callRegistry = registry,
        )
    }

    @Test
    fun a_redirect_into_loopback_is_refused_at_the_hop() = runBlocking {
        // The FIRST url passes the policy (injected as allowed, standing in
        // for a public host); the redirect target is loopback and must be
        // refused when the loop re-checks it.
        var checks = 0
        val outcome = http().getText(
            url = "http://127.0.0.1:${server.localPort}/open",
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            urlPolicy = { u ->
                checks++
                // Hop 0 (the "public" entry point) is allowed; every later
                // hop is judged by the real policy.
                if (checks == 1) PreviewUrlPolicy.Verdict.Allowed else PreviewUrlPolicy.check(u)
            },
        )

        assertTrue(
            outcome is GatedAppHttp.Outcome.RefusedDestination,
            "a redirect to loopback must be refused, got " + outcome,
        )
        assertEquals("loopback_address", outcome.reason)
        assertTrue(checks >= 2, "the policy must be applied to the redirect hop, not just hop 0")
        synchronized(hits) {
            assertTrue(hits.none { it.contains("/secret") }, "the redirect target was fetched: $hits")
        }
    }

    @Test
    fun redirect_hops_are_capped() = runBlocking {
        // A server that redirects to itself forever must terminate.
        var checks = 0
        val outcome = http().getText(
            url = "http://127.0.0.1:${server.localPort}/loop",
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            urlPolicy = { checks++; PreviewUrlPolicy.Verdict.Allowed },
        )
        assertTrue(outcome is GatedAppHttp.Outcome.Failed, "expected a bounded failure, got " + outcome)
        assertTrue(
            checks <= PreviewUrlPolicy.MAX_REDIRECTS + 2,
            "redirect following must be capped, made $checks hops",
        )
    }

    @Test
    fun a_non_redirecting_target_is_fetched_normally() = runBlocking {
        // Non-vacuity control for the whole redirect loop.
        val outcome = http().getText(
            url = "http://127.0.0.1:${server.localPort}/plain",
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertTrue(outcome is GatedAppHttp.Outcome.Ok, "expected Ok, got " + outcome)
        assertEquals("secret", outcome.value)
    }

    @Test
    fun the_gate_still_precedes_the_address_policy() = runBlocking {
        // In Private nothing is attempted at all, whatever the URL says.
        val registry = EgressCallRegistry()
        val blocked = GatedAppHttp(
            egressGate = RestEgressGate(
                policy = RestEgressPolicy { RestEgressDecision.AnonymousRequiredButUnavailable },
                callRegistry = registry,
            ),
            callRegistry = registry,
        )
        val before = connections.get()
        val outcome = blocked.getText(
            url = "http://127.0.0.1:${server.localPort}/plain",
            maxChars = 4096,
            userAgent = "PHANTOM/1.0",
            urlPolicy = { PreviewUrlPolicy.Verdict.Allowed },
        )
        assertEquals(GatedAppHttp.Outcome.BlockedByPrivacyMode, outcome)
        assertEquals(before, connections.get(), "no connection may be opened in Private")
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}
