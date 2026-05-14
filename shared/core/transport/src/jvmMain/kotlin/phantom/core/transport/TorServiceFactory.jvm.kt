// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM (desktop) stub of [TorService] (ADR-016).
 *
 * The desktop client does not yet bundle Briar's tor stack — desktop is a
 * post-Alpha-2 product line — so for now the JVM target compiles a no-op
 * implementation that stays in [TorState.Off]. Keeps the KMP module graph
 * honest (the transport module advertises a `TorService` API on every
 * target it supports) without forcing the desktop build to drag in tor
 * binaries it cannot use yet.
 *
 * When desktop integration begins, swap this for `org.briarproject:
 * onionwrapper-java` (the JVM-side variant of the same wrapper Briar
 * publishes for Android).
 */
internal class TorServiceJvm : TorService {
    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    override suspend fun start(bridgeProfile: BridgeProfile) {
        // Intentional no-op on desktop until the JVM Tor stack is wired here too.
        // The bridgeProfile parameter is accepted for interface conformance;
        // it has no effect because no tor instance is launched.
    }

    override suspend fun stop() {
        // Intentional no-op — see start().
    }
}

actual fun createTorService(config: TorServiceConfig, platformContext: Any?): TorService =
    TorServiceJvm()
