// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C6-a round-8 REDLINE — tests for [applyStartupDecision]
 * orchestrator.
 *
 * Architect's round-8 scope-lock explicitly lists the
 * marker-discipline scenarios that MUST be covered by
 * orchestration tests (not just the pure decider):
 *   - All 4 cancellation variants → no marker write, no
 *     presentation change (rethrown).
 *   - FreshOnboarding / Migration / ChatList → no marker write.
 *   - All 5 transient reasons → no marker write, StartupError
 *     presentation.
 *   - All 3 deterministic repair reasons → OnboardingRepair
 *     presentation + marker write attempt.
 *   - Marker-write failure → OnboardingRepair (in-memory) still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2StartupPresentationTest {

    /**
     * Recording marker writer that captures calls + return value
     * so tests can assert both "was it called" and "what did it
     * return this call".
     */
    private class RecordingMarkerWriter(
        private val returnValue: Boolean = true,
    ) {
        val callCount = AtomicInteger(0)
        val writer: suspend () -> Boolean = {
            callCount.incrementAndGet()
            returnValue
        }
    }

    private class ThrowingMarkerWriter(
        private val throwable: Throwable,
    ) {
        val callCount = AtomicInteger(0)
        val writer: suspend () -> Boolean = {
            callCount.incrementAndGet()
            throw throwable
        }
    }

    // ── Marker discipline: NON-Repair decisions never write ──────────

    @Test
    fun FreshOnboarding_never_writes_marker() = runTest {
        val marker = RecordingMarkerWriter()
        val presentation = applyStartupDecision(
            StartupRouteDecision.FreshOnboarding,
            marker.writer,
        )
        assertEquals(StartupPresentation.FreshOnboarding, presentation)
        assertEquals(0, marker.callCount.get(),
            "FreshOnboarding MUST NOT write the marker.")
    }

    @Test
    fun Migration_never_writes_marker() = runTest {
        val marker = RecordingMarkerWriter()
        val presentation = applyStartupDecision(
            StartupRouteDecision.Migration,
            marker.writer,
        )
        assertEquals(StartupPresentation.Migration, presentation)
        assertEquals(0, marker.callCount.get())
    }

    @Test
    fun ChatList_never_writes_marker() = runTest {
        val marker = RecordingMarkerWriter()
        val presentation = applyStartupDecision(
            StartupRouteDecision.ChatList,
            marker.writer,
        )
        assertEquals(StartupPresentation.ChatList, presentation)
        assertEquals(0, marker.callCount.get())
    }

    @Test
    fun all_TransientStartupFailure_reasons_never_write_marker() = runTest {
        for (reason in TransientReason.entries) {
            val marker = RecordingMarkerWriter()
            val presentation = applyStartupDecision(
                StartupRouteDecision.TransientStartupFailure(reason),
                marker.writer,
            )
            assertEquals(
                StartupPresentation.StartupError(reason),
                presentation,
                "Transient reason $reason MUST map to StartupError presentation.",
            )
            assertEquals(
                0, marker.callCount.get(),
                "Round-8 §P0 pin: TransientStartupFailure($reason) MUST NOT write " +
                    "the marker. Round-7 shape wrote it, trapping healthy identities.",
            )
        }
    }

    // ── Repair discipline: ALL 3 reasons write marker + return OnboardingRepair ─

    @Test
    fun all_RepairQuarantine_reasons_write_marker_and_return_OnboardingRepair() = runTest {
        for (reason in RepairReason.entries) {
            val marker = RecordingMarkerWriter(returnValue = true)
            val presentation = applyStartupDecision(
                StartupRouteDecision.RepairQuarantine(reason),
                marker.writer,
            )
            assertEquals(
                StartupPresentation.OnboardingRepair(reason),
                presentation,
            )
            assertEquals(
                1, marker.callCount.get(),
                "RepairQuarantine($reason) MUST invoke markerWriter exactly once.",
            )
        }
    }

    // ── Marker-write failure: still show repair (in-memory fallback) ─

    @Test
    fun RepairQuarantine_marker_write_returns_false_still_returns_OnboardingRepair() = runTest {
        val marker = RecordingMarkerWriter(returnValue = false)
        val presentation = applyStartupDecision(
            StartupRouteDecision.RepairQuarantine(RepairReason.MalformedSigningKeyHex),
            marker.writer,
        )
        assertEquals(
            StartupPresentation.OnboardingRepair(RepairReason.MalformedSigningKeyHex),
            presentation,
            "Round-8 §P0 pin: even when the durable write fails, the CURRENT session " +
                "must still show repair via the in-memory presentation. A later " +
                "cold-start re-detects via decideStartupRoute's malformed-hex branch.",
        )
        assertEquals(1, marker.callCount.get())
    }

    @Test
    fun RepairQuarantine_marker_write_throws_still_returns_OnboardingRepair() = runTest {
        val marker = ThrowingMarkerWriter(IllegalStateException("SharedPrefs broken"))
        val presentation = applyStartupDecision(
            StartupRouteDecision.RepairQuarantine(RepairReason.NullHexNoMigrationExpected),
            marker.writer,
        )
        assertEquals(
            StartupPresentation.OnboardingRepair(RepairReason.NullHexNoMigrationExpected),
            presentation,
        )
        assertEquals(1, marker.callCount.get())
    }

    // ── Cancellation propagates through orchestrator ──────────────────

    @Test
    fun marker_write_cancellation_propagates() = runTest {
        val marker = ThrowingMarkerWriter(CancellationException("Activity recreated"))
        assertFailsWith<CancellationException> {
            applyStartupDecision(
                StartupRouteDecision.RepairQuarantine(RepairReason.MarkerAlreadySet),
                marker.writer,
            )
        }
    }
}
