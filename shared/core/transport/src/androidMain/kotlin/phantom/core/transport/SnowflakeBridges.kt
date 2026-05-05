// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Built-in Snowflake bridge lines for the embedded tor (ADR-018, Stage 5B).
 *
 * Snowflake is a Tor pluggable transport that disguises Tor circuit traffic
 * as WebRTC DataChannel traffic to a volunteer browser-based proxy, which
 * in turn forwards it to a Snowflake server connected to the Tor network.
 * Combined with HTTPS domain-fronting on the broker (the bootstrap step
 * where a client discovers a volunteer proxy), this makes the entire
 * handshake indistinguishable from normal video-call setup to a DPI box.
 *
 * Why these specific lines: they are the **default Snowflake bridges
 * shipped in Tor Browser**, mirrored verbatim from
 * `gitlab.torproject.org/tpo/applications/tor-browser-build/-/blob/main/projects/common/bridges_list.snowflake.txt`.
 * Updates to those lines arrive in Tor Browser releases — typically when
 * fronting domains, broker URLs or STUN servers need to rotate after a
 * censorship adaptation. We track the same source.
 *
 * Maintenance: when a Tor Browser release updates the snowflake bridge
 * list (visible in tor-browser-build commits with subject "Bug 41574:
 * Update Snowflake builtin bridge lines"), copy the new lines here and
 * ship a patch release. See `docs/operations/TOR_STACK_MAINTENANCE.md`
 * for the operational protocol.
 *
 * Each line is the raw transport spec — Briar's `enableBridges` API
 * adds the `Bridge ` prefix internally.
 */
internal object SnowflakeBridges {

    /**
     * Default-shipped Snowflake bridge entries. Two lines, two distinct
     * fingerprints — clients pick whichever bridge their first guard
     * negotiation reaches; having two reduces single-point-of-failure.
     *
     * Last synced: 2026-05-05 from Tor Browser (Bug 41574 commit msg
     * `211804.html` on tor-commits mailing list).
     */
    val DEFAULT: List<String> = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://voluble-torrone-fc39bf.netlify.app/ " +
            "fronts=vuejs.org " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478," +
            "stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",

        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://voluble-torrone-fc39bf.netlify.app/ " +
            "fronts=vuejs.org " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478," +
            "stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
    )
}
