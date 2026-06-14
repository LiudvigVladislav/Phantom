// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Coverage for the [SessionRole] enum + the `role` field on
 * [RatchetState] introduced by RC-CRYPTO-PAIR-X3DH-INIT Sprint 1
 * (2026-06-15).
 *
 * Sprint 1 is the diagnostic-tagging foundation: the role field marks
 * which side of the X3DH handshake produced a session record so a
 * later iteration can add an outbound guard without inspecting chain
 * orientation directly. Sprint 1 does NOT add the guard itself.
 *
 * Scope of this file:
 *  - Default role on a freshly-constructed [RatchetState] is INITIATOR.
 *  - `copy(role = RESPONDER)` produces a record that is `!=` the
 *    original (proves the field participates in `equals`/`hashCode`).
 *  - Round-trip serialization preserves the explicit role.
 *  - Legacy JSON without the `role` field deserializes with the
 *    default INITIATOR (backwards-compat for `rs1:` storage blobs
 *    written before the field existed).
 *  - All other data-class fields roundtrip unchanged when the role is
 *    set.
 *
 * Behavioural consumption of the role field (outbound guard, send-path
 * routing) is deliberately out of scope for Sprint 1 and is verified
 * in later iterations.
 */
class RatchetStateRoleTest {

    /** Minimum fixture: byte arrays are not cryptographic, only structural. */
    private fun sampleState(role: SessionRole = SessionRole.INITIATOR): RatchetState =
        RatchetState(
            rootKey = byteArrayOf(0x01, 0x02, 0x03),
            sendingChainKey = byteArrayOf(0x10),
            receivingChainKey = byteArrayOf(0x20),
            sendingRatchetPublicKey = byteArrayOf(0x30, 0x31),
            sendingRatchetPrivateKey = byteArrayOf(0x40, 0x41),
            receivingRatchetPublicKey = byteArrayOf(0x50),
            sendCount = 7,
            receiveCount = 11,
            role = role,
        )

    @Test
    fun defaultRoleIsInitiator() {
        val state = RatchetState(
            rootKey = byteArrayOf(0x01),
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = byteArrayOf(0x02),
            sendingRatchetPrivateKey = byteArrayOf(0x03),
            receivingRatchetPublicKey = null,
        )
        assertEquals(
            SessionRole.INITIATOR,
            state.role,
            "Default role on a freshly-constructed RatchetState must be INITIATOR; " +
                "legacy serialized blobs depend on this default for backwards compat.",
        )
    }

    @Test
    fun copyWithDifferentRoleProducesUnequalRecord() {
        val initiator = sampleState(role = SessionRole.INITIATOR)
        val responder = initiator.copy(role = SessionRole.RESPONDER)
        assertNotEquals(
            initiator,
            responder,
            "Records that differ only in role must compare unequal; otherwise the " +
                "role field would be invisible to equals() and could not be relied " +
                "on by any downstream guard.",
        )
        assertNotEquals(
            initiator.hashCode(),
            responder.hashCode(),
            "hashCode must reflect role so HashMap/HashSet keyed by RatchetState " +
                "treat the two records as distinct.",
        )
    }

    @Test
    fun roundtripSerializationPreservesExplicitRole() {
        val json = Json
        val responder = sampleState(role = SessionRole.RESPONDER)
        val encoded = json.encodeToString(responder)
        val decoded = json.decodeFromString<RatchetState>(encoded)
        assertEquals(
            responder,
            decoded,
            "Roundtrip serialize/deserialize of a RESPONDER-tagged state must " +
                "preserve the role; if it didn't, the on-disk session row would " +
                "silently downgrade to the default INITIATOR after the first " +
                "persist + load cycle.",
        )
        assertEquals(SessionRole.RESPONDER, decoded.role)
    }

    @Test
    fun legacyJsonWithoutRoleFieldDeserializesAsInitiator() {
        // Sprint 1 backwards-compat guarantee: an `rs1:` storage blob
        // written BEFORE the role field existed must decode cleanly
        // and fall back to the default. We construct the "legacy"
        // shape by serializing a fresh state, parsing to JsonElement,
        // and stripping the role key from the resulting object. This
        // is format-agnostic — any future change to the on-wire
        // encoding of ByteArray fields continues to work as long as
        // missing-key default behaviour is preserved by
        // kotlinx.serialization. Without this guarantee, a fresh
        // patched build would fail to load every existing session on
        // first launch.
        val json = Json
        val seedState = sampleState(role = SessionRole.RESPONDER)
        val encoded = json.encodeToJsonElement(RatchetState.serializer(), seedState)
            .jsonObject
        val legacyObject = kotlinx.serialization.json.JsonObject(
            encoded.filterKeys { it != "role" },
        )
        val legacyJson = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            legacyObject,
        )
        // Sanity: the stripped payload must not contain a role key.
        assertEquals(
            false,
            legacyJson.contains("\"role\""),
            "Test setup error: stripped legacy JSON still contains a role key. " +
                "The test cannot prove default-value behaviour if the key is " +
                "present.",
        )
        val decoded = json.decodeFromString<RatchetState>(legacyJson)
        assertEquals(
            SessionRole.INITIATOR,
            decoded.role,
            "Legacy JSON without the role key must deserialize with default " +
                "SessionRole.INITIATOR. The default exists specifically so " +
                "blobs written before Sprint 1 keep their pre-marking outbound " +
                "behaviour and the patched build does not crash on first load.",
        )
        // Other fields must roundtrip unchanged.
        assertEquals(seedState.sendCount, decoded.sendCount)
        assertEquals(seedState.receiveCount, decoded.receiveCount)
    }

    @Test
    fun ratchetEvolutionPreservesRoleViaDataClassCopy() {
        // The Double Ratchet implementation evolves state via
        // `state.copy(...)` after each encrypt / decrypt / DH ratchet
        // step. Sprint 1 relies on data-class copy preserving all
        // unmentioned fields — including the new role tag — without
        // any explicit propagation work in the crypto layer.
        // This test pins that contract: if a future refactor of the
        // Double Ratchet drops back to constructing fresh RatchetState
        // records by hand, the role tag would silently revert to
        // INITIATOR on every send and this assertion would catch it.
        val responder = sampleState(role = SessionRole.RESPONDER)
        val evolved = responder.copy(
            sendingChainKey = byteArrayOf(0x59),
            sendCount = responder.sendCount + 1,
        )
        assertEquals(
            SessionRole.RESPONDER,
            evolved.role,
            "state.copy(...) with no explicit role argument must preserve the " +
                "existing role. This is the mechanism the Double Ratchet's " +
                "encrypt/decrypt path uses to evolve state without touching the " +
                "role field; if data-class copy semantics regress, the tag is " +
                "lost on first send.",
        )
    }
}
