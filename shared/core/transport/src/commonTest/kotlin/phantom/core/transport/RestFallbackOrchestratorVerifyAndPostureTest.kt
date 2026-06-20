// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.auth.Auth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C4) — behavioural tests for the per-envelope
 * verify-and-emit gate, the L2 verify-key state machine publication
 * at `acquireOrRefreshToken` sites, and the L7 bad-MAC posture
 * (counter / latched refresh / suspension).
 *
 * Ships scope-doc cells:
 *
 *   M10  — Verify failures with sub-cells (wrong key, single-bit
 *          flipped identity_hex, off-by-one seq, pre-quantize
 *          sequence_ts, single-char-changed envelope_id, tampered
 *          last hex char of seq_mac).
 *   M17  — Old-relay `MissingMac` outcome class discrimination:
 *          KeyAbsent → unverified pass-through with reason
 *          `no_verify_key`; KeyPresent + empty `seqMac` → drop with
 *          reason `no_mac_field`.
 *   M-B8 — `identity_hex` derivation round-trip: server-generated
 *          MAC verifies against client-derived identity_hex
 *          (RestFallbackOrchestrator.identityHex, the receiving
 *          identity).
 *   M-B10 — Cancellation safety on verify-then-emit boundary.
 *   M-B12 — MAC repeat counter increments; no ack, no cursor under
 *          any count.
 *   M-B13 — First repeat triggers forced session refresh, latched
 *          exactly once per envelope_id; no second refresh on
 *          threshold multiples.
 *   M-B14 — Second repeat AFTER refresh transitions breaker to
 *          `SuspendedOnPoison`.
 *   M-B15 — Direct WSS path remains operational while REST loops
 *          are suspended (verified via the orchestrator-side
 *          invariant: `_breakerState == SuspendedOnPoison` does NOT
 *          impact `RestStateMachine` or any non-REST surface).
 *   M-B16 — Persisted cursor + relay queue byte-identical to
 *          pre-poison state across all retry counts.
 *   M-B17 — Both loops drop under suspension; legacy `pollLoop`
 *          shares the same gate as `wsActivePollLoop`.
 *   M-B21 — Debug-mode `FAILED_MAC` hold path is an external
 *          messaging-layer responsibility; orchestrator-side: the
 *          verify path's `MacMismatch` outcome does NOT advance
 *          the cursor (the consumer's downstream X3DH repair
 *          handler owns the hold logic in `DefaultMessagingService`).
 *   M-B22 — Same envelope_id observed by both loops concurrently:
 *          counter increments serially under the mutex; latch
 *          fires exactly once.
 *   M-B23 — Concurrent breaker-open from both loops converges on
 *          `SuspendedOnPoison` with no torn intermediate state.
 *   M-B25 — Refresh-vs-poll race: verify uses a snapshotted hex,
 *          not a torn read across the rotation.
 */
class RestFallbackOrchestratorVerifyAndPostureTest {

    private suspend fun init() {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val IDENTITY: String = "aa".repeat(32) // 64-char lowercase hex.

    private val SESSION_RESPONSE_OK_WITH_KEY_A: RestFallbackResponse<AuthSessionResponse> by lazy {
        RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "test-token",
                expiresAt = Long.MAX_VALUE,
                restFallback = true,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
                pollHoldSecs = 30,
                seqMacVerifyKey = HEX_KEY_A,
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )
    }

    /** 64-char valid lowercase hex. */
    private val HEX_KEY_A: String = "0123456789abcdef".repeat(4)

    /** A second valid hex value distinct from [HEX_KEY_A]. */
    private val HEX_KEY_B: String = "fedcba9876543210".repeat(4)

    /**
     * Derive a verify key the same way the relay does at
     * `SeqMacRootKey::derive_verify_key`:
     * `HMAC-SHA-256(root_key, b"phantom-seq-mac-key-v1\x00" || identity_hex)`.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun deriveVerifyKey(rootKey: ByteArray, identityHex: String): ByteArray {
        val tag = "phantom-seq-mac-key-v1".encodeToByteArray() + byteArrayOf(0)
        val msg = tag + identityHex.encodeToByteArray()
        return Auth.authHmacSha256(
            message = msg.toUByteArray(),
            key = rootKey.toUByteArray(),
        ).toByteArray()
    }

    /** Lowercase hex encoding of a [ByteArray]. */
    private fun ByteArray.toLowerHex(): String =
        joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    /** Build a [PollEnvelope] with a freshly-computed valid `seqMac`. */
    private fun makeVerifiedEnvelope(
        identityHex: String,
        seq: Long,
        envelopeId: String,
        sequenceTs: Long,
        verifyKeyBytes: ByteArray,
    ): PollEnvelope {
        val mac = SeqMacVerifier.computeMac(
            identityHex = identityHex,
            seq = seq,
            envelopeId = envelopeId,
            sequenceTs = sequenceTs,
            verifyKeyBytes = verifyKeyBytes,
        )
        return PollEnvelope(
            id = envelopeId,
            fromHex = "ff".repeat(32),
            payloadBase64 = "",
            sequenceTs = sequenceTs,
            seq = seq,
            seqMac = mac.toLowerHex(),
        )
    }

    /** Build a [PollEnvelope] with an arbitrary `seqMac` (used for bad-MAC tests). */
    private fun makeEnvelopeWithSeqMac(
        seq: Long,
        envelopeId: String,
        sequenceTs: Long,
        seqMac: String,
    ): PollEnvelope = PollEnvelope(
        id = envelopeId,
        fromHex = "ff".repeat(32),
        payloadBase64 = "",
        sequenceTs = sequenceTs,
        seq = seq,
        seqMac = seqMac,
    )

    /**
     * FakeTransport supporting verify-path tests. Tracks ack and
     * authSession calls. Defaults: returns ok ack 200; no envelopes
     * on poll; success session response with [HEX_KEY_A].
     */
    private class VerifyTransport(
        var sessionScript: suspend (callIndex: Int) -> RestFallbackResponse<AuthSessionResponse>,
    ) : RestFallbackTransport {

        var ackScript: (envelopeId: String) -> RestFallbackResponse<AckDeliverResponse> = { _ ->
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        val ackCalls: MutableList<String> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return sessionScript(authCalls.size - 1)
        }

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> = fail("send unexpected")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = PollResponse(envelopes = emptyList(), more = false),
            rawBody = "{}",
            elapsedMs = 1L,
        )

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return ackScript(body.id)
        }
    }

    private class RecordingCursorRepo : LongPollCursorRepository {
        val reads: MutableList<String> = mutableListOf()
        val writes: MutableList<Triple<String, Long, Long>> = mutableListOf()
        var stored: Long? = null
        override suspend fun getLastSeenSeq(identityHex: String): Long? {
            reads += identityHex
            return stored
        }
        override suspend fun upsertLastSeenSeq(
            identityHex: String,
            seq: Long,
            nowMs: Long,
        ): CursorUpsertOutcome {
            val previous = stored
            if (previous != null && previous >= seq) {
                return CursorUpsertOutcome.NoChange(previous)
            }
            writes += Triple(identityHex, seq, nowMs)
            stored = maxOf(previous ?: Long.MIN_VALUE, seq)
            return CursorUpsertOutcome.Advanced(seq)
        }
    }

    private fun buildOrchestrator(
        transport: VerifyTransport,
        cursor: LongPollCursorRepository?,
        scheduler: TestCoroutineScheduler,
        logSink: (String) -> Unit = {},
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = IDENTITY,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        log = logSink,
        longPollEnabled = false,
        cursorRepository = cursor,
        dispatcher = StandardTestDispatcher(scheduler),
    )

    private suspend fun bootstrapped(orch: RestFallbackOrchestrator) {
        val caps = orch.bootstrap()
        check(caps.restFallback)
    }

    // ── L2 publication at acquireOrRefreshToken sites ────────────────────────

    @Test
    fun bootstrap_with_valid_seq_mac_verify_key_publishes_KeyPresent_state() = runTest {
        init()
        val transport = VerifyTransport(sessionScript = { SESSION_RESPONSE_OK_WITH_KEY_A })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)

        // Initial state is KeyAbsent (the documented "bootstrap is
        // just the first refresh observation, not a special
        // pre-state" rule).
        assertEquals(VerifyKeyState.KeyAbsent, orch.peekVerifyKeyStateForTest())

        bootstrapped(orch)

        // After bootstrap (== a successful refresh with a valid hex
        // key), the state machine transitions to KeyPresent(hex).
        assertEquals(VerifyKeyState.KeyPresent(HEX_KEY_A), orch.peekVerifyKeyStateForTest())
    }

    @Test
    fun bootstrap_with_empty_seq_mac_verify_key_publishes_KeyAbsent_state() = runTest {
        init()
        val transport = VerifyTransport(sessionScript = {
            // Old-relay shape: response with empty seq_mac_verify_key.
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "test-token",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    seqMacVerifyKey = "",
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        // From KeyAbsent + Empty → KeyAbsent (legacy unverified
        // pass-through allowed under this state).
        assertEquals(VerifyKeyState.KeyAbsent, orch.peekVerifyKeyStateForTest())
    }

    @Test
    fun bootstrap_with_malformed_seq_mac_verify_key_publishes_KeySuspended_state() = runTest {
        init()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "test-token",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    seqMacVerifyKey = "NOT_VALID_HEX", // wrong length + uppercase
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        // KeyAbsent + Malformed → KeySuspended (M-B19 fail-closed).
        assertEquals(VerifyKeyState.KeySuspended, orch.peekVerifyKeyStateForTest())
    }

    @Test
    fun failed_refresh_after_KeyPresent_publishes_KeySuspended_L2_corollary() = runTest {
        // L2 corollary: a forced refresh that itself fails
        // transitions the state to KeySuspended. No REST ingestion
        // happens until a subsequent successful refresh moves the
        // state back to KeyPresent.
        init()
        var callIndex = 0
        val transport = VerifyTransport(sessionScript = {
            callIndex++
            if (callIndex == 1) {
                SESSION_RESPONSE_OK_WITH_KEY_A
            } else {
                // Failure: return null body. authSessionOnce treats
                // 200 with null body as a parsing failure.
                RestFallbackResponse(
                    statusCode = 500,
                    bodyParsed = null,
                    rawBody = "server error",
                    elapsedMs = 1L,
                )
            }
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)
        assertEquals(VerifyKeyState.KeyPresent(HEX_KEY_A), orch.peekVerifyKeyStateForTest())

        // Force a refresh that will fail.
        val result = orch.acquireOrRefreshToken(reason = "test", forceRefresh = true)
        assertNull(result)
        assertEquals(VerifyKeyState.KeySuspended, orch.peekVerifyKeyStateForTest())
    }

    // ── M10 / M17 / M-B8 — verify path outcomes ─────────────────────────────

    @Test
    fun m10_verified_envelope_emits_and_returns_true() = runTest {
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()

        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ),
                rawBody = "{}", elapsedMs = 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeVerifiedEnvelope(
            identityHex = IDENTITY,
            seq = 7L,
            envelopeId = "env-7",
            sequenceTs = 1_700_000_000_000L,
            verifyKeyBytes = derivedKey,
        )
        val emitted = orch.processInboundEnvelopeWithVerifyForTest(
            env = env,
            currentToken = "tok",
            loopTag = "test",
        )
        assertTrue(emitted, "verified envelope must emit")
        assertEquals(7L, orch.peekPendingSeqForAckForTest("env-7"))
        // Counter stays at 0 (verify succeeded).
        assertEquals(0, orch.peekMacFailCountForTest("env-7"))
    }

    @Test
    fun m10_mac_mismatch_drops_envelope_no_emit() = runTest {
        // Verify failures don't emit. Sub-cells (wrong key / single-bit
        // / off-by-one / pre-quantize / changed envelope_id / tampered
        // last hex char) all converge on the same MacMismatch outcome
        // at the orchestrator level; testing one representative is
        // sufficient because [SeqMacVerifierTest] pins each sub-cell
        // at the verifier level.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()

        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        // Build a valid envelope but tamper the last hex char of seqMac.
        val verified = makeVerifiedEnvelope(IDENTITY, 1L, "env-bad", 60_000L, derivedKey)
        val tamperedMac = verified.seqMac.dropLast(1) + if (verified.seqMac.last() == 'a') 'b' else 'a'
        val tampered = verified.copy(seqMac = tamperedMac)

        val emitted = orch.processInboundEnvelopeWithVerifyForTest(tampered, "tok", "test")
        assertFalse(emitted, "MAC mismatch must drop envelope")
        assertNull(orch.peekPendingSeqForAckForTest("env-bad"))
        // Counter incremented by 1.
        assertEquals(1, orch.peekMacFailCountForTest("env-bad"))
    }

    @Test
    fun m17_KeyAbsent_passes_through_with_no_verify_key_reason() = runTest {
        // Old-relay shape: KeyAbsent state, any seqMac value passes
        // through unverified.
        init()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = "",
                ), "{}", 1L,
            )
        })
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler, logSink = { logLines += it })
        bootstrapped(orch)
        assertEquals(VerifyKeyState.KeyAbsent, orch.peekVerifyKeyStateForTest())

        val env = makeEnvelopeWithSeqMac(seq = 5L, envelopeId = "env-old", sequenceTs = 60_000L, seqMac = "")
        val emitted = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertTrue(emitted, "KeyAbsent state must pass envelope through unverified")
        assertEquals(5L, orch.peekPendingSeqForAckForTest("env-old"))
        assertTrue(
            logLines.any { it.contains("inbound_unverified") && it.contains("reason=no_verify_key") },
            "expected `inbound_unverified ... reason=no_verify_key` log; got:\n${logLines.joinToString("\n")}",
        )
    }

    @Test
    fun m17_KeyPresent_with_empty_seq_mac_drops_with_no_mac_field_reason() = runTest {
        // KeyPresent + empty seqMac → drop with `no_mac_field`. The
        // load-bearing discriminator from the KeyAbsent unverified
        // pass-through case above.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler, logSink = { logLines += it })
        bootstrapped(orch)
        assertEquals(VerifyKeyState.KeyPresent(keyHex), orch.peekVerifyKeyStateForTest())

        val env = makeEnvelopeWithSeqMac(seq = 5L, envelopeId = "env-no-mac", sequenceTs = 60_000L, seqMac = "")
        val emitted = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertFalse(emitted, "empty seqMac under KeyPresent must drop")
        assertNull(orch.peekPendingSeqForAckForTest("env-no-mac"))
        assertTrue(
            logLines.any { it.contains("poll_mac_verify_repeat") && it.contains("reason=no_mac_field") },
            "expected `poll_mac_verify_repeat ... reason=no_mac_field` log",
        )
        // Counter incremented exactly once.
        assertEquals(1, orch.peekMacFailCountForTest("env-no-mac"))
    }

    @Test
    fun mb8_identity_hex_round_trips_against_receiving_identity() = runTest {
        // M-B8: the MAC computed using the receiving identity
        // (`RestFallbackOrchestrator.identityHex`) verifies. Using
        // a DIFFERENT identity (a hostile-substitution scenario)
        // does NOT verify.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        // Verifying envelope MAC computed against the receiving identity.
        val envOk = makeVerifiedEnvelope(IDENTITY, 1L, "env-ok", 60_000L, derivedKey)
        assertTrue(orch.processInboundEnvelopeWithVerifyForTest(envOk, "tok", "test"))

        // The verify path uses RestFallbackOrchestrator.identityHex
        // as the canonical receiving identity. A MAC computed
        // against a DIFFERENT identity does not verify here — the
        // orchestrator's identityHex is the constructor-bound
        // receiving identity, NOT a per-envelope field.
        val otherIdentity = "bb".repeat(32)
        val otherDerivedKey = deriveVerifyKey(rootKey, otherIdentity)
        val envOtherIdentity = makeVerifiedEnvelope(otherIdentity, 2L, "env-wrong", 60_000L, otherDerivedKey)
        // The orchestrator verifies using ITS identity (IDENTITY)
        // not the envelope's notion of identity. So this MAC, which
        // was computed against `otherIdentity`, fails the verifier.
        assertFalse(orch.processInboundEnvelopeWithVerifyForTest(envOtherIdentity, "tok", "test"))
    }

    // ── M-B10 — cancellation safety on verify-then-emit ─────────────────────

    @Test
    fun mb10_cancellation_between_verify_and_emit_does_not_advance_cursor() = runTest {
        // Verify-then-emit is a single suspending function from the
        // poll loop's perspective; the cancel-safe emit path in
        // `emitWithCancellationSafeRollback` (already exercised in
        // `RestFallbackOrchestratorPollLoopTest`) handles the
        // emission side. The verifier itself is a pure compute call
        // (no suspension); a cancel hitting between the verify
        // result and the emit MUST NOT have populated the
        // `_pendingSeqForAck` entry (emit-with-rollback handles
        // that). The cursor remains at its pre-verify value.
        //
        // Behavioural witness: under cancellation of the caller,
        // the `_pendingSeqForAck` entry for the envelope is removed
        // by the cancel-safe emit's rollback path, no upsert is
        // called on the cursor, and the cursor reads remain
        // pristine.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val cursor = RecordingCursorRepo()
        val orch = buildOrchestrator(transport, cursor, testScheduler)
        bootstrapped(orch)

        // Fill the SharedFlow buffer so the trial emit suspends.
        val blocker = launch { orch.inbound.collect { kotlinx.coroutines.awaitCancellation() } }
        runCurrent()
        repeat(33) { i ->
            orch.emitWithCancellationSafeRollbackForTest(
                makeVerifiedEnvelope(IDENTITY, (1000L + i), "env-fill-$i", 60_000L, derivedKey),
            )
        }
        runCurrent()

        // Trial envelope: verify succeeds, but emit suspends.
        val env = makeVerifiedEnvelope(IDENTITY, 5L, "env-mb10", 60_000L, derivedKey)
        val emitJob = launch {
            try {
                orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
                fail("expected CancellationException — emit must suspend on full buffer")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected.
            }
        }
        runCurrent()

        emitJob.cancel()
        runCurrent()

        // Verify-then-emit cancellation rolled back the entry.
        assertNull(orch.peekPendingSeqForAckForTest("env-mb10"))
        // Cursor never written.
        assertEquals(0, cursor.writes.size)

        blocker.cancel()
        runCurrent()
    }

    // ── M-B12 — bad-MAC counter increments ───────────────────────────────────

    @Test
    fun mb12_bad_mac_counter_increments_no_ack_no_cursor() = runTest {
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val cursor = RecordingCursorRepo()
        val orch = buildOrchestrator(transport, cursor, testScheduler)
        bootstrapped(orch)

        // Make an envelope with a definitively bad MAC: 64 zero hex chars.
        val env = makeEnvelopeWithSeqMac(seq = 1L, envelopeId = "env-bad", sequenceTs = 60_000L, seqMac = "0".repeat(64))

        // First failure: counter goes to 1; no refresh yet.
        val emitted1 = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertFalse(emitted1)
        assertEquals(1, orch.peekMacFailCountForTest("env-bad"))
        assertEquals(LongPollBreakerState.Closed, orch.peekBreakerStateForTest())
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.NotAttempted, orch.peekMacRefreshStatusForTest("env-bad"))

        // No ack call, no cursor write.
        assertEquals(0, transport.ackCalls.size)
        assertEquals(0, cursor.writes.size)
    }

    // ── M-B13 — first repeat triggers forced refresh, latched once ──────────

    @Test
    fun mb13_threshold_trigger_calls_acquireOrRefreshToken_once_per_envelope_id() = runTest {
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)
        val authBeforePost = transport.authCalls.size

        val env = makeEnvelopeWithSeqMac(seq = 1L, envelopeId = "env-mb13", sequenceTs = 60_000L, seqMac = "0".repeat(64))

        // First failure: counter=1, no refresh.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(1, orch.peekMacFailCountForTest("env-mb13"))
        assertEquals(authBeforePost, transport.authCalls.size, "no refresh on first fail")
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.NotAttempted, orch.peekMacRefreshStatusForTest("env-mb13"))

        // Second failure: counter=2 = THRESHOLD. Refresh fires; latch set.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(2, orch.peekMacFailCountForTest("env-mb13"))
        assertEquals(authBeforePost + 1, transport.authCalls.size, "refresh fires at threshold")
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.Completed, orch.peekMacRefreshStatusForTest("env-mb13"))
    }

    @Test
    fun mb13_latch_prevents_second_refresh_on_subsequent_failures_for_same_id() = runTest {
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(seq = 1L, envelopeId = "env-latch", sequenceTs = 60_000L, seqMac = "0".repeat(64))
        // Cross the threshold.
        repeat(2) {
            orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        }
        val authAfterThreshold = transport.authCalls.size

        // Further failures DO NOT trigger another refresh (latch
        // prevents). Each instead transitions the breaker to
        // SuspendedOnPoison via L7 step 4. Once Suspended, the
        // verify gate drops envelopes outright (no counter
        // increment because the breaker check happens BEFORE
        // verify in `processInboundEnvelopeWithVerify`).
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(authAfterThreshold, transport.authCalls.size, "no second refresh due to latch")
    }

    // ── M-B14 — second repeat after refresh suspends both loops ─────────────

    @Test
    fun mb14_second_fail_after_refresh_transitions_breaker_to_SuspendedOnPoison() = runTest {
        init()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = HEX_KEY_A,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(seq = 1L, envelopeId = "env-mb14", sequenceTs = 60_000L, seqMac = "0".repeat(64))
        // Cross threshold (count goes to 2 → refresh).
        repeat(2) { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test") }
        assertEquals(LongPollBreakerState.Closed, orch.peekBreakerStateForTest())

        // One more failure on the SAME id, latch already set →
        // suspension.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(LongPollBreakerState.SuspendedOnPoison, orch.peekBreakerStateForTest())
    }

    // ── Round 11 (council follow-up) — bounded bad-MAC map LRU ──────────────

    @Test
    fun r11_bad_mac_maps_evict_oldest_envelope_id_when_cap_exceeded() = runTest {
        // Defence-in-depth against a malicious relay that fabricates
        // unique envelope_ids on every poll. Even with server-side
        // `pollMaxEnvelopes = 1`, ~1 fabricated id per poll over the
        // orchestrator lifetime would grow `_macFailCount` and
        // `_macRefreshStatus` unboundedly without a client cap.
        //
        // The cap is asserted as a behavioural invariant: after
        // `cap + N` distinct bad-MAC ids each failing once, the
        // first N ids (oldest first-seen) must be EVICTED from the
        // tracker and the most recent `cap` ids must remain.
        // Eviction is keyed on insertion order (the eldest entry in
        // [_macFailCount]); both maps are evicted on the same key
        // synchronously so they never disagree about which ids are
        // tracked.
        //
        // No individual id reaches the L7 refresh threshold
        // (`MAC_REPEAT_REFRESH_THRESHOLD = 2`) — each id fails
        // exactly once — so the breaker stays Closed and the verify
        // gate is not short-circuited by SuspendedOnPoison.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val cap = RestFallbackOrchestrator.BAD_MAC_TRACKED_ENVELOPE_CAP
        val excess = 44 // arbitrary > 0, chosen to bracket realistic burst pressure
        val total = cap + excess

        for (n in 0 until total) {
            val env = makeEnvelopeWithSeqMac(
                seq = (n + 1).toLong(),
                envelopeId = "evict-$n",
                sequenceTs = 60_000L,
                seqMac = "0".repeat(64), // always-bad MAC
            )
            val emitted = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
            assertFalse(emitted, "bad-MAC envelope evict-$n must NOT emit downstream")
        }

        // Breaker invariant: with `cap + excess` distinct ids each
        // failing once, no id ever reaches `MAC_REPEAT_REFRESH_THRESHOLD`,
        // so the breaker must remain Closed.
        assertEquals(
            LongPollBreakerState.Closed, orch.peekBreakerStateForTest(),
            "no id reaches MAC_REPEAT_REFRESH_THRESHOLD; breaker must stay Closed under LRU eviction",
        )

        // Oldest `excess` envelope_ids must have been LRU-evicted.
        // `peekMacFailCountForTest` returns 0 for absent ids, which
        // is indistinguishable from "never seen" for the purpose of
        // future bad-MAC accounting — eviction safely re-arms the
        // L7 posture for that id (worst case: a relay can re-attack
        // the same evicted id but the counter starts from zero,
        // staying below threshold for at least one more cycle).
        for (n in 0 until excess) {
            assertEquals(
                0, orch.peekMacFailCountForTest("evict-$n"),
                "envelope evict-$n is older than cap=$cap and must be LRU-evicted",
            )
            assertEquals(
                RestFallbackOrchestrator.MacRefreshStatus.NotAttempted,
                orch.peekMacRefreshStatusForTest("evict-$n"),
                "evicted id must also be removed from _macRefreshStatus to keep the two maps consistent",
            )
        }

        // The most recent `cap` envelope_ids must still be tracked
        // with count=1 (each saw exactly one failure and was not yet
        // evicted by subsequent inserts).
        for (n in excess until total) {
            assertEquals(
                1, orch.peekMacFailCountForTest("evict-$n"),
                "envelope evict-$n is among the most recent $cap inserts and must still be tracked",
            )
        }
    }

    // ── Round 11 (council follow-up) — wsActivePollLoop drain-immediate symmetry ──

    @Test
    fun r11_ws_active_poll_loop_drains_with_immediate_delay_when_relay_signals_more() = runTest {
        // Behavioural fence for the wsActivePollLoop's `more=true`
        // short-cycle path. The legacy `pollLoop` (line ~1658)
        // delays only [POLL_DRAIN_IMMEDIATE_MS] (~100 ms) between
        // iterations when the relay sets `more=true` on the poll
        // response, draining backlog ~20× faster than the regular
        // jittered `intervalMs` cadence. Before Round 11, the
        // parallel `wsActivePollLoop` lacked this symmetry — it
        // always fell back to the jittered delay even when the
        // relay said "more pending".
        //
        // Without a behavioural assertion this regression could land
        // silently. The test scripts an inline transport that
        // returns 5 distinct envelopes + `more=true` on calls 0..4
        // and then an empty response on call 5+. With the symmetry
        // fix, all 5 short-cycle polls fit comfortably inside
        // 1500 ms of virtual time (5 × 100 ms drain + processing
        // overhead). Without the fix, the loop would issue at most
        // ~1 poll in that window because each iteration would
        // delay `intervalMs * jitterFactor` ≈ 2000-2400 ms.
        //
        // The test isolates `wsActivePollLoop` by keeping the state
        // machine in its initial mode (NOT `RestActive`), so the
        // legacy `pollLoop` does NOT spawn. Only the parallel loop
        // runs, and its polls are counted directly via the inline
        // transport.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = MorePollScriptingTransport(
            sessionScript = {
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = keyHex,
                    ), "{}", 1L,
                )
            },
            scheduler = testScheduler,
            shortCycleCount = 5,
        )
        val orch = buildOrchestratorWithPollLoops(
            transport = transport,
            cursor = RecordingCursorRepo(),
            scheduler = testScheduler,
            longPollEnabled = true,
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        orch.start()
        runCurrent()
        // Advance virtual time enough for 5 short-cycle polls to
        // complete plus a small slack for orchestrator bookkeeping.
        // Budget: 5 × POLL_DRAIN_IMMEDIATE_MS + 1000ms slack = 1500ms.
        advanceTimeBy(1500L)
        runCurrent()

        // Behavioural invariant 1: at least 5 polls must have fired
        // within the 1500ms window. Without the more=true symmetry,
        // each iteration would delay ~2000ms (POLL_ACTIVE_MS × jitter)
        // and at most 1 poll would land in this window.
        assertTrue(
            transport.pollTimestamps.size >= 5,
            "wsActivePollLoop must short-cycle through POLL_DRAIN_IMMEDIATE_MS when " +
                "the relay sets `more=true`. After 1500ms of virtual time, expected ≥5 polls " +
                "(5 × 100ms drain delay + slack); got ${transport.pollTimestamps.size}. " +
                "Without the more=true symmetry, the loop falls back to intervalMs " +
                "(POLL_ACTIVE_MS=2000ms × jitter), so the test would observe at most ~1 poll.",
        )

        // Behavioural invariant 2: inter-poll virtual-time gaps for
        // the more=true polls must be ≤ POLL_DRAIN_IMMEDIATE_MS plus
        // a small slack for the orchestrator's per-iteration work
        // (envelope processing, log emission). The slack accommodates
        // verify-fail bookkeeping plus the inline transport's own
        // bookkeeping; both are well under 20ms in virtual time.
        val timestamps = transport.pollTimestamps.take(5)
        val gapCap = RestFallbackOrchestrator.POLL_DRAIN_IMMEDIATE_MS + 20L
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertTrue(
                gap <= gapCap,
                "wsActivePollLoop poll #$i fired ${gap}ms after poll #${i - 1}; " +
                    "expected ≤ ${gapCap}ms (POLL_DRAIN_IMMEDIATE_MS=" +
                    "${RestFallbackOrchestrator.POLL_DRAIN_IMMEDIATE_MS}ms + 20ms slack). " +
                    "If this gap matches POLL_ACTIVE_MS × jitter (~2000-2400ms), the " +
                    "drain-immediate symmetry has regressed.",
            )
        }

        // Tear down so runTest's child-coroutine scope can complete.
        // Without stop(), the parallel wsActivePollLoop sits in
        // `while (scope.isActive) { ... }` forever and runTest
        // never returns.
        orch.stop()
        runCurrent()
    }

    /**
     * Inline transport that scripts poll responses to exercise the
     * `more=true` branch of `wsActivePollLoop`. The first
     * [shortCycleCount] polls return one unique envelope each with
     * `more=true`; subsequent polls return empty + `more=false`.
     *
     * The envelopes carry a bad MAC (`seqMac = "0".repeat(64)`)
     * with DISTINCT envelope_ids so no individual id reaches
     * `MAC_REPEAT_REFRESH_THRESHOLD` — the breaker stays Closed and
     * the poll loops keep running through the test window.
     *
     * `pollTimestamps` records the virtual-time instant of each
     * poll's entry, taken from [scheduler]. The test asserts the
     * inter-poll gap on this list.
     */
    private class MorePollScriptingTransport(
        var sessionScript: suspend (callIndex: Int) -> RestFallbackResponse<AuthSessionResponse>,
        val scheduler: TestCoroutineScheduler,
        val shortCycleCount: Int,
    ) : RestFallbackTransport {
        val pollTimestamps: MutableList<Long> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        private val mutex = Mutex()
        override suspend fun authSession(url: String, body: AuthSessionRequest): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return sessionScript(authCalls.size - 1)
        }
        override suspend fun send(url: String, token: String, idempotencyKey: String, body: SendRequest): RestFallbackResponse<SendResponse> =
            fail("send unexpected in r11 wsActivePoll drain test")
        override suspend fun poll(url: String, token: String, sinceSeq: Long?, longPollOptIn: Boolean, readTimeoutMs: Long?): RestFallbackResponse<PollResponse> {
            val callIdx = mutex.withLock {
                val idx = pollTimestamps.size
                pollTimestamps += scheduler.currentTime
                idx
            }
            return if (callIdx < shortCycleCount) {
                RestFallbackResponse(
                    200,
                    PollResponse(
                        envelopes = listOf(
                            PollEnvelope(
                                id = "evict-drain-$callIdx",
                                fromHex = "ff".repeat(32),
                                payloadBase64 = "",
                                sequenceTs = 60_000L + callIdx.toLong(),
                                seq = (callIdx + 1).toLong(),
                                seqMac = "0".repeat(64), // bad MAC keeps it in bad-MAC posture, no emit
                            ),
                        ),
                        more = true,
                    ),
                    "{}", 1L,
                )
            } else {
                RestFallbackResponse(
                    200,
                    PollResponse(envelopes = emptyList(), more = false),
                    "{}", 1L,
                )
            }
        }
        override suspend fun ackDeliver(url: String, token: String, body: AckDeliverRequest): RestFallbackResponse<AckDeliverResponse> =
            RestFallbackResponse(200, AckDeliverResponse(ok = 1), "{}", 1L)
    }

    // ── M-B15 / M-B16 — Direct WSS and cursor invariants under suspension ──

    @Test
    fun mb15_suspended_state_does_not_alter_RestStateMachine() = runTest {
        // The L9 breaker is SEPARATE from RestStateMachine. A
        // SuspendedOnPoison transition MUST NOT change RestMode
        // (Direct WSS / Reality / Tor + the rest of the messenger
        // continue operating).
        init()
        val transport = VerifyTransport(sessionScript = { SESSION_RESPONSE_OK_WITH_KEY_A })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val initialMode = orch.stateMachine.state.value
        orch.setBreakerStateForTest(LongPollBreakerState.SuspendedOnPoison)

        assertEquals(initialMode, orch.stateMachine.state.value,
            "SuspendedOnPoison must NOT mutate RestStateMachine.state")
    }

    @Test
    fun mb16_suspended_breaker_drops_all_envelopes_no_cursor_write() = runTest {
        // Once SuspendedOnPoison, the verify gate drops every
        // envelope regardless of its MAC. No emit, no ack, no
        // cursor advance.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val cursor = RecordingCursorRepo()
        val orch = buildOrchestrator(transport, cursor, testScheduler)
        bootstrapped(orch)

        orch.setBreakerStateForTest(LongPollBreakerState.SuspendedOnPoison)

        // Even a perfectly verified envelope is dropped.
        val env = makeVerifiedEnvelope(IDENTITY, 1L, "env-good-but-dropped", 60_000L, derivedKey)
        val emitted = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertFalse(emitted)
        assertNull(orch.peekPendingSeqForAckForTest("env-good-but-dropped"))
        assertEquals(0, transport.ackCalls.size)
        assertEquals(0, cursor.writes.size)
    }

    // ── M-B17 — real two-loop run under SuspendedOnPoison ───────────────────

    @Test
    fun mb17_real_both_poll_loops_stop_polling_under_SuspendedOnPoison() = runTest {
        // C4 review-fix (P2.4): the earlier draft of M-B17 called
        // the test helper twice with different loop-tag strings —
        // it did NOT prove BOTH production poll loops actually
        // observe the breaker. This version drives the real
        // `pollLoop` AND `wsActivePollLoop` via
        // `orchestrator.start()`, flipping state to RestActive so
        // the legacy loop spawns alongside the parallel one.
        //
        // We then set the breaker to SuspendedOnPoison via a
        // direct seam and assert that `transport.pollEnterCount`
        // stops growing — proving the new C4 P1.2 fix actually
        // GATES THE POLL CALL ITSELF, not just the envelope
        // ingestion afterward.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        // Flip state to RestActive so the legacy pollLoop also spawns.
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L,
                    inboundFrames = 0,
                    pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)

        orch.start()
        runCurrent()
        // Pump a few iterations to confirm BOTH loops poll.
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        val pollCountBeforeSuspension = transport.pollEnterCount
        assertTrue(
            pollCountBeforeSuspension >= 2,
            "expected both poll loops to have polled at least once; pollEnterCount=$pollCountBeforeSuspension",
        )
        // C4 review-fix round 2 (P2.3) — `pollEnterCount >= 2`
        // alone can mean two calls from the SAME loop. Pin the
        // distinctive log prefixes from BOTH loops to prove
        // ORIGIN:
        //
        //   `REST_TRACE poll_call ` — emitted ONLY by the legacy
        //   `pollLoop`.
        //   `REST_TRACE ws_active_poll_call ` — emitted ONLY by
        //   the parallel `wsActivePollLoop`.
        assertTrue(
            logLines.any { it.contains("REST_TRACE poll_call ") },
            "legacy pollLoop did not fire `REST_TRACE poll_call ` log line. " +
                "captured ${logLines.size} log lines:\n${logLines.takeLast(20).joinToString("\n")}",
        )
        assertTrue(
            logLines.any { it.contains("REST_TRACE ws_active_poll_call ") },
            "parallel wsActivePollLoop did not fire `REST_TRACE ws_active_poll_call ` log. " +
                "captured ${logLines.size} log lines:\n${logLines.takeLast(20).joinToString("\n")}",
        )
        val logCountAtPreSuspension = logLines.size

        // Flip the breaker to SuspendedOnPoison via the test seam.
        orch.setBreakerStateForTest(LongPollBreakerState.SuspendedOnPoison)
        runCurrent()

        // Advance virtual time many iterations and confirm BOTH
        // loops actually STOPPED polling — the breaker gate is
        // BEFORE `transport.poll(...)`.
        val pollCountAtFreezeMoment = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_FAIL_BACKOFF_MS * 20L)
        runCurrent()
        // Some grace polls may land if a poll was already in flight,
        // but eventually polling should freeze. We bound at +2 to
        // tolerate one in-flight poll per loop.
        assertTrue(
            transport.pollEnterCount - pollCountAtFreezeMoment <= 2,
            "poll count grew unexpectedly after SuspendedOnPoison: " +
                "before=$pollCountAtFreezeMoment, after=${transport.pollEnterCount}. " +
                "The breaker MUST gate `transport.poll(...)` itself, not just " +
                "envelope ingestion.",
        )

        // Now snapshot and verify total freeze over a further interval.
        val pollCountFrozen = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_FAIL_BACKOFF_MS * 100L)
        runCurrent()
        assertEquals(
            pollCountFrozen,
            transport.pollEnterCount,
            "after the breaker freeze settles, poll count must be perfectly static",
        )

        // C4 review-fix round 2 (P2.3) — pin the post-suspension
        // skip-log prefixes for BOTH loops. The legacy and parallel
        // loop each log their own
        // `(ws_active_)poll_call_skipped reason=breaker_suspended_on_poison`
        // line on every iteration after the breaker flipped.
        val postSuspensionLogs = logLines.subList(logCountAtPreSuspension, logLines.size)
        assertTrue(
            postSuspensionLogs.any {
                it.contains("REST_TRACE poll_call_skipped") &&
                    it.contains("reason=breaker_suspended_on_poison")
            },
            "legacy pollLoop did not fire `poll_call_skipped reason=breaker_suspended_on_poison` " +
                "log line after the breaker flipped. " +
                "Captured ${postSuspensionLogs.size} post-suspension log lines.",
        )
        assertTrue(
            postSuspensionLogs.any {
                it.contains("REST_TRACE ws_active_poll_call_skipped") &&
                    it.contains("reason=breaker_suspended_on_poison")
            },
            "parallel wsActivePollLoop did not fire " +
                "`ws_active_poll_call_skipped reason=breaker_suspended_on_poison` log line. " +
                "Captured ${postSuspensionLogs.size} post-suspension log lines.",
        )

        orch.stop()
        runCurrent()
    }

    // ── C4 review-fix round 4 P2 — start ↔ stop race regression pin ─────────

    @Test
    fun p14_concurrent_start_and_stop_serialize_under_lifecycle_mutex_no_orphan_polls() = runTest {
        // Round 3 only put `start()` under `_lifecycleMutex`; the
        // (non-suspending) `stop()` nulled job-handle fields
        // IMMEDIATELY on cancel. A concurrent `start()` re-reading
        // tail fields in Phase 2 of `cancelAndJoinAll` would see
        // nulls and skip the join, while a cancelled job's
        // `finally` block was still unwinding and could write stale
        // state AFTER the reset had landed.
        //
        // Round 4 makes `stop()` (and `close()`) suspending and
        // serialised under the same `_lifecycleMutex`, with the
        // teardown body itself wrapped in `withContext(NonCancellable)`
        // so a caller-cancellation cannot abort cleanup mid-way.
        //
        // This test repros the race window deterministically:
        //
        //   1. Drive state to `RestActive` so the production observer
        //      spawns `pollJob`; the parallel `wsActivePollJob` also
        //      runs. Both loops actually poll (`pollEnterCount >= 2`).
        //   2. Launch a SECOND `start()` and a concurrent `stop()`.
        //      Under round 3 these could interleave arbitrarily and
        //      leak orphan jobs; under round 4 they FIFO-serialise on
        //      `_lifecycleMutex`.
        //   3. Wait for both to complete.
        //   4. Advance virtual time. Assert `pollEnterCount` is
        //      perfectly static — proves no orphan poll job is still
        //      alive after the race.
        //   5. Assert `peekBreakerStateForTest()` is `Closed` — proves
        //      the reset was atomic (not torn by a concurrent stop).
        //
        // Determinism: `StandardTestDispatcher(testScheduler)` runs
        // launched coroutines in launch order; `Mutex` resumes
        // queued waiters FIFO.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)

        // First start: spawns observer + wsActivePollJob; observer's
        // `onModeChanged(RestActive)` spawns the legacy `pollJob`.
        orch.start()
        runCurrent()
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        assertTrue(
            transport.pollEnterCount >= 2,
            "expected both loops to have polled at least once before the race; " +
                "pollEnterCount=${transport.pollEnterCount}",
        )
        val resetLogsBeforeRace = logLines.count { it.contains("poison_state_reset_on_start") }
        check(resetLogsBeforeRace == 1) {
            "expected exactly one `poison_state_reset_on_start` log line after the first start; " +
                "got $resetLogsBeforeRace. captured ${logLines.size} log lines."
        }

        // Concurrent second `start()` and `stop()`. Under
        // `StandardTestDispatcher`, the first `launch` dispatches
        // first → acquires `_lifecycleMutex` first → runs its full
        // body (teardown under NonCancellable + reset + spawn new
        // jobs) → releases. The second `launch` queues on the
        // mutex and runs after. Either order proves the
        // serialisation; this test fixes the order via launch order
        // for determinism but the assertions hold either way.
        val secondStart = launch { orch.start() }
        val stopJob = launch { orch.stop() }
        // Drain. Both calls should complete without throwing.
        advanceTimeBy(60_000L)
        runCurrent()
        secondStart.join()
        stopJob.join()

        // Both lifecycle methods entered their critical section.
        // `start()` logs `poison_state_reset_on_start`; `stop()`
        // does NOT log a reset (it only cancels). So we observed
        // exactly one additional reset (from the second start),
        // for a total of 2 — proves the second start's body ran
        // under the mutex AND the stop did not double-reset.
        val resetLogsAfterRace = logLines.count { it.contains("poison_state_reset_on_start") }
        assertEquals(
            2,
            resetLogsAfterRace,
            "exactly one additional reset must have landed (from the second start). " +
                "Got $resetLogsAfterRace total. The stop() must NOT log a reset.",
        )

        // After the race resolves, no jobs are alive. Advance
        // significant virtual time and verify `pollEnterCount` is
        // perfectly static. Any orphan job from a torn lifecycle
        // teardown would keep polling at `POLL_ACTIVE_MS` cadence.
        val pollCountAfterRace = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS * 50L)
        runCurrent()
        assertEquals(
            pollCountAfterRace,
            transport.pollEnterCount,
            "after the start()/stop() race resolves, pollEnterCount MUST be perfectly static. " +
                "Got before=$pollCountAfterRace, after=${transport.pollEnterCount}. " +
                "A diff means an orphan job survived the teardown — the lifecycle mutex did NOT serialise " +
                "the race, OR the two-phase `cancelAndJoinAll` did not catch a job spawned between phases.",
        )

        // The reset is atomic: breaker stays `Closed`. A stale
        // write from a torn teardown could land here as
        // SuspendedOnPoison (if any job had its `finally` write
        // race the reset). A clean Closed value confirms atomicity.
        assertEquals(
            LongPollBreakerState.Closed,
            orch.peekBreakerStateForTest(),
            "after the race resolves, breaker MUST be Closed — proves the second start's reset was atomic w.r.t. the racing stop",
        )
    }

    // ── C4 review-fix round 5 P2 — reverse-order race + cancelled waiter + terminal close ─

    @Test
    fun p15_concurrent_stop_then_start_serializes_and_leaves_new_jobs_polling() = runTest {
        // Round 5 P2.a: round-4's p14 fixed `start()` then `stop()`
        // launch order. Reverse the order so the FIFO mutex queue
        // serialises stop → start. After the race, the second
        // `start()`'s body must have run (new observer + new
        // `wsActivePollJob` spawned), so subsequent polling SHOULD
        // resume — the opposite outcome to p14.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)

        orch.start()
        runCurrent()
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        assertTrue(
            transport.pollEnterCount >= 2,
            "expected both loops to have polled at least once before the race; pollEnterCount=${transport.pollEnterCount}",
        )

        // REVERSE launch order: stop FIRST, start SECOND. Under
        // FIFO mutex resume, stop acquires first; start queues
        // behind. After stop completes (cancels all jobs), start
        // runs (resets state + spawns new observer + new
        // wsActivePollJob). Net effect: the orchestrator is alive
        // again after the race.
        val stopJob = launch { orch.stop() }
        val startJob = launch { orch.start() }
        advanceTimeBy(60_000L)
        runCurrent()
        stopJob.join()
        startJob.join()

        // The second start ran: assert by counting
        // `poison_state_reset_on_start` logs (one before race + one
        // from the second start = two total).
        val resetLogs = logLines.count { it.contains("poison_state_reset_on_start") }
        assertEquals(
            2, resetLogs,
            "second start under FIFO must have logged a reset; got $resetLogs total reset logs",
        )

        // Polling SHOULD resume after the race — proves the second
        // start's spawn block ran (caller-cancel gate did not fire,
        // _closed did not fire, capability still enabled).
        val pollCountAfterRace = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS * 5L)
        runCurrent()
        assertTrue(
            transport.pollEnterCount > pollCountAfterRace,
            "after stop→start race, polling MUST resume (the second start's spawn block ran). " +
                "Got before=$pollCountAfterRace, after=${transport.pollEnterCount}.",
        )

        // Clean up.
        orch.stop()
        runCurrent()
    }

    @Test
    fun p15_cancelled_stop_waiter_completes_cleanup_after_mutex_release() = runTest {
        // Round 5 P1.1 regression pin: the lifecycle mutex acquire
        // itself must run under NonCancellable. Under round 4 a
        // `stop()` suspended waiting for the lifecycle mutex would
        // ABORT entirely if its caller was cancelled — the cleanup
        // never ran. Under round 5 the acquire-then-cleanup
        // sequence is all under NonCancellable; once the mutex
        // frees, the teardown runs regardless of caller cancel
        // state.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        orch.start()
        runCurrent()
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        val pollCountBeforeRace = transport.pollEnterCount
        assertTrue(
            pollCountBeforeRace >= 2,
            "expected both loops alive before race; got pollEnterCount=$pollCountBeforeRace",
        )

        // Hold the lifecycle mutex externally so the stop() suspends
        // on the acquire.
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            orch.withLifecycleMutexHeldForTest { release.await() }
        }
        runCurrent()

        // stop() suspends on the lifecycle mutex acquire.
        val stopCompleted = CompletableDeferred<Unit>()
        val stopCaller = launch {
            orch.stop()
            stopCompleted.complete(Unit)
        }
        runCurrent()
        assertFalse(
            stopCompleted.isCompleted,
            "stop() should still be suspended waiting for the externally-held lifecycle mutex",
        )

        // Cancel the stop's caller WHILE IT WAITS on the mutex.
        // Under round 4 this would abort the stop entirely.
        stopCaller.cancel()
        runCurrent()
        assertFalse(
            stopCompleted.isCompleted,
            "stop() body has not started yet (still waiting on mutex)",
        )

        // Release the holder. Now the mutex frees; the cancelled
        // stop()'s NonCancellable-wrapped acquire proceeds; the
        // teardown runs to completion.
        release.complete(Unit)
        holder.join()
        runCurrent()
        // Drain in case cancelAndJoinAll has any virtual-time work.
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(
            stopCompleted.isCompleted,
            "stop() body ran to completion AFTER mutex release, even though its caller was cancelled — " +
                "proves the mutex acquire is under NonCancellable",
        )

        // Polling has stopped (jobs were cancelled + joined by the
        // cleanup that proceeded despite caller cancellation).
        val pollCountAfterCleanup = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS * 50L)
        runCurrent()
        assertEquals(
            pollCountAfterCleanup,
            transport.pollEnterCount,
            "after cancelled-waiter stop ran cleanup, pollEnterCount MUST be static. " +
                "A diff means cleanup did NOT run — the cancellation aborted the stop, OR mutex acquire is still cancellable.",
        )
    }

    @Test
    fun p16_cancelled_start_waiter_does_not_destroy_working_orchestrator() = runTest {
        // Round 6 P1 regression pin: ASYMMETRIC `start()` vs
        // `stop()`/`close()` cancellation semantics. `start()`
        // re-arms a running orchestrator; a caller cancelled
        // BEFORE the mutex is acquired must be a true no-op —
        // the working orchestrator stays working.
        //
        // Under round 5 the start()'s mutex acquire ran inside
        // `withContext(NonCancellable)`. A cancelled waiter would
        // STILL acquire the mutex eventually, run
        // `cancelAndJoinAll()` (destroying running jobs), reset
        // state, and only then skip the spawn via the
        // `callerJob.isCancelled` gate. Net effect on a healthy
        // transport: the cancellation tore it down.
        //
        // Under round 6 the start()'s mutex acquire is
        // CANCELLABLE. The cancelled waiter throws
        // CancellationException out of the acquire phase; the
        // body never runs; the running orchestrator is
        // untouched. The NonCancellable transaction only kicks
        // in AFTER successful acquire (to keep teardown+reset+
        // spawn atomic if the call does land).
        //
        // Repro: hold the lifecycle mutex externally; launch a
        // start() that suspends on the acquire; cancel its
        // caller; release the mutex; assert the original workers
        // are still polling AND no second `poison_state_reset_on_start`
        // was logged.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        orch.start()
        runCurrent()
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        val pollCountBeforeRace = transport.pollEnterCount
        assertTrue(
            pollCountBeforeRace >= 2,
            "expected both loops alive + polling before the race; got pollEnterCount=$pollCountBeforeRace",
        )
        val resetLogsBeforeRace = logLines.count { it.contains("poison_state_reset_on_start") }
        check(resetLogsBeforeRace == 1) {
            "expected exactly one reset log from the first start; got $resetLogsBeforeRace"
        }

        // Hold the lifecycle mutex externally; the start() will
        // suspend on the acquire.
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            orch.withLifecycleMutexHeldForTest { release.await() }
        }
        runCurrent()

        // start() suspends on the cancellable acquire.
        val startCompleted = CompletableDeferred<Boolean>()
        val startCaller = launch {
            try {
                orch.start()
                startCompleted.complete(true)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                startCompleted.complete(false)
                throw ce
            }
        }
        runCurrent()
        assertFalse(
            startCompleted.isCompleted,
            "start() should still be suspended waiting for the externally-held lifecycle mutex",
        )

        // Cancel the start's caller WHILE IT WAITS on the
        // (cancellable) mutex acquire.
        startCaller.cancel()
        startCaller.join()
        runCurrent()
        assertEquals(
            false,
            startCompleted.await(),
            "start() must have exited via CancellationException — its body never ran. " +
                "If this returns true, the acquire was NonCancellable and the body executed despite caller cancel.",
        )

        // Release the holder. Mutex is now free.
        release.complete(Unit)
        holder.join()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        // The working orchestrator must be untouched: pollEnterCount
        // keeps growing, no second reset log emitted.
        val pollCountAfterMutexRelease = transport.pollEnterCount
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS * 5L)
        runCurrent()
        assertTrue(
            transport.pollEnterCount > pollCountAfterMutexRelease,
            "cancelled-waiter start() must NOT destroy the running orchestrator. " +
                "Old workers must keep polling. Got before=$pollCountAfterMutexRelease " +
                "after=${transport.pollEnterCount}. A non-growing count means the cancelled " +
                "start() tore down the workers — the round-5 NonCancellable-on-acquire bug.",
        )
        val resetLogsAfterRace = logLines.count { it.contains("poison_state_reset_on_start") }
        assertEquals(
            1,
            resetLogsAfterRace,
            "no second `poison_state_reset_on_start` log MUST be emitted — the cancelled " +
                "start() never reached the reset block. Got $resetLogsAfterRace total " +
                "reset logs (expected 1 from the first start). Any extra means the cancelled " +
                "start()'s body ran past the acquire and reset state.",
        )
        // Also confirm: the post-teardown abort log from round 5
        // MUST NOT be present (we never enter the body now).
        assertFalse(
            logLines.any { it.contains("orchestrator_start_aborted") },
            "no `orchestrator_start_aborted` log MUST be emitted — under round 6 the body never runs " +
                "for a cancelled pre-acquire waiter. The presence of this log would indicate the body " +
                "ran past teardown and aborted at the post-teardown gate (round-5 leftover).",
        )

        // Clean up.
        orch.stop()
        runCurrent()
    }

    @Test
    fun p15_start_after_close_is_terminal_noop_no_new_jobs_spawned() = runTest {
        // Round 5 P1.2 regression pin: `close()` is terminal. A
        // subsequent `start()` MUST NOT spawn new jobs into the
        // cancelled scope; under round 4 it would happily `launch
        // { ... }` and the resulting jobs died immediately, leaving
        // log noise and a misleading `orchestrator_started` trace.
        init()
        val transport = PollLoopCountingTransport(
            sessionScript = { _ ->
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = "",
                    ), "{}", 1L,
                )
            },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestratorWithPollLoops(
            transport = transport, cursor = RecordingCursorRepo(),
            scheduler = testScheduler, longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        orch.start()
        runCurrent()
        repeat(5) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
        }
        val pollCountBeforeClose = transport.pollEnterCount

        // Terminal close.
        orch.close()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        // Idempotent: second close is a no-op.
        orch.close()
        runCurrent()

        // Re-arm attempt MUST be a no-op (terminal flag).
        val resetLogsBeforeReArm = logLines.count { it.contains("poison_state_reset_on_start") }
        orch.start()
        runCurrent()
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS * 50L)
        runCurrent()

        val resetLogsAfterReArm = logLines.count { it.contains("poison_state_reset_on_start") }
        assertEquals(
            resetLogsBeforeReArm,
            resetLogsAfterReArm,
            "start() after close() MUST NOT log `poison_state_reset_on_start`. " +
                "Got before=$resetLogsBeforeReArm, after=$resetLogsAfterReArm.",
        )
        assertTrue(
            logLines.any { it.contains("orchestrator_start_skipped") && it.contains("reason=closed") },
            "start() after close() MUST log `orchestrator_start_skipped reason=closed`. " +
                "Captured logs:\n${logLines.takeLast(10).joinToString("\n")}",
        )
        // No new poll calls happened — proves no new pollJob /
        // wsActivePollJob was spawned.
        assertEquals(
            pollCountBeforeClose,
            transport.pollEnterCount,
            "start() after close() MUST NOT spawn new poll jobs. " +
                "Got pollEnterCount before=$pollCountBeforeClose after=${transport.pollEnterCount}.",
        )

        // stop() after close() is also a no-op (idempotent).
        orch.stop()
        runCurrent()
    }

    /**
     * Transport that returns empty poll responses + counts poll
     * calls; used by the real two-loop tests below. Distinct from
     * the verify-path [VerifyTransport] which fails poll on entry.
     */
    private class PollLoopCountingTransport(
        var sessionScript: (callIndex: Int) -> RestFallbackResponse<AuthSessionResponse>,
    ) : RestFallbackTransport {
        val ackCalls: MutableList<String> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        var pollEnterCount: Int = 0
        private val counterMutex: Mutex = Mutex()
        override suspend fun authSession(url: String, body: AuthSessionRequest): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return sessionScript(authCalls.size - 1)
        }
        override suspend fun send(url: String, token: String, idempotencyKey: String, body: SendRequest): RestFallbackResponse<SendResponse> = fail("send unexpected")
        override suspend fun poll(url: String, token: String, sinceSeq: Long?, longPollOptIn: Boolean, readTimeoutMs: Long?): RestFallbackResponse<PollResponse> {
            counterMutex.withLock { pollEnterCount += 1 }
            return RestFallbackResponse(200, PollResponse(envelopes = emptyList(), more = false), "{}", 1L)
        }
        override suspend fun ackDeliver(url: String, token: String, body: AckDeliverRequest): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return RestFallbackResponse(200, AckDeliverResponse(ok = 1), "{}", 1L)
        }
    }

    private fun buildOrchestratorWithPollLoops(
        transport: RestFallbackTransport,
        cursor: LongPollCursorRepository?,
        scheduler: TestCoroutineScheduler,
        longPollEnabled: Boolean = true,
        logSink: (String) -> Unit = {},
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = IDENTITY,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        log = logSink,
        longPollEnabled = longPollEnabled,
        cursorRepository = cursor,
        dispatcher = StandardTestDispatcher(scheduler),
    )

    // ── M-B21 — verify failure does NOT advance the cursor (debug hold note) ─

    @Test
    fun mb21_mac_mismatch_does_not_advance_cursor_or_call_ack() = runTest {
        // The debug `holdMacFailures` hold path is OWNED by
        // `DefaultMessagingService` (downstream of the orchestrator
        // emit). At the orchestrator-side, the load-bearing
        // invariant is: a MAC mismatch produces NO emit, NO ack
        // call to the relay, and NO cursor write. The downstream
        // hold logic preserves the relay copy by skipping
        // `sendDeliveryAck` — the orchestrator's own
        // `ackInboundAndAdvanceCursor` is never called for this
        // envelope from the dedup path.
        init()
        val rootKey = ByteArray(32)
        val derivedKey = deriveVerifyKey(rootKey, IDENTITY)
        val keyHex = derivedKey.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHex,
                ), "{}", 1L,
            )
        })
        val cursor = RecordingCursorRepo()
        val orch = buildOrchestrator(transport, cursor, testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(1L, "env-mb21", 60_000L, "0".repeat(64))
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")

        assertNull(orch.peekPendingSeqForAckForTest("env-mb21"))
        assertEquals(0, transport.ackCalls.size, "no ack call on MAC mismatch")
        assertEquals(0, cursor.writes.size, "no cursor write on MAC mismatch")
    }

    // ── M-B22 — concurrent same envelope_id: serial counter + single latch ──

    @Test
    fun mb22_concurrent_same_envelope_id_from_both_loops_serialises_counter_and_latch() = runTest {
        // Both loops fail-verify the same envelope_id concurrently.
        // The `_inboundStateMutex` discipline serialises the counter
        // increment + latch check. Counter goes 1, 2; latch
        // admits exactly one refresh.
        init()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = HEX_KEY_A,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)
        val authBefore = transport.authCalls.size

        val env = makeEnvelopeWithSeqMac(1L, "env-concurrent", 60_000L, "0".repeat(64))
        val loopA = async { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "pollLoop") }
        val loopB = async { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "wsActivePollLoop") }
        listOf(loopA, loopB).awaitAll()

        // Counter incremented exactly twice (once per loop).
        assertEquals(2, orch.peekMacFailCountForTest("env-concurrent"))
        // Latch fired exactly once (the second fail set the latch
        // BEFORE either could trigger a second refresh).
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.Completed, orch.peekMacRefreshStatusForTest("env-concurrent"))
        // Exactly ONE refresh call landed on authSession (the L7
        // step 3 refresh).
        assertEquals(authBefore + 1, transport.authCalls.size, "exactly one refresh under concurrent contention")
    }

    // ── M-B23 — concurrent breaker open from both loops converges ───────────

    @Test
    fun mb23_concurrent_breaker_open_from_both_loops_converges_on_SuspendedOnPoison() = runTest {
        // Two loops simultaneously hit the "repeat after refresh"
        // condition. `_breakerState` transitions to
        // SuspendedOnPoison; the second loop also writes
        // SuspendedOnPoison (idempotent), no torn intermediate
        // state visible.
        init()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = HEX_KEY_A,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(1L, "env-mb23", 60_000L, "0".repeat(64))
        // Pre-seed: trigger the latched refresh.
        repeat(2) { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test") }
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.Completed, orch.peekMacRefreshStatusForTest("env-mb23"))

        // Now two concurrent post-refresh failures from both loops.
        val loopA = async { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "pollLoop") }
        val loopB = async { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "wsActivePollLoop") }
        listOf(loopA, loopB).awaitAll()

        assertEquals(
            LongPollBreakerState.SuspendedOnPoison,
            orch.peekBreakerStateForTest(),
            "concurrent post-refresh failures converge on SuspendedOnPoison",
        )
    }

    // ── M-B25 — refresh-vs-poll race: verify uses snapshot, not torn read ───

    @Test
    fun mb25_verify_uses_snapshotted_key_not_torn_read_under_concurrent_refresh() = runTest {
        // The verify path snapshots the verify-key state under
        // `_inboundStateMutex`, releases, then verifies against the
        // snapshot. A concurrent state flip (KeyPresent(hexA) →
        // KeyPresent(hexB)) cannot torn-read the in-flight verify.
        //
        // We model this by:
        //   1. Setting state to KeyPresent(hexA).
        //   2. Building an envelope whose MAC was computed against hexA.
        //   3. Concurrently launching the verify AND a state flip to KeyPresent(hexB).
        //   4. Asserting the verify outcome depends on which snapshot
        //      was taken at the verify's lock-acquire moment.
        //
        // Under the StandardTestDispatcher's deterministic ordering,
        // if the verify enters its mutex critical section before
        // the state flip, the snapshot is hexA and the envelope
        // verifies (MAC was computed against hexA + key). If the
        // flip races ahead, the snapshot is hexB and the envelope
        // fails (MAC computed against hexA + key, but verify uses
        // hexB + key — mismatch).
        //
        // Crucially, the outcome MUST NOT be "torn" — i.e., the
        // verify must NEVER use half-hexA half-hexB. We assert
        // this property by running many concurrent iterations and
        // checking that EVERY iteration's outcome is either
        // "verified against the original snapshotted key" or
        // "failed against a fresh snapshotted key", with no torn
        // results in between.
        init()
        val rootKey = ByteArray(32)
        val derivedKeyA = deriveVerifyKey(rootKey, IDENTITY)
        val keyAHex = derivedKeyA.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyAHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val verifySuccesses = Mutex()
        var verified = 0
        var failed = 0

        // C4 review-fix round 2 (P2.4) — drive REAL refresh-induced
        // flips via `acquireOrRefreshToken(forceRefresh = true)`,
        // not the test seam `setVerifyKeyStateForTest`. The session
        // script alternates the returned `seqMacVerifyKey` between
        // hexA and hexB on each refresh call so that every refresh
        // actually publishes a new state through the L2 classifier
        // + lock-order discipline.
        //
        // This exercises:
        //   * L2 transition through the production code path.
        //   * L6 lock order (`tokenMutex` outer / `_inboundStateMutex`
        //     inner) under concurrent verify pressure.
        //   * The snapshot-then-verify invariant that prevents torn
        //     hex reads across the rotation.
        var nextRefreshKeyIsB = true
        transport.sessionScript = { _ ->
            val keyHexNow = if (nextRefreshKeyIsB) HEX_KEY_B else keyAHex
            nextRefreshKeyIsB = !nextRefreshKeyIsB
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyHexNow,
                ), "{}", 1L,
            )
        }

        // C4 review-fix (P2.5): scope locks 100 × 100 stress (100
        // concurrent verify-vs-flip races, 100 refresh-induced
        // flips). Bumped to 100 × 100, wrapped in `withTimeout` so
        // a deadlock surfaces by name rather than wedging gradle.
        // The flips now go through `acquireOrRefreshToken` (real
        // refresh publication path), NOT the test seam — so the
        // test exercises full lock-order discipline.
        withTimeout(60_000L) {
            val ops = (0 until 100).map { i ->
                val env = makeVerifiedEnvelope(IDENTITY, (i + 1).toLong(), "env-mb25-$i", 60_000L, derivedKeyA)
                launch {
                    val emitted = orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
                    verifySuccesses.withLock {
                        if (emitted) verified += 1 else failed += 1
                    }
                }
            }

            // 100 refresh-induced flips. Each `forceRefresh = true`
            // call walks `tokenMutex` outer + `_inboundStateMutex`
            // inner and publishes a new `VerifyKeyState` via the
            // production classifier + transition path.
            val flips = (0 until 100).map {
                launch {
                    orch.acquireOrRefreshToken(reason = "mb25_stress", forceRefresh = true)
                }
            }

            runCurrent()
            ops.forEach { it.join() }
            flips.forEach { it.join() }
        }

        // Defined outcome for every operation. A torn read would
        // throw inside SeqMacVerifier (hex-decode failure or HMAC
        // primitive throw); crashes propagate through `launch`
        // and would manifest as the count being less than 100.
        assertEquals(
            100,
            verified + failed,
            "every concurrent verify operation must produce a defined outcome (no torn read crash, no operation lost). " +
                "Got verified=$verified failed=$failed sum=${verified + failed}",
        )
    }

    // ── M-B25 — C4 review-fix round 3 P2 — deterministic snapshot proofs ────

    @Test
    fun mb25_barrier_verify_alone_under_held_mutex_snapshots_original_key_and_verifies() = runTest {
        // C4 review-fix round 3 (P2): the 100x100 stochastic stress
        // test above proves "no torn crash" but does NOT prove the
        // snapshot semantic (that verify reads the key value at its
        // own lock-acquire moment, not at queue-time or after a later
        // flip). On `StandardTestDispatcher`, all 100 verify jobs are
        // dispatched before any refresh job; verify has no suspension
        // point between dispatch and its mutex critical section, so
        // every verify acquires the mutex BEFORE the first refresh
        // even starts. The stochastic outcome carries no information
        // about snapshot vs torn-read discipline.
        //
        // This barrier test makes the snapshot semantic explicit and
        // deterministic. Setup:
        //   1. State = KeyPresent(hexA).
        //   2. Envelope MAC'd against hexA.
        //   3. Hold `_inboundStateMutex` externally.
        //   4. Launch verify INSIDE the held block; verify suspends
        //      at its `_inboundStateMutex.withLock` (queue position 1).
        //   5. Release holder.
        //   6. Verify resumes, snapshots KeyPresent(hexA), HMAC'd
        //      envelope passes → returns true.
        //
        // No second waiter is queued, so there is no flip to race
        // against; this isolates the "no other waiter" arm and pins
        // the happy-path snapshot semantic.
        init()
        val rootKey = ByteArray(32)
        val derivedKeyA = deriveVerifyKey(rootKey, IDENTITY)
        val keyAHex = derivedKeyA.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyAHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)
        assertEquals(VerifyKeyState.KeyPresent(keyAHex), orch.peekVerifyKeyStateForTest())

        val env = makeVerifiedEnvelope(IDENTITY, 1L, "env-mb25-barrier-a", 60_000L, derivedKeyA)

        var verifyOutcome: Boolean? = null
        orch.withInboundStateMutexHeldForTest {
            launch {
                verifyOutcome = orch.processInboundEnvelopeWithVerifyForTest(
                    env, "tok", "test-barrier-a",
                )
            }
            // Drain to verify's suspension on the held mutex. After
            // this `runCurrent` returns, verify is parked on the
            // `_inboundStateMutex.withLock` waiter queue.
            runCurrent()
        }
        // Mutex released. Verify resumes, snapshots state under its
        // own withLock acquisition, releases, then HMAC-checks.
        runCurrent()

        assertEquals(
            true,
            verifyOutcome,
            "verify acquires the mutex AFTER the holder releases (no other waiters queued), " +
                "snapshots KeyPresent(hexA), and hexA-MAC'd envelope verifies",
        )
    }

    @Test
    fun mb25_barrier_flip_queued_before_verify_makes_verify_snapshot_fresh_key_and_fail() = runTest {
        // C4 review-fix round 3 (P2): the load-bearing barrier
        // assertion. `_inboundStateMutex` is held externally; we
        // queue a state flip FIRST, then queue the verify. FIFO
        // mutex resume after the holder releases:
        //
        //   1. Flip resumes first → publishes KeyPresent(hexB) under
        //      its own `_inboundStateMutex.withLock`.
        //   2. Verify resumes second → its
        //      `_inboundStateMutex.withLock` acquires AFTER the flip
        //      has committed, snapshots the FRESH KeyPresent(hexB),
        //      and the hexA-MAC'd envelope fails HMAC under hexB.
        //
        // The fail outcome is the DETERMINISTIC proof of the
        // snapshot-at-lock-acquire semantic: if verify had snapshotted
        // at queue-time (an erroneous semantic) it would have read
        // hexA and incorrectly passed; if verify had torn-read (the
        // other erroneous semantic) the HMAC primitive's behaviour
        // is undefined and the count assertion at the bottom would
        // not be a defined boolean.
        init()
        val rootKey = ByteArray(32)
        val derivedKeyA = deriveVerifyKey(rootKey, IDENTITY)
        val keyAHex = derivedKeyA.toLowerHex()
        val transport = VerifyTransport(sessionScript = {
            RestFallbackResponse(
                200, AuthSessionResponse(
                    token = "tok", expiresAt = Long.MAX_VALUE,
                    restFallback = true, maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1, pollHoldSecs = 30,
                    seqMacVerifyKey = keyAHex,
                ), "{}", 1L,
            )
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)
        assertEquals(VerifyKeyState.KeyPresent(keyAHex), orch.peekVerifyKeyStateForTest())

        val env = makeVerifiedEnvelope(IDENTITY, 1L, "env-mb25-barrier-b", 60_000L, derivedKeyA)

        var verifyOutcome: Boolean? = null
        orch.withInboundStateMutexHeldForTest {
            // Queue position 1 — the flip. Uses the test seam
            // `setVerifyKeyStateForTest` which itself enters
            // `_inboundStateMutex.withLock` and so will queue
            // behind the externally-held mutex.
            launch {
                orch.setVerifyKeyStateForTest(VerifyKeyState.KeyPresent(HEX_KEY_B))
            }
            runCurrent()
            // Queue position 2 — the verify. Suspends at its own
            // `_inboundStateMutex.withLock` snapshot acquire, BEHIND
            // the flip per FIFO.
            launch {
                verifyOutcome = orch.processInboundEnvelopeWithVerifyForTest(
                    env, "tok", "test-barrier-b",
                )
            }
            runCurrent()
        }
        // Mutex released. FIFO resume:
        //   1. Flip wakes, publishes KeyPresent(HEX_KEY_B).
        //   2. Verify wakes, snapshots fresh KeyPresent(HEX_KEY_B);
        //      hexA-MAC'd envelope fails under hexB.
        runCurrent()

        assertEquals(
            false,
            verifyOutcome,
            "FIFO mutex resume after holder release: flip queued first publishes KeyPresent(HEX_KEY_B) " +
                "BEFORE verify acquires; verify queued second snapshots the fresh key and the hexA-MAC'd " +
                "envelope fails. This is the deterministic proof of snapshot-at-lock-acquire semantics " +
                "(neither queue-time snapshot nor torn read across the rotation).",
        )
        // Post-condition: the published state is the fresh one. The
        // peek confirms the flip actually committed, ruling out
        // "verify ran first by accident" as a path to the false
        // outcome.
        assertEquals(
            VerifyKeyState.KeyPresent(HEX_KEY_B),
            orch.peekVerifyKeyStateForTest(),
            "post-barrier state must be the flipped key, proving the flip ran ahead of the verify",
        )
    }

    // ── C4 review-fix P1.1 — refresh latch race regression pin ───────────────

    @Test
    fun p11_inflight_refresh_does_not_falsely_trigger_SuspendedOnPoison_on_third_fail() = runTest {
        // C4 review-fix P1.1: the original implementation set the
        // refresh-attempted latch BEFORE the refresh actually
        // completed. A third bad-MAC arriving while the refresh
        // was still in flight saw the latch and (incorrectly)
        // transitioned the breaker to SuspendedOnPoison.
        //
        // The fix replaces the boolean latch with a three-state
        // status: NotAttempted / InFlight / Completed. Suspension
        // fires ONLY on Completed. While InFlight, additional
        // failures just log (the in-flight refresh may yet recover
        // the verify-key state).
        //
        // We model this by delaying the second authSession call
        // (the refresh triggered at threshold) via a deferred. The
        // third fail arrives BEFORE the refresh completes; the
        // breaker MUST stay Closed.
        init()
        val refreshUnblock = CompletableDeferred<Unit>()
        var callIndex = 0
        val transport = VerifyTransport(sessionScript = {
            val idx = callIndex
            callIndex += 1
            if (idx == 0) {
                // Bootstrap.
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = HEX_KEY_A,
                    ), "{}", 1L,
                )
            } else {
                // Refresh — block until the test releases.
                refreshUnblock.await()
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = HEX_KEY_A,
                    ), "{}", 1L,
                )
            }
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(1L, "env-p11", 60_000L, "0".repeat(64))
        // First fail: count=1, NotAttempted, justLog.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.NotAttempted, orch.peekMacRefreshStatusForTest("env-p11"))

        // Second fail: count=2, threshold, status → InFlight,
        // refresh fires. But because authSession awaits
        // refreshUnblock, the refresh hangs. We launch the second
        // fail in a coroutine so the test can proceed.
        val secondFailJob = launch {
            orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        }
        runCurrent()
        // The refresh is in flight; status should be InFlight.
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.InFlight, orch.peekMacRefreshStatusForTest("env-p11"))

        // Third fail arrives while refresh is in flight. The
        // status is InFlight → JustLog. Breaker MUST stay Closed.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(
            LongPollBreakerState.Closed,
            orch.peekBreakerStateForTest(),
            "Third bad-MAC during InFlight refresh MUST NOT prematurely suspend",
        )

        // Release the refresh; secondFailJob completes; status → Completed.
        refreshUnblock.complete(Unit)
        runCurrent()
        secondFailJob.join()
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.Completed, orch.peekMacRefreshStatusForTest("env-p11"))

        // NOW a fresh fail (post-Completed) DOES suspend.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        assertEquals(
            LongPollBreakerState.SuspendedOnPoison,
            orch.peekBreakerStateForTest(),
            "Fail AFTER refresh Completed must suspend",
        )
    }

    // ── C4 review-fix round 2 P1.2 — refresh cancellation rolls back ─────────

    @Test
    fun p12_refresh_cancellation_rolls_status_back_to_NotAttempted() = runTest {
        // C4 review-fix round 2 P1.2: a CancellationException
        // arriving during the L7-triggered refresh MUST roll the
        // `_macRefreshStatus` entry back to `NotAttempted` so the
        // next bad-MAC cycle retries the refresh. If the cancel
        // left the status pinned at `InFlight` or accidentally
        // promoted it to `Completed`, the L7 step 4 trigger
        // ("repeat-after-refresh → suspend") would either never
        // fire (InFlight) or fire incorrectly on the next single
        // failure (Completed).
        init()
        val refreshSuspends = CompletableDeferred<Unit>()
        var refreshCallCount = 0
        val transport = VerifyTransport(sessionScript = { idx ->
            if (idx == 0) {
                // Bootstrap.
                RestFallbackResponse(
                    200, AuthSessionResponse(
                        token = "tok", expiresAt = Long.MAX_VALUE,
                        restFallback = true, maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1, pollHoldSecs = 30,
                        seqMacVerifyKey = HEX_KEY_A,
                    ), "{}", 1L,
                )
            } else {
                // The triggered refresh suspends indefinitely; we
                // cancel the calling coroutine to exercise the
                // rollback path. The deferred is never completed.
                refreshCallCount += 1
                refreshSuspends.await()
                error("unreached")
            }
        })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        val env = makeEnvelopeWithSeqMac(1L, "env-p12-cancel", 60_000L, "0".repeat(64))
        // First fail: count=1.
        orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        // Second fail triggers refresh that will hang.
        val secondFail = launch {
            orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test")
        }
        runCurrent()
        assertEquals(
            RestFallbackOrchestrator.MacRefreshStatus.InFlight,
            orch.peekMacRefreshStatusForTest("env-p12-cancel"),
        )

        // Cancel the second-fail coroutine while refresh is suspended.
        secondFail.cancel()
        runCurrent()

        // Rollback: status returns to NotAttempted (no Completed).
        assertEquals(
            RestFallbackOrchestrator.MacRefreshStatus.NotAttempted,
            orch.peekMacRefreshStatusForTest("env-p12-cancel"),
            "cancellation during the refresh MUST roll status back to NotAttempted; " +
                "got ${orch.peekMacRefreshStatusForTest("env-p12-cancel")}",
        )
        // Breaker not suspended; the refresh did NOT get to count
        // as a "Completed" attempt.
        assertEquals(LongPollBreakerState.Closed, orch.peekBreakerStateForTest())
    }

    // ── C4 review-fix P1.3 — start() resets poison state ─────────────────────

    @Test
    fun p13_start_resets_breakerState_macFailCount_macRefreshStatus_to_initial() = runTest {
        // C4 review-fix P1.3: `stop()` + `start()` must reset the
        // L7 poison surface. Scope L9: "Stage 2B-B does NOT persist
        // breaker state across orchestrator lifecycles ...
        // SuspendedOnPoison reset on next start(), no persistence."
        init()
        val transport = VerifyTransport(sessionScript = { SESSION_RESPONSE_OK_WITH_KEY_A })
        val orch = buildOrchestrator(transport, RecordingCursorRepo(), testScheduler)
        bootstrapped(orch)

        // Simulate prior poison-state.
        val env = makeEnvelopeWithSeqMac(1L, "env-p13", 60_000L, "0".repeat(64))
        repeat(2) { orch.processInboundEnvelopeWithVerifyForTest(env, "tok", "test") }
        // Force the breaker into SuspendedOnPoison via test seam to
        // simulate a finalised poison cycle.
        orch.setBreakerStateForTest(LongPollBreakerState.SuspendedOnPoison)

        // Sanity check the pre-restart state.
        assertEquals(LongPollBreakerState.SuspendedOnPoison, orch.peekBreakerStateForTest())
        assertTrue(orch.peekMacFailCountForTest("env-p13") >= 2)
        assertEquals(RestFallbackOrchestrator.MacRefreshStatus.Completed, orch.peekMacRefreshStatusForTest("env-p13"))

        // stop() + start() resets.
        orch.stop()
        orch.start()
        runCurrent()

        // All three poison-surface fields reset to initial values.
        assertEquals(
            LongPollBreakerState.Closed,
            orch.peekBreakerStateForTest(),
            "start() must reset _breakerState to Closed",
        )
        assertEquals(
            0,
            orch.peekMacFailCountForTest("env-p13"),
            "start() must clear _macFailCount",
        )
        assertEquals(
            RestFallbackOrchestrator.MacRefreshStatus.NotAttempted,
            orch.peekMacRefreshStatusForTest("env-p13"),
            "start() must clear _macRefreshStatus",
        )

        orch.stop()
    }
}
