// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Sprint 2b-C L3 Slice 2 — [BootstrapArtifacts] JSON contract.
 *
 * The blob is the storage-layer-opaque payload written to
 * `pending_ratchet_state.bootstrap_artifacts_blob` on the OUTBOUND-
 * INITIATOR path. The contract is round-trip stability + tolerant
 * null-parse semantics — Sprint 2b-B legacy rows (NULL column) and
 * any future malformed entries deserialize to null so the outbound-
 * reuse path can fall through to a fresh bootstrap without
 * crashing.
 */
class BootstrapArtifactsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun bootstrapArtifacts_jsonRoundTrip_preservesAllFields() {
        val original = BootstrapArtifacts(
            x3dhInit = X3dhInitHeader(
                ephemeralPubKeyHex = "ab".repeat(32),
                spkKeyId = 1_700_000_000_000L,
                opkKeyIdHex = "cd".repeat(16),
            ),
            recipientPubkeyHex = "ef".repeat(32),
        )

        val blob = original.toBlob(json)
        val decoded = BootstrapArtifacts.fromBlob(json, blob)

        assertNotNull(decoded, "fromBlob MUST decode the blob written by toBlob.")
        assertEquals(original.x3dhInit.ephemeralPubKeyHex, decoded.x3dhInit.ephemeralPubKeyHex)
        assertEquals(original.x3dhInit.spkKeyId, decoded.x3dhInit.spkKeyId)
        assertEquals(original.x3dhInit.opkKeyIdHex, decoded.x3dhInit.opkKeyIdHex)
        assertEquals(original.recipientPubkeyHex, decoded.recipientPubkeyHex)
        // Round-trip equality on the data class itself.
        assertEquals(original, decoded)
    }

    @Test
    fun bootstrapArtifacts_jsonRoundTrip_handlesNullOpkKeyIdHex() {
        // The 3-DH variant of X3DH carries x3dhInit without an OPK
        // reference (the peer's pool was empty at bundle fetch time).
        // BootstrapArtifacts must round-trip the null cleanly.
        val original = BootstrapArtifacts(
            x3dhInit = X3dhInitHeader(
                ephemeralPubKeyHex = "11".repeat(32),
                spkKeyId = 42L,
                opkKeyIdHex = null,
            ),
            recipientPubkeyHex = "22".repeat(32),
        )

        val decoded = BootstrapArtifacts.fromBlob(json, original.toBlob(json))

        assertNotNull(decoded)
        assertNull(decoded.x3dhInit.opkKeyIdHex,
            "null opkKeyIdHex (3-DH variant) MUST round-trip as null.")
        assertEquals(original, decoded)
    }

    @Test
    fun bootstrapArtifacts_fromBlob_returnsNull_onNullInput() {
        // Sprint 2b-B legacy rows have a NULL column. The outbound-
        // reuse path passes the column value verbatim; fromBlob must
        // accept null without crashing.
        val decoded = BootstrapArtifacts.fromBlob(json, blob = null)
        assertNull(decoded, "fromBlob(null) MUST return null (Sprint 2b-B legacy row shape).")
    }

    @Test
    fun bootstrapArtifacts_fromBlob_returnsNull_onMalformedJson() {
        val decoded = BootstrapArtifacts.fromBlob(json, blob = "not a json object")
        assertNull(decoded,
            "fromBlob MUST return null on malformed JSON — the outbound-reuse path " +
                "falls through to a fresh bootstrap rather than crashing.")
    }

    @Test
    fun bootstrapArtifacts_fromBlob_returnsNull_onJsonMissingRequiredField() {
        // x3dhInit object present but recipientPubkeyHex missing.
        val partialBlob = """{"x3dhInit":{"ephemeralPubKeyHex":"aa","spkKeyId":1,"opkKeyIdHex":null}}"""
        val decoded = BootstrapArtifacts.fromBlob(json, partialBlob)
        assertNull(decoded,
            "fromBlob MUST return null when a required field is missing.")
    }
}
