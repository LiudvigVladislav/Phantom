// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import io.matthewnelson.kmp.tor.runtime.TorRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [TorService] (ADR-016 Stage 2).
 *
 * **Stage 2A scope (this commit):** wires the kmp-tor 2.6.0 +
 * resource-noexec-tor 409.5.0 dependencies and exposes the public API
 * surface. The class deliberately does NOT call `TorRuntime.startDaemonAsync`
 * yet — Stage 2B owns the daemon lifecycle, Stage 2C wires the SOCKS proxy
 * into [KtorRelayTransport]. Until then [start] is a TODO that updates the
 * state flow only, so callers can integration-test the API contract without
 * actually launching tor.
 *
 * The unused [torRuntimeClassProbe] field exists purely so that Stage 2A
 * compilation fails fast if the kmp-tor artifact is misconfigured in
 * gradle — without a hard reference somewhere in androidMain the dependency
 * could resolve at runtime only.
 */
internal class TorServiceAndroid(
    @Suppress("unused") private val config: TorServiceConfig,
) : TorService {

    @Suppress("unused")
    private val torRuntimeClassProbe: Any = TorRuntime::class

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    override suspend fun start() {
        // TODO(Stage 2B): build TorRuntime via TorRuntime.Builder, attach
        //                 RuntimeEvent observers, call startDaemonAsync(),
        //                 forward Bootstrapped % updates to _state.
        _state.value = TorState.Bootstrapping(percent = 0)
    }

    override suspend fun stop() {
        // TODO(Stage 2B): runtime.stopDaemonAsync(), then drain _state.
        _state.value = TorState.Off
    }
}

actual fun createTorService(config: TorServiceConfig): TorService =
    TorServiceAndroid(config)
