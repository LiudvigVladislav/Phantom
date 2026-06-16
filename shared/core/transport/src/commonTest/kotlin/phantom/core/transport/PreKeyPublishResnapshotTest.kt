// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sprint 2b M-2bA-1 + M-2bA-2 cells (scope-doc
 * `docs/tracks/sprint-2b-opk-pending-session-scope.md` L1 +
 * "Sprint 2b commit boundary — Sprint 2b-A — Tests").
 *
 * Verifies that [PreKeyApiClient.publishWithRetry] re-snapshots the
 * `PublishRequest` body on EVERY retry attempt — Sprint 2b L1 factory-
 * lambda contract — instead of capturing it once before the loop and
 * replaying identical bytes (the pre-Sprint-2b shape that produced the
 * 2026-06-15 integration smoke `errorClass=OpkNotFound action=hold`
 * root cause: a retry succeeded server-side after a local OPK consume,
 * restoring the relay-side pool to a stale snapshot that included the
 * already-consumed OPK).
 *
 * Both cells use a [ThrowThenSucceedPublishTransport] that records every
 * `bodyBytes` it receives. The test wires a `requestProvider` lambda
 * over a mutable OPK pool, mutates the pool between attempts, and
 * asserts the captured bodies differ.
 *
 * M-2bA-1 — `publishBundle_retryRace_resnapshotsLocalPoolPerAttempt`:
 *   asserts that attempt N+1's body differs from attempt N's body when
 *   the underlying OPK list changes between attempts. FAILS on
 *   pre-Sprint-2b master (where bodyBytes was captured once before the
 *   loop and replayed byte-identical); PASSES after L1.
 *
 * M-2bA-2 — `publishWithRetry_perAttemptResnapshot_bodyDoesNotContainDeletedOpk`:
 *   asserts that the wire body sent on attempt 2 does NOT list the OPK
 *   that was removed from the local pool between attempts 1 and 2. The
 *   load-bearing field-correlation invariant — if a retry succeeds
 *   server-side here, the relay's restored pool no longer contains the
 *   consumed OPK (server-contract pin #1: `/prekeys/publish` REPLACES
 *   the pool wholesale).
 */
class PreKeyPublishResnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Throws on attempts 1 + 2, succeeds on attempt 3. Captures every
     * body bytes via [captureSink] so the test can decode and compare
     * across attempts. Mirrors the failure pattern PR-R0 already covers
     * (`SocketTimeoutException` on the body-upload path) but adds
     * body-capture for the L1 contract.
     */
    private class ThrowThenSucceedPublishTransport(
        private val captureSink: (ByteArray) -> Unit,
    ) : PreKeyPublishHttpTransport {
        var callCount: Int = 0
        override suspend fun publish(
            url: String,
            bodyBytes: ByteArray,
            contentType: String,
            requestId: String,
        ): PreKeyPublishHttpResponse {
            callCount++
            captureSink(bodyBytes.copyOf())
            return when (callCount) {
                1, 2 -> throw java.net.SocketTimeoutException(
                    "Read timed out (M-2bA-1/2 simulated attempt $callCount)",
                )
                else -> PreKeyPublishHttpResponse(
                    statusCode = 201,
                    bodyText = """{"stored_opks": ${callCount - 1}}""",
                    elapsedMs = 1L,
                )
            }
        }
    }

    /**
     * Minimal Ktor client for the non-publish paths. The L1 cells exercise
     * only POST /prekeys/publish via the native transport, so this client
     * is never invoked.
     */
    private val unusedKtorClient: HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { _ -> respond("", HttpStatusCode.OK) }
        }
    }

    private fun makeRequest(opks: List<WireOneTimePreKey>): PublishRequest = PublishRequest(
        identity_pubkey_hex = "aa".repeat(32),
        signing_pubkey_hex = "bb".repeat(32),
        signed_pre_key = WireSignedPreKey(
            key_id = 100L,
            public_key_hex = "cc".repeat(32),
            created_at_ms = 12345L,
            signature_hex = "dd".repeat(64),
        ),
        one_time_pre_keys = opks,
    )

    /** Build a syntactically valid wire OPK with all hex chars `idNibble`. */
    private fun makeOpk(idNibble: Char): WireOneTimePreKey = WireOneTimePreKey(
        key_id_hex = idNibble.toString().repeat(32),
        public_key_hex = idNibble.toString().repeat(64),
    )

    // ── M-2bA-1 ───────────────────────────────────────────────────────────────

    @Test
    fun publishBundle_retryRace_resnapshotsLocalPoolPerAttempt() = runTest {
        // Mutable pool drives the requestProvider lambda — every retry
        // attempt re-reads it and serialises a fresh body. This mirrors
        // what PreKeyLifecycleService passes in production:
        //     { oneTimePreKeyRepository.getAll() }
        // where the repository is mutated by a concurrent inbound
        // bootstrap consume between publish attempts.
        val opkPool = mutableListOf(
            makeOpk('0'),
            makeOpk('1'),
            makeOpk('2'),
            makeOpk('3'),
        )

        val captures = mutableListOf<ByteArray>()
        val transport = ThrowThenSucceedPublishTransport(captureSink = captures::add)
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        // The factory lambda is invoked once per attempt by
        // publishWithRetry. Between attempts the pool drops its head
        // (simulating a concurrent inbound bootstrap consume — the
        // 2026-06-15 smoke shape).
        var providerCalls = 0
        val result = api.publishBundle {
            providerCalls++
            if (providerCalls == 2 || providerCalls == 3) {
                opkPool.removeAt(0)
            }
            makeRequest(opkPool.toList())
        }

        // All 3 attempts fired: 1 + 2 threw, 3 returned 201.
        assertEquals(3, transport.callCount, "all 3 attempts must run")
        assertEquals(3, providerCalls, "requestProvider must be invoked once per attempt")
        assertTrue(result is PublishResult.Stored, "final attempt must succeed; got $result")

        // L1 invariant: bodies differ across attempts because the pool
        // changed between them. Pre-L1 master captures bodyBytes ONCE
        // before the loop, so attempts 1/2/3 would be byte-identical
        // and these assertions would fail.
        assertEquals(3, captures.size, "all 3 attempts must have captured bodies")
        assertNotEquals(
            captures[0].decodeToString(),
            captures[1].decodeToString(),
            "attempt 2 body must differ from attempt 1 — L1 re-snapshot",
        )
        assertNotEquals(
            captures[1].decodeToString(),
            captures[2].decodeToString(),
            "attempt 3 body must differ from attempt 2 — L1 re-snapshot",
        )
    }

    // ── M-2bA-2 ───────────────────────────────────────────────────────────────

    @Test
    fun publishWithRetry_perAttemptResnapshot_bodyDoesNotContainDeletedOpk() = runTest {
        // Attempt 1 pool contains the OPK that the field smoke shape
        // exercised: included in the bundle, consumed locally during the
        // retry window. Attempt 2's body must NOT republish it — the
        // load-bearing invariant that closes the relay-restoration race
        // under server-contract pin #1 ("REPLACE wholesale on publish").
        val opkConsumed = makeOpk('e')
        val opkKept1 = makeOpk('0')
        val opkKept2 = makeOpk('1')
        val opkPool = mutableListOf(opkConsumed, opkKept1, opkKept2)

        val captures = mutableListOf<ByteArray>()
        val transport = ThrowThenSucceedPublishTransport(captureSink = captures::add)
        val api = PreKeyApiClient(
            httpClient = unusedKtorClient,
            relayBaseUrl = "https://relay.test",
            publishTransport = transport,
        )

        var providerCalls = 0
        api.publishBundle {
            providerCalls++
            if (providerCalls == 2) {
                // Local consume between attempt 1 (failed) and attempt 2.
                opkPool.remove(opkConsumed)
            }
            makeRequest(opkPool.toList())
        }

        // Attempt 1 body lists the to-be-consumed OPK (pool unchanged
        // at attempt 1).
        val attempt1 = json.decodeFromString(
            PublishRequest.serializer(),
            captures[0].decodeToString(),
        )
        assertTrue(
            attempt1.one_time_pre_keys.any { it.key_id_hex == opkConsumed.key_id_hex },
            "attempt 1 body must list the to-be-consumed OPK (pool unchanged at attempt 1)",
        )

        // Attempt 2 body does NOT contain opkConsumed — L1 re-snapshot.
        // Pre-Sprint-2b master would have replayed attempt 1's bytes
        // byte-identical here, restoring the relay's pool to a stale
        // 3-OPK set that includes the already-consumed key.
        val attempt2 = json.decodeFromString(
            PublishRequest.serializer(),
            captures[1].decodeToString(),
        )
        assertFalse(
            attempt2.one_time_pre_keys.any { it.key_id_hex == opkConsumed.key_id_hex },
            "attempt 2 body must NOT contain locally-consumed OPK — L1 re-snapshot " +
                "(pre-Sprint-2b master replayed attempt 1's body byte-identical)",
        )
        assertTrue(
            attempt2.one_time_pre_keys.any { it.key_id_hex == opkKept1.key_id_hex },
            "attempt 2 body must retain non-consumed OPKs",
        )
        assertTrue(
            attempt2.one_time_pre_keys.any { it.key_id_hex == opkKept2.key_id_hex },
            "attempt 2 body must retain non-consumed OPKs",
        )
        assertEquals(
            expected = 2,
            actual = attempt2.one_time_pre_keys.size,
            message = "attempt 2 body has exactly the post-consume pool size",
        )
    }
}
