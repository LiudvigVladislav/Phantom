// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM (desktop) stub of [TorService] (ADR-016 Stage 2).
 *
 * The desktop client does not yet bundle kmp-tor's JVM runtime — the
 * desktop product itself is post-Alpha-2 — so for now the JVM target
 * compiles a no-op implementation that stays in [TorState.Off]. This keeps
 * the KMP module graph honest (the transport module advertises a
 * `TorService` API on every target it supports) without forcing the
 * desktop build to drag in tor binaries it cannot use yet.
 *
 * When desktop integration begins, swap this for a `kmp-tor:runtime` +
 * `resource-exec-tor` (or noexec on JVM, when supported) backed
 * implementation alongside the Android one.
 */
internal class TorServiceJvm : TorService {
    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    override suspend fun start() {
        // Intentional no-op on desktop until kmp-tor is wired here too.
    }

    override suspend fun stop() {
        // Intentional no-op — see start().
    }
}

actual fun createTorService(config: TorServiceConfig): TorService =
    TorServiceJvm()
