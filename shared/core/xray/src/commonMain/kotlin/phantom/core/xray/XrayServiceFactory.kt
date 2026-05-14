// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

/**
 * Platform factory for [XrayService] (ADR-019 Stage 5E).
 *
 * Each platform supplies its own actual:
 *  - **androidMain** wraps libXray's gomobile AAR (Xray-core compiled to a
 *    JNI library, loaded in-process — no child process, no third-party app).
 *  - **jvmMain** is a no-op stub; the desktop client does not embed Xray.
 *  - **iosMain** will arrive with the iOS XCFramework build (post-Alpha 2).
 *
 * Construction-time configuration is supplied via [XrayServiceConfig] —
 * server endpoint plus REALITY keypair, identical to the values the operator
 * deploys on the Hetzner Xray service (`deploy/xray/`).
 */
expect fun createXrayService(config: XrayServiceConfig): XrayService

/**
 * Construction-time configuration for [XrayService].
 *
 * The fields below match the on-wire VLESS+REALITY parameters; [serverHost]
 * carries the actual server IP (or DNS name) the client connects to, while
 * [sni] is the cover host whose TLS certificate REALITY mirrors. They are
 * deliberately separate: the cover SNI is what the censor sees in
 * ClientHello, the real server is wherever we choose to host it.
 *
 * @property serverHost IP literal or hostname of the operator-controlled
 *   Xray server (Stage 5E.A: `65.108.154.152`).
 * @property serverPort TCP port the Xray server listens on (Stage 5E.A:
 *   `8443`).
 * @property sni Cover hostname whose TLS handshake REALITY mimics. Must be a
 *   real, well-known TLS endpoint (e.g. `www.microsoft.com`); the censor's
 *   DPI uses the certificate it presents to classify the flow as a trusted
 *   CDN connection.
 * @property publicKey REALITY x25519 public key (URL-safe base64, no
 *   padding). Pinned by the operator at server-deploy time.
 * @property shortId REALITY shortId (8 hex bytes / 16 chars). Identifies the
 *   client's authentication slot on the server.
 * @property uuid VLESS user UUID — second-layer auth checked after REALITY
 *   has succeeded.
 * @property dataDirectoryPath Absolute path the runtime uses as its working
 *   directory (geo data, mph cache). Losing it triggers a clean re-init at
 *   next start; it holds no PHANTOM identity material.
 * @property socksPort TCP port the embedded SOCKS5 inbound binds on
 *   `127.0.0.1`. Default `10808` — caller must guarantee no port collision
 *   with another in-process listener (e.g. embedded Tor's SOCKS).
 */
data class XrayServiceConfig(
    val serverHost: String,
    val serverPort: Int,
    val sni: String,
    val publicKey: String,
    val shortId: String,
    val uuid: String,
    val dataDirectoryPath: String,
    val socksPort: Int = 10808,
)
