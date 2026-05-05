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
     * **Empty until the operator deploys the bridge and extracts the
     * canonical line.** Until populated, PHANTOM clients on censored
     * networks will fail to bootstrap (Snowflake fallback in
     * `SnowflakeBridges.DEFAULT` is best-effort and depends on whether
     * the network blocks Tor Project's broker URLs).
     *
     * Expected entry format (paste from `get-bridge-line.sh` output,
     * with "Bridge " prefix):
     *
     *   "Bridge webtunnel <IPv4>:443 <FINGERPRINT> " +
     *       "url=https://bridge.phntm.pro/<secret-path> " +
     *       "ver=0.0.1"
     *
     * After populating, rebuild + reship the APK. Operators rotating
     * the bridge identity update this list and republish accordingly.
     */
    val WEBTUNNEL: List<String> = emptyList()
}
