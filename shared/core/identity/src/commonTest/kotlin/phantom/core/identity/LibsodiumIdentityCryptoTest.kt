// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NOTE on libsodium initialisation: every test that touches
 * [LibsodiumIdentityCrypto] MUST first call
 * `LibsodiumInitializer.initialize()` and therefore runs inside
 * `runTest { ... }`. The Android target picks up libsodium through the
 * JNI loader and works without an explicit init, but the JVM target
 * (which is what `:shared:core:identity:jvmTest` runs against in CI)
 * uses JNA and throws
 *   `kotlin.UninitializedPropertyAccessException: lateinit property
 *    sodiumJna has not been initialized`
 * if `Box.keypair()` is called before the initializer ran. The init
 * call is idempotent — running it inside every test costs nothing
 * after the first one.
 */
class LibsodiumIdentityCryptoTest {

    private val crypto = LibsodiumIdentityCrypto()

    @Test
    fun generateKeyPair_returnsNonEmptyKeys() = runTest {
        LibsodiumInitializer.initialize()
        val kp = crypto.generateKeyPair()
        assertTrue(kp.publicKey.bytes.isNotEmpty())
        assertTrue(kp.privateKey.bytes.isNotEmpty())
    }

    /**
     * Pre-written for Beta — sign/verify on the identity crypto are not
     * wired in Alpha 2. LibsodiumIdentityCrypto.sign/verify currently
     * throw NotImplementedError ("Signing deferred to Beta"), so this
     * test was always going to fail when actually executed; it never
     * was, because the project had no CI gate until phase1/week2/
     * ci-foundation.
     *
     * Drop the @Ignore once Ed25519 signing is implemented (planned for
     * Beta alongside secure key storage on Keystore-backed identity).
     */
    @Test
    @Ignore
    fun signAndVerify_roundTrip() = runTest {
        LibsodiumInitializer.initialize()
        val kp = crypto.generateKeyPair()
        val message = "hello phantom".encodeToByteArray()
        val signature = crypto.sign(message, kp.privateKey)
        assertTrue(crypto.verify(message, signature, kp.publicKey))
    }

    /** See [signAndVerify_roundTrip] — also waits on Beta sign/verify wiring. */
    @Test
    @Ignore
    fun verify_failsWithWrongPublicKey() = runTest {
        LibsodiumInitializer.initialize()
        val kp = crypto.generateKeyPair()
        val wrongKp = crypto.generateKeyPair()
        val message = "hello phantom".encodeToByteArray()
        val signature = crypto.sign(message, kp.privateKey)
        assertFalse(crypto.verify(message, signature, wrongKp.publicKey))
    }

    @Test
    fun publicKeyHex_roundTrip() = runTest {
        LibsodiumInitializer.initialize()
        val kp = crypto.generateKeyPair()
        val hex = crypto.publicKeyToHex(kp.publicKey)
        val recovered = crypto.hexToPublicKey(hex)
        assertEquals(hex, crypto.publicKeyToHex(recovered))
    }
}
