// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C6-a round-4 REDLINE §P1 pin — pure JVM tests for the
 * durable [IdentityRepairMarker].
 *
 * The marker is the persistent quarantine anchor for the
 * "identity persisted with broken signing-key material" corner
 * case. Round-3 shape only closed the current task via
 * `Activity.finishAffinity()` — the SharedPreferences-backed
 * identity remained on disk and the next launch reached
 * `MainActivity`'s `identity != null → ChatList` gate. Round-4
 * writes this marker BEFORE the finish, and MainActivity reads
 * it BEFORE the identity gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2IdentityRepairMarkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearMarker() {
        // Fresh SharedPreferences state per test. Each test
        // asserts an invariant that depends on knowing the
        // starting value.
        IdentityRepairMarker.clearRepairRequiredBlocking(context)
    }

    @After
    fun tearDown() {
        IdentityRepairMarker.clearRepairRequiredBlocking(context)
    }

    @Test
    fun isRepairRequired_returns_false_by_default_on_fresh_install() {
        assertFalse(
            IdentityRepairMarker.isRepairRequired(context),
            "Fresh install (no prior marker write) MUST return false — the flow " +
                "should follow the normal `identity == null → Onboarding, else → " +
                "ChatList` path without spurious repair-quarantine routing.",
        )
    }

    @Test
    fun markRepairRequiredBlocking_writes_true_and_returns_true() {
        val ok = IdentityRepairMarker.markRepairRequiredBlocking(context)
        assertTrue(ok, "markRepairRequiredBlocking should succeed on a healthy SharedPreferences")
        assertTrue(
            IdentityRepairMarker.isRepairRequired(context),
            "After a successful mark, the read must return true.",
        )
    }

    @Test
    fun clearRepairRequiredBlocking_returns_marker_to_false() {
        IdentityRepairMarker.markRepairRequiredBlocking(context)
        assertTrue(IdentityRepairMarker.isRepairRequired(context))
        val ok = IdentityRepairMarker.clearRepairRequiredBlocking(context)
        assertTrue(ok)
        assertFalse(
            IdentityRepairMarker.isRepairRequired(context),
            "After clear, the read must return false — this is what a downstream " +
                "commit's real repair capability will call after writing a healthy " +
                "replacement identity.",
        )
    }

    @Test
    fun marker_is_durable_across_read_write_cycles() {
        // Round-4 §P1 pin: the marker MUST survive multiple
        // read cycles. The next-launch quarantine relies on the
        // marker being on disk after the process that wrote it
        // has died.
        repeat(3) {
            IdentityRepairMarker.markRepairRequiredBlocking(context)
        }
        repeat(5) {
            assertTrue(
                IdentityRepairMarker.isRepairRequired(context),
                "Marker must remain true across N reads without any intervening clear.",
            )
        }
    }

    @Test
    fun marker_uses_the_shared_phantom_prefs_file() {
        // Round-4 §P1 pin: marker lives in `phantom_prefs`
        // alongside `notifications_user_opted_in` and
        // `privacy_mode`. Uninstall wipes the file → marker is
        // cleared → user reinstalling gets a clean slate. Any
        // other file location would either survive uninstall
        // (bad) or require its own uninstall lifecycle
        // (unnecessary code).
        IdentityRepairMarker.markRepairRequiredBlocking(context)
        val direct = context.applicationContext
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .getBoolean(IdentityRepairMarker.IDENTITY_REPAIR_REQUIRED_KEY, false)
        assertTrue(
            direct,
            "Marker MUST live in `phantom_prefs` under the key `identity_repair_required` " +
                "so uninstall clears it atomically with the rest of the app-wide prefs.",
        )
    }

    @Test
    fun key_constant_matches_the_stable_name() {
        assertEquals(
            "identity_repair_required",
            IdentityRepairMarker.IDENTITY_REPAIR_REQUIRED_KEY,
            "The key name is part of the durable on-disk contract. Changing it would " +
                "orphan the marker on existing installs (they'd read false and " +
                "bypass the quarantine).",
        )
    }
}

/**
 * C6-a round-4 REDLINE §P1 pin — durable quarantine
 * integration test.
 *
 * Simulates the "next-launch" scenario the architect asked for:
 *   1. First composition of a stand-in for the flow's
 *      `rememberOnboardingFinalizeStateHolder(initialState)` —
 *      seeds from the marker via the composable factory contract.
 *   2. Verify the initial state matches the marker's disk value.
 *   3. Verify the flow's short-circuit renders
 *      `OnboardingRepairRequiredScreen` at cold-start when the
 *      marker is set.
 *
 * A second test uses `createAndroidComposeRule` to prove the
 * marker-driven initial state ACTUALLY makes the repair screen
 * visible on the first composition (no user interaction needed —
 * this simulates the app reopening after a prior Exit tap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2NextLaunchQuarantineTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() =
        composeTestRule.activity.applicationContext

    @Before
    fun clearMarker() {
        IdentityRepairMarker.clearRepairRequiredBlocking(context)
    }

    @After
    fun tearDown() {
        IdentityRepairMarker.clearRepairRequiredBlocking(context)
    }

    // Round-7 REDLINE: prior `cold_start_with_marker_present_…`
    // tests are retired. Round-7 removed the marker-fallback
    // from OnboardingFlowV2's holder seeding — the flow no
    // longer independently reads the marker; the ONLY source of
    // truth is MainActivity's `explicitInitialFinalizeState`
    // override, which is driven by `decideStartupRoute`. The
    // "marker → initial state" mapping is covered by
    // OnboardingV2StartupRouteTest (pure JVM decider tests) and
    // the "override → repair-screen visible" wiring is covered
    // by `explicit_override_wins_over_absent_disk_marker` below
    // + the new production-wrapper test in
    // OnboardingV2ProductionWrapperQuarantineTest.
    //
    // The retired tests seeded holder via a bespoke
    //   if (isRepairRequired(ctx)) MissingKeyRepairRequired else NotStarted
    // pattern that hand-rolled the mapping the flow used to do
    // internally. That mapping is now `decideStartupRoute`'s
    // responsibility and is unit-tested there.

    // ── Round-5 §P1 load-bearing tests ──────────────────────────────

    private val brokenRecord = phantom.core.identity.IdentityRecord(
        id = "alice-id",
        username = "alice",
        publicKeyHex = "aa".repeat(32),
        dhPrivateKeyHex = "bb".repeat(32),
        createdAt = 0L,
        signingPublicKeyHex = null,  // broken: no Ed25519 signing key
        signingPrivateKeyHex = null,
    )
    private val syntheticKeyPair = phantom.core.identity.IdentityKeyPair(
        publicKey = phantom.core.identity.PublicKey(ByteArray(32) { it.toByte() }),
        privateKey = phantom.core.identity.PrivateKey(ByteArray(32) { (it + 100).toByte() }),
    )

    @Test
    fun broken_flow_writes_marker_at_MissingKeyMaterial_detection_not_at_Exit() {
        // Round-5 §P1-1 load-bearing test: marker starts FALSE.
        // Broken controller drives full flow through Done. AFTER
        // Done + waitForIdle (but BEFORE any Exit tap), the marker
        // MUST already be TRUE. Round-4 shape wrote the marker
        // only on Exit — this test would fail on that shape.
        assertFalse(
            IdentityRepairMarker.isRepairRequired(context),
            "Sanity: marker starts false (test fixture cleared it)",
        )
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { },
                controller = controller,
            )
        }
        // Drive the flow through all 5 steps up to Done.
        composeTestRule.onNodeWithText("Get started").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Create my identity").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            androidx.compose.ui.test.hasSetTextAction(),
        ).performTextInput("alice")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done, let's go").performClick()
        composeTestRule.waitForIdle()

        // At this point the flow's coroutine has:
        //   1. Called runFinalize → returned MissingKeyMaterial
        //      (broken record has null signingPublicKeyHex).
        //   2. Called persistMissingKeyMarkerIfNeeded, which
        //      wrote IdentityRepairMarker.markRepairRequiredBlocking.
        //   3. Called holder.applyFinalizeOutcome →
        //      MissingKeyRepairRequired → repair screen visible.
        //
        // The Exit CTA has NOT been tapped. The marker MUST
        // already be true.
        assertTrue(
            IdentityRepairMarker.isRepairRequired(context),
            "Round-5 §P1-1 load-bearing pin: the durable marker MUST be TRUE " +
                "immediately after the flow detects MissingKeyMaterial (BEFORE " +
                "any user interaction with the repair screen). Round-4 shape " +
                "wrote the marker only from the Exit tap — process kill / app " +
                "backgrounding between MissingKeyMaterial and Exit left a broken " +
                "identity on disk with no marker.",
        )
        // Repair screen visible as belt-and-suspenders check.
        composeTestRule.onNodeWithContentDescription("OnboardingRepairRequiredScreen")
            .assertIsDisplayed()
        // Activity NOT finishing yet — Exit hasn't been tapped.
        assertFalse(
            composeTestRule.activity.isFinishing,
            "Activity MUST NOT be finishing — Exit was never tapped. The marker " +
                "was written independently of the Exit action.",
        )
    }

    @Test
    fun exit_tap_is_idempotent_only_finishes_activity_marker_already_true() {
        // Round-5 §P1-1 load-bearing pin: Exit CTA is now
        // IDEMPOTENT with respect to the marker. The marker was
        // already written at MissingKeyMaterial detection time
        // (see broken_flow_writes_marker_at_MissingKeyMaterial_detection
        // above); Exit's sole job is to close the Activity.
        //
        // Seed marker + render repair screen directly (skipping
        // the full-flow drive to keep the test fast — the
        // full-flow test above already covers detection-time
        // marker write).
        // Round-7: seed marker AND pass explicit override —
        // MainActivity's contract is that RepairQuarantine ALWAYS
        // ships the override down, so tests that render the flow
        // directly must simulate that plumbing.
        IdentityRepairMarker.markRepairRequiredBlocking(context)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* unreachable */ },
            createOrLoad = { _ -> error("unreachable") },
            initMessaging = { _, _ -> error("unreachable") },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { },
                controller = controller,
                explicitInitialFinalizeState =
                    OnboardingFinalizeState.MissingKeyRepairRequired,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Exit onboarding").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            IdentityRepairMarker.isRepairRequired(context),
            "After Exit tap the marker MUST still be true (idempotency — a " +
                "subsequent cold-start would still route to quarantine via " +
                "decideStartupRoute's marker branch).",
        )
        assertTrue(
            composeTestRule.activity.isFinishing,
            "Activity MUST be finishing after Exit tap.",
        )
    }

    // ── Round-6 §P1: explicit override WINS over disk marker ─────

    @Test
    fun explicit_override_wins_over_absent_disk_marker() {
        // Round-6 §P1 architect-requested test: MainActivity's
        // OnboardingRepairQuarantine branch writes the durable
        // marker, but SharedPreferences `.commit()` CAN return
        // false. Prior round-5 shape discarded that boolean, so
        // a failed commit meant the flow's rememberSaveable
        // holder read the disk marker as false and seeded at
        // NotStarted — landing the user on Welcome despite the
        // identity being broken.
        //
        // Round-6 plumbs an `explicitInitialFinalizeState`
        // param through OnboardingScreenV2 → OnboardingFlowV2
        // → OnboardingFlowV2Internal. MainActivity passes
        // MissingKeyRepairRequired when `markRepairRequiredBlocking`
        // returned false. This test proves the override WINS
        // over the disk marker being absent.
        assertFalse(
            IdentityRepairMarker.isRepairRequired(context),
            "Sanity: disk marker is FALSE (cleared in @Before)",
        )
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* unreachable */ },
            createOrLoad = { _ -> error("controller.createOrLoad reached unexpectedly") },
            initMessaging = { _, _ -> error("controller.initMessaging reached unexpectedly") },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { },
                controller = controller,
                explicitInitialFinalizeState =
                    OnboardingFinalizeState.MissingKeyRepairRequired,
            )
        }
        composeTestRule.waitForIdle()
        // Repair screen visible EVEN THOUGH the disk marker is
        // absent — the explicit override wins.
        composeTestRule.onNodeWithContentDescription("OnboardingRepairRequiredScreen")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Identity repair required").assertIsDisplayed()
    }

    @Test
    fun null_override_seeds_NotStarted_regardless_of_disk_marker() {
        // Round-7 REDLINE: `OnboardingFlowV2` NO LONGER reads
        // the disk marker itself — the flow's holder seeds
        // NotStarted when the override is null, PERIOD. Prior
        // round-6 shape fell back to the disk marker; that
        // duplicated decideStartupRoute's logic and led to the
        // round-7 §P1-1 finding (marker-present cold-start
        // walked through Terms first because MainActivity only
        // forwarded the override on marker-write failure).
        //
        // This test seeds the marker AND passes override=null,
        // then verifies the flow opens on Welcome (NotStarted).
        // If the marker-fallback creeps back into the flow,
        // this test fails because it would render the repair
        // screen instead.
        IdentityRepairMarker.markRepairRequiredBlocking(context)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* not reached during Welcome */ },
            createOrLoad = { _ -> error("not reached") },
            initMessaging = { _, _ -> error("not reached") },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { },
                controller = controller,
                explicitInitialFinalizeState = null,
            )
        }
        composeTestRule.waitForIdle()
        // Welcome step visible (Get started CTA) — proves the
        // flow ignored the disk marker.
        composeTestRule.onNodeWithText("Get started").assertIsDisplayed()
        // Repair screen NOT visible.
        composeTestRule.onAllNodesWithText("Identity repair required")
            .assertCountEquals(0)
    }
}
