// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import phantom.core.identity.IdentityRecord
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C6-a round-8 REDLINE — pure-JVM tests for [decideStartupRoute].
 *
 * Round-8 refactored the decision types to split PROVEN
 * corruption (RepairQuarantine with typed reason) from
 * OPERATIONAL failure (TransientStartupFailure with typed
 * reason). ALL FOUR suspend calls (markerRead + loadIdentity
 * + initMessaging + needsMigration) are cancellation-safe.
 *
 * Test matrix from architect's round-8 scope-lock:
 *   - All 4 cancellation propagate + no presentation change.
 *   - All 4 non-cancellation operational failures → Transient.
 *   - All 3 proven-corruption reasons → RepairQuarantine.
 *   - Ordering: marker → identity → key validation → messaging →
 *     migration.
 */
class OnboardingV2StartupRouteTest {

    private val validHex = "cc".repeat(32)
    private val malformedHex = "notvalidhex"

    private fun makeRecord(
        signingHex: String? = validHex,
    ): IdentityRecord = IdentityRecord(
        id = "alice-id",
        username = "alice",
        publicKeyHex = "aa".repeat(32),
        dhPrivateKeyHex = "bb".repeat(32),
        createdAt = 0L,
        signingPublicKeyHex = signingHex,
        signingPrivateKeyHex = "dd".repeat(32),
    )

    // ── Marker short-circuit ──────────────────────────────────────────

    @Test
    fun marker_set_returns_RepairQuarantine_with_MarkerAlreadySet_reason() = runTest {
        val decision = decideStartupRoute(
            markerRead = { true },
            loadIdentity = { error("must not be called when marker is set") },
            initMessaging = { error("must not be called") },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.RepairQuarantine(RepairReason.MarkerAlreadySet),
            decision,
        )
    }

    // ── Round-8 §P0: proven-corruption reasons → Repair ──────────────

    @Test
    fun malformed_non_null_hex_returns_MalformedSigningKeyHex() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = malformedHex) },
            initMessaging = { error("must not be called for malformed hex") },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.RepairQuarantine(RepairReason.MalformedSigningKeyHex),
            decision,
        )
    }

    @Test
    fun null_hex_no_migration_expected_returns_NullHexNoMigrationExpected() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = null) },
            initMessaging = { true },
            needsMigration = { false },
        )
        assertEquals(
            StartupRouteDecision.RepairQuarantine(RepairReason.NullHexNoMigrationExpected),
            decision,
        )
    }

    // ── Round-8 §P0: operational failures → Transient (never Repair) ─

    @Test
    fun marker_read_throws_returns_TransientStartupFailure_MarkerReadThrew() = runTest {
        val thrown = IllegalStateException("prefs read failed")
        val decision = decideStartupRoute(
            markerRead = { throw thrown },
            loadIdentity = { error("must not be called on marker-read throw") },
            initMessaging = { error("must not be called") },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(TransientReason.MarkerReadThrew),
            decision,
        )
        // Mini-round §P2 pin: the Throwable is captured on the
        // decision so `applyStartupDecision` can log it with a
        // stack trace instead of dropping it.
        val transient = decision as StartupRouteDecision.TransientStartupFailure
        assertTrue(transient.cause === thrown, "Cause MUST be the original Throwable")
    }

    @Test
    fun loadIdentity_throws_returns_TransientStartupFailure_LoadIdentityThrew() = runTest {
        val thrown = IllegalStateException("Keystore hiccup")
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { throw thrown },
            initMessaging = { error("must not be called") },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(TransientReason.LoadIdentityThrew),
            decision,
            "Round-8 §P0 pin: loadIdentity throw is TRANSIENT, not Repair — a Keystore " +
                "hiccup on cold boot MUST NOT trap a healthy identity.",
        )
        val transient = decision as StartupRouteDecision.TransientStartupFailure
        assertTrue(transient.cause === thrown, "Cause MUST propagate for stack-trace logging")
    }

    @Test
    fun initMessaging_throws_returns_TransientStartupFailure_InitMessagingThrew() = runTest {
        val thrown = IllegalStateException("messaging bootstrap glitch")
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord() },
            initMessaging = { throw thrown },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(TransientReason.InitMessagingThrew),
            decision,
        )
        assertTrue((decision as StartupRouteDecision.TransientStartupFailure).cause === thrown)
    }

    @Test
    fun initMessaging_returns_false_has_null_cause() = runTest {
        // Mini-round §P2 pin: InitMessagingReturnedFalse is the
        // only transient reason WITHOUT a Throwable — the caller
        // lambda returned `false` cleanly. `cause` MUST be null
        // so `applyStartupDecision`'s log branches to the
        // no-stack-trace path.
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord() },
            initMessaging = { false },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(
                TransientReason.InitMessagingReturnedFalse,
            ),
            decision,
        )
        assertEquals(
            null,
            (decision as StartupRouteDecision.TransientStartupFailure).cause,
        )
    }

    @Test
    fun initMessaging_returns_false_returns_TransientStartupFailure_InitMessagingReturnedFalse() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord() },
            initMessaging = { false },
            needsMigration = { error("must not be called") },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(
                TransientReason.InitMessagingReturnedFalse,
            ),
            decision,
        )
    }

    @Test
    fun needsMigration_throws_returns_TransientStartupFailure_NeedsMigrationThrew() = runTest {
        val thrown = IllegalStateException("migration query failed")
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord() },
            initMessaging = { true },
            needsMigration = { throw thrown },
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(TransientReason.NeedsMigrationThrew),
            decision,
        )
        assertTrue((decision as StartupRouteDecision.TransientStartupFailure).cause === thrown)
    }

    // ── Mini-round §P1 pin — checkNotNull(migrationManager) throws ────

    @Test
    fun migrationManager_null_via_checkNotNull_routes_to_TransientStartupFailure_not_repair() = runTest {
        // Mini-round §P1 architect-requested test: MainActivity's
        // production `needsMigration` lambda does
        //   val mgr = checkNotNull(container.migrationManager) { ... }
        //   mgr.needsMigration()
        // When `container.migrationManager == null`, `checkNotNull`
        // throws `IllegalStateException`. Prior shape returned
        // `false` via `mgr != null && mgr.needsMigration()`;
        // for a null-hex identity that flipped to
        // NullHexNoMigrationExpected → permanent Repair quarantine.
        //
        // This test simulates the production lambda's behaviour
        // and verifies the decider routes to Transient
        // (NeedsMigrationThrew) — no marker write.
        val simulatedProductionLambda: suspend () -> Boolean = {
            val mgr: Any? = null   // container.migrationManager returns null
            checkNotNull(mgr) { "migrationManager unavailable after initMessaging" }
            error("unreachable — checkNotNull throws")
        }
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = null) },  // null-hex → would trap in old shape
            initMessaging = { true },
            needsMigration = simulatedProductionLambda,
        )
        assertEquals(
            StartupRouteDecision.TransientStartupFailure(TransientReason.NeedsMigrationThrew),
            decision,
            "migrationManager==null MUST route to Transient (proven-corruption reservation " +
                "is for hex/marker cases only). Old `mgr != null && …` shape returned " +
                "false, which trapped null-hex identities as NullHexNoMigrationExpected.",
        )
    }

    // ── Round-8 §P1: cancellation ALL FOUR suspend calls ────────────

    @Test
    fun marker_read_cancellation_propagates() = runTest {
        assertFailsWith<CancellationException> {
            decideStartupRoute(
                markerRead = { throw CancellationException("Activity recreated") },
                loadIdentity = { error("must not be reached") },
                initMessaging = { error("must not be reached") },
                needsMigration = { error("must not be reached") },
            )
        }
    }

    @Test
    fun loadIdentity_cancellation_propagates() = runTest {
        assertFailsWith<CancellationException> {
            decideStartupRoute(
                markerRead = { false },
                loadIdentity = { throw CancellationException("Activity recreated") },
                initMessaging = { error("must not be reached") },
                needsMigration = { error("must not be reached") },
            )
        }
    }

    @Test
    fun initMessaging_cancellation_propagates() = runTest {
        assertFailsWith<CancellationException> {
            decideStartupRoute(
                markerRead = { false },
                loadIdentity = { makeRecord() },
                initMessaging = { throw CancellationException("Activity recreated") },
                needsMigration = { error("must not be reached") },
            )
        }
    }

    @Test
    fun needsMigration_cancellation_propagates() = runTest {
        assertFailsWith<CancellationException> {
            decideStartupRoute(
                markerRead = { false },
                loadIdentity = { makeRecord() },
                initMessaging = { true },
                needsMigration = { throw CancellationException("Activity recreated") },
            )
        }
    }

    // ── Alpha 1 + happy paths ────────────────────────────────────────

    @Test
    fun null_hex_needs_migration_returns_Migration() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = null) },
            initMessaging = { true },
            needsMigration = { true },
        )
        assertEquals(StartupRouteDecision.Migration, decision)
    }

    @Test
    fun fresh_install_returns_FreshOnboarding() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { null },
            initMessaging = { error("must not be called for null identity") },
            needsMigration = { error("must not be called") },
        )
        assertEquals(StartupRouteDecision.FreshOnboarding, decision)
    }

    @Test
    fun healthy_identity_no_migration_returns_ChatList() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = validHex) },
            initMessaging = { true },
            needsMigration = { false },
        )
        assertEquals(StartupRouteDecision.ChatList, decision)
    }

    @Test
    fun healthy_identity_needs_migration_returns_Migration() = runTest {
        val decision = decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = validHex) },
            initMessaging = { true },
            needsMigration = { true },
        )
        assertEquals(StartupRouteDecision.Migration, decision)
    }

    // ── Ordering pin ─────────────────────────────────────────────────

    @Test
    fun ordering_marker_then_identity_then_hex_then_init_then_migration() = runTest {
        val order = mutableListOf<String>()
        decideStartupRoute(
            markerRead = { order += "markerRead"; false },
            loadIdentity = {
                order += "loadIdentity"
                makeRecord(signingHex = null)  // Alpha 1 → all subsequent stages run
            },
            initMessaging = { order += "initMessaging"; true },
            needsMigration = { order += "needsMigration"; true },
        )
        assertEquals(
            listOf("markerRead", "loadIdentity", "initMessaging", "needsMigration"),
            order,
            "Ordering pin: markerRead → loadIdentity → (hex validation is pure, no lambda) " +
                "→ initMessaging → needsMigration. Round-8 keeps initMessaging BEFORE " +
                "needsMigration so a broken messaging stack short-circuits before " +
                "we ask about migration.",
        )
    }

    @Test
    fun hex_validation_precedes_initMessaging() = runTest {
        var initCalled = false
        decideStartupRoute(
            markerRead = { false },
            loadIdentity = { makeRecord(signingHex = malformedHex) },
            initMessaging = { initCalled = true; true },
            needsMigration = { error("must not be called") },
        )
        assertFalse(
            initCalled,
            "Malformed hex → Repair short-circuits BEFORE initMessaging — no point " +
                "bringing messaging up over a broken key.",
        )
    }

    // ── Call-count pins ──────────────────────────────────────────────

    @Test
    fun markerRead_called_exactly_once() = runTest {
        var calls = 0
        decideStartupRoute(
            markerRead = { calls++; false },
            loadIdentity = { null },
            initMessaging = { true },
            needsMigration = { false },
        )
        assertEquals(1, calls)
    }

    @Test
    fun loadIdentity_not_called_when_marker_set() = runTest {
        var called = false
        decideStartupRoute(
            markerRead = { true },
            loadIdentity = { called = true; null },
            initMessaging = { error("must not be called") },
            needsMigration = { error("must not be called") },
        )
        assertFalse(
            called,
            "Marker-set short-circuit MUST NOT invoke loadIdentity — a corrupt Keystore " +
                "already known to be broken (marker set from prior session) MUST NOT " +
                "get re-read.",
        )
    }
}
