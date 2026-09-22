// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

import phantom.core.transport.TurnCredentialsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallIceServerSpecTest {
    @Test
    fun direct_stun_is_first_and_turn_credentials_are_ephemeral_second_choice() {
        val specs = buildCallIceServerSpecs(
            TurnCredentialsResponse(
                username = "1900:opaque",
                credential = "secret",
                expiresAt = 1_900,
                ttlSeconds = 900,
                uris = listOf(
                    "turn:turn.phntm.pro:3478?transport=udp",
                    "turn:turn.phntm.pro:3478?transport=tcp",
                    "turns:turn.phntm.pro:443?transport=tcp",
                ),
            ),
        )

        assertEquals(listOf("stun:turn.phntm.pro:3478"), specs[0].uris)
        assertNull(specs[0].username)
        assertEquals(3, specs[1].uris.size)
        assertEquals("1900:opaque", specs[1].username)
        assertEquals("secret", specs[1].credential)
    }

    @Test
    fun invalid_schemes_are_rejected_and_stun_fallback_remains() {
        val specs = buildCallIceServerSpecs(
            TurnCredentialsResponse(
                username = "user",
                credential = "secret",
                expiresAt = 1_900,
                ttlSeconds = 900,
                uris = listOf("https://turn.phntm.pro", "stun:untrusted.example"),
            ),
        )

        assertEquals(1, specs.size)
        assertEquals(listOf("stun:turn.phntm.pro:3478"), specs.single().uris)
    }

    @Test
    fun duplicate_turn_uris_are_collapsed() {
        val specs = buildCallIceServerSpecs(
            TurnCredentialsResponse(
                username = "user",
                credential = "secret",
                expiresAt = 1_900,
                ttlSeconds = 900,
                uris = listOf(
                    "turn:turn.phntm.pro:3478?transport=udp",
                    "turn:turn.phntm.pro:3478?transport=udp",
                ),
            ),
        )

        assertEquals(1, specs[1].uris.size)
    }
}
