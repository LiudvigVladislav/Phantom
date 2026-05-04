// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Platform factory for [TorService] (ADR-016 Stage 2).
 *
 * Each platform supplies its own actual:
 *  - **androidMain** wraps kmp-tor's `TorRuntime` (resource-noexec-tor JNI).
 *  - **jvmMain** is a no-op stub (TorState stays [TorState.Off]); the
 *    desktop client does not need an embedded tor and we do not bundle the
 *    JNI binaries on JVM. Future desktop work can swap this for a real
 *    implementation backed by kmp-tor's JVM runtime.
 *  - **iosMain** will arrive with the iOS XCFramework build (post-Alpha 2).
 *
 * Stage 2A only delivers the Android actual; Stage 2B exercises the
 * lifecycle from `PhantomMessagingService`, Stage 2C plugs the SOCKS proxy
 * into [KtorRelayTransport].
 */
expect fun createTorService(config: TorServiceConfig): TorService

/**
 * Construction-time configuration for [TorService].
 *
 * @property dataDirectoryPath Absolute path that the tor process uses to
 *   persist guards, microdescriptors, and the cached consensus across app
 *   restarts. Losing this directory triggers a full re-bootstrap (slow,
 *   visible to the user) but is otherwise safe — it holds no PHANTOM
 *   identity material.
 * @property cacheDirectoryPath Absolute path for transient tor cache files.
 *   May be evicted by the OS without breaking functionality; tor will
 *   simply re-fetch on next bootstrap.
 * @property socksPort TCP port tor binds its SOCKS5 listener on. Default
 *   `0` means tor picks an ephemeral port and the implementation discovers
 *   it after bootstrap; callers consume the resolved port through the
 *   implementation rather than hard-coding 9050.
 */
data class TorServiceConfig(
    val dataDirectoryPath: String,
    val cacheDirectoryPath: String,
    val socksPort: Int = 0,
)
