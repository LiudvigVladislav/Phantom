// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Platform factory for [TorService] (ADR-016 + ADR-018).
 *
 * Each platform supplies its own actual:
 *  - **androidMain** wraps Briar's `AndroidTorWrapper` (org.briarproject:
 *    onionwrapper-android), which bundles `tor-android` + `lyrebird-android`
 *    (Snowflake / WebTunnel / obfs4 / meek pluggable transports). The
 *    Android wrapper requires an `Application` context for its wake-lock
 *    manager — passed via [platformContext].
 *  - **jvmMain** is a no-op stub (TorState stays [TorState.Off]); the
 *    desktop client does not yet bundle a tor binary. Future desktop work
 *    will swap this for a real implementation.
 *  - **iosMain** will arrive with the iOS XCFramework build (post-Alpha 2).
 *
 * @param config the data + cache directories. Briar's wrapper uses a single
 *   tor directory; Android impl resolves to [TorServiceConfig.dataDirectoryPath].
 * @param platformContext platform-specific handle:
 *   - Android: must be an `android.app.Application`. Throws if missing or
 *     of wrong type.
 *   - JVM / iOS: ignored. Pass `null`.
 */
expect fun createTorService(config: TorServiceConfig, platformContext: Any?): TorService

/**
 * Construction-time configuration for [TorService].
 *
 * @property dataDirectoryPath Absolute path that the tor process uses to
 *   persist guards, microdescriptors, and the cached consensus across app
 *   restarts. Losing this directory triggers a full re-bootstrap (slow,
 *   visible to the user) but is otherwise safe — it holds no PHANTOM
 *   identity material.
 * @property cacheDirectoryPath Absolute path for transient tor cache files.
 *   With Briar's wrapper this is informational; the wrapper currently
 *   reuses the same directory as data. Kept for API symmetry and future
 *   extraction.
 * @property socksPort TCP port tor binds its SOCKS5 listener on. Default
 *   `0` means "let the implementation pick"; Briar's `AndroidTorWrapper`
 *   requires a fixed value at construction time and uses 39050 by default.
 *   Callers consume the resolved port through [TorState.Ready.socksPort]
 *   rather than reading [socksPort] directly.
 */
data class TorServiceConfig(
    val dataDirectoryPath: String,
    val cacheDirectoryPath: String,
    val socksPort: Int = 0,
    /**
     * When `true`, the implementation enables pluggable-transport bridges
     * (currently Snowflake) before bringing the tor network up. Required
     * for any user behind a network that throttles or drops vanilla Tor
     * TLS handshakes — confirmed in Test 6 to apply to МТС / Билайн /
     * Ростелеком / similar Russian carriers.
     *
     * - Standard / Private mode: `false` by default; flipped to `true`
     *   only by the auto-fallback path after a direct WSS attempt fails.
     * - Ghost mode: always `true` (privacy contract — see
     *   `docs/spec/PRIVACY_MODE_BEHAVIOR.md` §3.2).
     *
     * Bridge addresses are hard-coded in the Android implementation
     * (Stage 5B) — currently the public Snowflake brokers from
     * Tor Browser's built-in bridge list, mirrored at
     * `gitlab.torproject.org/tpo/applications/tor-browser-build`.
     */
    val useBridges: Boolean = false,
)
