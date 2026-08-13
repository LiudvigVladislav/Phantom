// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.identity.PrivateKey
import phantom.core.identity.PublicKey
import phantom.core.transport.PrivacyMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Privacy-mode ordering + retry-reuses-mode contract tests —
 * extracted from `OnboardingV2FinalizeContractTest.kt` in round-4
 * REDLINE §P2-1 test-file split so both files stay below the
 * 500-line CLAUDE.md ceiling.
 *
 * These tests pin the round-1 REDLINE §P1-1 finalize-order contract
 * (savePrivacyMode → createOrLoad → initMessaging) and the round-2
 * REDLINE retry-reuses-mode invariant (Persisted state carries
 * privacyMode; retry with a different mode argument is ignored).
 *
 * The bulk of the finalize state-machine contract (double-tap,
 * cancellation, phase-1/phase-2 failure) lives in the main file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingV2PrivacyFinalizeOrderingTest {

    private val fakeHex = "abcd" + "0123456789abcdef".repeat(3) + "abcd123456ef"

    private fun makeRecord(username: String = "alice"): IdentityRecord =
        IdentityRecord(
            id = "test-id-$username",
            username = username,
            publicKeyHex = "aa".repeat(32),
            dhPrivateKeyHex = "bb".repeat(32),
            createdAt = 1700000000L,
            signingPublicKeyHex = fakeHex,
            signingPrivateKeyHex = "cc".repeat(64),
        )

    private fun makeKeyPair(): IdentityKeyPair =
        IdentityKeyPair(
            publicKey = PublicKey(ByteArray(32) { it.toByte() }),
            privateKey = PrivateKey(ByteArray(32) { (it + 1).toByte() }),
        )

    // ── Round-1 REDLINE on Commit 4 · Privacy mode persistence ────────

    @Test
    fun selected_privacy_mode_is_saved_before_init_messaging_fires() = runTest {
        // Ordering contract: savePrivacyMode(mode) MUST land BEFORE
        // createOrLoad AND BEFORE initMessaging. Runtime relies on
        // TransportPreferences.privacyMode being the user's choice
        // by the time TransportManager.connect() runs — which happens
        // during initMessaging.
        val callOrder = mutableListOf<String>()
        val record = makeRecord()
        val keyPair = makeKeyPair()

        val controller = OnboardingFinalizeController(
            savePrivacyMode = { mode ->
                callOrder.add("savePrivacyMode:$mode")
            },
            createOrLoad = { username ->
                callOrder.add("createOrLoad:$username")
                record to keyPair
            },
            initMessaging = { _, _ ->
                callOrder.add("initMessaging")
            },
        )

        controller.finalize("alice", PrivacyMode.Private)

        assertEquals(
            listOf(
                "savePrivacyMode:${PrivacyMode.Private}",
                "createOrLoad:alice",
                "initMessaging",
            ),
            callOrder,
            "Mode persistence must fire BEFORE createOrLoad and BEFORE initMessaging.",
        )
        val persisted = controller.state
        assertIs<FinalizeState.Complete>(persisted)
    }

    @Test
    fun retry_after_phase2_failure_reuses_persisted_privacy_mode() = runTest {
        // On retry from Persisted state, savePrivacyMode MUST NOT
        // fire again — the mode was frozen at first success and a
        // fresh callback-argument (from a hypothetically re-selected
        // form state) must be ignored. Same shape as the username
        // invariant pinned by `retry_after_persistence_ignores_new_username_argument`.
        val record = makeRecord()
        val keyPair = makeKeyPair()
        val saveModeCalls = mutableListOf<PrivacyMode>()
        val initMessagingCalls = AtomicInteger(0)

        val controller = OnboardingFinalizeController(
            savePrivacyMode = { mode -> saveModeCalls.add(mode) },
            createOrLoad = { _ -> record to keyPair },
            initMessaging = { _, _ ->
                val nth = initMessagingCalls.incrementAndGet()
                if (nth == 1) throw RuntimeException("phase 2 fail")
            },
        )

        controller.finalize("alice", PrivacyMode.Private)
        val persisted = controller.state
        assertIs<FinalizeState.Persisted>(persisted)
        assertEquals(PrivacyMode.Private, persisted.privacyMode)
        assertEquals(listOf(PrivacyMode.Private), saveModeCalls)

        // Retry with a DIFFERENT mode argument. Must NOT invoke
        // savePrivacyMode again — the persisted mode is authoritative.
        controller.dismissTransientError()
        controller.finalize("alice", PrivacyMode.Standard)

        assertEquals(
            listOf(PrivacyMode.Private),
            saveModeCalls,
            "Retry with a fresh mode argument must not overwrite the persisted mode.",
        )
        assertEquals(2, initMessagingCalls.get())
        assertIs<FinalizeState.Complete>(controller.state)
    }
}
