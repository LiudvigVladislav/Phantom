// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * N1-F2 R-N1.4 .. R-N1.6 — address policy for link preview.
 *
 * The preview URL is chosen by whoever SENT the message. In Standard the
 * user can tap to load it, and the app then performs a GET against an
 * arbitrary host — including `http://127.0.0.1:<port>/…`,
 * `http://192.168.1.1/…`, or a public URL that redirects to either. That
 * makes the preview an SSRF primitive against the phone's own loopback
 * services and the user's local network.
 *
 * ## The criterion is IANA "Globally Reachable"
 *
 * The IANA IPv4 and IPv6 Special-Purpose Address Registries mark each
 * entry Globally Reachable True or False. This policy refuses exactly
 * the False ones, plus multicast (not unicast) and the deprecated 6to4
 * relay anycast prefix.
 *
 * ## Longest-prefix semantics — R-N1.6
 *
 * The registries are NESTED: `192.0.0.0/24` is False, but `192.0.0.9/32`
 * (PCP anycast) and `192.0.0.10/32` (TURN anycast) inside it are True.
 * `2001::/23` is False, but `2001:20::/28`, `2001:30::/28`, `2001:3::/32`
 * and several /128 anycast addresses inside it are True.
 *
 * R-N1.4 and R-N1.5 expressed the registries as ad-hoc `when` branches,
 * which cannot represent that nesting: broad rules swallowed the
 * specific exceptions, `3fff::/20` was written as a /16, and the broad
 * `2001::/23` entry was missing altogether, so unallocated space such as
 * `2001:5::1` was allowed.
 *
 * The rules now live in one ordered table — longest prefix first, first
 * match wins, each entry carrying its Globally Reachable flag. That is
 * the registry's own evaluation order, so an entry can be added without
 * reasoning about what else might shadow it.
 *
 * ## Embedded IPv4 must be judged, not the wrapper
 *
 * Several IPv6 forms carry an IPv4 address inside them. Judging the
 * wrapper alone is a bypass: `64:ff9b::7f00:1` sits in a prefix IANA
 * marks Globally Reachable = True, yet a NAT64 gateway translates it to
 * `127.0.0.1`. The same applies to 6to4 (`2002::/16`) and to both
 * IPv4-mapped and the deprecated IPv4-compatible forms. Those are
 * unwrapped and judged by the IPv4 table before the IPv6 table runs.
 *
 * ## Deliberate scope
 *
 * Only `http` and `https` are accepted. Userinfo in the authority is
 * refused outright. ALL resolved addresses must pass: a host answering
 * with one public and one private address is refused, not partially
 * allowed.
 *
 * ## Residual: DNS rebinding
 *
 * This check resolves the host, and the HTTP stack resolves it again
 * when it connects. A hostile DNS server can answer differently the
 * second time. **SSRF is therefore NOT fully closed**, and this class
 * does not claim it is. Closing it requires the connection to be pinned
 * to the validated address with an explicit `Host` header, which the
 * current HTTP layer does not expose.
 *
 * ## Residual: the registries change
 *
 * This table is a snapshot. Nothing in the build detects a registry
 * update, and two consecutive rounds shipped tables that were already
 * wrong.
 */
object PreviewUrlPolicy {

    sealed class Verdict {
        data object Allowed : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    /** Max redirect hops a preview may follow. Each hop is re-checked. */
    const val MAX_REDIRECTS: Int = 5

    /** Injectable for tests; production resolves through the platform. */
    fun interface Resolver {
        fun resolve(host: String): List<InetAddress>
    }

    private val systemResolver = Resolver { host ->
        InetAddress.getAllByName(host).toList()
    }

    fun check(rawUrl: String, resolver: Resolver = systemResolver): Verdict {
        val uri = try {
            URI(rawUrl)
        } catch (_: Throwable) {
            return Verdict.Refused("malformed_url")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Verdict.Refused("scheme_not_allowed:${scheme ?: "none"}")
        }
        if (uri.userInfo != null) return Verdict.Refused("userinfo_present")
        val host = uri.host
        if (host.isNullOrBlank()) return Verdict.Refused("no_host")

        val addresses = try {
            resolver.resolve(host)
        } catch (_: Throwable) {
            return Verdict.Refused("unresolvable_host")
        }
        if (addresses.isEmpty()) return Verdict.Refused("unresolvable_host")

        for (address in addresses) {
            // The address itself is never echoed: a refusal reason may be
            // shown to the user and must not leak local network topology.
            refusalReason(address)?.let { return Verdict.Refused(it) }
        }
        return Verdict.Allowed
    }

    /** `null` when the address is globally reachable unicast. */
    fun refusalReason(address: InetAddress): String? = when (address) {
        is Inet4Address -> ipv4RefusalReason(address.address)
        is Inet6Address -> ipv6RefusalReason(address)
        else -> "unknown_address_family"
    }

    // -- The rule table ------------------------------------------------

    /**
     * One registry entry. [globallyReachable] mirrors the registry
     * column; [reason] is the refusal label, unused when the entry is
     * reachable. Entries are evaluated longest [bits] first.
     *
     * [literal] is always an IP literal, so building the prefix never
     * performs a DNS lookup.
     */
    private class Rule(
        literal: String,
        val bits: Int,
        val globallyReachable: Boolean,
        val reason: String,
    ) {
        val prefix: ByteArray = InetAddress.getByName(literal).address

        fun matches(addr: ByteArray): Boolean {
            if (addr.size != prefix.size) return false
            var remaining = bits
            var i = 0
            while (remaining >= 8) {
                if (addr[i] != prefix[i]) return false
                i++
                remaining -= 8
            }
            if (remaining == 0) return true
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            return (addr[i].toInt() and mask) == (prefix[i].toInt() and mask)
        }
    }

    private fun List<Rule>.longestPrefixFirst() = sortedByDescending { it.bits }

    /** IANA IPv4 Special-Purpose Address Registry. */
    private val ipv4Rules: List<Rule> = listOf(
        // Globally Reachable = TRUE, nested inside 192.0.0.0/24.
        Rule("192.0.0.9", 32, true, "pcp_anycast"),
        Rule("192.0.0.10", 32, true, "turn_anycast"),
        // Globally Reachable = TRUE, real public anycast services.
        Rule("192.31.196.0", 24, true, "as112_v4"),
        Rule("192.52.193.0", 24, true, "amt"),
        Rule("192.175.48.0", 24, true, "as112_direct_delegation"),
        // Globally Reachable = FALSE.
        Rule("192.0.0.8", 32, false, "ipv4_dummy_address"),
        Rule("192.0.0.170", 32, false, "nat64_discovery"),
        Rule("192.0.0.171", 32, false, "nat64_discovery"),
        Rule("192.0.0.0", 29, false, "ds_lite"),
        Rule("192.0.0.0", 24, false, "ietf_protocol_assignment"),
        Rule("192.0.2.0", 24, false, "test_net_1"),
        Rule("198.51.100.0", 24, false, "test_net_2"),
        Rule("203.0.113.0", 24, false, "test_net_3"),
        // Deprecated by RFC 7526. Not a Globally-Reachable=False entry,
        // but a deprecated anycast relay prefix is never a legitimate
        // preview target and reaching it would tunnel through a third
        // party. Refused on that stated basis.
        Rule("192.88.99.0", 24, false, "deprecated_6to4_relay"),
        Rule("169.254.0.0", 16, false, "link_local_address"),
        Rule("192.168.0.0", 16, false, "private_address"),
        Rule("198.18.0.0", 15, false, "benchmark_address"),
        Rule("172.16.0.0", 12, false, "private_address"),
        Rule("100.64.0.0", 10, false, "cgnat_address"),
        Rule("0.0.0.0", 8, false, "this_network"),
        Rule("10.0.0.0", 8, false, "private_address"),
        Rule("127.0.0.0", 8, false, "loopback_address"),
        Rule("224.0.0.0", 4, false, "multicast_address"),
        Rule("240.0.0.0", 4, false, "reserved_address"),
    ).longestPrefixFirst()

    /** IANA IPv6 Special-Purpose Address Registry. */
    private val ipv6Rules: List<Rule> = listOf(
        // Globally Reachable = TRUE, nested inside 2001::/23.
        Rule("2001:1::1", 128, true, "pcp_anycast"),
        Rule("2001:1::2", 128, true, "turn_anycast"),
        Rule("2001:1::3", 128, true, "dnssd_anycast"),
        Rule("2001:4:112::", 48, true, "as112_v6"),
        Rule("2001:3::", 32, true, "amt"),
        Rule("2001:20::", 28, true, "orchid_v2"),
        Rule("2001:30::", 28, true, "drone_remote_id"),
        // Globally Reachable = TRUE, outside 2001::/23.
        Rule("2620:4f:8000::", 48, true, "as112_direct_delegation"),
        // Globally Reachable = FALSE.
        Rule("::", 128, false, "unspecified_address"),
        Rule("::1", 128, false, "loopback_address"),
        Rule("64:ff9b:1::", 48, false, "local_use_nat64"),
        Rule("100::", 64, false, "discard_only_address"),
        Rule("100:0:0:1::", 64, false, "dummy_prefix_address"),
        Rule("2001:2::", 48, false, "benchmark_address"),
        Rule("2001::", 32, false, "teredo_address"),
        Rule("2001:db8::", 32, false, "documentation_address"),
        Rule("2001:10::", 28, false, "deprecated_orchid"),
        // The broad entry. Everything inside it that is not one of the
        // TRUE carve-outs above is refused.
        Rule("2001::", 23, false, "ietf_protocol_assignment"),
        // RFC 9637 documentation. /20, not /16: 3fff:1000:: is OUTSIDE it.
        Rule("3fff::", 20, false, "documentation_address"),
        Rule("5f00::", 16, false, "srv6_sid_address"),
        Rule("fe80::", 10, false, "link_local_address"),
        Rule("fec0::", 10, false, "site_local_address"),
        Rule("ff00::", 8, false, "multicast_address"),
        Rule("fc00::", 7, false, "unique_local_address"),
    ).longestPrefixFirst()

    // -- Evaluation ----------------------------------------------------

    private fun ipv4RefusalReason(b: ByteArray): String? {
        if (b.size != 4) return "malformed_address"
        val rule = ipv4Rules.firstOrNull { it.matches(b) } ?: return null
        return if (rule.globallyReachable) null else rule.reason
    }

    private fun ipv6RefusalReason(address: Inet6Address): String? {
        val b = address.address
        if (b.size != 16) return "malformed_address"
        // Judge an embedded IPv4 as IPv4, whatever wrapper carries it.
        embeddedIpv4(b)?.let { return ipv4RefusalReason(it) }
        val rule = ipv6Rules.firstOrNull { it.matches(b) } ?: return null
        return if (rule.globallyReachable) null else rule.reason
    }

    /**
     * The IPv4 address embedded in [b], or `null` if there is none.
     *
     * Covers IPv4-mapped (`::ffff:a.b.c.d`), the deprecated
     * IPv4-compatible form (`::a.b.c.d`), the well-known NAT64 prefix
     * (`64:ff9b::a.b.c.d`) and 6to4 (`2002:a.b.c.d::/48`).
     *
     * `::` and `::1` are NOT treated as embedded IPv4 — they are the
     * unspecified and loopback addresses, judged as themselves.
     */
    private fun embeddedIpv4(b: ByteArray): ByteArray? {
        fun last4() = byteArrayOf(b[12], b[13], b[14], b[15])
        val firstTenZero = (0..9).all { b[it].toInt() == 0 }
        val ffff = (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        if (firstTenZero && ffff) return last4()
        if (firstTenZero && b[10].toInt() == 0 && b[11].toInt() == 0) {
            val tail = last4()
            val tailIsZeroOrOne = tail[0].toInt() == 0 && tail[1].toInt() == 0 &&
                tail[2].toInt() == 0 && (tail[3].toInt() == 0 || tail[3].toInt() == 1)
            return if (tailIsZeroOrOne) null else tail
        }
        val nat64 = (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 &&
            (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
            (4..11).all { b[it].toInt() == 0 }
        if (nat64) return last4()
        if ((b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x02) {
            return byteArrayOf(b[2], b[3], b[4], b[5])
        }
        return null
    }
}
