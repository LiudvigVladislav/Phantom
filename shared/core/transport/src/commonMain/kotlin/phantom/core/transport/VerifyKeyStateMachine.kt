// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Trek 2 Stage 2B-B (L2) — verify-key state machine for the per-identity
 * `seq_mac` verify key the relay publishes via `SessionResponse.seq_mac_verify_key`.
 *
 * Three states, four refresh outcomes, twelve transitions. The matrix
 * is the load-bearing primitive that prevents a MAC-verify downgrade
 * triggered by relay state: once a session has observed a valid key
 * (`KeyPresent`), the orchestrator MUST NOT regress to `KeyAbsent` on
 * a subsequent empty / malformed / failed refresh. The downgrade-safe
 * regression is to `KeySuspended` — drop all envelopes, no emit, no
 * cursor advance — until a fresh valid key arrives. See [transition]
 * for the locked matrix.
 *
 * **States.** The orchestrator publishes exactly one [VerifyKeyState]
 * at a time and snapshots it inside `_inboundStateMutex.withLock` before
 * running any verify call (L6). The state machine itself is pure —
 * `transition()` is referentially transparent — and is owned by the
 * orchestrator's state-publication seam.
 *
 *   * [VerifyKeyState.KeyAbsent] — the relay never supplied a verify
 *     key in this orchestrator-session. Either an old relay (Stage 1.x
 *     not deployed) or a `RELAY_SEQ_MAC_KEY`-less deployment. Behaviour
 *     under this state is legacy unverified pass-through with
 *     structured log `reason=no_verify_key`. This is the ONLY state in
 *     which a `PollEnvelope` reaches the inbound flow without MAC
 *     verification. The initial state is `KeyAbsent` — bootstrapping
 *     is just the first refresh observation, not a special pre-state.
 *
 *   * [VerifyKeyState.KeyPresent] — the most recent successful
 *     `acquireOrRefreshToken` returned a non-empty, well-formed
 *     `seq_mac_verify_key`. Behaviour is verify on every envelope and
 *     gate cursor advance on the result. This is the normal operating
 *     state.
 *
 *   * [VerifyKeyState.KeySuspended] — the orchestrator cleared the key
 *     after a failed refresh, an empty key from a previously-known-good
 *     relay, or a malformed key observation. Behaviour is drop all
 *     envelopes — no emit, no ack, no cursor advance — until a
 *     subsequent successful `acquireOrRefreshToken` returns a fresh,
 *     non-empty, 64-char lowercase-hex key (transition to
 *     `KeyPresent`).
 *
 * **Refresh outcomes.** The classifier [classifyVerifyKeyResponse]
 * maps a 2xx-response `seq_mac_verify_key` field into one of
 * `Empty` / `Valid(hex)` / `Malformed`. The `Failure` case is
 * constructed directly at the failure branch of
 * `acquireOrRefreshToken` (null body, 4xx, 5xx, network exception,
 * timeout). The four cases are exhaustive over the wire.
 *
 * **Forced-refresh-failure corollary (L2 + D15).** If a forced
 * session/key refresh under L7 itself fails, the verify-key state
 * transitions to [VerifyKeyState.KeySuspended] immediately. No REST
 * ingestion happens on either `wsActivePollJob` or the legacy
 * `pollLoop` until a subsequent successful re-authorisation moves the
 * state back to `KeyPresent`. The corollary falls out of the
 * `KeyPresent(_) + Failure → KeySuspended` row in [transition] — the
 * row is named in the scope-doc so an implementer cannot forget to
 * verify it. Cell M-B11 exercises this path explicitly.
 *
 * **Why nested `when` blocks (not `when (state to outcome)`).**
 * Kotlin's compile-time exhaustiveness check works only on a single
 * sealed type per `when` block; `when (current to outcome)` would
 * compile against the generic `Pair<VerifyKeyState, RefreshOutcome>`
 * type, which is NOT a sealed hierarchy and therefore admits no
 * exhaustiveness check. The nested pattern below makes an accidental
 * omission of any state OR outcome row a compile error — adding a
 * fourth state or a fifth outcome surfaces as a "when must be
 * exhaustive" diagnostic on the affected nested block.
 *
 * No production wiring lands in C2: this module supplies the types
 * and the [transition] / [classifyVerifyKeyResponse] helpers. C4
 * wires them into both REST poll loops at the
 * `acquireOrRefreshToken` publication site.
 */

/**
 * One of three named verify-key states. Owned by the orchestrator
 * and serialised under `_inboundStateMutex` (L6).
 *
 * The hierarchy is sealed so [transition] can pattern-match
 * exhaustively at compile time and a regression that introduced a
 * fourth state would break the build.
 */
sealed class VerifyKeyState {

    /** No verify key observed in this orchestrator-session yet. Legacy
     *  unverified pass-through. Initial state. */
    object KeyAbsent : VerifyKeyState()

    /**
     * A valid 64-char lowercase-hex verify key is currently published.
     *
     * The `hex` payload is asserted well-formed at construction so the
     * type itself carries the validity invariant; downstream verify
     * paths can trust the shape without re-validating per-envelope.
     * Constructing a `KeyPresent("garbage")` directly is prevented by
     * the `init` block — the only legitimate construction path is
     * [transition] applied to a `Valid(hex)` outcome, which routes the
     * already-validated hex through.
     */
    /**
     * Companion holding the constant render string used by
     * [KeyPresent.toString] (and by the matching [RefreshOutcome.Valid]
     * override). Surfaced via a `const val` rather than inlining the
     * literal so the test suite can read the expected value through
     * the same public symbol the production code uses.
     */
    companion object {
        const val KEY_PRESENT_REDACTED_RENDER: String = "KeyPresent(hex=[REDACTED])"
    }

    data class KeyPresent(val hex: String) : VerifyKeyState() {
        init {
            require(isValidLowercaseHexVerifyKey(hex)) {
                // Do NOT echo the offending value back in the message —
                // the rejection might still surface in logs and the
                // verify-key hex is treated as a session secret per
                // the locked threat model. The classifier's failure
                // log line (when wired in C4) carries enough diagnostic
                // context (response length, offending char index) for
                // operator triage without leaking the key material
                // itself.
                "KeyPresent.hex must be 64-char lowercase hex"
            }
        }

        /**
         * Fully redact the verify-key payload from string output. The
         * default `data class` `toString()` would emit the full 64-char
         * hex, leaking the per-identity session secret into any log
         * line, assertion message, or exception surface that prints a
         * `VerifyKeyState`. The verify key is a session-scoped MAC
         * verifier — an attacker with access to logs that contain it
         * can verify (and therefore forge in concert with the relay's
         * canonical-input encoding) any envelope MAC for the bound
         * identity.
         *
         * The locked Stage 1.x threat model treats the derived
         * `seq_mac_verify_key` as a secret whose appearance in logs is
         * an explicit violation: a leading-prefix disclosure is enough
         * for an offline correlator to link sessions of the same
         * identity across logs even when the rest of the key is
         * absent. The render therefore carries ZERO per-key bits —
         * two different verify keys produce byte-identical
         * `toString()` output.
         */
        override fun toString(): String = KEY_PRESENT_REDACTED_RENDER
    }

    /** Drop all envelopes; no emit, no cursor advance. Recovery is the
     *  next successful refresh that classifies to `Valid(hex)`. */
    object KeySuspended : VerifyKeyState()
}

/**
 * One of four refresh-outcome cases observed at the
 * `acquireOrRefreshToken` publication site. Sealed so [transition]
 * pattern-matches exhaustively over the cross-product with
 * [VerifyKeyState].
 */
sealed class RefreshOutcome {

    /** 2xx response with `seq_mac_verify_key == ""`. Old-relay shape. */
    object Empty : RefreshOutcome()

    /**
     * 2xx response with a 64-char lowercase-hex `seq_mac_verify_key`.
     * The `hex` payload is asserted well-formed at construction so
     * downstream consumers can route it into [VerifyKeyState.KeyPresent]
     * without re-validating.
     */
    /**
     * Companion holding the constant render string used by
     * [Valid.toString].
     */
    companion object {
        const val VALID_REDACTED_RENDER: String = "Valid(hex=[REDACTED])"
    }

    data class Valid(val hex: String) : RefreshOutcome() {
        init {
            require(isValidLowercaseHexVerifyKey(hex)) {
                // Do NOT echo the offending value back in the message —
                // see the matching KeyPresent.init comment for the
                // threat-model rationale.
                "Valid.hex must be 64-char lowercase hex"
            }
        }

        /**
         * Fully redact the verify-key payload from string output. See
         * [VerifyKeyState.KeyPresent.toString] for the threat-model
         * rationale — the locked Stage 1.x model treats ANY prefix or
         * suffix fragment of the derived `seq_mac_verify_key` as
         * sensitive, because a leading-prefix disclosure is enough to
         * link sessions of the same identity across logs. The render
         * therefore carries ZERO per-key bits.
         */
        override fun toString(): String = VALID_REDACTED_RENDER
    }

    /**
     * 2xx response with a non-empty `seq_mac_verify_key` that is NOT
     * 64 lowercase-hex characters (non-hex, uppercase, odd length,
     * wrong length). The Stage 1.x server contract emits only the
     * `Empty` or `Valid` shape, so a `Malformed` observation is a relay
     * bug or hostile injection — the state machine fails closed by
     * transitioning to `KeySuspended` from any current state.
     */
    object Malformed : RefreshOutcome()

    /**
     * The relay did not respond 2xx for this refresh attempt (null body,
     * 4xx, 5xx, network exception, timeout). The orchestrator routes
     * this case from the existing `response == null` branch of
     * `acquireOrRefreshToken` at the verify-key publication site.
     *
     * From `KeyPresent` this case is the load-bearing
     * forced-refresh-failure corollary path — transitions to
     * `KeySuspended` so no envelope is ingested under a stale-or-empty
     * key until a subsequent successful re-authorisation.
     */
    object Failure : RefreshOutcome()
}

// ── Classifier ────────────────────────────────────────────────────────────────

/**
 * Width of the verify-key hex projection — 64 lowercase ASCII chars
 * for the 32-byte HMAC-SHA-256 output produced by the relay's
 * `derive_verify_key`. Mirrors [SeqMacVerifier.IDENTITY_HEX_LENGTH]
 * (same numeric value, different semantic — kept as a distinct
 * constant so a future drift in either width can be tracked
 * independently).
 */
internal const val VERIFY_KEY_HEX_LENGTH: Int = 64

/**
 * Classify a 2xx response's `seq_mac_verify_key` field value into one
 * of the three success-path refresh outcomes. Empty string maps to
 * [RefreshOutcome.Empty]; a well-formed 64-char lowercase-hex value
 * maps to [RefreshOutcome.Valid]; anything else (non-hex, uppercase,
 * odd length, wrong length) maps to [RefreshOutcome.Malformed].
 *
 * The [RefreshOutcome.Failure] case is NOT produced here — it is
 * constructed directly at the orchestrator's `acquireOrRefreshToken`
 * failure branch (null body / 4xx / 5xx / network exception / timeout).
 *
 * The orchestrator does NOT trust the relay to label the outcome:
 * the classifier runs at the publication site under
 * `_inboundStateMutex` and produces the case from the raw string.
 */
fun classifyVerifyKeyResponse(rawVerifyKey: String): RefreshOutcome = when {
    rawVerifyKey.isEmpty() -> RefreshOutcome.Empty
    isValidLowercaseHexVerifyKey(rawVerifyKey) -> RefreshOutcome.Valid(rawVerifyKey)
    else -> RefreshOutcome.Malformed
}

/**
 * Returns true iff [s] has exactly [VERIFY_KEY_HEX_LENGTH] characters
 * and every character is in `[0-9a-f]`. Uppercase hex is rejected
 * because the server contract emits only lowercase; accepting
 * uppercase would let a hostile injection use casing tricks to
 * silently inject a key with a different byte pattern under a
 * casing-tolerant `==` comparison.
 *
 * Internal so [VerifyKeyState.KeyPresent] / [RefreshOutcome.Valid]
 * `init` blocks share the same predicate; future widening of the
 * accepted hex shape (e.g. uppercase) MUST update this single
 * function — not scatter the change across each consumer.
 */
internal fun isValidLowercaseHexVerifyKey(s: String): Boolean {
    if (s.length != VERIFY_KEY_HEX_LENGTH) return false
    for (c in s) {
        val lowerHex = c in '0'..'9' || c in 'a'..'f'
        if (!lowerHex) return false
    }
    return true
}

// ── Transition matrix (12 cells, exhaustive) ─────────────────────────────────

/**
 * Apply [outcome] to [current] and return the next state per the
 * locked 12-cell matrix in `docs/tracks/trek2-stage2b-b-scope.md`.
 *
 * Implemented as two nested `when` blocks over sealed types: the
 * outer over [VerifyKeyState], the inner over [RefreshOutcome]. Adding
 * a fourth state or a fifth outcome surfaces as a compile error.
 * Replacing this with `when (current to outcome)` over a `Pair`
 * would compile against `Pair<VerifyKeyState, RefreshOutcome>` (a
 * non-sealed type) and silently lose exhaustiveness.
 *
 * **Locked matrix (verbatim from the scope-doc):**
 *
 *   | Current state    | Outcome         | Next state              |
 *   |------------------|-----------------|-------------------------|
 *   | KeyAbsent        | Empty           | KeyAbsent               |
 *   | KeyAbsent        | Valid(hex)      | KeyPresent(hex)         |
 *   | KeyAbsent        | Malformed       | KeySuspended            |
 *   | KeyAbsent        | Failure         | KeyAbsent               |
 *   | KeyPresent(_)    | Empty           | KeySuspended  ← downgrade
 *   | KeyPresent(_)    | Valid(newHex)   | KeyPresent(newHex)      |
 *   | KeyPresent(_)    | Malformed       | KeySuspended  ← downgrade
 *   | KeyPresent(_)    | Failure         | KeySuspended  ← corollary
 *   | KeySuspended     | Empty           | KeySuspended            |
 *   | KeySuspended     | Valid(hex)      | KeyPresent(hex)         |
 *   | KeySuspended     | Malformed       | KeySuspended            |
 *   | KeySuspended     | Failure         | KeySuspended            |
 *
 * Load-bearing properties:
 *
 *   1. **No `KeyPresent → KeyAbsent` transition exists.** A `KeyPresent`
 *      session that observes an empty / malformed / failed refresh
 *      regresses to `KeySuspended`, never back to `KeyAbsent`. This
 *      closes R2-S-B5: the relay cannot cause a downgrade of an
 *      already-active verify path back to unverified pass-through.
 *      A regression that mapped `KeyPresent + Empty → KeyAbsent` would
 *      re-open the legacy unverified ingestion bypass; cells M-B11
 *      and M17 pin the inverse.
 *
 *   2. **`KeyAbsent + Failure → KeyAbsent`.** A bootstrap-failure on
 *      a session that has not yet observed any key remains in
 *      `KeyAbsent` — no token is published (the existing
 *      `sessionToken == null` discipline pauses ingestion), and the
 *      verify-key state stays consistent with reality: we have NOT
 *      observed a relay claiming otherwise. This is the only row
 *      where `Failure` does NOT lead to `KeySuspended`; the asymmetry
 *      with `KeyPresent + Failure → KeySuspended` is intentional and
 *      corresponds to the bootstrap-vs-established distinction.
 *
 *   3. **Forced-refresh-failure corollary.** The
 *      `KeyPresent(_) + Failure → KeySuspended` row is the L7 corollary
 *      ("if a forced session/key refresh under L7 itself fails, the
 *      verify-key state transitions to KeySuspended immediately"). No
 *      REST ingestion happens until a subsequent successful refresh
 *      produces `Valid(hex)` and the matrix flips the state back to
 *      `KeyPresent`.
 */
fun transition(current: VerifyKeyState, outcome: RefreshOutcome): VerifyKeyState =
    when (current) {
        VerifyKeyState.KeyAbsent -> when (outcome) {
            RefreshOutcome.Empty     -> VerifyKeyState.KeyAbsent
            is RefreshOutcome.Valid  -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed -> VerifyKeyState.KeySuspended
            RefreshOutcome.Failure   -> VerifyKeyState.KeyAbsent
        }
        is VerifyKeyState.KeyPresent -> when (outcome) {
            // Downgrade protection: an empty key from a previously-good
            // relay regresses to KeySuspended, NOT KeyAbsent. The
            // relay's intent is ambiguous (genuine downgrade attempt
            // or transient bug); the safer fallback is to suspend
            // ingestion until a non-empty key reappears.
            RefreshOutcome.Empty     -> VerifyKeyState.KeySuspended
            is RefreshOutcome.Valid  -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed -> VerifyKeyState.KeySuspended
            // L2 forced-refresh-failure corollary.
            RefreshOutcome.Failure   -> VerifyKeyState.KeySuspended
        }
        VerifyKeyState.KeySuspended -> when (outcome) {
            RefreshOutcome.Empty     -> VerifyKeyState.KeySuspended
            is RefreshOutcome.Valid  -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed -> VerifyKeyState.KeySuspended
            RefreshOutcome.Failure   -> VerifyKeyState.KeySuspended
        }
    }
