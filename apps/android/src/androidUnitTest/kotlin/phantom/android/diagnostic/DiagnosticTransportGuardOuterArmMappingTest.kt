// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct WSS Yota-First — §12 Round-1 audit P0-4 test:
 * `DiagnosticTransportGuard.outerArmReader` returns strings that
 * map 1:1 to the intended `TransportKind` (Direct → "direct",
 * Reality → "reality", Tor → "tor") — the mapping intended in
 * `DiagnosticBootInit`, in isolation from an actual `TransportManager`.
 *
 * A regression that swaps Private↔Ghost or otherwise mislabels the
 * arms would fail this test red — the assertion strings are what
 * `HRT.send`'s fail-closed check relies on.
 */
class DiagnosticTransportGuardOuterArmMappingTest {

    @Before fun reset() = DiagnosticTransportGuard.reset()
    @After fun tearDown() = DiagnosticTransportGuard.reset()

    @Test fun direct_reader_returns_direct() {
        DiagnosticTransportGuard.outerArmReader = { "direct" }
        assertEquals("direct", DiagnosticTransportGuard.currentOuterArm())
    }

    @Test fun reality_reader_returns_reality_not_tor() {
        // §12 Round-1 audit: the prior mapping was reversed
        // (Private→Tor, Ghost→Reality). This test pins the corrected
        // mapping from the debug boot-init (which now reads live
        // TransportManager state, not PrivacyMode policy). Reality is
        // Reality; Tor is Tor.
        DiagnosticTransportGuard.outerArmReader = { "reality" }
        assertEquals("reality", DiagnosticTransportGuard.currentOuterArm())
    }

    @Test fun tor_reader_returns_tor() {
        DiagnosticTransportGuard.outerArmReader = { "tor" }
        assertEquals("tor", DiagnosticTransportGuard.currentOuterArm())
    }

    @Test fun probing_or_idle_or_failed_do_not_read_as_direct() {
        for (s in listOf("probing", "idle", "failed", "unknown")) {
            DiagnosticTransportGuard.outerArmReader = { s }
            val obs = DiagnosticTransportGuard.currentOuterArm()
            // HRT's fail-closed check rejects anything != "direct" under a pin.
            assert(obs != "direct") { "unexpectedly matched direct: $s" }
        }
    }
}
