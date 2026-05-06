// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM (desktop) stub of [XrayService] (ADR-018 Stage 5E).
 *
 * The desktop client does not bundle libXray — Stage 5E targets Android RU
 * mobile carriers (TSPU 16-KB curtain), and Windows/Linux/macOS desktops
 * are post-Alpha-2 anyway. The stub stays in [XrayState.Off] forever so
 * the KMP module graph compiles on JVM without dragging the 85 MB AAR onto
 * a target that cannot use it.
 *
 * When desktop integration begins, swap this for an implementation backed
 * by Xray's standalone JVM bindings (or a child-process variant if no
 * in-process JVM build exists).
 */
internal class XrayServiceJvm : XrayService {
    private val _state = MutableStateFlow<XrayState>(XrayState.Off)
    override val state: StateFlow<XrayState> = _state.asStateFlow()

    override suspend fun start() {
        // Intentional no-op on desktop until libXray is wired here too.
    }

    override suspend fun stop() {
        // Intentional no-op — see start().
    }
}

actual fun createXrayService(config: XrayServiceConfig): XrayService =
    XrayServiceJvm()
