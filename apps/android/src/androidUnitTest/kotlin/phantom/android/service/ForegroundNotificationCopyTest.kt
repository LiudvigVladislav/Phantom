// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.core.transport.BridgeProfile
import phantom.core.transport.ManagerState
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TorBootstrapStage
import phantom.core.transport.TorProbingStatus
import phantom.core.transport.TransportAttemptFailure
import phantom.core.transport.TransportKind
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ForegroundNotificationCopyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun idleAndConnectedLabelsUseTheEffectiveMode() {
        assertEquals(
            "Waiting for connection · Standard",
            foregroundTransportStatus(context, ManagerState.Idle, PrivacyMode.Standard),
        )
        assertEquals(
            "Online via Reality · Private",
            foregroundTransportStatus(
                context, ManagerState.Connected(TransportKind.Reality), PrivacyMode.Private,
            ),
        )
    }

    @Test
    fun torProbeKeepsProgressAndBridgeRotationVisible() {
        val status = TorProbingStatus(
            percent = 45,
            stage = TorBootstrapStage.Searching,
            elapsedMs = 60_000L,
            bridgeProfile = BridgeProfile.SnowflakeOnly,
            attempt = 2,
            totalAttempts = 4,
        )
        assertEquals(
            "Searching for a reachable route… 45% · snowflake (2/4) · Ghost",
            foregroundTransportStatus(
                context, ManagerState.Probing(TransportKind.Tor, status), PrivacyMode.Ghost,
            ),
        )
        assertEquals(
            "Connecting via Direct… · Standard",
            foregroundTransportStatus(
                context, ManagerState.Probing(TransportKind.Direct), PrivacyMode.Standard,
            ),
        )
    }

    @Test
    fun everyTorStageHasDisplayCopy() {
        val expected = mapOf(
            TorBootstrapStage.Initial to "Connecting to Tor network…",
            TorBootstrapStage.Negotiating to "Negotiating censorship-resistant bridge…",
            TorBootstrapStage.Searching to "Searching for a reachable route…",
            TorBootstrapStage.Slow to "This network is slowing Tor connections…",
            TorBootstrapStage.Throttled to
                "Tor is heavily throttled on this network. VPN may improve connection speed.",
        )
        assertEquals(TorBootstrapStage.entries.toSet(), expected.keys)
        expected.forEach { (stage, label) ->
            val status = TorProbingStatus(0, stage, 0L, BridgeProfile.Mixed, 1, 4)
            val text = foregroundTransportStatus(
                context, ManagerState.Probing(TransportKind.Tor, status), PrivacyMode.Private,
            )
            assertEquals("$label 0% · mixed (1/4) · Private", text)
        }
    }

    @Test
    fun failedPathsDoNotClaimCensorshipWasProven() {
        assertEquals(
            "Tor could not connect on this network. Try another network or a VPN. · Ghost",
            foregroundTransportStatus(
                context,
                ManagerState.AllFailed(listOf(TransportAttemptFailure(TransportKind.Tor, "timeout"))),
                PrivacyMode.Ghost,
            ),
        )
        assertEquals(
            "Cannot reach relay (tried 1) · Standard",
            foregroundTransportStatus(
                context,
                ManagerState.AllFailed(listOf(TransportAttemptFailure(TransportKind.Direct, "timeout"))),
                PrivacyMode.Standard,
            ),
        )
    }

    @Test
    fun restOverlayKeepsActualOuterTransportOrSafeFallback() {
        val connected = PhantomMessagingService.transportNameForOverlay(
            ManagerState.Connected(TransportKind.Tor),
        )
        val idle = PhantomMessagingService.transportNameForOverlay(ManagerState.Idle)
        assertEquals(
            "Online via Tor · Limited realtime · Ghost",
            foregroundRestStatus(context, connected, PrivacyMode.Ghost, recovering = false),
        )
        assertEquals(
            "Online via relay · Verifying realtime · Private",
            foregroundRestStatus(context, idle, PrivacyMode.Private, recovering = true),
        )
        assertEquals(
            "Online via relay · Limited realtime · Standard",
            foregroundRestStatus(context, "unknown", PrivacyMode.Standard, recovering = false),
        )
    }
}
