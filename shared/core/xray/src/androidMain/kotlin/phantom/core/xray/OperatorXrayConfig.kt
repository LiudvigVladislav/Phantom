// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

/**
 * Production endpoint for the operator-controlled Xray VLESS+REALITY service
 * (ADR-018 Stage 5E.A — Hetzner FSN1).
 *
 * Why pinned in source rather than fetched at runtime: the whole point of
 * REALITY is to authenticate the server with a key the client already trusts
 * before any handshake — fetching the key from a remote endpoint would
 * recreate exactly the trust-on-first-use vulnerability REALITY is designed
 * to eliminate. Rotation requires a client release; that is acceptable
 * because rotation events are rare (server compromise, key wear) and a
 * stale-client connection is a CONNECT failure the user will retry, not a
 * silent downgrade.
 *
 * The values below are public — they identify the server but do not
 * authenticate any client. The VLESS [uuid] is the only credential, and it
 * is a *capability* (anyone holding it can use the server) rather than a
 * secret tied to identity. Stage 5E.B.5 keeps a single shared UUID for all
 * Phantom installs because Xray's purpose here is censorship circumvention,
 * not access control — relay-level auth handles abuse separately.
 *
 * Server config lives at `deploy/xray/config.json.template` on the operator
 * VPS; the values here MUST stay in lockstep with the rendered server
 * config. See `deploy/xray/.env` (gitignored, on VPS only) for the canonical
 * source of truth at runtime.
 */
internal object OperatorXrayConfig {
    const val SERVER_HOST: String = "65.108.154.152"
    const val SERVER_PORT: Int = 8443
    const val SNI: String = "www.microsoft.com"
    const val PUBLIC_KEY: String = "kDRyYpqpNGT_2IEbJ2pCxpkrinGBhokiNpO4cFOM6w0"
    const val SHORT_ID: String = "ab580a24c7a1e293"
    const val UUID: String = "09c6fd0e-dc89-4659-a7c3-9c6476590a6a"

    /**
     * Build an [XrayServiceConfig] for the production endpoint. [dataDir]
     * must be an absolute path the runtime can write to (typically
     * `Context.filesDir.resolve("xray").absolutePath`).
     */
    fun toConfig(dataDir: String, socksPort: Int = 10808): XrayServiceConfig =
        XrayServiceConfig(
            serverHost = SERVER_HOST,
            serverPort = SERVER_PORT,
            sni = SNI,
            publicKey = PUBLIC_KEY,
            shortId = SHORT_ID,
            uuid = UUID,
            dataDirectoryPath = dataDir,
            socksPort = socksPort,
        )
}
