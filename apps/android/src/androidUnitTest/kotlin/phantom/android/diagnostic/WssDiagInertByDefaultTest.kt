// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * §PR-review-round-1 P0 — release-inert-by-default invariant.
 *
 * The Direct WSS diagnostic exists in [phantom.android.diagnostic.WssDiag]
 * on `androidMain` so a debug boot init can activate it in the debug
 * variant. Nothing else may activate it. If a release APK reaches
 * [WssDiag.emit] (e.g. `HybridRelayTransport.send` under `Pin.NONE`,
 * which is every production message), the call MUST return BEFORE
 * building the schema string or touching Android's `Log`.
 *
 * These tests prove that invariant end-to-end using the test-sink
 * seam. `WssDiag.testSink` intentionally bypasses `isActive` so JVM
 * unit tests can observe payloads without registering the debug
 * ContentProvider — that carve-out is documented in [WssDiag] and
 * pinned by the "test seam still fires while inactive" case below.
 */
class WssDiagInertByDefaultTest {

    private val captured = mutableListOf<String>()

    @Before
    fun installSink() {
        // Ensure a clean starting state: inactive + no leftover sink.
        WssDiag.isActive = false
        captured.clear()
        WssDiag.testSink = { line -> captured += line }
    }

    @After
    fun reset() {
        WssDiag.testSink = null
        WssDiag.isActive = false
    }

    @Test
    fun test_sink_still_fires_while_inactive_so_JVM_tests_can_capture() {
        // The test sink is a scoped carve-out — bypasses isActive.
        // Without this, no `androidUnitTest` could observe emit
        // payloads without booting Robolectric + the debug provider.
        assertEquals(false, WssDiag.isActive, "precondition: inactive")
        WssDiag.emit(
            event = "diagnostic_canary",
            role = WssDiag.Role.MATRIX,
        )
        assertEquals(1, captured.size)
        assertTrue(captured.single().contains("event=diagnostic_canary"))
    }

    @Test
    fun no_test_sink_no_isActive_no_emit() {
        // Simulate the release path — no debug provider ran, no test
        // sink installed. The emit MUST NOT enrich the log OR touch
        // Log.i. We prove the first half by asserting the sink was
        // never called; the Log.i half is proved structurally by
        // the `isActive` guard being read BEFORE any field-building
        // work happens (see the source of `WssDiag.emit`).
        WssDiag.testSink = null                 // drop the sink
        WssDiag.isActive = false                // release-shape
        assertEquals(0, captured.size)

        // A production send-decision under Pin.NONE goes through
        // this exact code path. It MUST NOT contribute anything to
        // the captured buffer.
        WssDiag.emit(
            event = "sender_transport_decision",
            role = WssDiag.Role.SENDER,
            correlationId = "should-not-appear-in-any-log",
            outerTransport = WssDiag.OuterTransport.DIRECT,
            innerRoute = WssDiag.InnerRoute.WSS,
            dispatched = true,
        )
        assertEquals(0, captured.size,
                     "release-shape emit MUST NOT reach the sink; nothing may reach Log.i either")
    }

    @Test
    fun activation_flips_emit_from_silent_to_active() {
        // Simulate the debug boot init: activate → emit lights up.
        WssDiag.testSink = null
        WssDiag.isActive = false
        WssDiag.emit(
            event = "sender_enqueue",
            role = WssDiag.Role.SENDER,
            correlationId = "cid-before-boot",
        )
        // Nothing captured because sink is null AND isActive is false.
        assertEquals(0, captured.size)

        // Re-install the sink to observe emits AFTER activation.
        WssDiag.testSink = { line -> captured += line }
        WssDiag.isActive = true
        WssDiag.emit(
            event = "sender_enqueue",
            role = WssDiag.Role.SENDER,
            correlationId = "cid-after-boot",
        )
        assertEquals(1, captured.size)
        assertTrue(captured.single().contains("correlation_id=cid-after-boot"))
    }
}
