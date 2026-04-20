package phantom.core.crypto

import com.ionspin.kotlin.crypto.hash.Hash

/**
 * Safety Number — 60-digit fingerprint for key verification.
 *
 * Algorithm:
 *   1. Sort both public-key hex strings lexicographically so the result is
 *      identical regardless of which side calls compute().
 *   2. Concatenate as UTF-8 bytes and hash with SHA-256 (libsodium).
 *   3. Map each nibble of the first 30 digest bytes to a decimal digit
 *      (nibble value mod 10) — yields exactly 60 digits with no java.* dependency.
 *   4. Split into 5 groups of 12 and join with two spaces for readability.
 *
 * The nibble-mod-10 mapping introduces a mild bias (nibbles 0–5 appear slightly
 * more often than 6–9) which is acceptable for a human-readable fingerprint.
 * The security rests on SHA-256 pre-image resistance, not digit distribution.
 *
 * Post-Alpha-1: consider HKDF-SHA256 with identity keys + usernames for stronger
 * channel binding (Signal-style fingerprint v2).
 */
@OptIn(ExperimentalUnsignedTypes::class)
object SafetyNumber {

    /**
     * Returns a fingerprint string such as:
     *   "031742896150  582091437620  849301256784  120943875612  094823716450"
     */
    fun compute(myPubKeyHex: String, theirPubKeyHex: String): String {
        val (first, second) = listOf(myPubKeyHex, theirPubKeyHex).sorted()
        val input = (first + second).encodeToByteArray().toUByteArray()
        val digest = Hash.sha256(input).toByteArray()

        // 30 bytes × 2 nibbles = 60 decimal digits
        val digits = buildString(60) {
            for (i in 0 until 30) {
                val byte = digest[i].toInt() and 0xFF
                append((byte ushr 4) % 10)        // high nibble → 0-9
                append((byte and 0x0F) % 10)      // low nibble  → 0-9
            }
        }

        return digits.chunked(12).joinToString("  ")
    }
}
