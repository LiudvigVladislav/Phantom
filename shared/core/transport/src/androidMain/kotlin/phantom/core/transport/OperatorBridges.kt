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
    val OBFS4: List<String> = listOf(
        // bridge2.phntm.pro (FlokiNET RO, AS200651) — same VPS as the
        // existing WebTunnel bridge, separate Tor instance + separate
        // listener port (TCP/8443 obfs4 vs TCP/443 WebTunnel-Caddy).
        // Bootstrapped 2026-05-09 (deploy log:
        // deploy/obfs4-bridge-setup.md, Vladislav SSH session).
        // ORPort 9001 fixed; obfs4 transport on 8443.
        // Self-test passed: "Self-testing indicates your ORPort
        // 185.165.171.206:9001 is reachable from the outside."
        "Bridge obfs4 185.165.171.206:8443 " +
            "5EDD29A187D8354D68DDDB2E711005D6DFFD6F3A " +
            "cert=yU6ljZFFQ49J6VCipe7QBQt38fQ6O3ncJoNzTQzk8tJ/hg9n1GyOKpYF1YSC/OBib4tqVA " +
            "iat-mode=0",
    )

    /**
     * Briar's "non-default" obfs4 bridge set (PR-E, 2026-05-12).
     *
     * Mirrored verbatim from
     * `briar/onionwrapper @ master, bridges-n-zz` (GPL-3, compatible with
     * our AGPL-3) which Briar uses for every country it considers
     * censorship-active — RU, BY, CN, IR, etc. — instead of the public
     * "default" obfs4 set that DPI vendors enumerate first.
     *
     * Source: https://github.com/briar/onionwrapper/blob/master/onionwrapper-core/src/main/resources/bridges-n-zz
     *
     * Why we ship these alongside our own [OBFS4] FlokiNET bridge:
     *   - Test #5 (2026-05-11) showed our single FlokiNET obfs4 bridge
     *     stalled at 10 % on МТС without VPN. A single bridge is one
     *     point of failure against an active censor.
     *   - Briar's 9 non-default obfs4 lines span multiple ASNs and
     *     `iat-mode` values (0 and 1) — tor will try them in order
     *     until one negotiates.
     *   - These are the same bridges Briar ships to every Briar user
     *     in RU; if Briar works, these are the lines doing the work.
     *
     * Maintenance: when Briar updates `bridges-n-zz` (visible at
     * https://github.com/briar/onionwrapper/commits/master/onionwrapper-core/src/main/resources/bridges-n-zz),
     * sync the new lines here and ship a patch release.
     */
    val OBFS4_NON_DEFAULT: List<String> = listOf(
        "Bridge obfs4 120.29.217.52:5223 40FE3DB9800272F9CDC76422F8ED7883280EE96D " +
            "cert=/71PS4l8c/XJ4DIItlH9xMqNvPFg2RUTrHvPlQWh48u5et8h/yyyjCcYphUadDsfBWpaGQ " +
            "iat-mode=0",
        "Bridge obfs4 185.177.207.138:8443 53716FE26F23C8C6A71A2BC5D9D8DC64747278C7 " +
            "cert=6jcYVilMEzxdsWghSrQFpYYJlkkir/GPIXw/EnddUK3S8ToVpMG8u1SwMOEdEs735RrMHw " +
            "iat-mode=0",
        "Bridge obfs4 94.142.246.132:8088 135C158527AA9FE9A2F26EC515EB6999D813D347 " +
            "cert=wTUz0/5FhAZRkitil5MprGbSF3JzjxjxI1kAmxAdSeDy98NgcLr11f/qUXWDC76Y97RiSg " +
            "iat-mode=0",
        "Bridge obfs4 185.177.207.132:8443 4FB781F7A9DD39DA53A7996907817FC479874D19 " +
            "cert=UL2gCAXWW5kEWY4TQ0lNeu6OAmzh40bXYVhMnTWVG8USnyy/zEKGSIPgmwTDMumWr9c1Pg " +
            "iat-mode=0",
        "Bridge obfs4 82.64.115.17:990 B08238781C2CD80DBD95AEABEB6F6C75F2E2CEB6 " +
            "cert=1udeMlFNs3sJ20zwpPE6nShZqqwDb3F1ET4KzfSfD+fktkue9zNx9H3t+yLCPAsg+6UTUA " +
            "iat-mode=1",
        "Bridge obfs4 185.177.207.153:8443 6574D4D903FDE714F2759A3B3C31C0363A92DCDC " +
            "cert=VAZd6bOJ6BKUZLLOYhMuaSPxjf+ZGAspvdQkf8C3naGk8r2b77WXWj9JF8+jLYb8l2fnUw " +
            "iat-mode=0",
        "Bridge obfs4 78.159.118.224:19998 9735DAE37918DD9F0BA9CF56D336294BCB4207CC " +
            "cert=MIBlPdg69nSskD9Id8bLzwQFJ1zICUMwwG9apMlvF35Y6Z9W8AVbSlahxlY17l8zLvwdEA " +
            "iat-mode=0",
        "Bridge obfs4 23.94.169.122:63000 1878E0F5DAFFBD4A0F8DC75820ADB9B10E8ABAF0 " +
            "cert=iESu5/SAcFI8GPUXHaV4Yk1zraP1AjZySYSsKh0lT+Bj0q/pdwa4PjMjsOusMxiOuUQAOA " +
            "iat-mode=0",
        "Bridge obfs4 185.177.207.146:8443 4EBA78385FCA62C8A26EDEEC6752068C676287F3 " +
            "cert=IPU7h22MwNmvCogs3TrR4NO/RcIb9asRU0saVWfAPvBbWc1YALuHxDhOl5Sri2rZ7QhKNg " +
            "iat-mode=0",
    )
}
