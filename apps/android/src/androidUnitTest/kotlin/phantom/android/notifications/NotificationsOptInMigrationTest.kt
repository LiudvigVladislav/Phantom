// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-5 REDLINE on Commit 5 §P0 pin — restart/upgrade tests
 * for [migrateNotificationsOptInIfNeeded] + the pre-migration
 * fallback in [readUserOptedInToNotifications] + the real
 * publisher gate coupling in [isEffectiveNotificationsEnabled].
 *
 * Three scenarios explicitly enumerated by the architect:
 *   1. Existing user + key absent → migration writes `true`.
 *   2. New install + key absent → migration writes `false`.
 *   3. Explicit `false` already persisted → migration NEVER
 *      overwrites; subsequent reads keep returning `false`.
 *
 * Plus:
 *   4. Idempotency — running the migration twice on the same
 *      state does not change the outcome (marker gates it).
 *   5. Pre-migration read fallback — while the marker is unset
 *      and the key is unset, `readUserOptedInToNotifications`
 *      returns `false` (fail-CLOSED — round-6 §P1 pin). Publisher
 *      paths always wait on `PhantomApplication.ready`; the
 *      migration completes before ready resolves; the pre-
 *      migration window is not reachable from the callback in
 *      practice, but if a race regression makes it so, dropping
 *      a possible notification is strictly safer than firing one
 *      the user hasn't opted into.
 *   6. Real publisher gate — with the migration outcome persisted,
 *      `isEffectiveNotificationsEnabled(readCurrentNotificationsGate(ctx))`
 *      returns the correct boolean the publisher would consume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationsOptInMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Purge any state from earlier tests in the same JVM fork —
        // Robolectric shares the SharedPreferences file across tests
        // in the same class.
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(NOTIFICATIONS_USER_OPTED_IN_KEY)
            .remove(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY)
            .commit()
    }

    // ── Scenario 1: existing user + key absent ────────────────────────

    @Test
    fun existing_user_with_no_key_gets_true() {
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        assertTrue(
            outcome is NotificationsOptInMigrationOutcome.SetTrueForExistingUser,
            "expected SetTrueForExistingUser, got $outcome",
        )
        assertTrue((outcome as NotificationsOptInMigrationOutcome.SetTrueForExistingUser).commitOk)
        // Reading through the public API should now return true
        // (persisted, not just the pre-migration fallback).
        assertTrue(readUserOptedInToNotifications(context))
        // The marker should be set so subsequent runs no-op.
        assertMigrationMarkerSet()
    }

    // ── Scenario 2: new install + key absent ──────────────────────────

    @Test
    fun new_install_with_no_key_gets_false() {
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = false)
        assertTrue(
            outcome is NotificationsOptInMigrationOutcome.SetFalseForNewInstall,
            "expected SetFalseForNewInstall, got $outcome",
        )
        assertTrue((outcome as NotificationsOptInMigrationOutcome.SetFalseForNewInstall).commitOk)
        assertFalse(readUserOptedInToNotifications(context))
        assertMigrationMarkerSet()
    }

    // ── Scenario 3: explicit false already persisted ──────────────────

    @Test
    fun explicit_false_is_never_overwritten_even_for_existing_user() {
        // User (or a prior onboarding pass) explicitly opted OUT.
        writeUserOptedInToNotificationsBlocking(context, value = false)
        // Migration runs with `hasExistingIdentity = true` — the
        // path that WOULD write `true` if the key were absent.
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        assertTrue(
            outcome is NotificationsOptInMigrationOutcome.RespectedExistingValue,
            "expected RespectedExistingValue, got $outcome",
        )
        assertTrue((outcome as NotificationsOptInMigrationOutcome.RespectedExistingValue).commitOk)
        // Key MUST still read as false.
        assertFalse(
            readUserOptedInToNotifications(context),
            "explicit false was overwritten during migration",
        )
        assertMigrationMarkerSet()
        // Idempotency: a second migration run also no-ops (marker
        // already set) and doesn't touch the value.
        val secondOutcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        assertEquals(NotificationsOptInMigrationOutcome.AlreadyRun, secondOutcome)
        assertFalse(readUserOptedInToNotifications(context))
    }

    // ── Scenario 3b: explicit true is also never overwritten ──────────

    @Test
    fun explicit_true_is_respected_for_new_install_case_too() {
        writeUserOptedInToNotificationsBlocking(context, value = true)
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = false)
        assertTrue(outcome is NotificationsOptInMigrationOutcome.RespectedExistingValue)
        assertTrue(readUserOptedInToNotifications(context))
        assertMigrationMarkerSet()
    }

    // ── Scenario 4: idempotency across process launches ───────────────

    @Test
    fun second_migration_run_is_no_op_regardless_of_current_state() {
        migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        // Simulate a subsequent process launch reaching the migration
        // step again. The marker MUST gate all further work.
        val second = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = false)
        assertEquals(NotificationsOptInMigrationOutcome.AlreadyRun, second)
        // Value untouched.
        assertTrue(readUserOptedInToNotifications(context))
    }

    // ── Scenario 5: pre-migration read fallback ───────────────────────

    @Test
    fun pre_migration_read_returns_false_when_key_and_marker_absent() {
        // Round-6 REDLINE on Commit 5 §P1 pin: prior round-5 shape
        // returned `true` in this state, on the theory that this
        // was an existing-install upgrade window and dropping a
        // notification would be worse than firing one the user
        // didn't ask for. That was wrong on Android ≤ 12 where
        // all OS-side gates default to `true` — the round-5
        // fallback would have made an unopted-in fresh install
        // fire notifications. Round-6 pins the read to fail-closed:
        // key unset → false, regardless of marker. Publisher paths
        // always wait on `PhantomApplication.ready`, and the
        // migration completes before that resolves, so the pre-
        // migration window is not reachable from the callback in
        // practice — this pin is a defence-in-depth backstop.
        assertFalse(readUserOptedInToNotifications(context))
    }

    @Test
    fun phantom_application_source_contract_identity_failure_branch_skips_migration() {
        // Round-8 REDLINE on Commit 5 §P2 pin: source-contract test
        // that reads `PhantomApplication.kt` as text and asserts
        // the identity-failure branch takes the SKIP path — NOT
        // the "call migration with hasExistingIdentity = false"
        // path (which would silently misclassify existing users
        // whose Keystore transiently failed).
        //
        // Grep-based; fragile but honest. Any refactor of the
        // identity-failure handling MUST also update this test.
        val source = java.io.File(
            "src/androidMain/kotlin/phantom/android/PhantomApplication.kt",
        ).readText()
        // Contract 1: the source MUST contain the "identity_load_failed"
        // marker inside a log line — that's how the SKIP branch signals
        // it took the fail-closed path. The prior round-6 shape used
        // "treating_as_fresh_install" which was still calling migration
        // with a synthetic false — that string must NOT appear now.
        assertTrue(
            source.contains("identity_load_failed"),
            "PhantomApplication.kt does not log an identity_load_failed marker — " +
                "the SKIP branch appears to have been removed",
        )
        assertFalse(
            source.contains("treating_as_fresh_install"),
            "PhantomApplication.kt still contains 'treating_as_fresh_install' — " +
                "round-6 shape resurrected. Round-7 pin: identity failure must NOT " +
                "invoke migrateNotificationsOptInIfNeeded at all",
        )
        assertFalse(
            source.contains("treating_as_existing_user"),
            "PhantomApplication.kt still contains 'treating_as_existing_user' — " +
                "the pre-round-7 shape has resurrected",
        )
        // Contract 2: the `identityLoad.isFailure` branch must only
        // log + fall through — must NOT call the migration function.
        // Match the whole SKIP block and ensure it doesn't contain
        // migrateNotificationsOptInIfNeeded.
        val skipBlockPattern = Regex(
            """isFailure\s*\)\s*\{[^}]*?identity_load_failed[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val skipBlockMatch = skipBlockPattern.find(source)
        assertTrue(
            skipBlockMatch != null,
            "Could not locate the isFailure { ... identity_load_failed ... } " +
                "block in PhantomApplication.kt — the source-contract test needs " +
                "updating along with the branch refactor",
        )
        val skipBlockBody = skipBlockMatch!!.value
        assertFalse(
            skipBlockBody.contains("migrateNotificationsOptInIfNeeded"),
            "PhantomApplication.kt calls migrateNotificationsOptInIfNeeded from the " +
                "identity-failure branch — this is the exact P0 that round-7 §P1 " +
                "closed. The failure branch MUST log + fall through only",
        )
    }

    @Test
    fun identity_failure_must_not_set_marker_so_next_launch_retries() {
        // Round-7 REDLINE on Commit 5 §P1 pin: the CALLER
        // (PhantomApplication) MUST NOT invoke the migration when
        // identity load throws. If it did, the migration would
        // atomically write `userOptedIn = false` + marker and the
        // next launch (even with successful identity load) would
        // skip migration entirely — a transient DB/Keystore
        // failure would become an irreversible OFF for a real
        // existing user.
        //
        // This test pins the CONTRACT: when the caller chooses
        // NOT to run migration, no marker exists → the very next
        // (successful) launch runs migration with the correct
        // identity signal. Reproduces the boot-order behaviour
        // by inspecting SharedPreferences state directly.
        val prefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        // Pre-condition: fresh state (setUp cleared both keys).
        assertFalse(prefs.contains(NOTIFICATIONS_USER_OPTED_IN_KEY))
        assertFalse(prefs.getBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, false))
        // Simulate the boot path taking the identity-failure branch:
        // it does NOT call migrateNotificationsOptInIfNeeded at all.
        // (Nothing to do — just don't call the migration function.)
        // Now simulate the NEXT launch after the transient failure
        // heals: identity loads, migration runs with the true
        // existing-user signal.
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        assertTrue(
            outcome is NotificationsOptInMigrationOutcome.SetTrueForExistingUser,
            "expected retry to classify as existing user, got $outcome",
        )
        assertTrue(readUserOptedInToNotifications(context))
    }

    @Test
    fun post_migration_marker_set_no_key_reads_false() {
        // Defensive edge case — should not happen in practice
        // (migration writes both atomically) but pins the behaviour
        // of the read path when only the marker is present.
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, true)
            .commit()
        assertFalse(readUserOptedInToNotifications(context))
    }

    // ── Scenario 6: real publisher gate consumes the migrated value ────

    @Test
    fun publisher_gate_reads_migrated_value_end_to_end() {
        // Simulate an existing-user upgrade end-to-end: no key, no
        // marker, identity present → migration writes true. Then
        // read the full gate the way `PhantomNotificationManager`
        // reads it. `userOptedIn` should be `true`; the other
        // three signals depend on the Robolectric environment
        // (Robolectric default: notifications enabled, no runtime
        // perm required at SDK 35 unless declared).
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = true)
        assertTrue(outcome is NotificationsOptInMigrationOutcome.SetTrueForExistingUser)
        val gate = readCurrentNotificationsGate(context)
        assertTrue(gate.userOptedIn, "publisher gate reads userOptedIn=false after migration")
        // The migration ensures userOptedIn is TRUE; whether
        // `isEffectiveNotificationsEnabled` is true depends on OS
        // signals not controlled by this test. We assert only the
        // component this test controls: userOptedIn.
    }

    @Test
    fun publisher_gate_reads_false_after_new_install_migration() {
        val outcome = migrateNotificationsOptInIfNeeded(context, hasExistingIdentity = false)
        assertTrue(outcome is NotificationsOptInMigrationOutcome.SetFalseForNewInstall)
        val gate = readCurrentNotificationsGate(context)
        assertFalse(gate.userOptedIn)
        // With userOptedIn=false, the effective gate MUST be false
        // regardless of any other signal.
        assertFalse(isEffectiveNotificationsEnabled(gate))
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun assertMigrationMarkerSet() {
        val marker = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .getBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, false)
        assertTrue(marker, "migration marker was not set")
    }
}
