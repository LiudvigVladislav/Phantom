// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.transport.PrivacyMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * C6-a REDLINE pin — regression suite for the sealed
 * [OnboardingFinalizeState] model + its holder API + Saver.
 *
 * Round-2 amend adds the [OnboardingFinalizeState.MissingKeyRepairRequired]
 * state + a strict Ed25519 hex contract on `Completed` and the
 * `FinalizeOutcome.Completed` outcome. Prior round-1 shape
 * routed null-hex Complete to `FailedAfterPersistence`
 * (silent-InFlight-tupik) and let empty-hex reach the sealed
 * constructor as an unhandled `IllegalArgumentException`. The
 * round-2 shape:
 *
 *   - validates hex against the Ed25519 contract
 *     ([isValidEd25519PublicKeyHex]: exactly 64 chars, `[0-9a-fA-F]`)
 *     BEFORE constructing an outcome, upstream in `runFinalize`;
 *   - routes any malformed hex (null / empty / wrong length /
 *     non-hex) to a NEW outcome `FinalizeOutcome.MissingKeyMaterial`;
 *   - maps that outcome via the holder to a NEW sealed state
 *     `MissingKeyRepairRequired` — a VISIBLE repair-required
 *     screen with a safe-exit action, not an invisible retry loop;
 *   - unlocks system Back in the repair-required state (safe exit
 *     up the flow);
 *   - `Completed(hex).init` + `FinalizeOutcome.Completed.init`
 *     BOTH `require` the Ed25519 contract, so a malformed hex
 *     escaping the runFinalize filter would fail-loud at
 *     construction rather than silently corrupting the sealed
 *     state.
 *
 * Scenarios pinned:
 *
 *   ── `runFinalize` outcome contract ──────────────────────────
 *   1. Success → `Completed(hex)`; controller Complete(record).
 *   2. Phase-0 error → `FailedBeforePersistence`; controller Idle.
 *   3. Phase-2 error → `FailedAfterPersistence`; controller Persisted.
 *   4. Retry after Phase-2 → `Completed(hex)`; no re-createOrLoad.
 *   5. C6-a REDLINE round-2 §P1: NULL hex Complete →
 *      `MissingKeyMaterial`.
 *   6. C6-a REDLINE round-2 §P1: EMPTY hex Complete →
 *      `MissingKeyMaterial` (does NOT throw at outcome layer).
 *   7. C6-a REDLINE round-2 §P1: MALFORMED hex Complete (wrong
 *      length / non-hex chars) → `MissingKeyMaterial`.
 *
 *   ── Ed25519 hex contract ──────────────────────────────────
 *   8.  `isValidEd25519PublicKeyHex` accepts 64-char hex.
 *   9.  Rejects: empty, wrong length, non-hex chars.
 *  10.  `OnboardingFinalizeState.Completed("")` throws.
 *  11.  `Completed(wrongLength)` throws.
 *  12.  `Completed(nonHex)` throws.
 *  13.  `FinalizeOutcome.Completed("")` throws (defense in depth
 *       at the outcome boundary too).
 *
 *   ── Holder API + state-machine guards ────────────────────
 *  14.  `markInFlight`: NotStarted → InFlight.
 *  15.  `markInFlight`: InFlight → InFlight (idempotent).
 *  16.  `markInFlight`: Completed → NO-OP (terminal guard).
 *  17.  `markInFlight`: MissingKeyRepairRequired → NO-OP
 *       (needs explicit repair via resetFromRepairRequired).
 *  18.  `applyFinalizeOutcome(Completed(hex))` from InFlight
 *       transitions to `Completed(hex)`.
 *  19.  `applyFinalizeOutcome(FailedBeforePersistence)` from
 *       InFlight → NotStarted.
 *  20.  `applyFinalizeOutcome(FailedAfterPersistence)` from
 *       InFlight → InFlight.
 *  21.  `applyFinalizeOutcome(MissingKeyMaterial)` from InFlight
 *       → MissingKeyRepairRequired (round-2 §P1 pin).
 *  22.  `applyFinalizeOutcome` NO-OP from NotStarted (stale
 *       outcome guard).
 *  23.  `applyFinalizeOutcome` NO-OP from Completed (stale
 *       outcome guard; also blocks a MissingKeyMaterial from
 *       "downgrading" a happy Completed).
 *  24.  `applyFinalizeOutcome` NO-OP from
 *       MissingKeyRepairRequired (defense: only the repair
 *       screen's Start-over action mutates this state).
 *  25.  `resetFromRepairRequired`: MissingKeyRepairRequired
 *       → NotStarted.
 *  26.  `resetFromRepairRequired` NO-OP from every other state.
 *
 *   ── Saver round-trip ────────────────────────────────────
 *  27.  NotStarted round-trips.
 *  28.  InFlight round-trips.
 *  29.  Completed(hex) round-trips with hex intact.
 *  30.  MissingKeyRepairRequired round-trips (round-2 §P1 pin).
 *  31.  Saver restore rejects unknown tag.
 *  32.  Saver restore of Completed slot with EMPTY hex fails-loud.
 *  33.  Saver restore of Completed slot with MALFORMED hex
 *       (wrong length / non-hex) fails-loud (round-2 §P1 pin).
 *
 *   ── Full end-to-end via runFinalize + holder ────────────
 *  34.  Success end-to-end: holder lands Completed with hex.
 *  35.  Phase-0 error e2e: holder reverts to NotStarted.
 *  36.  Phase-2 error e2e: holder stays InFlight.
 *  37.  NULL hex e2e: holder lands MissingKeyRepairRequired
 *       (round-2 §P1 pin — architect asked for this specifically).
 *  38.  EMPTY hex e2e: holder lands MissingKeyRepairRequired.
 *  39.  MALFORMED hex e2e: holder lands MissingKeyRepairRequired.
 *
 *   ── Structural block on regular navigation → Finale ────
 *  40.  `canAdvanceFromV2(Permissions, any)` is false.
 *  41.  `computeNextNavigationStep(Permissions)` is null.
 *  42.  `computeNextNavigationStep(FinaleConfirmation)` is null.
 *  43.  `computeNextNavigationStep(any)` never returns Finale.
 *  44.  `computeNextNavigationStep` positive-side advances
 *       Welcome → How → Identity → Privacy.
 *
 *   ── Source-contract tripwire (NON-EXHAUSTIVE, tightened) ─
 *  45.  Combined test: no direct writes to former finalize
 *       fields; holder call parity (Done tap + resume = 2
 *       applyFinalizeOutcome + 2 runFinalize + 1 markInFlight).
 *
 *   ── Defensive: transient error is cleared on next attempt ─
 *  46.  Retry after Phase-2 error clears
 *       `controller.transientErrorMessage`.
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
    private val syntheticHex: String = syntheticRecord.signingPublicKeyHex!!
    private val syntheticEncHex: String = syntheticRecord.publicKeyHex
    private val syntheticKeyPair = IdentityKeyPair(
        publicKey = phantom.core.identity.PublicKey(ByteArray(32) { it.toByte() }),
        privateKey = phantom.core.identity.PrivateKey(ByteArray(32) { (it + 100).toByte() }),
    )

    // ── Scenario 1: success ───────────────────────────────────────────

    @Test
    fun success_returns_Completed_and_invokes_initMessaging_exactly_once() = runTest {
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> initMessagingCalls.incrementAndGet() },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertTrue(outcome is FinalizeOutcome.Completed, "expected Completed, got $outcome")
        assertEquals(syntheticHex, (outcome as FinalizeOutcome.Completed).signingPublicKeyHex)
        assertEquals(1, initMessagingCalls.get())
        assertTrue(controller.state is FinalizeState.Complete)
    }

    // ── Scenario 2: phase-0 error (createOrLoad throws) ───────────────

    @Test
    fun phase_0_error_returns_FailedBeforePersistence_and_reverts_to_Idle() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> throw IllegalStateException("phase 0 fail") },
            initMessaging = { _, _ -> /* unreachable */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.FailedBeforePersistence, outcome)
        assertTrue(controller.state is FinalizeState.Idle)
        assertNotNull(controller.transientErrorMessage)
    }

    // ── Scenario 3: phase-2 error (initMessaging throws) ──────────────

    @Test
    fun phase_2_error_returns_FailedAfterPersistence_and_stays_Persisted() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> throw IllegalStateException("phase 2 fail") },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.FailedAfterPersistence, outcome)
        assertTrue(controller.state is FinalizeState.Persisted)
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
        assertTrue(secondOutcome is FinalizeOutcome.Completed)
        assertEquals(syntheticHex, (secondOutcome as FinalizeOutcome.Completed).signingPublicKeyHex)
        assertEquals(1, createOrLoadCalls.get())
        assertEquals(2, initMessagingCalls.get())
    }

    // ── Scenarios 5, 6, 7: null / empty / malformed hex from controller ─

    @Test
    fun null_hex_Complete_from_controller_routes_to_MissingKeyMaterial() = runTest {
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = null)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(
            FinalizeOutcome.MissingKeyMaterial,
            outcome,
            "null hex Complete MUST route to MissingKeyMaterial (round-2 §P1 pin) — " +
                "routing to FailedAfterPersistence (round-1 shape) trapped user at " +
                "InFlight/Permissions with Back locked and no visible error.",
        )
    }

    @Test
    fun empty_hex_Complete_from_controller_routes_to_MissingKeyMaterial() = runTest {
        // Round-1 shape only checked hex == null, so empty string
        // reached Completed("") constructor and threw
        // IllegalArgumentException in the coroutine. Round-2
        // filters upfront in runFinalize.
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = "")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    @Test
    fun malformed_hex_Complete_from_controller_routes_to_MissingKeyMaterial() = runTest {
        // Wrong length (63 chars) — closer to a real ed25519 hex
        // but off-by-one, the kind of bug a truncation would
        // produce.
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = "ab".repeat(31) + "a")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    @Test
    fun non_hex_chars_Complete_from_controller_routes_to_MissingKeyMaterial() = runTest {
        // 64 chars total but contains a non-hex character.
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = "z" + "b".repeat(63))
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    // ── Dual-key labels track 2026-08-10 §3.3 — X25519 side of runFinalize ─

    @Test
    fun empty_X25519_publicKeyHex_Complete_from_controller_routes_to_MissingKeyMaterial() = runTest {
        // Symmetric to the Ed25519 empty-hex test above: an X25519
        // `publicKeyHex = ""` from the controller-returned record
        // MUST route atomically to MissingKeyMaterial. Both hexes
        // must pass validation together (dual-key contract §3.3).
        val brokenRecord = syntheticRecord.copy(publicKeyHex = "")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    @Test
    fun wrong_length_X25519_publicKeyHex_Complete_routes_to_MissingKeyMaterial() = runTest {
        // 63 chars, off-by-one truncation kind of bug.
        val brokenRecord = syntheticRecord.copy(publicKeyHex = "ab".repeat(31) + "c")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    @Test
    fun non_hex_chars_X25519_publicKeyHex_Complete_routes_to_MissingKeyMaterial() = runTest {
        // 64 chars total but contains a non-hex character.
        val brokenRecord = syntheticRecord.copy(publicKeyHex = "z" + "b".repeat(63))
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertEquals(FinalizeOutcome.MissingKeyMaterial, outcome)
    }

    @Test
    fun valid_record_produces_Completed_with_both_hexes() = runTest {
        // Positive complement: BOTH signing + encryption hexes on
        // the record are 64-char valid → outcome carries both.
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        assertTrue(outcome is FinalizeOutcome.Completed)
        val completed = outcome as FinalizeOutcome.Completed
        assertEquals(syntheticHex, completed.signingPublicKeyHex)
        assertEquals(syntheticEncHex, completed.publicKeyHex)
    }

    // ── Ed25519 hex contract ─────────────────────────────────────────

    @Test
    fun isValidEd25519PublicKeyHex_accepts_64_char_hex() {
        assertTrue(isValidEd25519PublicKeyHex("0123456789abcdef".repeat(4)))
        assertTrue(isValidEd25519PublicKeyHex("A".repeat(64)))
        assertTrue(isValidEd25519PublicKeyHex("f".repeat(64)))
    }

    @Test
    fun isValidEd25519PublicKeyHex_rejects_empty_wrong_length_or_non_hex() {
        assertTrue(!isValidEd25519PublicKeyHex(""))
        assertTrue(!isValidEd25519PublicKeyHex("a".repeat(63)))
        assertTrue(!isValidEd25519PublicKeyHex("a".repeat(65)))
        // 64 chars but 'g' is not hex.
        assertTrue(!isValidEd25519PublicKeyHex("g" + "a".repeat(63)))
        // 64 chars but space char.
        assertTrue(!isValidEd25519PublicKeyHex(" " + "a".repeat(63)))
    }

    @Test
    fun Completed_construction_rejects_empty_hex() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed("", syntheticEncHex)
        }
        assertTrue(
            ex.message?.contains("Ed25519") == true,
            "IllegalArgumentException must name the contract, got: ${ex.message}",
        )
    }

    @Test
    fun Completed_construction_rejects_wrong_length_hex() {
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed("a".repeat(63), syntheticEncHex)
        }
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed("a".repeat(65), syntheticEncHex)
        }
    }

    @Test
    fun Completed_construction_rejects_non_hex_characters() {
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed("g" + "a".repeat(63), syntheticEncHex)
        }
    }

    @Test
    fun FinalizeOutcome_Completed_construction_rejects_invalid_hex() {
        // Defense in depth: the outcome type ALSO enforces the
        // Ed25519 contract at construction. A refactor that
        // bypassed the runFinalize filter would still fail-loud
        // here rather than reaching the holder.
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed("", syntheticEncHex)
        }
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed("a".repeat(63), syntheticEncHex)
        }
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed("z" + "a".repeat(63), syntheticEncHex)
        }
    }

    // ── Dual-key labels track 2026-08-10 §3 additions ────────────────

    @Test
    fun Completed_construction_rejects_empty_publicKeyHex_X25519_side() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed(syntheticHex, "")
        }
        assertTrue(
            ex.message?.contains("X25519") == true,
            "IllegalArgumentException must name the X25519 contract, got: ${ex.message}",
        )
    }

    @Test
    fun Completed_construction_rejects_wrong_length_publicKeyHex() {
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed(syntheticHex, "a".repeat(63))
        }
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed(syntheticHex, "a".repeat(65))
        }
    }

    @Test
    fun Completed_construction_rejects_non_hex_publicKeyHex_characters() {
        assertFailsWith<IllegalArgumentException> {
            OnboardingFinalizeState.Completed(syntheticHex, "g" + "a".repeat(63))
        }
    }

    @Test
    fun FinalizeOutcome_Completed_construction_rejects_invalid_publicKeyHex() {
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed(syntheticHex, "")
        }
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed(syntheticHex, "a".repeat(63))
        }
        assertFailsWith<IllegalArgumentException> {
            FinalizeOutcome.Completed(syntheticHex, "z" + "a".repeat(63))
        }
    }

    // ── C6-a sealed holder API ────────────────────────────────────────

    private fun makeHolder(
        initial: OnboardingFinalizeState = OnboardingFinalizeState.NotStarted,
    ): OnboardingFinalizeStateHolder =
        OnboardingFinalizeStateHolder(mutableStateOf(initial))

    @Test
    fun holder_markInFlight_transitions_from_NotStarted_to_InFlight() {
        val h = makeHolder()
        h.markInFlight()
        assertEquals(OnboardingFinalizeState.InFlight, h.state)
        assertEquals(OnboardingFinalizePhase.InFlight, h.phase)
    }

    @Test
    fun holder_markInFlight_is_idempotent_on_InFlight() {
        val h = makeHolder(OnboardingFinalizeState.InFlight)
        h.markInFlight()
        assertEquals(OnboardingFinalizeState.InFlight, h.state)
    }

    @Test
    fun holder_markInFlight_is_NO_OP_when_Completed_terminal_guard() {
        val completedState = OnboardingFinalizeState.Completed(syntheticHex, syntheticEncHex)
        val h = makeHolder(completedState)
        h.markInFlight()
        assertEquals(completedState, h.state)
    }

    @Test
    fun holder_markInFlight_is_NO_OP_when_MissingKeyRepairRequired() {
        // Round-2 §P1 pin: the round-1 amend's tupik was a
        // silent Done retap. The repair-required state MUST NOT
        // silently accept another markInFlight — the user needs
        // to explicitly acknowledge via `resetFromRepairRequired`
        // (the "Start over" button on the repair screen).
        val h = makeHolder(OnboardingFinalizeState.MissingKeyRepairRequired)
        h.markInFlight()
        assertEquals(
            OnboardingFinalizeState.MissingKeyRepairRequired, h.state,
            "markInFlight from MissingKeyRepairRequired must be a no-op — user needs " +
                "to explicitly reset via resetFromRepairRequired first.",
        )
    }

    @Test
    fun holder_applyFinalizeOutcome_Completed_from_InFlight_lands_Completed() {
        val h = makeHolder(OnboardingFinalizeState.InFlight)
        h.applyFinalizeOutcome(FinalizeOutcome.Completed(syntheticHex, syntheticEncHex))
        val state = h.state
        assertTrue(state is OnboardingFinalizeState.Completed)
        assertEquals(syntheticHex, (state as OnboardingFinalizeState.Completed).signingPublicKeyHex)
    }

    @Test
    fun holder_applyFinalizeOutcome_FailedBeforePersistence_reverts_to_NotStarted() {
        val h = makeHolder(OnboardingFinalizeState.InFlight)
        h.applyFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence)
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
    }

    @Test
    fun holder_applyFinalizeOutcome_FailedAfterPersistence_keeps_InFlight() {
        val h = makeHolder(OnboardingFinalizeState.InFlight)
        h.applyFinalizeOutcome(FinalizeOutcome.FailedAfterPersistence)
        assertEquals(OnboardingFinalizeState.InFlight, h.state)
    }

    @Test
    fun holder_applyFinalizeOutcome_MissingKeyMaterial_lands_MissingKeyRepairRequired() {
        // Round-2 §P1 pin: the visible repair state.
        val h = makeHolder(OnboardingFinalizeState.InFlight)
        h.applyFinalizeOutcome(FinalizeOutcome.MissingKeyMaterial)
        assertEquals(
            OnboardingFinalizeState.MissingKeyRepairRequired, h.state,
            "MissingKeyMaterial outcome MUST land the visible MissingKeyRepairRequired " +
                "state so the user sees the error and has a safe-exit action.",
        )
        assertEquals(OnboardingFinalizePhase.MissingKeyRepair, h.phase)
        assertNull(h.signingPublicKeyHex)
    }

    @Test
    fun holder_applyFinalizeOutcome_is_NO_OP_from_NotStarted() {
        val h = makeHolder(OnboardingFinalizeState.NotStarted)
        h.applyFinalizeOutcome(FinalizeOutcome.Completed(syntheticHex, syntheticEncHex))
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
        h.applyFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence)
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
        h.applyFinalizeOutcome(FinalizeOutcome.MissingKeyMaterial)
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
    }

    @Test
    fun holder_applyFinalizeOutcome_is_NO_OP_from_Completed_terminal_guard() {
        val completedState = OnboardingFinalizeState.Completed(syntheticHex, syntheticEncHex)
        val h = makeHolder(completedState)
        // Different-hex Completed — terminal must survive.
        h.applyFinalizeOutcome(FinalizeOutcome.Completed("ee".repeat(32), "ff".repeat(32)))
        assertEquals(completedState, h.state)
        // Failure outcomes — terminal must survive.
        h.applyFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence)
        assertEquals(completedState, h.state)
        h.applyFinalizeOutcome(FinalizeOutcome.FailedAfterPersistence)
        assertEquals(completedState, h.state)
        // Round-2 §P1 pin: MissingKeyMaterial arriving after
        // Completion must NOT downgrade a happy Completed to
        // MissingKeyRepairRequired.
        h.applyFinalizeOutcome(FinalizeOutcome.MissingKeyMaterial)
        assertEquals(
            completedState, h.state,
            "A stale MissingKeyMaterial after Completion must NOT " +
                "downgrade the terminal state.",
        )
    }

    @Test
    fun holder_applyFinalizeOutcome_is_NO_OP_from_MissingKeyRepairRequired() {
        val h = makeHolder(OnboardingFinalizeState.MissingKeyRepairRequired)
        h.applyFinalizeOutcome(FinalizeOutcome.Completed(syntheticHex, syntheticEncHex))
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)
        h.applyFinalizeOutcome(FinalizeOutcome.FailedBeforePersistence)
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)
        h.applyFinalizeOutcome(FinalizeOutcome.MissingKeyMaterial)
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)
    }

    @Test
    fun holder_resetFromRepairRequired_transitions_to_NotStarted() {
        val h = makeHolder(OnboardingFinalizeState.MissingKeyRepairRequired)
        h.resetFromRepairRequired()
        assertEquals(
            OnboardingFinalizeState.NotStarted, h.state,
            "resetFromRepairRequired MUST return holder to NotStarted so the user " +
                "can navigate the flow again OR safe-exit via system Back.",
        )
    }

    @Test
    fun holder_resetFromRepairRequired_is_NO_OP_from_every_other_state() {
        // Defensive: only the repair screen's Start-over action
        // wires this. Any other caller trying to reset a happy
        // Completed or an InFlight would be a bug.
        listOf(
            OnboardingFinalizeState.NotStarted,
            OnboardingFinalizeState.InFlight,
            OnboardingFinalizeState.Completed(syntheticHex, syntheticEncHex),
        ).forEach { start ->
            val h = makeHolder(start)
            h.resetFromRepairRequired()
            assertEquals(
                start, h.state,
                "resetFromRepairRequired from $start must be a no-op.",
            )
        }
    }

    // ── C6-a Saver round-trip ─────────────────────────────────────────

    private fun <T, S : Any> roundTrip(saver: Saver<T, S>, value: T): T {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(saver) { scope.save(value) }
            ?: error("Saver returned null for value $value")
        return saver.restore(saved) ?: error("Saver restore returned null for payload $saved")
    }

    @Test
    fun saver_round_trips_NotStarted() {
        assertSame(
            OnboardingFinalizeState.NotStarted,
            roundTrip(OnboardingFinalizeStateSaver, OnboardingFinalizeState.NotStarted),
        )
    }

    @Test
    fun saver_round_trips_InFlight() {
        assertSame(
            OnboardingFinalizeState.InFlight,
            roundTrip(OnboardingFinalizeStateSaver, OnboardingFinalizeState.InFlight),
        )
    }

    @Test
    fun saver_round_trips_Completed_with_both_hexes_intact() {
        val signHex = "abcd" + "0123456789abcdef".repeat(3) + "abcd123456ef"
        val encHex  = "1234" + "fedcba9876543210".repeat(3) + "1234abcdef56"
        require(signHex.length == 64)
        require(encHex.length == 64)
        val original = OnboardingFinalizeState.Completed(signHex, encHex)
        val restored = roundTrip(OnboardingFinalizeStateSaver, original)
        assertTrue(restored is OnboardingFinalizeState.Completed)
        val r = restored as OnboardingFinalizeState.Completed
        assertEquals(signHex, r.signingPublicKeyHex)
        assertEquals(encHex, r.publicKeyHex)
        assertEquals(original, restored)
    }

    // ── Dual-key labels track §3.6 — legacy 2-elem restore path ─

    @Test
    fun saver_restore_legacy_two_element_Completed_with_valid_signing_hex_hydrates_as_InFlight() {
        // Pre-dual-key builds wrote `[Completed, signingHex]`. On
        // dual-key restore, if the signing hex is VALID (healthy
        // identity), the state is rehydrated as `InFlight` so the
        // composable's resume LaunchedEffect fires an idempotent
        // `runFinalize` that loads the already-persisted record
        // and produces `Completed(both hexes)`. Healthy identity
        // is NOT quarantined (dual-key track §3.6 architect
        // Option A + graceful recovery).
        val validSignHex = "abcd" + "0123456789abcdef".repeat(3) + "abcd123456ef"
        require(validSignHex.length == 64)
        val legacyList = listOf("Completed", validSignHex)
        val restored = OnboardingFinalizeStateSaver.restore(legacyList)
        assertEquals(
            OnboardingFinalizeState.InFlight,
            restored,
            "Legacy 2-element Completed(signingHex) with a VALID signing hex MUST " +
                "rehydrate as InFlight so an idempotent runFinalize re-produces " +
                "Completed(both hexes) via IdentityManager.createOrLoad. Healthy " +
                "identity must NOT be quarantined.",
        )
    }

    @Test
    fun saver_restore_legacy_two_element_Completed_with_malformed_signing_hex_rejects() {
        // Corrupt legacy payload (signing hex too short) MUST NOT
        // use the compatibility path — reject as corrupt saved
        // state, same as any new-format Completed with malformed
        // hex (dual-key track §3.6 architect explicit).
        val malformedList = listOf("Completed", "a".repeat(63))
        val ex = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(malformedList)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex.message?.contains("Ed25519") == true,
            "Restore error must name the Ed25519 contract; got: ${ex.message}",
        )
    }

    @Test
    fun saver_restore_new_three_element_Completed_with_malformed_publicKeyHex_rejects() {
        val validSignHex = "abcd" + "0123456789abcdef".repeat(3) + "abcd123456ef"
        require(validSignHex.length == 64)
        val corruptEncHex = "a".repeat(63)
        val corruptList = listOf("Completed", validSignHex, corruptEncHex)
        val ex = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(corruptList)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex.message?.contains("X25519") == true,
            "Restore error must name the X25519 contract; got: ${ex.message}",
        )
    }

    @Test
    fun saver_round_trips_MissingKeyRepairRequired() {
        assertSame(
            OnboardingFinalizeState.MissingKeyRepairRequired,
            roundTrip(
                OnboardingFinalizeStateSaver,
                OnboardingFinalizeState.MissingKeyRepairRequired,
            ),
        )
    }

    @Test
    fun saver_rejects_unknown_tag_defensively() {
        val ex = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(listOf("MysteryVariant", ""))
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex.message?.contains("Unknown OnboardingFinalizeState tag") == true)
    }

    @Test
    fun saver_rejects_empty_hex_on_Completed_restore() {
        val ex = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(listOf("Completed", ""))
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex.message?.contains("invalid Ed25519 signingPublicKeyHex") == true,
            "restore error must name the contract, got: ${ex.message}",
        )
    }

    @Test
    fun saver_rejects_malformed_hex_on_Completed_restore() {
        // Round-2 §P1 pin: wrong length + non-hex chars must both
        // fail-loud, matching the outcome and construction guards.
        val exWrongLength = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(listOf("Completed", "a".repeat(63)))
        }.exceptionOrNull()
        assertNotNull(exWrongLength)
        assertTrue(exWrongLength.message?.contains("invalid Ed25519 signingPublicKeyHex") == true)

        val exNonHex = kotlin.runCatching {
            OnboardingFinalizeStateSaver.restore(listOf("Completed", "z" + "a".repeat(63)))
        }.exceptionOrNull()
        assertNotNull(exNonHex)
        assertTrue(exNonHex.message?.contains("invalid Ed25519 signingPublicKeyHex") == true)
    }

    // ── Full end-to-end via runFinalize + holder ────────────────────

    @Test
    fun end_to_end_success_lands_Completed_with_holder() = runTest {
        val h = makeHolder()
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertTrue(h.state is OnboardingFinalizeState.Completed)
        assertEquals(syntheticHex, h.signingPublicKeyHex)
    }

    @Test
    fun end_to_end_phase_0_error_reverts_holder_to_NotStarted() = runTest {
        val h = makeHolder()
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> throw IllegalStateException("fail phase 0") },
            initMessaging = { _, _ -> /* unreachable */ },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
    }

    @Test
    fun end_to_end_phase_2_error_keeps_holder_InFlight() = runTest {
        val h = makeHolder()
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> syntheticRecord to syntheticKeyPair },
            initMessaging = { _, _ -> throw IllegalStateException("fail phase 2") },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertEquals(OnboardingFinalizeState.InFlight, h.state)
    }

    // Round-2 §P1 architect-requested end-to-end tests:

    @Test
    fun end_to_end_null_hex_lands_holder_MissingKeyRepairRequired() = runTest {
        val h = makeHolder()
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = null)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertEquals(
            OnboardingFinalizeState.MissingKeyRepairRequired,
            h.state,
            "null hex end-to-end MUST land holder at MissingKeyRepairRequired — this " +
                "is the visible repair state; NOT InFlight (round-1 tupik) NOT " +
                "Completed(null) (would strand user on Finale with no exit).",
        )
        assertNull(h.signingPublicKeyHex)
    }

    @Test
    fun end_to_end_empty_hex_lands_holder_MissingKeyRepairRequired() = runTest {
        val h = makeHolder()
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = "")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        h.markInFlight()
        // Round-2 §P1 pin: this MUST NOT throw. Round-1 shape
        // threw IllegalArgumentException inside the coroutine
        // because Completed("") tripped the require.
        val outcome = runFinalize(controller, "alice", PrivacyMode.Standard)
        h.applyFinalizeOutcome(outcome)
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)
    }

    @Test
    fun end_to_end_malformed_hex_lands_holder_MissingKeyRepairRequired() = runTest {
        val h = makeHolder()
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = "ab".repeat(31) + "a")
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)
    }

    @Test
    fun end_to_end_repair_screen_start_over_resets_holder_to_NotStarted() = runTest {
        // The composable renders OnboardingRepairRequiredScreen
        // when state is MissingKeyRepairRequired; its "Start over"
        // button calls holder.resetFromRepairRequired(). Pin the
        // full end-to-end action here.
        val h = makeHolder()
        val brokenRecord = syntheticRecord.copy(signingPublicKeyHex = null)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        h.markInFlight()
        h.applyFinalizeOutcome(runFinalize(controller, "alice", PrivacyMode.Standard))
        assertEquals(OnboardingFinalizeState.MissingKeyRepairRequired, h.state)

        // Simulate user tapping "Start over" on repair screen.
        h.resetFromRepairRequired()
        assertEquals(OnboardingFinalizeState.NotStarted, h.state)
    }

    // ── Structural block on regular navigation opening Finale ────────

    @Test
    fun canAdvanceFromV2_Permissions_is_false() {
        assertEquals(
            false,
            canAdvanceFromV2(OnboardingStepV2.Permissions, OnboardingFormStateV2()),
        )
    }

    @Test
    fun computeNextNavigationStep_Permissions_is_null() {
        assertNull(computeNextNavigationStep(OnboardingStepV2.Permissions))
    }

    @Test
    fun computeNextNavigationStep_FinaleConfirmation_is_null() {
        assertNull(computeNextNavigationStep(OnboardingStepV2.FinaleConfirmation))
    }

    @Test
    fun computeNextNavigationStep_never_returns_FinaleConfirmation_from_any_step() {
        OnboardingStepV2.entries.forEach { step ->
            val next = computeNextNavigationStep(step)
            assertTrue(
                next != OnboardingStepV2.FinaleConfirmation,
                "computeNextNavigationStep($step) returned FinaleConfirmation — " +
                    "Finale MUST be unreachable via regular navigation.",
            )
        }
    }

    @Test
    fun computeNextNavigationStep_advances_the_reachable_steps() {
        assertEquals(OnboardingStepV2.How, computeNextNavigationStep(OnboardingStepV2.Welcome))
        assertEquals(OnboardingStepV2.Identity, computeNextNavigationStep(OnboardingStepV2.How))
        assertEquals(OnboardingStepV2.Privacy, computeNextNavigationStep(OnboardingStepV2.Identity))
        assertEquals(OnboardingStepV2.Permissions, computeNextNavigationStep(OnboardingStepV2.Privacy))
    }

    // ── Source-contract tripwire (NON-EXHAUSTIVE) ─────────────────────

    @Test
    fun source_contract_flow_has_no_direct_finalize_writes_and_uses_holder_only() {
        val source = java.io.File(
            "src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt",
        ).readText()

        val externalPhaseWrites = Regex("""(?<![\w./`])finalizePhase\s*=(?!=)""")
            .findAll(source).count()
        assertEquals(0, externalPhaseWrites,
            "OnboardingFlowV2.kt must not contain any external `finalizePhase = …` " +
                "assignments. Found $externalPhaseWrites.")
        val externalPhaseDecls = Regex("""(?<![\w./`])var\s+finalizePhase\b""")
            .findAll(source).count()
        assertEquals(0, externalPhaseDecls,
            "OnboardingFlowV2.kt must not declare a `var finalizePhase`. Found $externalPhaseDecls.")

        val hexAssignments = Regex(
            """formState\s*=\s*formState\.copy\s*\(\s*signingPublicKeyHex\s*=""",
        ).findAll(source).count()
        assertEquals(0, hexAssignments,
            "OnboardingFlowV2.kt must not write formState.copy(signingPublicKeyHex = …). " +
                "Found $hexAssignments.")

        val finaleAssignments = Regex(
            """(?<![`])currentStep\s*=\s*OnboardingStepV2\.FinaleConfirmation""",
        ).findAll(source).count()
        assertEquals(0, finaleAssignments,
            "OnboardingFlowV2.kt must not directly assign " +
                "`currentStep = OnboardingStepV2.FinaleConfirmation`. Found $finaleAssignments.")

        val applyOutcomeCalls = Regex("""(?<![\w])finalizeHolder\.applyFinalizeOutcome\s*\(""")
            .findAll(source).count()
        assertEquals(2, applyOutcomeCalls,
            "OnboardingFlowV2.kt must call finalizeHolder.applyFinalizeOutcome exactly " +
                "twice (Done tap + resume LaunchedEffect). Found $applyOutcomeCalls.")
        val runFinalizeCalls = Regex("""(?<!fun )\brunFinalize\s*\(""")
            .findAll(source).count()
        assertEquals(applyOutcomeCalls, runFinalizeCalls,
            "runFinalize call count ($runFinalizeCalls) MUST equal " +
                "finalizeHolder.applyFinalizeOutcome call count ($applyOutcomeCalls).")
        val markInFlightCalls = Regex("""(?<![\w])finalizeHolder\.markInFlight\s*\(""")
            .findAll(source).count()
        assertEquals(1, markInFlightCalls,
            "OnboardingFlowV2.kt must call finalizeHolder.markInFlight exactly once " +
                "(from the Done tap, pre-launch). Found $markInFlightCalls.")
    }

    // ── Source-contract accuracy note (C6-a round-2) ─────────────────
    //
    // The regex checks above catch STRAIGHT-FORWARD shapes and are
    // NOT exhaustive. Real guarantees come from:
    //   1. Sealed model — three former slots physically no longer
    //      exist as independent mutable fields.
    //   2. Ed25519 hex contract enforced at three layers:
    //      `runFinalize` filter + `FinalizeOutcome.Completed.init`
    //      + `OnboardingFinalizeState.Completed.init`.
    //   3. Holder state-machine guards — Completed is TERMINAL;
    //      MissingKeyRepairRequired needs explicit
    //      resetFromRepairRequired; stale-outcome guard on
    //      applyFinalizeOutcome.
    //   4. `computeNextNavigationStep` — structurally refuses
    //      FinaleConfirmation regardless of enum ordering.
    //   5. `canAdvanceFromV2(Permissions) = false` —
    //      belt-and-suspenders first line of defence.
    //   6. Composable short-circuits into
    //      OnboardingRepairRequiredScreen when state is
    //      MissingKeyRepairRequired — the state is VISIBLE to
    //      the user, not silent (round-2 §P1 pin).

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
        assertNotNull(controller.transientErrorMessage)
        runFinalize(controller, "alice", PrivacyMode.Standard)
        assertNull(controller.transientErrorMessage)
    }
}
