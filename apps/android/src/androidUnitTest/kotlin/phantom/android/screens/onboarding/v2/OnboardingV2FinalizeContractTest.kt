// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.copyFullHexToClipboard
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.android.ui.designv2.formatFullKeyForDisplay
import phantom.android.ui.designv2.formatShortKeyIdForDisplay
import phantom.core.identity.PrivateKey
import phantom.core.identity.PublicKey
import phantom.core.transport.PrivacyMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the [OnboardingFinalizeController] state machine
 * plus the clipboard invariants of the finale confirmation surface.
 *
 * Every state transition documented in [FinalizeState]'s KDoc has a
 * dedicated test here. Fakes for [createOrLoad] and [initMessaging]
 * are simple lambdas that either return successfully, throw a
 * configurable exception, or suspend on a CompletableDeferred to
 * exercise cancellation / in-flight guards.
 *
 * Not covered here (deferred to Commit 4/5): UI-level end-to-end
 * "Permissions Done → advance to Finale" test. Requires driving the
 * flow through a real Privacy step, which is still a placeholder in
 * Commit 3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingV2FinalizeContractTest {

    private val fakeHex = "abcd" + "0123456789abcdef".repeat(3) + "abcd123456ef"

    init {
        require(fakeHex.length == 64) {
            "Test setup invariant: fakeHex must be 64 chars, was ${fakeHex.length}."
        }
    }

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

    // ── Clipboard contract (redline §C1) ──────────────────────────────

    @Test
    fun copy_writes_full_64_char_hex_to_clipboard() {
        val context: Context = ApplicationProvider.getApplicationContext()
        copyFullHexToClipboard(context, fakeHex)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals(1, clip.itemCount)
        val copied = clip.getItemAt(0).text.toString()
        assertEquals(fakeHex, copied)
        assertEquals(64, copied.length)
    }

    @Test
    fun copy_does_not_transform_or_wrap_the_hex() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mixedCase = "AbCdEf0123456789" + "abcdef0123456789".repeat(2) + "AbCdEf0123456789"
        copyFullHexToClipboard(context, mixedCase)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(mixedCase, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun display_and_short_form_derive_from_the_same_hex() {
        val display = formatFullKeyForDisplay(fakeHex)
        val short = formatShortKeyIdForDisplay(fakeHex)
        assertEquals(fakeHex, display.replace(" ", ""))
        assertEquals(9, short.length)
        assertEquals(fakeHex.substring(0, 4), short.substring(0, 4))
        assertEquals(fakeHex.substring(60, 64), short.substring(5, 9))
    }

    // ── Controller state-machine contract ─────────────────────────────

    @Test
    fun first_success_transitions_idle_working_persisted_complete() = runTest {
        val record = makeRecord("alice")
        val keyPair = makeKeyPair()
        val createOrLoadCalls = AtomicInteger(0)
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { username ->
                createOrLoadCalls.incrementAndGet()
                assertEquals("alice", username)
                record to keyPair
            },
            initMessaging = { r, kp ->
                initMessagingCalls.incrementAndGet()
                assertEquals(record.id, r.id)
                assertEquals(keyPair, kp)
            },
        )
        assertEquals(FinalizeState.Idle, controller.state)

        controller.finalize("alice", PrivacyMode.Standard)

        assertEquals(1, createOrLoadCalls.get())
        assertEquals(1, initMessagingCalls.get())
        val complete = controller.state
        assertIs<FinalizeState.Complete>(complete)
        assertEquals(record.id, complete.record.id)
        assertNull(controller.transientErrorMessage)
    }

    @Test
    fun double_tap_while_working_short_circuits_the_second_call() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Pair<IdentityRecord, IdentityKeyPair>>()
        val createOrLoadCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ ->
                createOrLoadCalls.incrementAndGet()
                gate.await()  // suspend indefinitely
            },
            initMessaging = { _, _ -> },
        )

        // Kick off the first finalize — it will suspend inside createOrLoad.
        val job1 = launch { controller.finalize("alice", PrivacyMode.Standard) }
        // While it's in flight (state=Working), a second call must
        // short-circuit without invoking createOrLoad again.
        assertEquals(FinalizeState.Working, controller.state)
        controller.finalize("alice", PrivacyMode.Standard)
        assertEquals(1, createOrLoadCalls.get())

        // Let the first call complete so runTest doesn't hang.
        gate.complete(makeRecord() to makeKeyPair())
        job1.join()
        assertIs<FinalizeState.Complete>(controller.state)
        // Second call was a no-op, so still one createOrLoad total.
        assertEquals(1, createOrLoadCalls.get())
    }

    @Test
    fun phase1_failure_leaves_state_idle_and_sets_transient_error() = runTest {
        val loggedErrors = mutableListOf<Throwable>()
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ ->
                throw RuntimeException("simulated disk error — sensitive/path/detail")
            },
            initMessaging = { _, _ ->
                error("initMessaging must not run if createOrLoad throws")
            },
            onError = { loggedErrors.add(it) },
        )

        controller.finalize("alice", PrivacyMode.Standard)

        // State reverts to Idle so user can retry with (possibly a
        // different) username.
        assertEquals(FinalizeState.Idle, controller.state)
        val msg = controller.transientErrorMessage
        assertNotNull(msg)
        // Redline §P1-2: no raw throwable.message leakage — the
        // user-facing string must be a stable phrase.
        assertTrue(!msg.contains("sensitive/path/detail"))
        assertEquals(1, loggedErrors.size)
        assertTrue(loggedErrors.single().message!!.contains("sensitive/path/detail"))
    }

    @Test
    fun phase2_failure_keeps_state_persisted_for_retry() = runTest {
        val record = makeRecord("alice")
        val keyPair = makeKeyPair()
        val createOrLoadCalls = AtomicInteger(0)
        var initMessagingShouldThrow = true
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ ->
                createOrLoadCalls.incrementAndGet()
                record to keyPair
            },
            initMessaging = { _, _ ->
                initMessagingCalls.incrementAndGet()
                if (initMessagingShouldThrow) throw RuntimeException("boot failed")
            },
        )

        controller.finalize("alice", PrivacyMode.Standard)

        // createOrLoad ran once; identity has been persisted to disk.
        assertEquals(1, createOrLoadCalls.get())
        assertEquals(1, initMessagingCalls.get())
        val persisted = controller.state
        assertIs<FinalizeState.Persisted>(persisted)
        assertEquals(record.id, persisted.record.id)
        assertNotNull(controller.transientErrorMessage)

        // Retry — must NOT call createOrLoad again (identity is
        // already on disk; a second call could ignore the user's
        // input entirely). Must call initMessaging with the persisted
        // record.
        controller.dismissTransientError()
        initMessagingShouldThrow = false
        controller.finalize("bob-changed-username-but-should-be-ignored", PrivacyMode.Standard)

        assertEquals(1, createOrLoadCalls.get())  // still one call
        assertEquals(2, initMessagingCalls.get())  // two total
        val complete = controller.state
        assertIs<FinalizeState.Complete>(complete)
        // Complete carries the ORIGINAL record, not one produced from
        // the second call's username.
        assertEquals(record.id, complete.record.id)
        assertEquals("alice", complete.record.username)
    }

    @Test
    fun retry_after_persistence_ignores_new_username_argument() = runTest {
        val record = makeRecord("alice")
        val keyPair = makeKeyPair()
        val receivedUsernames = mutableListOf<String>()
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { u ->
                receivedUsernames.add(u)
                record to keyPair
            },
            initMessaging = { _, _ ->
                // Fail only on the first call so the retry path can
                // succeed. Using a dedicated counter (not
                // receivedUsernames.size) since createOrLoad does not
                // run again on retry.
                val nth = initMessagingCalls.incrementAndGet()
                if (nth == 1) throw RuntimeException("phase 2 fail")
            },
        )

        controller.finalize("alice", PrivacyMode.Standard)
        assertIs<FinalizeState.Persisted>(controller.state)
        assertEquals(listOf("alice"), receivedUsernames)

        // A retry with a different username argument. createOrLoad
        // MUST NOT run again — the persisted record is authoritative.
        controller.finalize("bob", PrivacyMode.Standard)
        assertEquals(listOf("alice"), receivedUsernames)
        assertEquals(2, initMessagingCalls.get())
        assertIs<FinalizeState.Complete>(controller.state)
    }

    @Test
    fun cancellation_thrown_from_within_createOrLoad_is_rethrown_and_state_repaired() =
        runTest {
            // Round-2 REDLINE §P1 pin: when phase 1 is wrapped in
            // `withContext(NonCancellable) { ... }`, an OUTER
            // cancellation cannot interrupt createOrLoad — that's the
            // whole point of the guard, and is asserted by
            // `cancellation_after_save_but_before_return_still_lands_persisted`
            // above.
            //
            // The remaining phase-1 revert case: createOrLoad itself
            // throws CancellationException (fake, or a real
            // implementation that voluntarily cancels its own work
            // before persisting anything). The controller MUST
            // re-throw the CE for structured concurrency AND repair
            // state to Idle — because nothing was persisted, the flow
            // must allow the user to retry with a possibly-different
            // username.
            val controller = OnboardingFinalizeController(
                savePrivacyMode = { _ -> },
                createOrLoad = { _ -> throw CancellationException("simulated internal cancellation") },
                initMessaging = { _, _ -> error("must not run — createOrLoad threw") },
            )

            var caught: Throwable? = null
            try {
                controller.finalize("alice", PrivacyMode.Standard)
            } catch (t: Throwable) {
                caught = t
            }
            // CancellationException re-thrown, not swallowed.
            assertIs<CancellationException>(caught)
            // No save happened → state must repair to Idle so the user
            // can retry with (possibly a different) username.
            assertEquals(FinalizeState.Idle, controller.state)
        }

    // Round-2 REDLINE Commit-3 §P1 pin: the phase-1 boundary is
    // guarded by `withContext(NonCancellable) { createOrLoad(...);
    // state = Persisted }` in the controller. Without that guard, if
    // cancellation lands on the IO→calling-dispatcher return
    // suspension AFTER `createOrLoad` committed the row but BEFORE the
    // caller captured the pair, the `state = Persisted` assignment
    // never runs, the catch branch sees `state is Working`, and it
    // reverts to Idle — a lie about disk contents. The fake here
    // simulates that exact ordering: save side-effect fires, THEN the
    // fake suspends on a gate. If NonCancellable is missing, cancelling
    // the outer job while the fake is at the gate propagates
    // CancellationException into the fake, the pair never returns,
    // state stays Working, catch reverts to Idle, and this test fails
    // with "expected Persisted, got Idle". With NonCancellable in
    // effect, the gate.await() defers cancellation, the pair returns,
    // state = Persisted runs, block exits, cancellation propagates at
    // the next suspension (initMessaging → awaitCancellation), catch
    // sees Persisted → no revert → rethrow. Test passes.

    @Test
    fun cancellation_after_save_but_before_return_still_lands_persisted() =
        runTest(UnconfinedTestDispatcher()) {
            val record = makeRecord()
            val keyPair = makeKeyPair()
            val saveSideEffectFired = CompletableDeferred<Unit>()
            val allowCreateOrLoadReturn = CompletableDeferred<Unit>()

            val controller = OnboardingFinalizeController(
                savePrivacyMode = { _ -> },
                createOrLoad = { _ ->
                    // Model the SQLite insert committing before the
                    // caller ever sees a return value.
                    saveSideEffectFired.complete(Unit)
                    // Model the tail-end suspension of
                    // `withContext(Dispatchers.IO)` returning to the
                    // caller dispatcher — where a naive implementation
                    // would let cancellation interrupt the return path.
                    allowCreateOrLoadReturn.await()
                    record to keyPair
                },
                initMessaging = { _, _ -> awaitCancellation() },
            )

            val scope = TestScope(UnconfinedTestDispatcher())
            val job = scope.async { controller.finalize("alice", PrivacyMode.Standard) }
            // Wait until the save side-effect has fired — disk is now
            // committed from the flow's point of view.
            saveSideEffectFired.await()
            // Cancel the outer job. Any pending / future suspension
            // point outside NonCancellable will observe cancellation.
            job.cancel()
            // Now let createOrLoad complete its return. Inside
            // NonCancellable this must run to completion (which
            // includes the `state = Persisted` assignment in the
            // controller). If the guard were missing, the fake's
            // `allowCreateOrLoadReturn.await()` would have already
            // thrown CancellationException on the previous line, and
            // `complete(Unit)` here would be a no-op on an already-
            // resumed-with-cancellation continuation.
            allowCreateOrLoadReturn.complete(Unit)
            assertFails { job.await() }

            // P1 property: state must reflect disk reality. Persisted,
            // not Idle. Without the NonCancellable guard around
            // createOrLoad + state assignment, this assertion fails
            // (state == Idle) — which is exactly the round-2 REDLINE
            // scenario.
            assertIs<FinalizeState.Persisted>(controller.state)
        }

    @Test
    fun cancellation_during_phase2_keeps_state_persisted() = runTest(UnconfinedTestDispatcher()) {
        val record = makeRecord()
        val keyPair = makeKeyPair()
        val gate = CompletableDeferred<Unit>()
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ -> record to keyPair },
            initMessaging = { _, _ -> gate.await() },
        )

        val scope = TestScope(UnconfinedTestDispatcher())
        val job = scope.async { controller.finalize("alice", PrivacyMode.Standard) }
        // Phase 1 completed synchronously; controller now suspending
        // inside initMessaging. Verify Persisted.
        assertIs<FinalizeState.Persisted>(controller.state)

        job.cancel()
        // Phase 1 committed to disk. State MUST NOT revert to Idle
        // (that would lie about disk contents). Stays Persisted so a
        // subsequent finalize call resumes at phase 2.
        assertIs<FinalizeState.Persisted>(controller.state)
        assertFails { job.await() }
    }

    @Test
    fun complete_state_is_terminal_further_calls_noop() = runTest {
        val record = makeRecord()
        val keyPair = makeKeyPair()
        val createOrLoadCalls = AtomicInteger(0)
        val initMessagingCalls = AtomicInteger(0)
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ ->
                createOrLoadCalls.incrementAndGet()
                record to keyPair
            },
            initMessaging = { _, _ -> initMessagingCalls.incrementAndGet() },
        )
        controller.finalize("alice", PrivacyMode.Standard)
        assertIs<FinalizeState.Complete>(controller.state)

        // Extra taps are ignored — no work should re-run.
        controller.finalize("alice", PrivacyMode.Standard)
        controller.finalize("charlie", PrivacyMode.Standard)
        assertEquals(1, createOrLoadCalls.get())
        assertEquals(1, initMessagingCalls.get())
    }

    @Test
    fun back_nav_predicate_is_locked_iff_not_idle() {
        assertTrue(!isBackNavigationLockedByFinalize(FinalizeState.Idle))
        assertTrue(isBackNavigationLockedByFinalize(FinalizeState.Working))
        assertTrue(isBackNavigationLockedByFinalize(
            FinalizeState.Persisted(makeRecord(), makeKeyPair(), PrivacyMode.Standard),
        ))
        assertTrue(isBackNavigationLockedByFinalize(FinalizeState.Complete(makeRecord())))
    }

    @Test
    fun dismiss_transient_error_clears_message() = runTest {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { _ -> },
            createOrLoad = { _ -> throw RuntimeException("x") },
            initMessaging = { _, _ -> },
        )
        controller.finalize("alice", PrivacyMode.Standard)
        assertNotNull(controller.transientErrorMessage)
        controller.dismissTransientError()
        assertNull(controller.transientErrorMessage)
    }

}
