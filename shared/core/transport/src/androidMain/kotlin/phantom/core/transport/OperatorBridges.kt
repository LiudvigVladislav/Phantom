// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Operator-controlled WebTunnel bridge entries (ADR-018 Stage 5C).
 *
 * Where these come from:
 *   We run our own WebTunnel bridge on the same VPS that hosts the relay
 *   onion service (`bridge.phntm.pro`). After bringing the bridge up per
 *   `deploy/webtunnel-bridge-setup.md`, the operator extracts the canonical
 *   bridge line via `docker compose exec webtunnel-bridge get-bridge-line.sh`
 *   and pastes it here, prefixed with "Bridge ".
 *
 * Why these bridges instead of public Snowflake:
 *   Test 10 (2026-05-05) confirmed that public Snowflake brokers (Tor
 *   Project's Netlify-hosted infrastructure) are blocked on Russian
 *   carrier networks without a VPN. Our own bridge gives PHANTOM users a
 *   guaranteed-reachable entry point that we control directly. Once
 *   bridge.phntm.pro is reachable to the user, the rest of the Tor
 *   circuit + onion path falls into place.
 *
 * Failure modes:
 *   - Single bridge → if our VPS is down, censored users lose access.
 *     Mitigation in Beta: distribute additional bridges to separate
 *     providers / regions; fall back to public Snowflake.
 *   - Bridge fingerprint pinned → if we rotate the bridge identity
 *     (key compromise, fresh deploy from backup), every shipped APK
 *     with the old fingerprint silently stops working until the user
 *     updates. Coordinate rotations with planned APK releases.
 *
 * Format note (same as `SnowflakeBridges.kt`):
 *   Each entry is a complete tor control-protocol setConf entry —
 *   "Bridge " is the option name. Briar's `enableBridges` does NOT add
 *   the prefix internally; we must include it.
 */
internal object OperatorBridges {

    /**
     * Operator-controlled WebTunnel bridge line(s).
     *
     * Populated 2026-05-06 from the deployed `phantom-webtunnel-bridge`
     * container on Hetzner via `get-bridge-line.sh`. The IP-and-port
     * field is the IETF documentation prefix `2001:db8::/32` (RFC 3849)
     * — this is the Tor Project's canonical placeholder for URL-based
     * pluggable transports. WebTunnel clients ignore the IP entirely
     * and connect through the `url=` parameter; the field exists only
     * so the bridge line parses against tor's generic bridge syntax.
     *
     * Same convention as `SnowflakeBridges.DEFAULT` (which uses
     * `192.0.2.3` TEST-NET reserved addresses).
     *
     * To rotate the bridge identity:
     *   1. Follow `deploy/webtunnel-bridge-setup.md` §10
     *   2. Replace the line below with the new `get-bridge-line.sh`
     *      output (preserve the "Bridge " prefix)
     *   3. Rebuild + ship a new APK
     */
    val WEBTUNNEL: List<String> = listOf(
        // bridge2.phntm.pro (FlokiNET RO, AS200651) — listed FIRST.
        // FlokiNET sits outside TSPU's "16-kilobyte curtain" target list
        // (research/bridge-host-verification-2026-05-06/), so users on
        // Russian carrier networks reach it cleanly. Tor's bridge selection
        // tries entries in order; putting FlokiNET first means censored
        // users do not waste a connect-timeout cycle on Hetzner before
        // falling through. Bootstrapped 2026-05-06.
        "Bridge webtunnel [2001:db8:39d2:e768:10b6:5767:176d:9998]:443 " +
            "8BF17C225F5BF170CB2D7E65DA19D3D73D859451 " +
            "url=https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b",

        // bridge.phntm.pro (Hetzner DE, AS24940) — listed SECOND.
        // Retained as fallback for non-RU users for whom Hetzner is fine,
        // and as an ops-redundancy backstop if FlokiNET goes down.
        // Hetzner CIDR is on TSPU's 16-KB curtain target list — bootstrap
        // through this bridge stalls at 25-73% on Russian carriers without
        // VPN (Test 11 / 12, 2026-05-05/06).
        "Bridge webtunnel [2001:db8:1d47:723c:6cf0:a211:e413:8887]:443 " +
            "D2F3A6695223C0DCDBC14AF159807474673A539C " +
            "url=https://bridge.phntm.pro/2a8652911c0cf7150ad0a0b32626434a",
    )

    /**
     * Operator-controlled obfs4 bridge entries (ADR-020 Stage 5G Phase 1).
     *
     * Why obfs4 alongside WebTunnel:
     *   Test 13 (2026-05-06) showed that **WebTunnel** TLS handshakes hit
     *   the TSPU 16-KB curtain even on FlokiNET (the curtain is a
     *   behavioural classifier, not an ASN block). obfs4 has a different
     *   wire signature — it does not present a TLS ClientHello at all but
     *   a uniform-random byte stream — so it bypasses the curtain's TLS
     *   pattern matching. obfs4 is also Tor's most battle-tested PT
     *   (deployed since 2014, used by Tor Browser default-bridges).
     *
     * Where the line comes from:
     *   Run an obfs4 bridge on the existing FlokiNET VPS following
     *   `deploy/obfs4-bridge-setup.md`. After the bridge has been online
     *   for ~1 hour, extract the canonical line from
     *   `/var/lib/tor/pt_state/obfs4_bridgeline.txt` and paste it here
     *   (preserving the leading "Bridge " prefix).
     *
     * Listed BEFORE [WEBTUNNEL] in the chain that
     * `TorServiceFactory.android.kt` builds — when this list is non-empty
     * tor tries obfs4 first on the assumption that any user landing in
     * the bridge code path is on a network where vanilla guards already
     * failed (i.e. likely TSPU territory).
     *
     * Empty by default until Vladislav populates after running the deploy
     * script. An empty list contributes nothing to the bridge chain (just
     * a `+` of an empty list to the WebTunnel one) — no behaviour change
     * until the first real entry lands.
     */
    val OBFS4: List<String> = emptyList()
        // Example shape (do NOT commit example values — produce real ones
        // from the deploy script):
        //
        // "Bridge obfs4 192.0.2.10:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 " +
        //     "cert=AAAA…BBBB iat-mode=0",
}
