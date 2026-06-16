// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import phantom.core.transport.ManagerState
import phantom.core.transport.TransportKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DWS-UX.1 (2026-06-17) — verifies the REST fallback notification
 * overlay maps `TransportManager` state to an honest transport label
 * instead of the pre-DWS-UX hardcoded `"Online via Direct"`. The
 * earlier string was a factual lie when the user was on Ghost (Tor)
 * or Private (Reality) and the REST overlay fired: the relay was
 * reachable via the configured outer transport, not via a fresh
 * Direct connection.
 *
 * The helper [PhantomMessagingService.Companion.transportNameForOverlay]
 * lives on the companion object so the JVM unit-test source set can
 * target it without a foreground-service runtime. The contract pinned
 * here:
 *
 *   - `ManagerState.Connected(Direct)`  → `"Direct"`
 *   - `ManagerState.Connected(Reality)` → `"Reality"`
 *   - `ManagerState.Connected(Tor)`     → `"Tor"`
 *   - every other `ManagerState`        → `"relay"` (safe fallback)
 *
 * Three RestMode columns × three TransportKind rows form the
 * nine-cell mapping that `startRestFallbackNotificationOverlay`
 * concatenates with the privacy mode label and the `"Limited
 * realtime"` / `"Recovering"` suffix. The mode-and-suffix half is
 * covered by an integration-level smoke; this unit test only pins
 * the kind half so a future refactor that changes how
 * `TransportKind` renders cannot silently re-introduce the lie.
 */
class TransportNameForOverlayTest {

    @Test
    fun connected_direct_yields_direct() {
        assertEquals(
            "Direct",
            PhantomMessagingService.transportNameForOverlay(
                ManagerState.Connected(TransportKind.Direct),
            ),
        )
    }

    @Test
    fun connected_reality_yields_reality() {
        assertEquals(
            "Reality",
            PhantomMessagingService.transportNameForOverlay(
                ManagerState.Connected(TransportKind.Reality),
            ),
        )
    }

    @Test
    fun connected_tor_yields_tor() {
        assertEquals(
            "Tor",
            PhantomMessagingService.transportNameForOverlay(
                ManagerState.Connected(TransportKind.Tor),
            ),
        )
    }

    @Test
    fun idle_yields_generic_relay_label() {
        assertEquals(
            "relay",
            PhantomMessagingService.transportNameForOverlay(ManagerState.Idle),
        )
    }
}
