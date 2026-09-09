// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import phantom.core.crypto.EncryptedMessage
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.RatchetState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reproduction of the delivery-ordering failure, layer 1: the real
 * ratchet only, no messaging, no storage, no transport.
 *
 * The field observation was that the later-sent envelope arrived first,
 * failed authentication, and the earlier-sent one then decrypted; a
 * newly typed message afterwards also failed. These cases establish,
 * deterministically, which part of that is a property of the crypto
 * implementation itself and which is not.
 *
 * What is under test is [LibsodiumDoubleRatchet] as installed: it
 * derives the NEXT receive-chain key and ignores
 * [EncryptedMessage.messageIndex], and it has no skipped-key store. The
 * class KDoc of [RatchetState] states both limits explicitly ("No
 * skipped-message-key cache", "No out-of-order delivery support"), so
 * this is a deferred design constraint meeting real traffic, not an
 * accident of one method.
 *
 * Nothing here weakens authenticity: every case asserts that an
 * unauthenticated ciphertext is REJECTED, and the tamper case asserts
 * that it stays rejected.
 *
 * Session bootstrap mirrors `Alpha0IntegrationTest`, which mirrors the
 * `SessionManager` Alpha-0 path.
 */
class SessionOrderReproTest {

    private val x3dh = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

    /**
     * One sender with three messages on a single sending chain, and the
     * matching receiver state. A, B and C occupy chain positions 0, 1
     * and 2 and all carry the same ratchet public key, so no DH ratchet
     * step separates them -- exactly the shape of two messages sent
     * 31 ms apart in the field run.
     */
    private class Fixture(
        val bobState: RatchetState,
        val a: EncryptedMessage,
        val b: EncryptedMessage,
        val c: EncryptedMessage,
    )

    private suspend fun fixture(): Fixture {
        LibsodiumInitializer.initialize()
        val aliceKp = x3dh.generateDhKeyPair()
        val bobKp = x3dh.generateDhKeyPair()

        var aliceState = RatchetState(
            rootKey = x3dh.computeSharedSecret(aliceKp.privateKey, bobKp.publicKey),
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = aliceKp.publicKey.bytes,
            sendingRatchetPrivateKey = aliceKp.privateKey.bytes,
            receivingRatchetPublicKey = bobKp.publicKey.bytes,
        )
        val bobState = RatchetState(
            rootKey = x3dh.computeSharedSecret(bobKp.privateKey, aliceKp.publicKey),
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = bobKp.publicKey.bytes,
            sendingRatchetPrivateKey = bobKp.privateKey.bytes,
            receivingRatchetPublicKey = aliceKp.publicKey.bytes,
        )

        val (afterA, a) = ratchet.encrypt(aliceState, PLAINTEXT_A.encodeToByteArray())
        aliceState = afterA
        val (afterB, b) = ratchet.encrypt(aliceState, PLAINTEXT_B.encodeToByteArray())
        aliceState = afterB
        val (_, c) = ratchet.encrypt(aliceState, PLAINTEXT_C.encodeToByteArray())

        // The three messages really are one chain, consecutive.
        assertEquals(0, a.messageIndex)
        assertEquals(1, b.messageIndex)
        assertEquals(2, c.messageIndex)
        assertContentEquals(a.ratchetPublicKey, b.ratchetPublicKey)
        assertContentEquals(b.ratchetPublicKey, c.ratchetPublicKey)
        return Fixture(bobState, a, b, c)
    }

    // ── 1. Positive control ─────────────────────────────────────────────

    /** In order, both messages decrypt and the receive count advances. */
    @Test
    fun in_order_A_then_B_both_decrypt() = runTest {
        val f = fixture()
        val (afterA, plainA) = ratchet.decrypt(f.bobState, f.a)
        assertEquals(PLAINTEXT_A, plainA.decodeToString())
        val (afterB, plainB) = ratchet.decrypt(afterA, f.b)
        assertEquals(PLAINTEXT_B, plainB.decodeToString())
        assertEquals(1, afterA.receiveCount)
        assertEquals(2, afterB.receiveCount)
    }

    // ── 2. The defect ───────────────────────────────────────────────────

    /**
     * B first: rejected, although B is a genuine message from this
     * sender under this session. The receiver derives position 0's key
     * and B was sealed under position 1's.
     */
    @Test
    fun out_of_order_B_before_A_is_rejected() = runTest {
        val f = fixture()
        assertFailsWith<IllegalArgumentException> {
            ratchet.decrypt(f.bobState, f.b)
        }
    }

    /**
     * The rejection leaves the caller's state untouched, because the
     * advanced state is only RETURNED on success and the caller is what
     * persists it. This is the property that makes recovery possible at
     * all, so it is pinned rather than assumed.
     */
    @Test
    fun a_rejected_message_does_not_advance_the_receive_state() = runTest {
        val f = fixture()
        assertNull(f.bobState.receivingChainKey)
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(f.bobState, f.b) }
        assertNull(
            f.bobState.receivingChainKey,
            "the state handed in must be unchanged after a failed decrypt",
        )
        assertEquals(0, f.bobState.receiveCount)
    }

    /**
     * The full field sequence at crypto level: B is rejected, A then
     * decrypts, and the SAME B ciphertext decrypts on a later attempt.
     *
     * So the ciphertext of B was never unrecoverable. What the field run
     * lacked was a retry of B once A had been consumed -- not key
     * material.
     */
    @Test
    fun B_decrypts_on_retry_once_A_has_been_consumed() = runTest {
        val f = fixture()
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(f.bobState, f.b) }

        val (afterA, plainA) = ratchet.decrypt(f.bobState, f.a)
        assertEquals(PLAINTEXT_A, plainA.decodeToString())

        val (afterB, plainB) = ratchet.decrypt(afterA, f.b)
        assertEquals(
            PLAINTEXT_B,
            plainB.decodeToString(),
            "the identical B ciphertext must decrypt once the chain reaches its position",
        )
        assertEquals(2, afterB.receiveCount)
    }

    /**
     * Why the newly typed message also failed.
     *
     * With A consumed and B still outstanding, the chain sits at
     * position 1. A message sent afterwards is at position 2 and is
     * rejected -- and will keep being rejected for as long as B is not
     * consumed. One unconsumed gap blocks everything sent after it; the
     * failure is not specific to the message the user just typed.
     */
    @Test
    fun a_later_message_C_is_rejected_while_B_remains_unconsumed() = runTest {
        val f = fixture()
        val (afterA, _) = ratchet.decrypt(f.bobState, f.a)

        assertFailsWith<IllegalArgumentException>("C must not decrypt while B is missing") {
            ratchet.decrypt(afterA, f.c)
        }

        // Consuming B is what unblocks C -- nothing else is needed.
        val (afterB, plainB) = ratchet.decrypt(afterA, f.b)
        assertEquals(PLAINTEXT_B, plainB.decodeToString())
        val (_, plainC) = ratchet.decrypt(afterB, f.c)
        assertEquals(PLAINTEXT_C, plainC.decodeToString())
    }

    // ── 3. Authenticity controls, which any future fix must keep ────────

    /**
     * A tampered ciphertext is rejected at its own position, and stays
     * rejected. A fix for ordering must not turn "try another key" into
     * "accept a forgery".
     */
    @Test
    fun a_tampered_ciphertext_is_rejected_at_its_own_position() = runTest {
        val f = fixture()
        val forged = f.a.copy(
            ciphertext = f.a.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() },
        )
        assertFailsWith<IllegalArgumentException> { ratchet.decrypt(f.bobState, forged) }

        // The genuine A still decrypts afterwards: the forgery consumed
        // nothing.
        val (_, plainA) = ratchet.decrypt(f.bobState, f.a)
        assertEquals(PLAINTEXT_A, plainA.decodeToString())
    }

    /**
     * A duplicate of an already-consumed message does not decrypt a
     * second time: the chain has moved past its position. Replay
     * protection here is a side effect of the same rigidity that causes
     * the defect, so a fix must keep it deliberately rather than
     * inherit it.
     */
    @Test
    fun a_duplicate_of_a_consumed_message_does_not_decrypt_again() = runTest {
        val f = fixture()
        val (afterA, _) = ratchet.decrypt(f.bobState, f.a)
        assertFailsWith<IllegalArgumentException>("a replayed A must not decrypt twice") {
            ratchet.decrypt(afterA, f.a)
        }
    }

    /**
     * The index is present on the wire and is simply not consulted: the
     * receiver's behaviour is identical whatever the index says. Stated
     * as a measurement because the whole finding rests on it.
     */
    @Test
    fun the_message_index_does_not_affect_the_outcome() = runTest {
        val f = fixture()
        val bWithLyingIndex = f.b.copy(messageIndex = 0)
        assertFailsWith<IllegalArgumentException>(
            "claiming index 0 must not make B decrypt at position 0",
        ) { ratchet.decrypt(f.bobState, bWithLyingIndex) }

        val aWithLyingIndex = f.a.copy(messageIndex = 7)
        val (_, plainA) = ratchet.decrypt(f.bobState, aWithLyingIndex)
        assertTrue(
            plainA.decodeToString() == PLAINTEXT_A,
            "claiming index 7 must not stop A decrypting at position 0",
        )
    }

    private companion object {
        const val PLAINTEXT_A = "A: sent first"
        const val PLAINTEXT_B = "B: sent second, delivered first"
        const val PLAINTEXT_C = "C: typed after the failure"
    }
}
