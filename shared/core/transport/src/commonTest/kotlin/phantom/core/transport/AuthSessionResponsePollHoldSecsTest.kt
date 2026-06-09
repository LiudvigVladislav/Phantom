// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-shape contract tests for Trek 2 Stage 2A (A1) —
 * `poll_hold_secs` field on [AuthSessionResponse] + projection
 * through [RelayCapabilities].
 *
 * Cover four cases that together prove the field is purely additive:
 *
 *   1. New server response (with `poll_hold_secs`) → field
 *      deserialises to the announced value.
 *   2. Old server response (without `poll_hold_secs`) → field
 *      deserialises to the safe default `0` so an upgraded client
 *      talking to a pre-Stage-1 relay never engages long-poll.
 *   3. [AuthSessionResponse.toCapabilities] projects the field
 *      through to [RelayCapabilities.pollHoldSecs] verbatim.
 *   4. [RelayCapabilities.SAFE_DEFAULTS] carries `pollHoldSecs = 0`
 *      so a failed `/auth/session` call cannot accidentally enable
 *      long-poll.
 */
class AuthSessionResponsePollHoldSecsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun new_relay_response_parses_poll_hold_secs() {
        val wire = """
            {
              "token": "abc",
              "expires_at": 1700000000,
              "rest_fallback": true,
              "max_send_body_bytes": 4096,
              "poll_max_envelopes": 1,
              "media_capabilities": { "binary_v3": true, "max_upload_body_bytes": 3072 },
              "poll_hold_secs": 20
            }
        """.trimIndent()
        val parsed = json.decodeFromString(AuthSessionResponse.serializer(), wire)
        assertEquals(20, parsed.pollHoldSecs)
    }

    @Test
    fun old_relay_response_without_poll_hold_secs_defaults_to_zero() {
        // Mirrors the response shape from a pre-Stage-1 relay that
        // doesn't yet announce the field. The default of `0` MUST
        // keep the upgraded client on short-poll behaviour so the
        // Stage 2 contract holds.
        val wire = """
            {
              "token": "abc",
              "expires_at": 1700000000,
              "rest_fallback": true,
              "max_send_body_bytes": 4096,
              "poll_max_envelopes": 1
            }
        """.trimIndent()
        val parsed = json.decodeFromString(AuthSessionResponse.serializer(), wire)
        assertEquals(0, parsed.pollHoldSecs)
    }

    @Test
    fun toCapabilities_projects_poll_hold_secs() {
        val resp = AuthSessionResponse(
            token = "abc",
            expiresAt = 1700000000L,
            restFallback = true,
            maxSendBodyBytes = 4096,
            pollMaxEnvelopes = 1,
            mediaCapabilities = MediaCapabilities(binaryV3 = true, maxUploadBodyBytes = 3072),
            pollHoldSecs = 25,
        )
        val caps = resp.toCapabilities()
        assertEquals(25, caps.pollHoldSecs)
        assertTrue(caps.restFallback)
    }

    @Test
    fun safe_defaults_pin_poll_hold_secs_to_zero() {
        assertEquals(0, RelayCapabilities.SAFE_DEFAULTS.pollHoldSecs)
        // SAFE_DEFAULTS is the cold-start posture: assume the relay
        // does NOT support REST fallback. Carrying `pollHoldSecs=0`
        // there means a failed /auth/session can never accidentally
        // engage long-poll either.
        assertFalse(RelayCapabilities.SAFE_DEFAULTS.restFallback)
    }
}
