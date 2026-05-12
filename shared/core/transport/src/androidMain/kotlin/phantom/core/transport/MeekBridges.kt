// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
//
// Bridge line below is mirrored verbatim from
// briar/onionwrapper @ master, bridges-m-zz (GPL-3, compatible with our
// AGPL-3). See https://github.com/briar/onionwrapper/blob/master/onionwrapper-core/src/main/resources/bridges-m-zz
package phantom.core.transport

/**
 * meek_lite pluggable transport bridge (PR-E, 2026-05-12).
 *
 * meek_lite tunnels Tor traffic inside HTTPS to a domain-fronted CDN
 * endpoint. To a censor's DPI box the connection is indistinguishable
 * from a normal HTTPS request to whatever domain is in the SNI field
 * (here: `www.phpmyadmin.net` — a deliberately innocuous-looking
 * developer-tools site). The actual destination is hidden inside the
 * encrypted Host header that only the CDN edge sees.
 *
 * Why we ship this as a fourth fallback transport (after Snowflake,
 * non-default obfs4, and our own FlokiNET obfs4):
 *   - meek_lite has a wholly different wire signature from any of the
 *     above (HTTPS to a "real" CDN-hosted domain). Censors that block
 *     obfs4's uniform-random byte stream and snowflake's WebRTC traces
 *     often leave HTTPS-to-popular-CDNs alone because false positives
 *     would be huge.
 *   - Briar deliberately ships exactly this single line — it has been
 *     production-stable since Tor Project added meek_lite in 2018.
 *   - Bandwidth / latency is worst-of-all (CDN round-trip per cell),
 *     so we use it as a last-resort fallback, not the primary path.
 *
 * Ships as a single bridge entry; meek_lite does not benefit from
 * multiple parallel bridges the way obfs4/snowflake does because a
 * single CDN endpoint can serve the entire bridge userbase.
 *
 * The leading "Bridge " prefix is required for the same reason as
 * SnowflakeBridges and OperatorBridges — see those files for context.
 */
internal object MeekBridges {
    val DEFAULT: List<String> = listOf(
        "Bridge meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org " +
            "front=www.phpmyadmin.net utls=HelloRandomizedALPN",
    )
}
