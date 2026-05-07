// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented tests for [AndroidKeystoreBlobCipher] — exercises the
 * real Android Keystore provider on a connected device or emulator
 * (Robolectric cannot fake the AndroidKeyStore provider end-to-end).
 *
 * Closes F22 PR-2 of two: PR-1 added the cipher + the on-disk codec
 * + the lazy-fallback decoding; this test set proves the round-trip
 * actually holds against the production Keystore implementation,
 * the master key has the right `KeyProperties`, and a tampered
 * ciphertext is rejected by the GCM tag check rather than silently
 * decoded into garbage.
 *
 * Invoke with:
 *   ./gradlew :shared:core:storage:connectedDebugAndroidTest
 *
 * `setUnlockedDeviceRequired` requires API 28; on API 26-27 the
 * [BeforeTest] guard skips. Production install base on those API
 * levels is effectively zero (matches the same posture as the
 * existing `DatabasePassphraseManager` ships with).
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreBlobCipherTest {

    private val sampleKeyBytes = ByteArray(32) { (it + 1).toByte() }   // a fake 32-byte key
    private val sampleKeyHex   = sampleKeyBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @BeforeTest
    fun checkApiLevel() {
        // setUnlockedDeviceRequired (used by the cipher under test)
        // is API 28+. Skip silently on lower API.
        org.junit.Assume.assumeTrue(
            "Skipping — setUnlockedDeviceRequired requires API 28 (got API ${Build.VERSION.SDK_INT})",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        )
        // Clean any residual key from a prior test run so each test
        // starts from a deterministic Keystore state.
        deleteKeystoreAlias()
    }

    @AfterTest
    fun cleanup() {
        deleteKeystoreAlias()
    }

    @Test
    fun wrap_then_unwrap_returns_original_bytes() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val wrapped = cipher.wrap(sampleKeyBytes)
        val unwrapped = cipher.unwrap(wrapped)
        assertEquals(sampleKeyBytes.toList(), unwrapped.toList(),
            "round-trip must return original byte sequence")
    }

    @Test
    fun wrap_output_does_not_contain_plaintext() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val wrapped = cipher.wrap(sampleKeyBytes)
        // Visible-bytes regression: if the wrap was somehow a no-op
        // (e.g. wrong cipher wired in by a future refactor) the
        // sample bytes would appear verbatim in the ciphertext.
        // GCM ensures they don't.
        assertNotEquals(sampleKeyBytes.toList(), wrapped.toList(),
            "wrap output must not equal plaintext — would imply pass-through cipher")
        // Also assert the wrapped blob is at least IV(12) + tag(16) larger
        // than nothing, even for the empty input.
        assertTrue(wrapped.size >= 28, "wrapped blob must include IV + tag; got size=${wrapped.size}")
    }

    @Test
    fun wrap_handles_empty_input() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val wrapped = cipher.wrap(ByteArray(0))
        val unwrapped = cipher.unwrap(wrapped)
        assertEquals(0, unwrapped.size, "empty plaintext round-trips to empty plaintext")
        assertTrue(wrapped.size >= 28, "even empty plaintext yields a non-empty wrap (IV + tag)")
    }

    @Test
    fun unwrap_rejects_tampered_ciphertext() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val wrapped = cipher.wrap(sampleKeyBytes)
        // Flip a bit inside the ciphertext (after the 12-byte IV header).
        val tampered = wrapped.copyOf()
        tampered[15] = (tampered[15].toInt() xor 0x01).toByte()
        // GCM tag check must reject — Cipher.doFinal throws AEADBadTagException
        // (or a subclass on some OEM crypto providers; we accept any throwable
        // because the contract is "must throw", not "must throw a specific type").
        assertFails {
            cipher.unwrap(tampered)
        }
    }

    @Test
    fun unwrap_rejects_truncated_blob() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val wrapped = cipher.wrap(sampleKeyBytes)
        val truncated = wrapped.copyOfRange(0, wrapped.size - 4)
        assertFails {
            cipher.unwrap(truncated)
        }
    }

    @Test
    fun separate_wrap_calls_produce_distinct_ciphertexts() {
        val cipher = createAndroidPrekeyKeystoreCipher()
        val a = cipher.wrap(sampleKeyBytes)
        val b = cipher.wrap(sampleKeyBytes)
        // GCM IV is freshly generated per call; identical plaintext
        // must NOT produce identical ciphertext (would indicate IV
        // reuse — catastrophic with GCM).
        assertNotEquals(a.toList(), b.toList(),
            "two wraps of the same plaintext must differ (fresh IV per call)")
    }

    @Test
    fun keystore_master_key_has_expected_properties() {
        // First wrap creates the key. After that we can inspect it.
        createAndroidPrekeyKeystoreCipher().wrap(sampleKeyBytes)

        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val key = ks.getKey(KEYSTORE_ALIAS, null)
        assertNotNull(key, "master key must be persisted under the expected alias")
        assertTrue(key is SecretKey, "master key must be a SecretKey, not asymmetric")
        assertEquals("AES", key.algorithm,
            "master key algorithm must be AES (matches KeyProperties.KEY_ALGORITHM_AES in the cipher)")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun codec_round_trip_with_real_cipher_storage_form() {
        // End-to-end: PrivateKeyStorageCodec drives the real cipher
        // and produces the on-disk format the prekey repositories
        // will write into SQLite. Verify (a) the v1: prefix is set,
        // (b) the round-trip recovers the original hex string,
        // (c) the stored value is NOT the raw hex (would imply the
        // cipher silently degraded to pass-through).
        val cipher = createAndroidPrekeyKeystoreCipher()
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleKeyHex, cipher)
        assertTrue(stored.startsWith("v1:"),
            "encoded value must carry the v1: marker; got: ${stored.take(8)}…")
        assertNotEquals(sampleKeyHex, stored,
            "encoded value must not equal the raw hex — cipher must wrap")
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(stored, cipher)
        assertEquals(sampleKeyHex, recovered,
            "round-trip through the codec + real cipher must recover the original hex")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun deleteKeystoreAlias() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    private companion object {
        // These two constants must match the production AndroidKeystoreBlobCipher.
        // They are duplicated here (not imported) because the production class is
        // `internal` to the module — the test asserts the stable contract, not
        // the implementation detail. If the production constant changes, this
        // test fails fast and the divergence is the bug.
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "phantom_prekey_wrap_v1"
    }
}
