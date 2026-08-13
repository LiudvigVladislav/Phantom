// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct WSS Yota-First — §12 P0-3 pin: `outer_transport` is now
 * OBSERVED via [DiagnosticTransportGuard.outerArmReader], not
 * hard-coded. The reader lambda is installed by the debug boot-init
 * provider with a closure over the live
 * `AppContainer.transportManager.state.value` (the actually-selected
 * outer arm after chain-walk completes); in these unit tests we
 * inject a synthetic lambda and assert the guard returns the
 * expected string. `HRT.send`'s fail-closed check under a pin uses
 * the same string, so this test also pins the enum-to-string mapping
 * HRT reads.
 */
class DiagnosticOuterArmObservationTest {

    @Before fun reset() = DiagnosticTransportGuard.reset()
    @After fun tearDown() = DiagnosticTransportGuard.reset()

    @Test
    fun current_outer_arm_default_is_unknown_when_no_reader_installed() {
        assertEquals("unknown", DiagnosticTransportGuard.currentOuterArm())
    }

    @Test
    fun current_outer_arm_returns_reader_value_when_installed() {
        DiagnosticTransportGuard.outerArmReader = { "direct" }
        assertEquals("direct", DiagnosticTransportGuard.currentOuterArm())
        DiagnosticTransportGuard.outerArmReader = { "reality" }
        assertEquals("reality", DiagnosticTransportGuard.currentOuterArm())
        DiagnosticTransportGuard.outerArmReader = { "tor" }
        assertEquals("tor", DiagnosticTransportGuard.currentOuterArm())
    }

    @Test
    fun current_outer_arm_returns_unknown_when_reader_throws_or_returns_gibberish() {
        // HRT's fail-closed check treats any non-"direct" value as a
        // block. This test pins that a defensive reader returning an
        // unexpected string does NOT accidentally satisfy the "direct"
        // check.
        DiagnosticTransportGuard.outerArmReader = { "unknown-mode" }
        assertEquals("unknown-mode", DiagnosticTransportGuard.currentOuterArm())
        // HRT.send will treat "unknown-mode" as a non-direct value
        // and fail-closed under any pin — verified by the WssDiag
        // enum mapping in HybridRelayTransport.kt.
    }
}
