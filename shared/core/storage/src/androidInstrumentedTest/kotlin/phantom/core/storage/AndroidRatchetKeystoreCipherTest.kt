// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Instrumented tests for the ratchet-state Keystore cipher
 * ([createAndroidRatchetKeystoreCipher]).
 *
 * Verifies the cipher wired for Double Ratchet session-state blobs:
 *  - Uses alias `phantom_ratchet_wrap_v1` (distinct from the prekey alias)
 *  - Does NOT require the device to be unlocked — ratchet state is
 *    written on every incoming message including background delivery
 *  - Round-trip, tamper-rejection, and freshIV-per-call properties
 *
 * Invoke with:
 *   ./gradlew :shared:core:storage:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AndroidRatchetKeystoreCipherTest {

    private val sampleJson = """{"rootKey":"AAEC","sendingChainKey":"BAUG","sendCount":7}"""

    @BeforeTest
    fun setup() {
        deleteKeystoreAlias()
    }

    @AfterTest
    fun cleanup() {
        deleteKeystoreAlias()
    }

    @Test
    fun wrap_then_unwrap_returns_original_json() {
        val cipher = createAndroidRatchetKeystoreCipher()
        val wrapped = cipher.wrap(sampleJson.encodeToByteArray())
        val recovered = cipher.unwrap(wrapped).decodeToString()
        assertEquals(sampleJson, recovered, "round-trip must recover original JSON")
    }

    @Test
    fun wrap_output_does_not_contain_plaintext() {
        val cipher = createAndroidRatchetKeystoreCipher()
        val wrapped = cipher.wrap(sampleJson.encodeToByteArray())
        assertNotEquals(sampleJson.encodeToByteArray().toList(), wrapped.toList(),
            "wrap output must not equal plaintext — would imply pass-through cipher")
        assertTrue(wrapped.size >= 28, "wrapped blob must include IV(12) + tag(16)")
    }

    @Test
    fun unwrap_rejects_tampered_blob() {
        val cipher = createAndroidRatchetKeystoreCipher()
        val wrapped = cipher.wrap(sampleJson.encodeToByteArray())
        val tampered = wrapped.copyOf()
        tampered[15] = (tampered[15].toInt() xor 0x01).toByte()
        assertFails { cipher.unwrap(tampered) }
    }

    @Test
    fun separate_wrap_calls_produce_distinct_ciphertexts() {
        val cipher = createAndroidRatchetKeystoreCipher()
        val a = cipher.wrap(sampleJson.encodeToByteArray())
        val b = cipher.wrap(sampleJson.encodeToByteArray())
        assertNotEquals(a.toList(), b.toList(),
            "two wraps of the same plaintext must differ (fresh IV per call)")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun codec_round_trip_with_real_ratchet_cipher() {
        val cipher = createAndroidRatchetKeystoreCipher()
        val stored = RatchetStateStorageCodec.encodeForStorage(sampleJson, cipher)
        assertTrue(stored.startsWith("rs1:"),
            "encoded blob must carry rs1: prefix; got: ${stored.take(10)}…")
        assertNotEquals(sampleJson, stored,
            "stored form must not equal plaintext JSON")
        val recovered = RatchetStateStorageCodec.decodeFromStorage(stored, cipher)
        assertEquals(sampleJson, recovered,
            "full codec round-trip with real Keystore must recover original JSON")
    }

    @Test
    fun ratchet_alias_is_distinct_from_prekey_alias() {
        createAndroidRatchetKeystoreCipher().wrap(sampleJson.encodeToByteArray())
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias(RATCHET_ALIAS),
            "ratchet cipher must create key under $RATCHET_ALIAS")
        assertEquals(false, ks.containsAlias(PREKEY_ALIAS),
            "ratchet cipher must NOT touch $PREKEY_ALIAS")
    }

    private fun deleteKeystoreAlias() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(RATCHET_ALIAS)) ks.deleteEntry(RATCHET_ALIAS)
        }
    }

    private companion object {
        const val RATCHET_ALIAS = "phantom_ratchet_wrap_v1"
        const val PREKEY_ALIAS  = "phantom_prekey_wrap_v1"
    }
}
