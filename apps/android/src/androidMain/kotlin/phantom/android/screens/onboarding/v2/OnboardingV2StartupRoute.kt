// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import kotlinx.coroutines.CancellationException
import phantom.core.identity.IdentityRecord

/**
 * C6-a round-8 REDLINE — SINGLE source-of-truth for startup
 * routing with a HARD split between "proven corruption" and
 * "operational failure".
 *
 * Prior round-7 shape used a single `InitializationFailed`
 * bucket that MainActivity routed via the durable
 * `identity_repair_required` marker + `MissingKeyRepairRequired`
 * override. That made a ONE-OFF `initMessaging` failure
 * (e.g. transient IO glitch on a cold boot) look identical to a
 * genuinely broken signing key: the marker was written, the
 * repair screen accused the user of key corruption, and the
 * next launch's marker short-circuit made the quarantine
 * permanent. A healthy identity got trapped.
 *
 * Round-8 fixes this by returning a decision that carries its
 * reason:
 *
 * - [StartupRouteDecision.RepairQuarantine] — PROVEN corruption
 *   only. The caller MAY write the durable marker and force
 *   `MissingKeyRepairRequired`. Reasons:
 *     - [RepairReason.MarkerAlreadySet] — a prior session
 *       determined corruption and wrote the marker.
 *     - [RepairReason.MalformedSigningKeyHex] — the persisted
 *       identity has a non-null hex that fails
 *       [isValidEd25519PublicKeyHex].
 *     - [RepairReason.NullHexNoMigrationExpected] — an Alpha 2
 *       record with null hex and migration says migration is
 *       NOT needed. This is an unrecoverable state (a healthy
 *       Alpha 2 identity always has a hex; a legitimate Alpha 1
 *       identity has null hex + `needsMigration=true`).
 *
 * - [StartupRouteDecision.TransientStartupFailure] — retryable
 *   operational failure. The caller MUST NOT write the marker
 *   AND MUST NOT show any "identity repair required" text. It
 *   shows a startup error screen with a Retry action; a
 *   subsequent run of `decideStartupRoute` MAY reach ChatList
 *   or Migration cleanly. Reasons:
 *     - [TransientReason.MarkerReadThrew]
 *     - [TransientReason.LoadIdentityThrew]
 *     - [TransientReason.InitMessagingThrew]
 *     - [TransientReason.InitMessagingReturnedFalse]
 *     - [TransientReason.NeedsMigrationThrew]
 *
 * All four suspend calls (`markerRead`, `loadIdentity`,
 * `initMessaging`, `needsMigration`) are guarded by split
 * try/catch that FIRST rethrows `CancellationException` and
 * THEN catches `Throwable`. Cancellation from Activity
 * recreation must never poison the routing decision.
 */
internal sealed interface StartupRouteDecision {
    /** Fresh install, no prior identity. Show normal onboarding. */
    data object FreshOnboarding : StartupRouteDecision

    /**
     * Proven identity corruption. Caller writes the durable
     * marker + forces `MissingKeyRepairRequired`. Round-8: this
     * is ONLY returned for the three deterministic reasons in
     * [RepairReason]; operational failures go to
     * [TransientStartupFailure] instead.
     */
    data class RepairQuarantine(val reason: RepairReason) : StartupRouteDecision

    /** Legitimate Alpha 1 identity + migration required. */
    data object Migration : StartupRouteDecision

    /** Healthy Alpha 2+ identity. Normal main app. */
    data object ChatList : StartupRouteDecision

    /**
     * Retryable operational failure. Caller shows a startup
     * error screen with a Retry action; NO marker write, NO
     * finalize holder, NO "identity repair required" text.
     * A subsequent successful re-run may reach ChatList /
     * Migration / FreshOnboarding cleanly.
     *
     * Mini-round §P2 pin: carries the original [cause] Throwable
     * (except for [TransientReason.InitMessagingReturnedFalse]
     * which has no throwable — the caller-side lambda returned
     * `false` cleanly). The orchestrator
     * [applyStartupDecision] logs the cause with its stack trace
     * BEFORE returning the presentation, so `Log` sees the full
     * failure detail while the UI stays on a stable copy.
     *
     * Custom `equals` / `hashCode` ignore [cause] so
     * `assertEquals(TransientStartupFailure(reason), decision)`
     * remains ergonomic in tests that don't care about the
     * exact throwable identity.
     */
    class TransientStartupFailure(
        val reason: TransientReason,
        val cause: Throwable? = null,
    ) : StartupRouteDecision {
        override fun equals(other: Any?): Boolean =
            other is TransientStartupFailure && other.reason == reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String =
            "TransientStartupFailure(reason=$reason, cause=${cause?.javaClass?.simpleName})"
    }
}

/** Round-8 §P0 pin: reasons that qualify as PROVEN corruption. */
internal enum class RepairReason {
    /** A prior session wrote the durable marker. */
    MarkerAlreadySet,

    /**
     * Non-null `signingPublicKeyHex` that fails the Ed25519
     * contract (wrong length or non-hex chars). Deterministic
     * from the on-disk record; not a transient failure.
     */
    MalformedSigningKeyHex,

    /**
     * `signingPublicKeyHex == null` AND the migration check
     * succeeded and returned `false`. Alpha 2 schemas always
     * have a non-null hex; Alpha 1 records have
     * `needsMigration = true`. A null hex + successful
     * `needsMigration=false` is deterministically broken.
     */
    NullHexNoMigrationExpected,
}

/**
 * Round-8 §P0 pin: reasons that are OPERATIONAL failures. The
 * caller MUST NOT persist these — they're expected to be
 * transient (IO glitch, Keystore hiccup, one-off container
 * bootstrap failure).
 */
internal enum class TransientReason {
    MarkerReadThrew,
    LoadIdentityThrew,
    InitMessagingThrew,
    InitMessagingReturnedFalse,
    NeedsMigrationThrew,
}

/**
 * Pure suspend decision function. Callers provide lambdas so
 * the routing logic is testable in isolation.
 *
 * Cancellation contract (round-8 §P1): EVERY suspend call is
 * wrapped in split try/catch that rethrows
 * `CancellationException` FIRST, then catches remaining
 * `Throwable`.
 */
internal suspend fun decideStartupRoute(
    markerRead: suspend () -> Boolean,
    loadIdentity: suspend () -> IdentityRecord?,
    initMessaging: suspend () -> Boolean,
    needsMigration: suspend () -> Boolean,
): StartupRouteDecision {
    // Marker read — round-8 §P1 pin: markerRead is `suspend`
    // and could throw. Round-7 called it outside try/catch, so
    // a Keystore-adjacent read failure would surface as an
    // uncaught startup crash. Round-8 wraps it and treats a
    // read failure as TRANSIENT (transient IO, not proven
    // corruption).
    val markerSet = try {
        markerRead()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        return StartupRouteDecision.TransientStartupFailure(
            TransientReason.MarkerReadThrew, cause = t,
        )
    }
    if (markerSet) {
        return StartupRouteDecision.RepairQuarantine(RepairReason.MarkerAlreadySet)
    }

    val identity = try {
        loadIdentity()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        // Round-8 §P0 pin: loadIdentity throw is TRANSIENT, not
        // Repair. Round-7 shape treated any load failure as
        // proven corruption and permanently quarantined the
        // user via the marker — a transient Keystore hiccup on
        // cold boot would trap a healthy identity forever.
        return StartupRouteDecision.TransientStartupFailure(
            TransientReason.LoadIdentityThrew, cause = t,
        )
    }

    if (identity == null) return StartupRouteDecision.FreshOnboarding

    // Malformed non-null hex is DETERMINISTIC — the on-disk
    // record has bad bytes. Repair (proven corruption).
    val hex = identity.signingPublicKeyHex
    if (hex != null && !isValidEd25519PublicKeyHex(hex)) {
        return StartupRouteDecision.RepairQuarantine(RepairReason.MalformedSigningKeyHex)
    }

    // initMessaging — TRANSIENT on failure. Round-7 shape
    // routed to Repair via InitializationFailed → marker; a
    // one-off container bootstrap error trapped the user.
    val initOk = try {
        initMessaging()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        return StartupRouteDecision.TransientStartupFailure(
            TransientReason.InitMessagingThrew, cause = t,
        )
    }
    if (!initOk) {
        // No throwable — the caller returned `false` cleanly.
        return StartupRouteDecision.TransientStartupFailure(
            TransientReason.InitMessagingReturnedFalse, cause = null,
        )
    }

    val migration = try {
        needsMigration()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        return StartupRouteDecision.TransientStartupFailure(
            TransientReason.NeedsMigrationThrew, cause = t,
        )
    }
    if (migration) return StartupRouteDecision.Migration

    // Null hex + `needsMigration=false` is DETERMINISTIC. A
    // healthy Alpha 2 identity always has a hex; a legitimate
    // Alpha 1 identity has needsMigration=true.
    if (hex == null) {
        return StartupRouteDecision.RepairQuarantine(RepairReason.NullHexNoMigrationExpected)
    }

    return StartupRouteDecision.ChatList
}
