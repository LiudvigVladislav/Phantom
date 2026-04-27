// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

/**
 * ISO 7816-4 padding to a fixed block size.
 *
 * This hides message length from a passive observer who can see ciphertext sizes
 * on the wire. All padded outputs are multiples of BLOCK_SIZE (256 bytes).
 *
 * Pad:   append 0x80, then zero bytes up to the next block boundary.
 * Unpad: scan backwards, strip trailing 0x00 bytes, then strip the 0x80 marker.
 */
object MessagePadding {

    private const val BLOCK_SIZE = 256

    /**
     * Pads [data] to the next multiple of [BLOCK_SIZE] using ISO 7816-4 padding.
     * The minimum output size is BLOCK_SIZE bytes.
     */
    fun pad(data: ByteArray): ByteArray {
        val withMarker = data + byteArrayOf(0x80.toByte())
        val rem = withMarker.size % BLOCK_SIZE
        return if (rem == 0) withMarker else withMarker + ByteArray(BLOCK_SIZE - rem)
    }

    /**
     * Removes ISO 7816-4 padding added by [pad].
     * Returns the original data unchanged if no valid 0x80 marker is found
     * (graceful degradation for legacy un-padded messages).
     */
    fun unpad(data: ByteArray): ByteArray {
        var i = data.size - 1
        while (i >= 0 && data[i] == 0.toByte()) i--
        return if (i >= 0 && data[i] == 0x80.toByte()) data.copyOfRange(0, i) else data
    }
}
