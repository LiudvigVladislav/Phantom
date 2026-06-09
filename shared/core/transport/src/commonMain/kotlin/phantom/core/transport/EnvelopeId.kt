// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import phantom.core.crypto.LibsodiumCsprng

/**
 * Type-level wrapper around the wire-format envelope identifier used by
 * `RestFallbackOrchestrator.sendEnvelope` and `/relay/send`'s
 * `Idempotency-Key` header — Trek 2 Stage 2A (A5).
 *
 * WHY THIS EXISTS
 *
 * The Trek 2 mini-lock cross-cutting security invariant 2 requires that
 * every envelope identifier reaching the relay MUST be a freshly drawn
 * random value (16 bytes, CSPRNG, hex-encoded). It MUST NEVER be derived
 * from the encrypted payload, the ratchet state, or any content the
 * relay could observe — a content-derived ID would let a passive relay
 * operator build a timing / correlation oracle that defeats the
 * sealed-sender envelope's privacy intent.
 *
 * Before Stage 2A every `sendEnvelope` caller passed a plain `String`,
 * trusting itself to use `uuid4().toString()` or equivalent. The type
 * system could not catch a caller that wrote `messagePayloadHash` into
 * the field by accident. This value class is the wrapper that future
 * stages will promote into the orchestrator signature so a content-
 * derived id becomes a compile-time error at every call site that
 * exists today and at every future call site.
 *
 * STAGE 2A SCOPE (this commit)
 *
 * Per Vladislav OQ3 lock 2026-06-09 (Layer 2 Ruflo recommendation C —
 * boundary wrapper): this commit introduces the type + the public
 * `random()` factory + the internal `fromWire(...)` escape hatch.
 * The `RestFallbackOrchestrator.sendEnvelope` signature is NOT yet
 * changed — converting it would force every existing caller in the
 * messaging module to migrate within the same PR, which violates the
 * Stage 2A "foundations only, no behaviour change" guardrail. Stage 3
 * opens with a dedicated migration audit that converts every existing
 * call site after verifying none of them derive the value from content.
 *
 * To make the future migration discoverable, `TODO(stage3-migration):
 * ENVELOPE_ID_FULL_RETROFIT` markers are placed at every existing
 * envelope-creation site in this PR. A future engineer (or grep-driven
 * audit tool) can find every site that must be touched by Stage 3.
 *
 * CRYPTO BOUNDARY
 *
 * The Trek 2 mini-lock places the type wrapper ABOVE the crypto layer
 * — `EnvelopeId` carries the public wire-format identifier, not the
 * sealed-sender ciphertext nor any ratchet state. The factory's random
 * draw comes from [LibsodiumCsprng] (Trek 2 Stage 2A A7), which wraps
 * the same `randombytes_buf` primitive already used by the sealed-
 * sender and double-ratchet primitives — single audit point for every
 * security-purpose RNG draw in the app.
 *
 * @property value the 32-character lowercase-hex envelope id.
 */
@JvmInline
value class EnvelopeId internal constructor(val value: String) {

    companion object {

        /**
         * Draws a fresh cryptographically-random 16-byte envelope
         * identifier and hex-encodes it (32 lowercase characters).
         * Stage 2A's only sanctioned construction path for an
         * outbound envelope id. Backed by [LibsodiumCsprng.hex] —
         * NEVER `kotlin.random.Random.Default` (a JVM
         * linear-congruential PRNG, not a CSPRNG; see security
         * review zones Z3 + Z8 in the Stage 2 preflight synthesis).
         */
        fun random(): EnvelopeId = EnvelopeId(LibsodiumCsprng.hex(16))

        /**
         * Constructs an [EnvelopeId] from a wire-received raw string —
         * for example a `PollEnvelope.id` or a `Deliver.messageId`
         * echoed back by the relay. **`internal` visibility** because
         * outbound construction MUST go through [random] (the wire
         * branch is the only legitimate non-random construction
         * source).
         *
         * Callers in the messaging or storage modules that need to
         * round-trip an existing relay-issued id MUST go through the
         * future Stage 3 migration audit before using this factory —
         * a content-derived id would silently violate cross-cutting
         * security invariant 2, and the type system cannot tell the
         * difference between a relay-echoed id and a content-derived
         * one once the raw string is in hand.
         */
        internal fun fromWire(raw: String): EnvelopeId = EnvelopeId(raw)
    }
}
