// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
//
// Bridge lines below are mirrored from briar/onionwrapper resource files
// (briar/onionwrapper @ master, GPL-3 — compatible with our AGPL-3) which
// in turn track the Tor Project's tor-browser-build snowflake list. See
// https://github.com/briar/onionwrapper/tree/master/onionwrapper-core/src/main/resources/
// (`bridges-s-zz`, `bridges-s-ru`).
package phantom.core.transport

/**
 * Built-in Snowflake bridge lines for the embedded tor (ADR-016, Stage 5B).
 *
 * Snowflake is a Tor pluggable transport that disguises Tor circuit traffic
 * as WebRTC DataChannel traffic to a volunteer browser-based proxy, which
 * in turn forwards it to a Snowflake server connected to the Tor network.
 * Combined with HTTPS domain-fronting on the broker (the bootstrap step
 * where a client discovers a volunteer proxy), this makes the entire
 * handshake indistinguishable from normal video-call setup to a DPI box.
 *
 * **PR-E (2026-05-12):** prior `DEFAULT` lines were synced 2026-05-05 from
 * an older Tor Browser snowflake config (`voluble-torrone-fc39bf.netlify.app`
 * broker, single `vuejs.org` front). Both are now stale — Test #5 on МТС
 * Wi-Fi without VPN saw snowflake stall at 50 % using them. Briar's current
 * RU-tuned list (`bridges-s-ru`) ships four entries — two via cdn77 with
 * three RU-friendly fronts (`cdn.zk.mk, img.icons8.com, cdn.kde.org`) plus
 * two via Tor Project's AMP cache fronted on `www.google.com`. Blocking
 * `www.google.com` is functionally equivalent to blocking half the Russian
 * internet, so the AMP-cache path is the most resilient broker-discovery
 * route we have access to without deploying our own snowflake server.
 *
 * Each line is a complete tor control-protocol setConf entry — the
 * leading "Bridge " is the option name (mirrors how Briar's own
 * `CircumventionProviderImpl.getBridges()` builds its lines via
 * `bridges.add("Bridge " + line)`). Without the prefix, tor parses the
 * first token as the option name and rejects "snowflake" as
 * `Unknown option 'snowflake'. Failing.` — observed empirically in
 * Test 10 (2026-05-05) before the prefix was added.
 *
 * The companion `ClientTransportPlugin snowflake exec <lyrebird>`
 * directive is auto-configured by `AbstractTorWrapper`'s initial torrc
 * (it pre-wires obfs4 + meek_lite + snowflake against the bundled
 * `libLyrebird.so`). We do NOT need to set it from the caller side.
 *
 * Maintenance: when Briar's `onionwrapper` repo updates `bridges-s-ru`
 * or `bridges-s-zz` (visible at
 * `https://github.com/briar/onionwrapper/commits/master/onionwrapper-core/src/main/resources/`),
 * copy the new lines here and ship a patch release. Briar tracks Tor
 * Browser's Moat upstream — they are the freshest non-test source of
 * snowflake bridge configurations available to FOSS projects.
 */
internal object SnowflakeBridges {

    /**
     * RU-tuned Snowflake bridges. Mirrored verbatim from
     * `briar/onionwrapper @ master, bridges-s-ru` on 2026-05-12.
     *
     * Entries 1+2 reach the broker via cdn77 fronted on three RU-friendly
     * domains (`cdn.zk.mk`, `img.icons8.com`, `cdn.kde.org`).
     * Entries 3+4 reach the broker via the Tor Project's AMP cache
     * (`https://snowflake-broker.torproject.net/` with `ampcache=https://cdn.ampproject.org/`)
     * fronted on `www.google.com` — a censor cannot block this front
     * without taking down a substantial fraction of the Russian internet.
     *
     * Used as the FIRST single-PT profile in PHANTOM's bridge rotation
     * because Test #5 (2026-05-11) showed snowflake reached 50 % on МТС
     * without VPN — the highest single-PT score — and these RU-specific
     * entries have explicitly different fronts/STUN/broker URLs from the
     * generic `bridges-s-zz` list.
     *
     * **Privacy properties of the AMP-cache fallback path (entries 3+4)**
     *
     * The `ampcache=https://cdn.ampproject.org/ front=www.google.com` lines
     * route the **broker-discovery TLS request** (the matchmaking step
     * that finds an available volunteer Snowflake proxy) through Google's
     * AMP cache CDN. After the broker matches the client to a volunteer,
     * the actual Tor circuit traffic flows over WebRTC DataChannel
     * **directly** between the device and that volunteer browser — Google
     * is NOT on the data path.
     *
     * What Google sees:
     *   - Your IP making TLS connections to a Google CDN endpoint
     *   - Time / size / frequency pattern of those connections
     *   - The pattern in principle classifiable as "Snowflake-style
     *     broker discovery" — same pattern Tor Browser users on RU send
     *
     * What Google does NOT see:
     *   - Your PHANTOM identity / signing key
     *   - The relay's onion address (`zmdrxlrkd7iv...`)
     *   - Your contacts or the contact graph
     *   - Any Tor circuit traffic or message content
     *
     * What TSPU / RU carrier sees:
     *   - Only the TLS connection to `www.google.com` — indistinguishable
     *     from any of the dozens of legitimate Google services
     *
     * What we relied on before importing this set:
     *   - The previous default (`SnowflakeBridges.DEFAULT` pre-PR-E,
     *     fronted on `vuejs.org` via Netlify CDN). Netlify saw exactly
     *     the same pattern Google now sees. Privacy property is unchanged
     *     in kind; Google's value here is resilience against censorship,
     *     not a new privacy compromise.
     *
     * Entries 1+2 (cdn77 fronts) do not involve Google at all and are
     * tried first by tor's path-selection — the AMP-cache fallback only
     * actually fires when cdn77 is unreachable on the local network.
     *
     * This pattern is the same one Tor Browser ships by default for RU
     * users in `tor-browser-build` and that Briar ships in
     * `bridges-s-ru`. We follow established censorship-circumvention
     * industry practice rather than inventing our own. See
     * `KNOWN_ISSUES.md` ISSUE-016 for the full user-facing trust
     * trade-off discussion.
     */
    val RU_TUNED: List<String> = listOf(
        "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org " +
            "fronts=cdn.zk.mk,img.icons8.com,cdn.kde.org " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",

        "Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org " +
            "fronts=cdn.zk.mk,img.icons8.com,cdn.kde.org " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",

        "Bridge snowflake 192.0.2.5:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://snowflake-broker.torproject.net/ " +
            "ampcache=https://cdn.ampproject.org/ " +
            "front=www.google.com  " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",

        "Bridge snowflake 192.0.2.6:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://snowflake-broker.torproject.net/ " +
            "ampcache=https://cdn.ampproject.org/ " +
            "front=www.google.com " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
    )

    /**
     * Generic (non-country-tuned) Snowflake bridges. Mirrored verbatim
     * from `briar/onionwrapper @ master, bridges-s-zz` on 2026-05-12 —
     * the freshest Tor-Browser-compatible defaults available, fronted
     * via `app.datapacket.com` / `www.datapacket.com`.
     *
     * Kept separate from [RU_TUNED] because:
     *   - they use different cdn77 broker URL and different fronts
     *   - the AMP-cache fallback is RU-specific (only [RU_TUNED] has it)
     *
     * Available for future locale-aware bridge selection (when device
     * locale is not RU); for now [RU_TUNED] is what the PHANTOM rotation
     * actually consumes since our primary censorship target is RU.
     */
    val DEFAULT: List<String> = listOf(
        "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478," +
            "stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",

        "Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478," +
            "stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
    )
}
