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
 * @property loglevel Xray-core log level emitted to logcat
 *   (`debug` / `info` / `warning` / `error` / `none`). Defaults to
 *   `warning` because info-level leaks per-connection peer addresses to
 *   logcat which we do not want in production (privacy of the user's
 *   contact graph). Debug builds — specifically the RC-DIRECT-STABILITY1
 *   §14 Arm G diagnostic — override this to `debug` so the per-session
 *   diagnostic can see the Reality handshake / uTLS / splice events. The
 *   release-pinned `OperatorXrayConfig.toConfig(...)` keeps the default.
 * @property flow VLESS user-flow string passed verbatim into
 *   `outbounds[].settings.vnext[].users[].flow`. Defaults to
 *   `xtls-rprx-vision` — the production flow that pairs with the
 *   server's `clients[].flow = "xtls-rprx-vision"` user entry. The
 *   release-pinned `OperatorXrayConfig.toConfig(...)` therefore keeps
 *   production behavior byte-for-byte equivalent on this field. Override
 *   to `""` (plain VLESS without XTLS-Vision) ONLY in diagnostic test
 *   surfaces, e.g. the RC-LIBXRAY-REALITY-WIRE1 (Trek 1) Variant 2
 *   `drop-vision` discriminator (`apps/android-libxray-test/`). When the
 *   client `flow` is overridden, the server's matching `clients[].flow`
 *   must also accept the override OR a separate diagnostic Reality
 *   inbound must serve the test traffic — production `:8443` is NOT
 *   modified by Trek 1.
 * @property network Stream transport name passed verbatim into
 *   `outbounds[].streamSettings.network`. Defaults to `tcp` (raw TCP),
 *   matching production. Trek 1 Variant 3 (`drop-vision-xhttp`)
 *   overrides this to `xhttp` to test whether the multi-segment outer
 *   Reality ClientHello stall observed in Arm G v10/v11 + Trek 1
 *   Variants 1 & 2 disappears when the data rides an HTTP-framed stream
 *   instead of raw TCP. Per the official Xray transport documentation,
 *   `realitySettings` is valid with `raw` / `xhttp` / `grpc` but NOT
 *   `httpupgrade` — V4 `httpupgrade` is therefore deferred and must not
 *   be added blindly as a Reality variant without a config validation
 *   step that confirms the running Xray version accepts the combination.
 *   When the client `network` is overridden, a matching diagnostic
 *   inbound on the diagnostic Reality container must be present.
 * @property xhttpPath HTTP path used by xhttp / httpupgrade transports.
 *   Ignored when [network] is `tcp`. Must match the diagnostic Reality
 *   inbound's `xhttpSettings.path` (server-side template renders the
 *   exact same constant). Trek 1 Variant 3 hardcodes
 *   `/wire1-xhttp-test` here.
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
    val loglevel: String = "warning",
    val flow: String = "xtls-rprx-vision",
    val network: String = "tcp",
    val xhttpPath: String = "",
)
