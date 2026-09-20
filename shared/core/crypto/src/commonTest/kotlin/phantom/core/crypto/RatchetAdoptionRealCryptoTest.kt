// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * residual N1 Revision 4 — W1 adoption against the REAL ratchet.
 *
 * The outbox recovery rests on one claim about `LibsodiumDoubleRatchet`: for a
 * sending chain that is already keyed, the state `encrypt` commits is a
 * function of the chain alone. Not of the plaintext, not of the random nonce,
 * not of the ciphertext. That is what makes it safe, after a crash between the
 * row write and the state commit, to perform the missed commit now by
 * advancing the surviving state one step and to put the ORIGINAL stored bytes
 * on the wire.
 *
 * Revision 3 argued this from source and demonstrated it with a fake ratchet
 * that modelled the counters. A fake cannot prove a claim about a real KDF, a
 * real MAC or a real chain key, so this file drives the production ratchet:
 *
 *  1. an envelope is produced from state S;
 *  2. the commit that should have followed is discarded, as a crash would;
 *  3. adoption from the same S commits byte-for-byte the state the original
 *     encryption would have committed;
 *  4. the receiver decrypts the stored ORIGINAL envelope;
 *  5. the sender encrypts the next message from the adopted state;
 *  6. the receiver decrypts that one too;
 *  7. a reused index and a foreign chain key are both rejected.
 *
 * This test needs a working libsodium binding. Where the native binding does
 * not initialise — the Windows host this revision was written on — every case
 * here stops at `LibsodiumInitializer.initialize()` before reaching a single
 * assertion, and the result is reported as pending, never as green. No
 * signed-prekey verifier is injected, bypassed or faked anywhere in this file;
 * the states are built through the ordinary X3DH handshake.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class RatchetAdoptionRealCryptoTest {

    private val x3dh = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

    /** A real initiator/responder pair over a real 4-DH handshake. */
    private class Pair2(val sender: RatchetState, val receiver: RatchetState)

    private fun handshake(): Pair2 {
        val aliceIdentity = x3dh.generateDhKeyPair()
        val bobIdentity = x3dh.generateDhKeyPair()
        val bobSpk = x3dh.generateDhKeyPair()
        val bobOpk = x3dh.generateDhKeyPair()
        val aliceEphemeral = x3dh.generateDhKeyPair()

        val sender = x3dh.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey = bobSpk.publicKey,
            recipientOPK = bobOpk.publicKey,
            ephemeralKeyPair = aliceEphemeral,
        )
        val receiver = x3dh.recipientHandshake4DH(
            recipientIdentityKeyPair = bobIdentity,
            recipientSignedPreKeyPair = bobSpk,
            recipientOPKPair = bobOpk,
            initiatorIdentityPublicKey = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )
        return Pair2(sender, receiver)
    }

    /**
     * The sender state with a keyed sending chain, which is the precondition
     * the adoption rule is stated for. The first `encrypt` performs the DH
     * ratchet step that keys it; everything after that is chain arithmetic.
     */
    private fun keyed(pair: Pair2): Pair<RatchetState, RatchetState> {
        val (afterFirst, first) = ratchet.encrypt(pair.sender, "warm-up".encodeToByteArray())
        val (receiverAfterFirst, _) = ratchet.decrypt(pair.receiver, first)
        assertTrue(afterFirst.sendingChainKey != null, "precondition: the sending chain is keyed")
        return afterFirst to receiverAfterFirst
    }

    // ── the claim ──────────────────────────────────────────────────────────

    @Test
    fun adoption_from_the_pre_crash_state_commits_exactly_what_the_send_would_have() = runTest {
        LibsodiumInitializer.initialize()
        val (s, _) = keyed(handshake())

        // 1. The envelope the user's message produced, and the state that send
        //    would have committed.
        val (wouldHaveCommitted, envelope) = ratchet.encrypt(s, "the user's message".encodeToByteArray())

        // 2. The crash: `wouldHaveCommitted` never reached disk. Only `s` did,
        //    together with the row holding `envelope`.
        // 3. Adoption advances the surviving state one step, with no plaintext.
        val (adopted, _) = ratchet.encrypt(s, ByteArray(0))

        assertContentEquals(
            wouldHaveCommitted.sendingChainKey, adopted.sendingChainKey,
            "the committed chain key does not depend on the plaintext",
        )
        assertEquals(wouldHaveCommitted.sendCount, adopted.sendCount)
        assertContentEquals(wouldHaveCommitted.rootKey, adopted.rootKey)
        assertContentEquals(
            wouldHaveCommitted.sendingRatchetPublicKey, adopted.sendingRatchetPublicKey,
            "and neither does the chain identity",
        )
        assertEquals(wouldHaveCommitted, adopted, "the whole committed state is identical")
        assertEquals(s.sendCount, envelope.messageIndex, "the envelope sits at the uncommitted index")
        assertEquals(s.sendCount + 1, adopted.sendCount, "adoption commits exactly one step")
    }

    @Test
    fun the_stored_envelope_still_decrypts_after_adoption_and_so_does_the_next_one() = runTest {
        LibsodiumInitializer.initialize()
        val pair = handshake()
        val (s, receiverState) = keyed(pair)

        // The crashed send: the row kept these exact bytes.
        val (_, stored) = ratchet.encrypt(s, "the user's message".encodeToByteArray())
        // Recovery: adopt the missed commit, then send the stored bytes as they are.
        val (adopted, _) = ratchet.encrypt(s, ByteArray(0))

        val (receiverAfterStored, recovered) = ratchet.decrypt(receiverState, stored)
        assertEquals(
            "the user's message", recovered.decodeToString(),
            "the peer decrypts the envelope that was on disk all along",
        )

        // 5 + 6. The next message, from the adopted state.
        val (afterNext, next) = ratchet.encrypt(adopted, "and the next one".encodeToByteArray())
        val (_, alsoRecovered) = ratchet.decrypt(receiverAfterStored, next)
        assertEquals("and the next one", alsoRecovered.decodeToString())
        assertEquals(adopted.sendCount, next.messageIndex, "the next message took the next index")
        assertEquals(adopted.sendCount + 1, afterNext.sendCount)
    }

    @Test
    fun a_third_message_continues_the_chain_after_a_recovery() = runTest {
        LibsodiumInitializer.initialize()
        val pair = handshake()
        var (s, r) = keyed(pair)

        val (_, stored) = ratchet.encrypt(s, "one".encodeToByteArray())
        val (adopted, _) = ratchet.encrypt(s, ByteArray(0))
        s = adopted
        val d1 = ratchet.decrypt(r, stored); r = d1.first
        assertEquals("one", d1.second.decodeToString())

        for (text in listOf("two", "three")) {
            val (next, envelope) = ratchet.encrypt(s, text.encodeToByteArray())
            s = next
            val d = ratchet.decrypt(r, envelope)
            r = d.first
            assertEquals(text, d.second.decodeToString())
        }
    }

    // ── controls: the claim must be falsifiable ────────────────────────────

    @Test
    fun control_a_reused_index_is_rejected_by_the_real_receiver() = runTest {
        LibsodiumInitializer.initialize()
        val pair = handshake()
        val (s, receiverState) = keyed(pair)

        val (afterFirst, first) = ratchet.encrypt(s, "one".encodeToByteArray())
        val (receiverAfter, _) = ratchet.decrypt(receiverState, first)
        // Re-sending the same envelope: the receiving chain has already moved.
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(receiverAfter, first) }
        // And a different envelope at the same index is rejected just as hard.
        val (_, alsoIndexZero) = ratchet.encrypt(s, "one, again".encodeToByteArray())
        assertEquals(first.messageIndex, alsoIndexZero.messageIndex, "control: same index")
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(receiverAfter, alsoIndexZero) }
        assertEquals(s.sendCount + 1, afterFirst.sendCount)
    }

    @Test
    fun control_adoption_from_a_different_chain_does_not_produce_the_same_state() = runTest {
        LibsodiumInitializer.initialize()
        val (s, _) = keyed(handshake())
        val (other, _) = keyed(handshake())

        val (wouldHaveCommitted, _) = ratchet.encrypt(s, "the user's message".encodeToByteArray())
        val (adoptedFromWrongChain, _) = ratchet.encrypt(other, ByteArray(0))

        assertNotEquals(
            wouldHaveCommitted, adoptedFromWrongChain,
            "adopting from a state that did not produce the envelope must not look like success",
        )
        assertTrue(
            !wouldHaveCommitted.sendingChainKey.contentEquals(adoptedFromWrongChain.sendingChainKey),
            "the chain keys differ",
        )
    }

    @Test
    fun control_an_envelope_from_a_foreign_chain_does_not_decrypt() = runTest {
        LibsodiumInitializer.initialize()
        val pair = handshake()
        val (_, receiverState) = keyed(pair)
        val (otherSender, _) = keyed(handshake())

        val (_, foreign) = ratchet.encrypt(otherSender, "not for you".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(receiverState, foreign) }
    }

    @Test
    fun control_two_encryptions_of_the_same_plaintext_differ_on_the_wire() = runTest {
        LibsodiumInitializer.initialize()
        val (s, _) = keyed(handshake())
        val (_, a) = ratchet.encrypt(s, "same text".encodeToByteArray())
        val (_, b) = ratchet.encrypt(s, "same text".encodeToByteArray())
        assertTrue(
            !a.ciphertext.contentEquals(b.ciphertext) || !a.nonce.contentEquals(b.nonce),
            "control: the nonce is random, so the ciphertext is not reproducible — " +
                "which is exactly why the stored bytes must be re-sent rather than re-made",
        )
    }
}
