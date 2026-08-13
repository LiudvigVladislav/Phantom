// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.transport.PrivacyMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-15 REDLINE §P1 pin — regression suite for the
 * `runFinalize` / `FinalizeOutcome` contract that round-14
 * shipped for atomic success-transition. These tests fail if a
 * future refactor:
 *   - Splits the phase-write out of the coroutine again (round-13
 *     shape); or
 *   - Returns a wrong `FinalizeOutcome` for a given terminal
 *     controller state (which would let the caller either double-
 *     invoke initMessaging on the retry path or wrongly unlock
 *     Back on the post-persistence-error path).
 *
 * Scenarios pinned:
 *   1. Success → `FinalizeOutcome.Completed(record)`;
 *      `initMessaging` invoked exactly once;
 *      controller terminal state = `Complete(record)`.
 *   2. Phase-0 error (createOrLoad throws) → `FailedBeforePersistence`;
 *      controller reverts to `Idle`;
 *      `initMessaging` never invoked.
 *   3. Phase-2 error (initMessaging throws) → `FailedAfterPersistence`;
 *      controller stays at `Persisted(...)`;
 *      first `initMessaging` invoked once with the persisted pair.
 *   4. Retry after Phase-2 error → `Completed`;
 *      `createOrLoad` NOT called a second time (short-circuit);
 *      `initMessaging` invoked one MORE time with the SAME pair.
 *
 * The test uses real `OnboardingFinalizeController` (not a mock)
 * with test lambdas — the invariants are what the flow depends
 * on, not the controller shape.
 */
class OnboardingV2FinalizeOutcomeContractTest {

    private val syntheticRecord = IdentityRecord(
        id = "alice-id",
        username = "alice",
        publicKeyHex = "aa".repeat(32),
        dhPrivateKeyHex = "bb".repeat(32),
        createdAt = 0L,
        signingPublicKeyHex = "cc".repeat(32),
        signingPrivateKeyHex = "dd".repeat(32),
    )
    private val syntheticKeyPair = IdentityKeyPair(
        publicKey = phantom.core.identity.PublicKey(ByteArray(32) { it.toByte() }),
        privateKey = phantom.core.identity.PrivateKey(ByteArray(32) { (it + 100).toByte() }),
    )

    // ── Scenario 1: success ───────────────────────────────────────────

    @Test
    fun success_returns_Completed_and_invokes_initMessaging_exactly_once() = runTest {
        val initMessagingCalls = AtomicInteger(0)
        val savePrivacyCalls = AtomicInteger(0)
        val createOrLoadCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { savePrivacyCalls.incrementAndGet() },
            createOrLoad = { _ ->
                createOrLoadCalls.incrementAndGet()
                syntheticRecord to syntheticKeyPair
            },
            initMessaging = { _, _ -> initMessagingCalls.incrementAndGet() },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertTrue(
            outcome is FinalizeOutcome.Completed,
            "expected Completed, got $outcome",
        )
        assertEquals(syntheticRecord, (outcome as FinalizeOutcome.Completed).record)
        assertEquals(1, initMessagingCalls.get(), "initMessaging should be invoked exactly once")
        assertEquals(1, savePrivacyCalls.get())
        assertEquals(1, createOrLoadCalls.get())
        assertTrue(
            controller.state is FinalizeState.Complete,
            "controller terminal state should be Complete",
        )
    }

    // ── Scenario 2: phase-0 error (createOrLoad throws) ───────────────

    @Test
    fun phase_0_error_returns_FailedBeforePersistence_and_reverts_to_Idle() = runTest {
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> throw IllegalStateException("phase 0 fail") },
            initMessaging = { _, _ -> initMessagingCalls.incrementAndGet() },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.FailedBeforePersistence, outcome)
        assertTrue(
            controller.state is FinalizeState.Idle,
            "controller should revert to Idle after phase-0 error, got ${controller.state}",
        )
        assertEquals(
            0,
            initMessagingCalls.get(),
            "initMessaging must NEVER be invoked when createOrLoad throws",
        )
        // Transient error surface populated for UI toast.
        assertNotNull(controller.transientErrorMessage)
    }

    // ── Scenario 3: phase-2 error (initMessaging throws) ──────────────

    @Test
    fun phase_2_error_returns_FailedAfterPersistence_and_stays_Persisted() = runTest {
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ ->
                initMessagingCalls.incrementAndGet()
                throw IllegalStateException("phase 2 fail")
            },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.FailedAfterPersistence, outcome)
        val terminal = controller.state
        assertTrue(
            terminal is FinalizeState.Persisted,
            "controller should stay at Persisted after phase-2 error, got $terminal",
        )
        val persisted = terminal as FinalizeState.Persisted
        assertEquals(syntheticRecord, persisted.record)
        assertEquals(PrivacyMode.Standard, persisted.privacyMode)
        assertEquals(1, initMessagingCalls.get())
    }

    // ── Scenario 4: retry after phase-2 error ─────────────────────────

    @Test
    fun retry_after_phase_2_error_returns_Completed_without_re_calling_createOrLoad() = runTest {
        val initMessagingCalls = AtomicInteger(0)
        val createOrLoadCalls = AtomicInteger(0)
        var shouldInitFail = true
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ ->
                createOrLoadCalls.incrementAndGet()
                syntheticRecord to syntheticKeyPair
            },
            initMessaging = { _, _ ->
                val n = initMessagingCalls.incrementAndGet()
                if (n == 1 && shouldInitFail) throw IllegalStateException("phase 2 fail")
            },
        )
        val firstOutcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.FailedAfterPersistence, firstOutcome)
        shouldInitFail = false
        val secondOutcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertTrue(
            secondOutcome is FinalizeOutcome.Completed,
            "retry should reach Completed, got $secondOutcome",
        )
        assertEquals(
            1,
            createOrLoadCalls.get(),
            "createOrLoad MUST NOT be re-invoked on retry (Persisted → Complete short-circuit)",
        )
        assertEquals(
            2,
            initMessagingCalls.get(),
            "initMessaging invoked exactly once per attempt",
        )
        assertTrue(controller.state is FinalizeState.Complete)
    }

    // ── Round-16 §P1 pin: `applyFinalizeOutcome` reducer contract ────

    @Test
    fun apply_Completed_advance_has_hex_and_Finale_and_Completed() {
        val outcome = FinalizeOutcome.Completed(syntheticRecord)
        val advance = applyFinalizeOutcome(outcome)
        assertEquals(
            syntheticRecord.signingPublicKeyHex,
            advance.newSigningPublicKeyHex,
            "Completed advance MUST carry the record's signingPublicKeyHex",
        )
        assertEquals(
            OnboardingStepV2.FinaleConfirmation,
            advance.newStep,
            "Completed advance MUST transition to FinaleConfirmation",
        )
        assertEquals(
            OnboardingFinalizePhase.Completed,
            advance.newPhase,
            "Completed advance MUST set phase = Completed",
        )
    }

    @Test
    fun apply_FailedBeforePersistence_leaves_hex_and_step_and_sets_NotStarted() {
        val advance = applyFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence)
        assertNull(
            advance.newSigningPublicKeyHex,
            "FailedBeforePersistence must NOT change signingPublicKeyHex",
        )
        assertNull(
            advance.newStep,
            "FailedBeforePersistence must NOT change currentStep (user stays on Permissions)",
        )
        assertEquals(
            OnboardingFinalizePhase.NotStarted,
            advance.newPhase,
            "FailedBeforePersistence MUST unlock Back via NotStarted",
        )
    }

    @Test
    fun apply_FailedAfterPersistence_leaves_hex_and_step_and_keeps_InFlight() {
        val advance = applyFinalizeOutcome(FinalizeOutcome.FailedAfterPersistence)
        assertNull(advance.newSigningPublicKeyHex)
        assertNull(advance.newStep)
        assertEquals(
            OnboardingFinalizePhase.InFlight,
            advance.newPhase,
            "FailedAfterPersistence MUST keep Back locked via InFlight " +
                "(Done retap short-circuits Persisted → Complete)",
        )
    }

    // ── End-to-end: outcome + apply produce the fully-transitioned model.
    // Simulates the caller coroutine's behaviour without going through
    // Compose. A round-13 split-writer regression would leave one of
    // the three fields at its pre-transition value here.

    @Test
    fun success_end_to_end_all_three_fields_flip_atomically() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        // Model state before Done tap.
        var formHex: String? = null
        var currentStep: OnboardingStepV2 = OnboardingStepV2.Permissions
        var phase: OnboardingFinalizePhase = OnboardingFinalizePhase.NotStarted

        // Done tap: phase → InFlight.
        phase = OnboardingFinalizePhase.InFlight
        // Same body as production: runFinalize + applyFinalizeOutcome
        // + write all three fields atomically.
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        val advance = applyFinalizeOutcome(outcome)
        advance.newSigningPublicKeyHex?.let { formHex = it }
        advance.newStep?.let { currentStep = it }
        phase = advance.newPhase

        assertEquals(
            syntheticRecord.signingPublicKeyHex, formHex,
            "success: signingPublicKeyHex must be set",
        )
        assertEquals(
            OnboardingStepV2.FinaleConfirmation, currentStep,
            "success: currentStep must advance to FinaleConfirmation",
        )
        assertEquals(
            OnboardingFinalizePhase.Completed, phase,
            "success: phase must be Completed",
        )
    }

    @Test
    fun phase_0_error_end_to_end_stays_on_Permissions_with_NotStarted() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> throw IllegalStateException("fail phase 0") },
            initMessaging = { _, _ -> /* unreachable */ },
        )
        var formHex: String? = null
        var currentStep: OnboardingStepV2 = OnboardingStepV2.Permissions
        var phase: OnboardingFinalizePhase = OnboardingFinalizePhase.InFlight

        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        val advance = applyFinalizeOutcome(outcome)
        advance.newSigningPublicKeyHex?.let { formHex = it }
        advance.newStep?.let { currentStep = it }
        phase = advance.newPhase

        assertNull(formHex, "phase-0 error: hex must remain null")
        assertEquals(
            OnboardingStepV2.Permissions, currentStep,
            "phase-0 error: currentStep must stay on Permissions",
        )
        assertEquals(
            OnboardingFinalizePhase.NotStarted, phase,
            "phase-0 error: phase must revert to NotStarted (Back unlocks)",
        )
    }

    @Test
    fun phase_2_error_end_to_end_stays_on_Permissions_with_InFlight() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> throw IllegalStateException("fail phase 2") },
        )
        var formHex: String? = null
        var currentStep: OnboardingStepV2 = OnboardingStepV2.Permissions
        var phase: OnboardingFinalizePhase = OnboardingFinalizePhase.InFlight

        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        val advance = applyFinalizeOutcome(outcome)
        advance.newSigningPublicKeyHex?.let { formHex = it }
        advance.newStep?.let { currentStep = it }
        phase = advance.newPhase

        assertNull(formHex, "phase-2 error: hex must remain null")
        assertEquals(
            OnboardingStepV2.Permissions, currentStep,
            "phase-2 error: currentStep must stay on Permissions",
        )
        assertEquals(
            OnboardingFinalizePhase.InFlight, phase,
            "phase-2 error: phase must stay InFlight (Back stays locked)",
        )
    }

    // ── Round-17 §P1 pin: writer interface + single-helper contract ──

    @Test
    fun applyAndCommit_success_calls_writer_in_hex_step_phase_order() {
        val w = CapturingFinalizeStateWriter()
        applyAndCommitFinalizeOutcome(FinalizeOutcome.Completed(syntheticRecord), w)
        assertEquals(syntheticRecord.signingPublicKeyHex, w.capturedHex)
        assertTrue(w.advancedToFinaleConfirmation)
        assertEquals(OnboardingFinalizePhase.Completed, w.capturedPhase)
        // Ordering pin: hex first, then step advance, then phase.
        assertEquals(listOf("hex", "step", "phase"), w.callOrder)
    }

    @Test
    fun applyAndCommit_failed_before_persistence_calls_phase_only() {
        val w = CapturingFinalizeStateWriter()
        applyAndCommitFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence, w)
        assertNull(w.capturedHex)
        assertTrue(!w.advancedToFinaleConfirmation)
        assertEquals(OnboardingFinalizePhase.NotStarted, w.capturedPhase)
        assertEquals(listOf("phase"), w.callOrder)
    }

    @Test
    fun applyAndCommit_failed_after_persistence_calls_phase_only() {
        val w = CapturingFinalizeStateWriter()
        applyAndCommitFinalizeOutcome(FinalizeOutcome.FailedAfterPersistence, w)
        assertNull(w.capturedHex)
        assertTrue(!w.advancedToFinaleConfirmation)
        assertEquals(OnboardingFinalizePhase.InFlight, w.capturedPhase)
        assertEquals(listOf("phase"), w.callOrder)
    }

    // ── Source-contract pin: OnboardingFlowV2.kt uses helper only ─────

    @Test
    fun source_contract_flow_calls_applyAndCommit_exactly_twice_and_only_via_writer() {
        val source = java.io.File(
            "src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt",
        ).readText()

        // Must call helper exactly twice: once for Done tap, once
        // for post-recreation resume. Extra or missing calls signal
        // a bypass or new duplicated write site.
        // Negative lookbehind excludes the `internal fun` declaration
        // (function signature is not a call).
        val helperCalls = Regex("""(?<!fun )\bapplyAndCommitFinalizeOutcome\s*\(""")
            .findAll(source).count()
        assertEquals(
            2, helperCalls,
            "OnboardingFlowV2.kt must invoke applyAndCommitFinalizeOutcome exactly twice " +
                "(Done tap + resume). Found $helperCalls.",
        )

        // runFinalize must ALWAYS be paired with the helper — count
        // of runFinalize calls (excluding declaration) must equal
        // count of helper calls.
        val runFinalizeCalls = Regex("""(?<!fun )\brunFinalize\s*\(""")
            .findAll(source).count()
        assertEquals(
            helperCalls, runFinalizeCalls,
            "runFinalize call count ($runFinalizeCalls) MUST equal " +
                "applyAndCommitFinalizeOutcome call count ($helperCalls) — every " +
                "finalize invocation must feed its outcome through the writer.",
        )

        // No direct assignment to `formState.copy(signingPublicKeyHex ...`
        // outside the writer's `writeSigningPublicKeyHex` body.
        val hexAssignments = Regex(
            """formState\s*=\s*formState\.copy\s*\(\s*signingPublicKeyHex\s*=""",
        ).findAll(source).count()
        assertEquals(
            1, hexAssignments,
            "Only the FinalizeStateWriter's writeSigningPublicKeyHex may assign " +
                "formState.signingPublicKeyHex. Found $hexAssignments direct assignments.",
        )

        // No direct assignment `currentStep = OnboardingStepV2.FinaleConfirmation`
        // outside the writer's `advanceToFinaleConfirmation` body.
        val finaleAssignments = Regex(
            """currentStep\s*=\s*OnboardingStepV2\.FinaleConfirmation""",
        ).findAll(source).count()
        assertEquals(
            1, finaleAssignments,
            "Only the FinalizeStateWriter's advanceToFinaleConfirmation may assign " +
                "currentStep = OnboardingStepV2.FinaleConfirmation. " +
                "Found $finaleAssignments direct assignments.",
        )

        // Round-18 REDLINE §P1 pin: `finalizePhase = <anything>` must
        // appear EXACTLY ONCE in the file — inside the writer's
        // `setFinalizePhase` body. Round-17 shape allowed a direct
        // `finalizePhase = OnboardingFinalizePhase.InFlight` from the
        // Done-tap block; round-18 routes even that pre-launch
        // transition through the writer. Any additional direct
        // assignment fails this pin.
        val phaseAssignments = Regex(
            """(?<!\S)finalizePhase\s*=(?!=)""",
        ).findAll(source).count()
        assertEquals(
            1, phaseAssignments,
            "Only the FinalizeStateWriter's setFinalizePhase may assign " +
                "finalizePhase. Found $phaseAssignments direct assignments — every " +
                "InFlight / Completed / NotStarted transition MUST route through " +
                "the writer.",
        )
    }

    // ── Round-18 pin: source-contract accuracy note ────────────────
    //
    // The regex checks above catch the STRAIGHT-FORWARD assignment
    // forms that any reasonable refactor would produce (direct
    // `field = value` at file scope). They are not, and cannot be,
    // an exhaustive proof of writer exclusivity — a determined
    // refactor could route a write through an intermediate `val
    // updated = ...` binding or a reflected setter and slip past
    // the regex. The tripwire's role is to fail EARLY on the
    // common regression shape, not to certify total ownership.
    // Total ownership would require sealing the three state slots
    // behind a private state holder with a public writer-only API;
    // that is the direction Commit 6's holder refactor takes.

    private class CapturingFinalizeStateWriter : FinalizeStateWriter {
        val callOrder = mutableListOf<String>()
        var capturedHex: String? = null
        var advancedToFinaleConfirmation: Boolean = false
        var capturedPhase: OnboardingFinalizePhase? = null

        override fun writeSigningPublicKeyHex(hex: String) {
            capturedHex = hex
            callOrder += "hex"
        }

        override fun advanceToFinaleConfirmation() {
            advancedToFinaleConfirmation = true
            callOrder += "step"
        }

        override fun setFinalizePhase(phase: OnboardingFinalizePhase) {
            capturedPhase = phase
            callOrder += "phase"
        }
    }

    // ── Defensive: transient error is cleared on next attempt ─────────

    @Test
    fun retry_after_phase_2_error_clears_transient_error() = runTest {
        var attempts = 0
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("first attempt fails")
            },
        )
        runFinalize(controller, "alice", PrivacyMode.Standard)
        assertNotNull(controller.transientErrorMessage, "first attempt should set error")
        runFinalize(controller, "alice", PrivacyMode.Standard)
        assertNull(
            controller.transientErrorMessage,
            "retry clears the transient error (controller resets it before phase 2)",
        )
    }
}
