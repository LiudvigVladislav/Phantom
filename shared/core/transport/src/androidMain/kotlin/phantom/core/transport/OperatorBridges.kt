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
        "Bridge webtunnel [2001:db8:1d47:723c:6cf0:a211:e413:8887]:443 " +
            "D2F3A6695223C0DCDBC14AF159807474673A539C " +
            "url=https://bridge.phntm.pro/2a8652911c0cf7150ad0a0b32626434a",
    )
}
