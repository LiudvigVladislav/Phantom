// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * High-level lifecycle owner for the REST short-poll fallback transport — PR-D1.
 *
 * Responsibilities:
 *   1. Bootstrap a bearer token via `POST /auth/session` (one round-trip on
 *      cold start; subsequent refreshes near token expiry).
 *   2. Surface server-advertised [RelayCapabilities]. Stay completely dormant
 *      when the relay does not advertise REST support — the existing WS-only
 *      mode is unchanged.
 *   3. Own the [RestStateMachine] and forward orchestrator-level events
 *      (mode transitions, alive-tick) into it.
 *   4. Run an adaptive poll loop (`GET /relay/poll`) only while the state
 *      machine is in [RestMode.RestActive] or [RestMode.WsCandidate]. Stop
 *      polling immediately on [RestMode.WsActive].
 *   5. Provide a retry-safe [sendEnvelope] for outbound traffic in REST
 *      mode (5 attempts, backoff `1/3/8/20/60 s`, same Idempotency-Key
 *      across all retries — the server dedupes on the relay side per
 *      PR-D0r design).
 *   6. Provide [ackInbound] for the recipient client to confirm a polled
 *      envelope has been decrypted AND saved locally (same discipline
 *      as the PR-V0b voice ACK).
 *
 * Threading: all coroutine work runs on the provided [dispatcher] (default
 * [Dispatchers.Default]). The state machine itself is single-threaded; the
 * orchestrator serialises event submission onto the same context.
 *
 * Wire-up (PR-D1b, follow-up): a thin layer above the orchestrator will
 * subscribe to [state], translate poll-derived [PollEnvelope]s into the
 * messaging service's existing inbound pipeline, and forward
 * [RestStateMachine.Event]s from the WS layer. This PR ships the
 * orchestrator code; the wire-up arrives separately so PR-D1's diff
 * stays focused.
 */
class RestFallbackOrchestrator(
    private val baseUrl: String,
    private val identityHex: String,
    private val signingPubkeyHex: String,
    private val getChallenge: suspend (identityHex: String) -> String,
    private val signChallenge: suspend (challengeBytes: ByteArray) -> ByteArray,
    private val transport: RestFallbackTransport,
    private val now: () -> Long = { defaultNowMs() },
    private val log: (String) -> Unit = {},
    // PR-WS-HEALTH-STATE1 Commit 3.2a (architect P2-2, 2026-06-01):
    // forwarded to [RestStateMachine] so the AppContainer wire-up can
    // mirror REST_TRACE mode_switched reasons into the
    // [phantom.core.transport.WsDegradationDetector] telemetry stream
    // without parsing log lines. Optional and defaults to no-op.
    private val onModeSwitched: ((from: RestMode, to: RestMode, reason: String) -> Unit)? = null,
    /**
     * Trek 2 Stage 2A (A4) — local SOCKS5 port for the future Reality
     * (Stage 3) and Tor (Stage 4) tunnel paths. When non-null, the
     * AppContainer wire-up is expected to have constructed [transport]
     * with the same port so the long-poll surface inherits it. Stored
     * here so Stage 2B can read the value (e.g. for diagnostic log
     * fields) without re-deriving it from the transport.
     *
     * Stage 2 Standard mode passes `null` — no behaviour change. The
     * field is unread in this commit; the wire-up + consumption land
     * in later Stage 2A items (A6) and Stage 2B respectively.
     */
    @Suppress("unused")
    private val socksProxyPort: Int? = null,
    /**
     * Trek 2 Stage 2A (A6) — runtime gate that lets the AppContainer
     * wire-up flip every Stage 2B long-poll runtime path on or off
     * from one place without recompiling. Mirrors the existing
     * `DEBUG_RC_DIRECT_ARM` / `DEBUG_RC_DIRECT_HEARTBEAT_ECHO`
     * `BuildConfig.DEBUG && <flag-string> == "1"` idiom.
     *
     * Per Vladislav OQ7 lock 2026-06-09 the Android-side flag is the
     * String `"1"` / `"0"` `buildConfigField`; the AppContainer reads
     * it, computes a Boolean, and passes that Boolean here.
     *
     * Stage 2A stores the value but does NOT consume it at runtime —
     * Stage 2B's `wsActivePollJob`, opt-in header, raised socket
     * timeout, jittered hold consumption, and persisted lastSeenSeq
     * use will all gate on this single Boolean. Release builds always
     * pin the underlying `buildConfigField` to `"0"` (defence in
     * depth) so accidentally promoting Stage 2B runtime behaviour to
     * release cannot happen without a deliberate code change AND a
     * BuildConfig pin flip.
     */
    private val longPollEnabled: Boolean = false,
    /**
     * Trek 2 Stage 2B-B (C3, L4) — full read/write cursor seam used
     * by BOTH REST poll loops as the single source of truth for
     * `since_seq`. Replaces the Stage 2B-A read-only
     * [LongPollCursorReader] parameter; the read-only interface is
     * retained in the codebase for diagnostic / non-orchestrator
     * callers. Optional; tests and legacy callers pass `null` (the
     * default), in which case both loops poll without a `since_seq`
     * parameter (server treats `null` as `since_seq=0`) and the
     * orchestrator's cursor-advance path is a no-op.
     *
     * Writes happen ONLY from inside [ackInboundAndAdvanceCursor]
     * after the relay's `/relay/ack-deliver` has returned 2xx — never
     * from the poll loops directly. The interface itself does not
     * retry; the call site bounds retry via
     * [CURSOR_WRITE_MAX_ATTEMPTS] / [CURSOR_WRITE_RETRY_BACKOFF_MS].
     *
     * Lock L4: both loops share the persisted cursor; the legacy
     * `pollLoop`'s in-memory `lastSeenSeq` variable is decommissioned
     * in Stage 2B-B (OQ-6 LOCK).
     */
    private val cursorRepository: LongPollCursorRepository? = null,
    dispatcher: CoroutineContext = Dispatchers.Default,
) {

    init {
        // Trek 2 Stage 2B-B (C3, L3) — backoff-array contract: there
        // is exactly one wait BETWEEN attempts and NONE after the
        // final attempt. An array mismatch would either skip a
        // backoff (size < N-1) or index out of bounds at the third
        // attempt's pre-delay lookup (size > N-1). Construction-time
        // assertion catches the mismatch on every orchestrator
        // instantiation, including in the test suite.
        check(CURSOR_WRITE_RETRY_BACKOFF_MS.size == CURSOR_WRITE_MAX_ATTEMPTS - 1) {
            "CURSOR_WRITE_RETRY_BACKOFF_MS.size (${CURSOR_WRITE_RETRY_BACKOFF_MS.size}) " +
                "must equal CURSOR_WRITE_MAX_ATTEMPTS - 1 (${CURSOR_WRITE_MAX_ATTEMPTS - 1})"
        }
    }

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Pure state machine. Public so the wire-up layer can observe + submit events. */
    val stateMachine: RestStateMachine = RestStateMachine(
        now = now,
        log = log,
        onModeSwitched = onModeSwitched,
    )

    /** Convenience flow proxying [stateMachine.state]. */
    val state: StateFlow<RestMode> get() = stateMachine.state

    private val _capabilities = MutableStateFlow(RelayCapabilities.SAFE_DEFAULTS)

    /**
     * Server-advertised capabilities snapshot. Defaults to
     * [RelayCapabilities.SAFE_DEFAULTS] (`restFallback=false`) until
     * [bootstrap] succeeds and the relay advertises REST support.
     */
    val capabilities: StateFlow<RelayCapabilities> = _capabilities.asStateFlow()

    private val _inbound = MutableSharedFlow<PollEnvelope>(extraBufferCapacity = 32)

    /**
     * Inbound envelopes received via `/relay/poll`. Wire-up should collect
     * this flow and translate each envelope into the existing messaging
     * pipeline (Deliver → decrypt → persist → [ackInbound]).
     */
    val inbound: SharedFlow<PollEnvelope> = _inbound.asSharedFlow()

    /**
     * Serialises all reads/writes to [sessionToken] and [tokenExpiresAt]
     * across concurrent callers (poll-loop, send retries, migration,
     * ackInbound). Without this, two callers could enter
     * [authSessionOnce] simultaneously, and the server would replace the
     * first-issued token with the second — invalidating the first.
     * Locked PR-D1c.1 (2026-05-17) after Test #50 reproduced this race.
     */
    private val tokenMutex = Mutex()

    /**
     * Trek 2 Stage 2B-B (C3, L6) — orchestrator-scoped mutex that
     * serialises every read-modify-write on the in-memory shared
     * state introduced by Stage 2B-B (and the upcoming C4 verify-key
     * state machine + bad-MAC posture + breaker fields).
     *
     * Owned fields under this mutex (this commit ships the first two;
     * the rest land in C4 / C5):
     *   * [_pendingSeqForAck] — envelope-id → seq mapping populated at
     *     emit time, consumed by [ackInboundAndAdvanceCursor].
     *
     * **Lock order (L6).** `tokenMutex` outer, `_inboundStateMutex`
     * inner. Code that needs both MUST acquire `tokenMutex` first; no
     * call path holds them in the reverse order. The orchestrator's
     * own `acquireOrRefreshToken` already takes `tokenMutex`
     * internally, so callers MUST NOT wrap a call to it in
     * `tokenMutex.withLock { ... }` (Kotlin Mutex is NOT re-entrant
     * and would self-deadlock). Future C4 verify-key state publication
     * runs INSIDE `acquireOrRefreshToken`'s existing critical section.
     *
     * **Discipline.** Network I/O (`acquireOrRefreshToken`,
     * `transport.poll`, `transport.ackDeliver`) and storage I/O
     * (`cursorRepository.upsertLastSeenSeq`) MUST NOT be invoked
     * while holding this mutex — the three-phase pattern in
     * [ackInboundAndAdvanceCursor] is the canonical example.
     */
    private val _inboundStateMutex = Mutex()

    /**
     * Trek 2 Stage 2B-B (C3, L3) — in-memory `envelope_id → (seq, generation)`
     * mapping populated at the orchestrator's emit site BEFORE
     * `_inbound.emit(env)`. The generation token, incremented under
     * [_inboundStateMutex] on every insertion, lets the emit-side
     * cancellation cleanup (see [emitWithCancellationSafeRollback])
     * differentiate "MY suspended attempt was cancelled and the entry
     * is still mine to remove" from "another loop's emit landed and
     * overwrote my entry with their own attempt — leave their mapping
     * intact." Without the generation guard, a cancel-during-emit
     * rollback could delete a concurrent loop's CORRECT mapping for
     * the same envelope id (relay redelivery scenario across the two
     * REST poll loops), and the consumer's ack would not advance the
     * cursor.
     *
     * Consumed exactly once per envelope by
     * [ackInboundAndAdvanceCursor]: read inside `_inboundStateMutex`
     * to snapshot the pending seq, then removed in the cleanup
     * `finally` block regardless of cursor-write success/failure.
     *
     * **Single insertion point.** The map is populated ONLY from the
     * orchestrator's poll loops (legacy [pollLoop] +
     * `wsActivePollLoop`), each via
     * [emitWithCancellationSafeRollback]. The consumer
     * (`HybridRelayTransport.handleRestInbound`) is NOT a co-author —
     * it reads the envelope and eventually triggers
     * `ackInboundAndAdvanceCursor` via `sendDeliveryAck`, which reads
     * (and removes) the entry.
     *
     * The map is purely in-memory: on orchestrator restart it is
     * empty; pending envelopes re-arrive on the next poll. Access is
     * serialised through [_inboundStateMutex].
     */
    private data class PendingEntry(val seq: Long, val generation: Long)
    private val _pendingSeqForAck: MutableMap<String, PendingEntry> = mutableMapOf()

    /**
     * Generation counter for [_pendingSeqForAck] insertions. Always
     * incremented under [_inboundStateMutex] before assignment so
     * every (envelope_id, attempt) pair carries a strictly-increasing
     * token. The token is used by the cancel-safe emit cleanup to
     * detect whether another loop has overwritten the entry; see the
     * [_pendingSeqForAck] kdoc.
     */
    private var _emitGenerationCounter: Long = 0L

    /**
     * Trek 2 Stage 2B-B (C4, L2) — verify-key state machine value.
     * Published from inside [acquireOrRefreshToken]'s `tokenMutex`
     * critical section under [_inboundStateMutex] (L6 lock order:
     * `tokenMutex` outer, `_inboundStateMutex` inner). The poll
     * loops snapshot this value under [_inboundStateMutex], release,
     * and verify against the snapshot — so a refresh-vs-poll race
     * cannot produce a verify call that uses half a stale key and
     * half a fresh one (M-B25).
     *
     * Initial state is [VerifyKeyState.KeyAbsent]; bootstrap is
     * just the first refresh observation, not a special pre-state.
     */
    private var _verifyKeyState: VerifyKeyState = VerifyKeyState.KeyAbsent

    /**
     * Trek 2 Stage 2B-B (C4, L7 step 1) — per-orchestrator-session,
     * per-`envelope_id` MAC verify-fail counter. Increments on every
     * verify-fail outcome (`mac_mismatch` or `no_mac_field` under
     * `KeyPresent`). Resets only on orchestrator restart; the map
     * is purely in-memory.
     *
     * Accessed only under [_inboundStateMutex]. Drives the L7
     * threshold ([MAC_REPEAT_REFRESH_THRESHOLD] = 2) for the
     * one-shot forced refresh AND the subsequent transition to
     * [LongPollBreakerState.SuspendedOnPoison] on a second failure
     * after refresh.
     */
    private val _macFailCount: MutableMap<String, Int> = mutableMapOf()

    /**
     * Trek 2 Stage 2B-B (C4, L7 step 3) — latch set recording each
     * `envelope_id` for which the orchestrator has already attempted
     * a forced session/key refresh under the L7 posture. Subsequent
     * verify failures on the SAME envelope_id do NOT trigger
     * another refresh — they instead transition the breaker to
     * [LongPollBreakerState.SuspendedOnPoison] (step 4).
     *
     * Resets only on orchestrator restart. Accessed only under
     * [_inboundStateMutex]. Shared by BOTH REST poll loops per L6.
     */
    private val _macRefreshAttemptedFor: MutableSet<String> = mutableSetOf()

    /**
     * Trek 2 Stage 2B-B (C4, L9) — current breaker state. Both REST
     * poll loops snapshot this under [_inboundStateMutex] and gate
     * ingestion accordingly. C4 only uses [LongPollBreakerState.Closed]
     * and [LongPollBreakerState.SuspendedOnPoison]; C5 will add the
     * full circuit-breaker `Open` + `HalfOpen` states.
     *
     * Initial state is [LongPollBreakerState.Closed].
     */
    private var _breakerState: LongPollBreakerState = LongPollBreakerState.Closed

    private var sessionToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private var pollJob: Job? = null
    private var aliveTickJob: Job? = null
    private var stateObserverJob: Job? = null

    /**
     * Trek 2 Stage 2B-A (B3, L3) — parallel `/relay/poll` job that
     * runs in addition to the legacy [pollJob], not as a replacement.
     * Lifecycle is tied to [start] / [stop] / [shutdown] of the
     * orchestrator and to the [longPollEnabled] flag — NOT to the
     * WS connection's up/down state. Lock L3: the parallel job
     * continues issuing polls while WS is up so the storage layer's
     * envelope-id dedup keeps the message table consistent without
     * either transport claiming primacy. The Direct WSS fast path
     * stays active.
     */
    private var wsActivePollJob: Job? = null

    private var lastInboundOrSendAtMs: Long = 0L

    /**
     * Trek 2 Stage 2B-A (B3, L5) — session-scoped cache of the
     * relay-advertised `seq_mac_verify_key`. Captured in [bootstrap]
     * from [AuthSessionResponse.seqMacVerifyKey] and re-captured on
     * every subsequent bootstrap (e.g. token rotation). Empty string
     * when the relay does not announce the field — older relays or
     * a Stage 1.x deployment without `RELAY_SEQ_MAC_KEY` provisioned.
     *
     * Stage 2B-A surfaces this value via [seqMacVerifyKey] but does
     * NOT verify any MAC and does NOT advance `since_seq` based on
     * the unverified key (locks L5, L4). Stage 2B-B picks the value
     * up from here without a session-rotation handshake.
     */
    private var _seqMacVerifyKey: String = ""

    /**
     * Trek 2 Stage 2B-A (B3, L5) — read-only access to the cached
     * session verify key. Visible for Stage 2B-B and for behaviour
     * tests; the value is empty until [bootstrap] succeeds against a
     * Stage 1.x-deployed relay.
     */
    val seqMacVerifyKey: String get() = _seqMacVerifyKey

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * One-shot bootstrap: obtain a bearer token from `POST /auth/session`
     * and read advertised capabilities. Safe to call multiple times — each
     * call refreshes the token and re-reads capabilities.
     *
     * Returns the freshly-read [RelayCapabilities]. The caller decides
     * whether to subsequently invoke [start] (only meaningful when
     * `restFallback=true`).
     */
    suspend fun bootstrap(): RelayCapabilities {
        val token = acquireOrRefreshToken(reason = "bootstrap", forceRefresh = true)
        if (token == null) {
            log("REST_TRACE capability_disabled reason=auth_session_failed")
            _capabilities.value = RelayCapabilities.SAFE_DEFAULTS
            return RelayCapabilities.SAFE_DEFAULTS
        }
        val caps = _capabilities.value
        if (caps.restFallback) {
            log(
                "REST_TRACE capability_enabled max_body=${caps.maxSendBodyBytes} " +
                    "poll_max_envelopes=${caps.pollMaxEnvelopes}",
            )
        } else {
            log("REST_TRACE capability_disabled reason=server_field_false_or_absent")
        }
        return caps
    }

    /**
     * Arm the orchestrator: start observing state-machine transitions and
     * react by starting/stopping the poll loop and alive-tick timer.
     * No-op when capabilities advertise `restFallback=false` — keeps the
     * orchestrator dormant under old relays.
     *
     * Safe to call multiple times; existing observers are cancelled and
     * re-created.
     */
    fun start() {
        if (!_capabilities.value.restFallback) {
            log("REST_TRACE orchestrator_start_skipped reason=capability_disabled")
            return
        }
        stop()
        stateObserverJob = scope.launch {
            stateMachine.state.collect { mode -> onModeChanged(mode) }
        }
        // Trek 2 Stage 2B-A (B3, L3) — spawn the parallel REST poll
        // job iff `LONGPOLL_V2_ENABLED == "1"` (gated through the
        // `longPollEnabled` Boolean computed by the wire-up layer).
        // The job runs in parallel with the legacy `pollJob` AND with
        // the Direct WSS fast path; it never stops on
        // `RestMode.WsActive` (unlike [pollLoop]) — see L3 in
        // `docs/tracks/trek2-stage2b-a-client-shell.md`.
        if (longPollEnabled) {
            wsActivePollJob = scope.launch { wsActivePollLoop() }
            log("REST_TRACE ws_active_poll_started long_poll_enabled=true")
        } else {
            log("REST_TRACE ws_active_poll_skipped long_poll_enabled=false")
        }
        log("REST_TRACE orchestrator_started long_poll_enabled=$longPollEnabled")
    }

    /**
     * Cancel all background jobs (poll loop, alive tick, state observer).
     * Idempotent. The orchestrator can be re-armed by another [start] call.
     */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        aliveTickJob?.cancel(); aliveTickJob = null
        stateObserverJob?.cancel(); stateObserverJob = null
        // Trek 2 Stage 2B-A (B3, L3) — cancel the parallel poll job
        // on every stop. Lifecycle is tied to the orchestrator's own
        // start/stop, not to any WS mode transition.
        wsActivePollJob?.cancel(); wsActivePollJob = null
    }

    /**
     * Fully tear down. After this the orchestrator is dead — construct a
     * fresh instance to re-arm.
     */
    fun close() {
        stop()
        scope.cancel()
    }

    /** Forward an event into the state machine. */
    fun submitEvent(event: RestStateMachine.Event) {
        stateMachine.onEvent(event)
    }

    /**
     * Send one envelope via REST. Used by the caller (typically a
     * HybridRelayTransport wrapper or the messaging service) when the
     * state machine is in [RestMode.RestActive].
     *
     * Behavior:
     *   - Refuses (`SendOutcome.DisabledByCapability`) if capability is off.
     *   - Refuses (`SendOutcome.OversizeBody`) if `payloadBase64` would
     *     produce a request larger than the relay's advertised
     *     `max_send_body_bytes`.
     *   - Uses [envelopeId] as the `Idempotency-Key`, stable across retries.
     *   - Retries up to [SEND_MAX_ATTEMPTS] times with backoff
     *     [SEND_RETRY_DELAYS_MS] on transient failures (SocketTimeout,
     *     status 5xx, status 408/429).
     *   - Returns `Accepted` on 201, `Duplicate` on 200 (server replay),
     *     `Failed` on hard failure.
     */
    suspend fun sendEnvelope(
        // TODO(stage3-migration): ENVELOPE_ID_FULL_RETROFIT — Trek 2
        // Stage 3 migration audit promotes this parameter to
        // `envelopeId: phantom.core.transport.EnvelopeId` after every
        // caller in the messaging module has been verified to source
        // the id from `EnvelopeId.random()` (or `fromWire()` for relay-
        // echoed ids on the inbound replay path) — never from payload
        // hash or ratchet state. Per Vladislav OQ3=C lock 2026-06-09
        // the signature stays `String` in Stage 2A so existing callers
        // compile unchanged.
        envelopeId: String,
        toHex: String,
        payloadBase64: String,
        sequenceTs: Long,
        sealedSenderBase64: String = "",
    ): SendOutcome {
        if (!_capabilities.value.restFallback) {
            return SendOutcome.DisabledByCapability
        }

        val body = SendRequest(
            envelopeId = envelopeId,
            toHex = toHex,
            sealedSenderBase64 = sealedSenderBase64,
            payloadBase64 = payloadBase64,
            sequenceTs = sequenceTs,
        )

        // Soft pre-check — capability cap might be 0 (old relay default)
        // so we only enforce when the relay advertised a positive cap.
        val maxBody = _capabilities.value.maxSendBodyBytes
        if (maxBody > 0) {
            val approxBody = payloadBase64.length + APPROX_SEND_BODY_OVERHEAD_BYTES
            if (approxBody > maxBody) {
                log(
                    "REST_TRACE send_oversize id=${envelopeId.take(8)} " +
                        "bodyBytes=$approxBody max=$maxBody",
                )
                return SendOutcome.OversizeBody(bodyBytes = approxBody, maxBytes = maxBody)
            }
        }

        val url = "$baseUrl/relay/send"
        val totalStart = now()
        var lastStatus: Int? = null
        var lastReason: String = "unknown"
        // PR-D1c.1: acquire the token *inside* the retry loop. Previously
        // it was captured once before the loop, so even when a 401 forced
        // a refresh, subsequent attempts kept using the stale token.
        // Carrying [staleToken] across iterations lets [acquireOrRefreshToken]
        // distinguish "my token went bad, please refresh" from "first attempt"
        // and lets the CAS path re-use a token that a concurrent caller
        // already refreshed.
        var staleToken: String? = null
        for (attempt in 1..SEND_MAX_ATTEMPTS) {
            val token = acquireOrRefreshToken(
                reason = if (staleToken != null) "send_401" else "send",
                staleToken = staleToken,
            ) ?: return SendOutcome.Failed(
                statusCode = null,
                reason = "auth_session_failed",
            )
            staleToken = null

            val approxBody = payloadBase64.length + APPROX_SEND_BODY_OVERHEAD_BYTES
            log(
                "REST_TRACE send_start id=${envelopeId.take(8)} bodyBytes=$approxBody " +
                    "attempt=$attempt/$SEND_MAX_ATTEMPTS",
            )
            val attemptStart = now()
            val outcome = runCatching {
                transport.send(url = url, token = token, idempotencyKey = envelopeId, body = body)
            }
            val attemptElapsed = now() - attemptStart

            if (outcome.isFailure) {
                val ex = outcome.exceptionOrNull()!!
                lastStatus = null
                lastReason = ex::class.simpleName ?: "Exception"
                if (attempt < SEND_MAX_ATTEMPTS) {
                    // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                    // backoff. `next_delay_ms` is the actual wait we will
                    // perform; `nominal_delay_ms` is the un-jittered source
                    // for post-hoc verification of the ±20 % band.
                    //
                    // Rev2 fix (Vladislav P2 on PR #255): jitter computation +
                    // `send_retry` log moved INSIDE the `attempt < SEND_MAX_ATTEMPTS`
                    // check. On `attempt = SEND_MAX_ATTEMPTS` (final failure) the
                    // code now goes straight to `break` and the downstream
                    // `send_fail_giving_up` log records the terminal state. This
                    // keeps `next_delay_ms` semantics honest (= actual wait, never
                    // a phantom value on the giving-up branch) and restores the
                    // `SEND_RETRY_DELAYS_MS` doc claim that `delayForRetry(5)`
                    // is never called.
                    val nominalDelay = delayForRetry(attempt)
                    val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE send_retry id=${envelopeId.take(8)} reason=$lastReason " +
                            "attempt=$attempt next_delay_ms=$jitteredDelay " +
                            "nominal_delay_ms=$nominalDelay elapsedMs=$attemptElapsed",
                    )
                    delay(jitteredDelay)
                    continue
                }
                break
            }

            val response = outcome.getOrThrow()
            lastStatus = response.statusCode
            log(
                "REST_TRACE send_response id=${envelopeId.take(8)} status=${response.statusCode} " +
                    "elapsedMs=${response.elapsedMs}",
            )

            when (response.statusCode) {
                201 -> {
                    lastInboundOrSendAtMs = now()
                    return SendOutcome.Accepted
                }
                200 -> {
                    log("REST_TRACE send_success_dedup id=${envelopeId.take(8)}")
                    lastInboundOrSendAtMs = now()
                    return SendOutcome.Duplicate
                }
                401 -> {
                    // Token rejected by relay. Remember which one was stale
                    // and let acquireOrRefreshToken() decide on the next
                    // iteration: refresh, or CAS-reuse if a concurrent
                    // caller already refreshed.
                    staleToken = token
                    lastReason = "401_token_stale"
                    log(
                        "REST_TRACE send_401_token_stale id=${envelopeId.take(8)} " +
                            "attempt=$attempt",
                    )
                    if (attempt < SEND_MAX_ATTEMPTS) continue else break
                }
                400, 403, 409, 413 -> {
                    lastReason = "non_retryable_status_${response.statusCode}"
                    return SendOutcome.Failed(statusCode = response.statusCode, reason = lastReason)
                }
                in 500..599, 408, 429 -> {
                    lastReason = "retryable_status_${response.statusCode}"
                    if (attempt < SEND_MAX_ATTEMPTS) {
                        // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30):
                        // jittered backoff per the design note shape.
                        val nominalDelay = delayForRetry(attempt)
                        val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE send_retry id=${envelopeId.take(8)} " +
                                "reason=$lastReason attempt=$attempt " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay " +
                                "status=${response.statusCode}",
                        )
                        delay(jitteredDelay)
                        continue
                    }
                    break
                }
                else -> {
                    lastReason = "unexpected_status_${response.statusCode}"
                    if (attempt < SEND_MAX_ATTEMPTS) {
                        // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30):
                        // jittered backoff per the design note shape.
                        val nominalDelay = delayForRetry(attempt)
                        val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE send_retry id=${envelopeId.take(8)} " +
                                "reason=$lastReason attempt=$attempt " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay " +
                                "status=${response.statusCode}",
                        )
                        delay(jitteredDelay)
                        continue
                    }
                    break
                }
            }
        }
        val total = now() - totalStart
        log(
            "REST_TRACE send_fail_giving_up id=${envelopeId.take(8)} " +
                "total_elapsedMs=$total attempts=$SEND_MAX_ATTEMPTS reason=$lastReason",
        )
        return SendOutcome.Failed(statusCode = lastStatus, reason = lastReason)
    }

    /**
     * Confirm an inbound envelope has been fully processed (decrypted +
     * persisted). MUST be called by the wire-up layer ONLY after local
     * persistence succeeds — same pattern as PR-V0b voice ACK.
     */
    suspend fun ackInbound(envelopeId: String): AckOutcome {
        if (!_capabilities.value.restFallback) {
            return AckOutcome.DisabledByCapability
        }
        // PR-D1c.1: deliberately no 401-retry here. If relay rejects the
        // token, the next poll iteration will refresh it via CAS; the
        // server stores the inbound envelope until a successful ack, so
        // re-delivery on the next poll is the self-healing path. A real
        // ack-401-retry can land in PR-D1d if traces show it matters.
        val token = acquireOrRefreshToken(reason = "ack") ?: return AckOutcome.Failed(
            statusCode = null,
            reason = "auth_session_failed",
        )
        val url = "$baseUrl/relay/ack-deliver"
        val body = AckDeliverRequest(id = envelopeId)
        val response = runCatching { transport.ackDeliver(url, token, body) }
        if (response.isFailure) {
            val ex = response.exceptionOrNull()!!
            log("REST_TRACE ack_fail id=${envelopeId.take(8)} reason=${ex::class.simpleName}")
            return AckOutcome.Failed(statusCode = null, reason = ex::class.simpleName ?: "Exception")
        }
        val r = response.getOrThrow()
        log("REST_TRACE ack_sent id=${envelopeId.take(8)} status=${r.statusCode} elapsedMs=${r.elapsedMs}")
        return if (r.statusCode in 200..299) {
            AckOutcome.Acked
        } else {
            AckOutcome.Failed(statusCode = r.statusCode, reason = "status_${r.statusCode}")
        }
    }

    /**
     * Trek 2 Stage 2B-B (C3, L3) — single combined "ack the envelope
     * AND advance the persisted cursor" entry point.
     *
     * Replaces the previous split between [ackInbound] returning
     * `AckOutcome.Acked` and a separate cursor-advance call site at
     * the consumer (`HybridRelayTransport`). The split was structurally
     * unsafe: a coroutine cancellation arriving AFTER `ackInbound`
     * returned `Acked` but BEFORE the consumer's cursor-advance call
     * fired would have left the relay queue cleared, the cursor
     * un-advanced, and [_pendingSeqForAck]`[envelopeId]` leaked until
     * orchestrator restart. The combined method closes this window by
     * owning the entire post-ack lifecycle internally.
     *
     * **Three-phase pattern.** The retry loop ITSELF stays
     * fully cancellable so the caller can abort a slow shutdown; only
     * the cleanup in the outer `finally` block is wrapped in
     * [NonCancellable] so the in-memory [_pendingSeqForAck] entry is
     * always removed under [_inboundStateMutex] regardless of
     * cancellation. Wrapping the entire post-ack region in
     * `withContext(NonCancellable)` (a tempting earlier draft) would
     * have (a) prevented `CancellationException` propagation from
     * `upsert` and `delay`, (b) made `coroutineContext[Job]?.isCancelled`
     * always observe `NonCancellable.isCancelled = false` regardless
     * of the caller's job state, and (c) potentially allowed the
     * function to return successfully after caller cancellation. The
     * corrected pattern keeps the retry loop fully cancellable, wraps
     * ONLY the `finally` cleanup in `NonCancellable`, and tracks
     * cancellation via an explicit `var wasCancelled` flag captured
     * in the OUTER (cancellable) scope — NOT a runtime
     * `coroutineContext[Job]?.isCancelled` query from inside
     * `NonCancellable`.
     *
     * **Cursor-write bounded retry.** Once the relay has 2xx'd the
     * ack, the relay's copy of the envelope is gone — `ReAck` will
     * NOT trigger on a subsequent poll. The cursor write therefore
     * must be retried INSIDE the orchestrator; otherwise a transient
     * SQLCipher failure on the FIRST attempt would silently drop the
     * cursor advance and the entry would leak in
     * [_pendingSeqForAck]. The retry budget is bounded by
     * [CURSOR_WRITE_MAX_ATTEMPTS] = 3 attempts with `delay()` between
     * attempts only, per [CURSOR_WRITE_RETRY_BACKOFF_MS] = `[100, 500]`
     * ms. A structural failure that survives three attempts is
     * logged as `poll_cursor_write_exhausted` and the entry is
     * removed from the map (no leak), but the cursor stays at its
     * pre-failure value; sparse-sequence recovery (M-B26) catches up
     * via the next legitimate envelope arrival.
     *
     * **No-op cases.**
     *   * Returns the ack's outcome unchanged when it is not `Acked`
     *     (network failure, 5xx, DisabledByCapability). The cursor
     *     stays at its current value, the `_pendingSeqForAck` entry
     *     remains for the next ack attempt (the relay still holds
     *     the envelope; the `ReAck` branch will trigger on the next
     *     poll and retry the ack).
     *   * Returns `Acked` when [_pendingSeqForAck] has no entry for
     *     [envelopeId] — the relay ack succeeded but no cursor seq
     *     was registered (e.g. an envelope acked through the legacy
     *     non-poll-loop path before Stage 2B-B wiring). The cursor
     *     stays at its current value; nothing to clean up.
     *
     * **L3 contract on the orchestrator side.**
     *   * Map insertion happens ONLY in the poll loops, BEFORE
     *     `_inbound.emit(env)`, under [_inboundStateMutex] (Step 1 in
     *     the scope-doc). Consumers do NOT populate the map.
     *   * This method is the SOLE consumer that removes the entry.
     *
     * Cells M11 + M-B20 + M-B27 + M-B29 pin this.
     */
    suspend fun ackInboundAndAdvanceCursor(envelopeId: String): AckOutcome {
        // Phase 0 — cancellable: relay ack call.
        val ackOutcome = ackInbound(envelopeId)
        if (ackOutcome !is AckOutcome.Acked) {
            // Ack failed (network, 5xx, capability disabled). Cursor
            // stays; entry stays in _pendingSeqForAck for the next
            // ack attempt. The relay still holds the envelope; the
            // ReAck branch will trigger on the next poll and retry
            // the ack.
            return ackOutcome
        }

        // From here on the relay has CLEARED the envelope from its
        // queue. We MUST remove the _pendingSeqForAck entry regardless
        // of what happens next, including cancellation. The retry
        // loop ITSELF stays cancellable (so the caller can abort a
        // slow shutdown); only the cleanup in `finally` is wrapped
        // in NonCancellable.
        //
        // Critical: there is NO suspension point between the `return`
        // above and the `try { ... }` block. Cancellation cannot leak
        // into the gap. The first suspension point is
        // `_inboundStateMutex.withLock` INSIDE the try; from that
        // point on, `finally` guarantees cleanup. (M-B29 sub-cell (d)
        // verifies the absence of a suspension point structurally.)
        var pendingSeq: Long? = null
        var upsertOk = false
        var wasCancelled = false
        try {
            // Phase 1 — mutex held (cancellable, first suspension point):
            // snapshot the pending seq from the (seq, generation)
            // entry. Read-only; the remove happens in the Phase 3
            // cleanup under the same mutex.
            pendingSeq = _inboundStateMutex.withLock {
                _pendingSeqForAck[envelopeId]?.seq
            }
            if (pendingSeq == null) {
                // The relay ack succeeded but the orchestrator never
                // registered a seq for this envelope (e.g. acked
                // through a non-poll-loop path before Stage 2B-B
                // wiring landed). Cursor stays; nothing to clean up.
                // We fall through to the `finally`, which is now a
                // no-op because `pendingSeq == null` suppresses the
                // exhaustion log AND `_pendingSeqForAck.remove` on a
                // missing key is itself a no-op.
                return ackOutcome
            }

            // Phase 2 — mutex released, bounded retry loop (fully
            // cancellable):
            try {
                for (attemptIdx in 0 until CURSOR_WRITE_MAX_ATTEMPTS) {
                    if (cursorRepository == null) {
                        // No repository wired (legacy / test stub
                        // without storage). Treat as success so the
                        // entry is cleaned up; nothing to write.
                        upsertOk = true
                        break
                    }
                    try {
                        cursorRepository.upsertLastSeenSeq(
                            identityHex = identityHex,
                            seq = pendingSeq!!,
                            nowMs = now(),
                        )
                        upsertOk = true
                    } catch (ce: CancellationException) {
                        // Cancellation MUST propagate. `runCatching`
                        // would have silently caught it and turned
                        // this into `upsertOk = false`. Rethrow to
                        // the outer catch, which sets
                        // `wasCancelled = true` and re-throws,
                        // unwinding through `finally`.
                        throw ce
                    } catch (t: Throwable) {
                        // All other failures (SQLCipher I/O, disk
                        // full, transaction abort, etc.) feed the
                        // retry loop. Log once per attempt for
                        // diagnostic triage.
                        log(
                            "REST_TRACE poll_cursor_write_attempt_fail " +
                                "id=${envelopeId.take(8)} seq=$pendingSeq " +
                                "attempt=${attemptIdx + 1} of=$CURSOR_WRITE_MAX_ATTEMPTS " +
                                "reason=${t::class.simpleName}",
                        )
                    }
                    if (upsertOk) break
                    if (attemptIdx < CURSOR_WRITE_MAX_ATTEMPTS - 1) {
                        // delay() throws CancellationException
                        // naturally; the outer catch tags it as
                        // cancellation.
                        delay(CURSOR_WRITE_RETRY_BACKOFF_MS[attemptIdx])
                    }
                }
            } catch (ce: CancellationException) {
                wasCancelled = true
                throw ce // propagate to outer finally
            }
        } finally {
            // Phase 3 — cleanup. Runs even under cancellation,
            // wrapped narrowly in NonCancellable so the
            // _pendingSeqForAck.remove + the conditional exhaustion
            // log are guaranteed to complete. The retry loop above
            // stays cancellable — only THIS cleanup is non-cancellable.
            withContext(NonCancellable) {
                _inboundStateMutex.withLock {
                    // Remove the entry whether upsert eventually
                    // succeeded OR the local retry budget was
                    // exhausted OR the coroutine was cancelled OR
                    // `pendingSeq == null` (cancellation hit Phase 1
                    // before the snapshot completed). `remove` is
                    // idempotent: a no-op if absent.
                    _pendingSeqForAck.remove(envelopeId)
                    // Log the exhaustion event ONLY when it was a
                    // genuine attempt-budget exhaustion. Three
                    // conditions must hold: the upsert never
                    // succeeded (`!upsertOk`); the coroutine was NOT
                    // cancelled (`!wasCancelled` — the flag is
                    // captured in the OUTER cancellable scope before
                    // entering NonCancellable, so it reflects the
                    // caller's job state, NOT NonCancellable.isCancelled
                    // which is always `false`); and a seq was
                    // actually attempted (`pendingSeq != null`).
                    if (!upsertOk && !wasCancelled && pendingSeq != null) {
                        log(
                            "REST_TRACE poll_cursor_write_exhausted " +
                                "id=${envelopeId.take(8)} seq=$pendingSeq " +
                                "attempts=$CURSOR_WRITE_MAX_ATTEMPTS",
                        )
                    }
                }
            }
        }
        return ackOutcome
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun onModeChanged(mode: RestMode) {
        when (mode) {
            RestMode.WsActive -> {
                // Stop polling + alive tick. Token refresh stays cached
                // for next time the orchestrator needs it.
                pollJob?.cancel(); pollJob = null
                aliveTickJob?.cancel(); aliveTickJob = null
                log("REST_TRACE poll_stopped reason=ws_active")
            }
            RestMode.RestActive, RestMode.WsCandidate -> {
                if (pollJob == null || pollJob?.isActive != true) {
                    pollJob = scope.launch { pollLoop() }
                    log("REST_TRACE poll_started mode=$mode")
                }
                if (mode == RestMode.WsCandidate && (aliveTickJob == null || aliveTickJob?.isActive != true)) {
                    aliveTickJob = scope.launch { aliveTickLoop() }
                }
                if (mode == RestMode.RestActive) {
                    aliveTickJob?.cancel(); aliveTickJob = null
                }
            }
        }
    }

    private suspend fun pollLoop() {
        // Trek 2 Stage 2B-B (C3, L4 + OQ-6 LOCK) — legacy in-memory
        // `lastSeenSeq` decommissioned. Both REST poll loops now read
        // the single source of truth from `cursorRepository` at the
        // start of every iteration. `null` means "no persisted
        // cursor" — wire treats it as `since_seq=0`.
        // PR-D1c.1: same CAS discipline as sendEnvelope — remember the
        // token that just got 401'd, and let acquireOrRefreshToken either
        // refresh it or CAS-reuse what a concurrent caller already
        // refreshed. No more direct writes to sessionToken from here.
        var staleToken: String? = null
        while (scope.isActive) {
            val mode = stateMachine.state.value
            if (mode == RestMode.WsActive) break

            val token = acquireOrRefreshToken(
                reason = if (staleToken != null) "poll_401" else "poll",
                staleToken = staleToken,
            )
            if (token == null) {
                // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                // backoff. This site can herd in recovery when multiple
                // poll iterations all skip-no-token simultaneously.
                val nominalDelay = POLL_BACKOFF_NO_TOKEN_MS
                val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE poll_call_skipped reason=no_token " +
                        "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }
            staleToken = null

            val intervalMs = pollIntervalMs()
            val pollMode = pollMode()
            // L4 + OQ-6 LOCK: read the persisted cursor at the start
            // of every iteration. Both poll loops share this single
            // source of truth; the legacy in-memory `lastSeenSeq`
            // variable is gone.
            //
            // C3 review-fix (P2) — discriminate `NoCursor` vs
            // `ReadFailure`. A transient storage exception must NOT
            // degrade to `since_seq=null` because that re-fetches
            // the entire retention window of envelopes on every
            // iteration. Skip + back off so the next retry can
            // either recover or repeat the same skip safely.
            val lastSeenSeq: Long? = when (val outcome = readCursorSafely(loopTag = "pollLoop")) {
                is CursorReadOutcome.Persisted -> outcome.seq
                CursorReadOutcome.NoCursor -> null
                CursorReadOutcome.ReadFailure -> {
                    val nominalDelay = POLL_FAIL_BACKOFF_MS
                    val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE poll_call_skipped reason=cursor_read_fail " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }
            }
            log("REST_TRACE poll_call since_seq=${lastSeenSeq ?: -1L} mode=$pollMode")
            val startMs = now()
            val outcome = runCatching {
                // Trek 2 Stage 2B-A (B1) — gate the long-poll opt-in pair
                // (`X-Phantom-Long-Poll: 1` + `X-Phantom-Padded-Poll: 1`)
                // on the single `longPollEnabled` Boolean computed by the
                // wire-up layer from `LONGPOLL_V2_ENABLED`. Headers ride
                // ONE flag in this stage; lock L1 forbids LP-alone and
                // PP-alone client postures.
                //
                // Trek 2 Stage 2B-A (B2) — gate the raised OkHttp
                // read / call timeout on `LONGPOLL_V2_ENABLED == "1"`
                // AND `pollHoldSecs in 1..480` (lock L2). The L1 flag
                // alone is not sufficient: an opt-in client whose
                // server has the kill switch on (`pollHoldSecs == 0`)
                // or which advertises a value out of the locked range
                // gets the short-poll timeout — the headers go out,
                // but the budget does not change. The two-condition
                // gate is computed once per call in the companion
                // helper so M2's 9-cell matrix can pin it without
                // standing up an orchestrator.
                transport.poll(
                    url = "$baseUrl/relay/poll",
                    token = token,
                    sinceSeq = lastSeenSeq,
                    longPollOptIn = longPollEnabled,
                    readTimeoutMs = computeLongPollReadTimeoutMs(
                        longPollEnabled = longPollEnabled,
                        pollHoldSecs = _capabilities.value.pollHoldSecs,
                    ),
                )
            }
            val elapsed = now() - startMs

            if (outcome.isFailure) {
                val ex = outcome.exceptionOrNull()!!
                // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                // backoff. `next_delay_ms` is the actual wait;
                // `nominal_delay_ms` is the un-jittered source.
                val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE poll_fail reason=${ex::class.simpleName} elapsedMs=$elapsed " +
                        "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }

            val response = outcome.getOrThrow()
            when (response.statusCode) {
                401 -> {
                    staleToken = token
                    // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                    // backoff. Token-stale on a burst can herd if multiple
                    // poll iterations all 401 at the same moment.
                    val nominalDelay = POLL_FAIL_BACKOFF_MS
                    val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE poll_401_token_stale elapsedMs=$elapsed " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }
                in 200..299 -> {
                    val parsed = response.bodyParsed
                    if (parsed == null || parsed.envelopes.isEmpty()) {
                        log("REST_TRACE poll_empty elapsedMs=$elapsed")
                        delay(intervalMs)
                        continue
                    }
                    for (env in parsed.envelopes) {
                        log(
                            "REST_TRACE poll_received id=${env.id.take(8)} " +
                                "from=${env.fromHex.take(8)} elapsedMs=$elapsed more=${parsed.more}",
                        )
                        // Trek 2 Stage 2B-B (C4, L6 + L7) — verify
                        // the envelope against the snapshotted
                        // verify-key state and gate ingestion on the
                        // outcome. The helper handles all L2 / L6 /
                        // L7 cases including the bad-MAC posture;
                        // `lastInboundOrSendAtMs` is bumped only on
                        // a successful emit (verified path or
                        // KeyAbsent unverified pass-through).
                        val emitted = processInboundEnvelopeWithVerify(
                            env = env,
                            currentToken = token,
                            loopTag = "pollLoop",
                        )
                        if (emitted) {
                            lastInboundOrSendAtMs = now()
                        }
                    }
                    // Drain immediately if server says there's more.
                    if (parsed.more) {
                        delay(POLL_DRAIN_IMMEDIATE_MS)
                        continue
                    }
                    delay(intervalMs)
                }
                else -> {
                    // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                    // backoff. Server-unexpected-status retries can herd
                    // when a transient relay condition rejects many polls.
                    val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                    val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE poll_unexpected_status status=${response.statusCode} " +
                            "elapsedMs=$elapsed " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                }
            }
        }
    }

    private suspend fun aliveTickLoop() {
        while (scope.isActive) {
            delay(CANDIDATE_TICK_MS)
            stateMachine.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
            if (stateMachine.state.value != RestMode.WsCandidate) break
        }
    }

    /**
     * Trek 2 Stage 2B-A (B3, L3) — parallel `/relay/poll` loop that
     * runs alongside the legacy [pollLoop] AND the Direct WSS fast
     * path. The two loops issue independent poll requests; the
     * storage layer's envelope-id dedup keeps the message table
     * consistent.
     *
     * Lifecycle rules pinned by lock L3:
     *   - Spawned from [start] iff [longPollEnabled] is true.
     *   - Cancelled by [stop] / [shutdown] only. It is NOT
     *     state-machine-driven and does NOT exit on
     *     [RestMode.WsActive] (unlike [pollLoop]).
     *   - On token failure, the same `acquireOrRefreshToken` path as
     *     [pollLoop] is reused.
     *
     * Lock L4 — full read/write cursor seam (Stage 2B-B): the loop
     * reads [cursorRepository] each iteration if non-null and passes
     * the value as `since_seq` on the wire. Writes happen ONLY
     * through [ackInboundAndAdvanceCursor] after the relay 2xx's the
     * ack. When [cursorRepository] is null, the loop polls without a
     * `since_seq` parameter (server treats null as `since_seq=0`).
     *
     * Lock L5 — MAC unverified: the loop emits received
     * `PollEnvelope`s to [_inbound] as today; the new `seqMac` field
     * is presence-parsed into the DTO and forwarded unmodified.
     * There is no MAC verification call site on this loop.
     *
     * Cadence: reuses [pollIntervalMs] so the parallel loop matches
     * the legacy active/idle adaptive cadence. Stage 2B-B will
     * replace this with a dedicated long-poll cadence policy.
     */
    private suspend fun wsActivePollLoop() {
        var staleToken: String? = null
        while (scope.isActive) {
            val token = acquireOrRefreshToken(
                reason = if (staleToken != null) "ws_active_poll_401" else "ws_active_poll",
                staleToken = staleToken,
            )
            if (token == null) {
                val nominalDelay = POLL_BACKOFF_NO_TOKEN_MS
                val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE ws_active_poll_call_skipped reason=no_token " +
                        "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }
            staleToken = null

            // L4 + OQ-6 LOCK: both REST poll loops share the
            // persisted cursor via the same `cursorRepository` seam.
            // `null` means "no persisted cursor" — wire treats that
            // as `since_seq=0`. Writes happen ONLY through
            // `ackInboundAndAdvanceCursor` after the relay 2xx's
            // the ack; this loop NEVER writes back from the poll
            // response directly.
            //
            // C3 review-fix (P2) — discriminate `NoCursor` vs
            // `ReadFailure` to avoid blindly polling with
            // `since_seq=null` under a transient storage error.
            // Mirrors the legacy `pollLoop` decision tree.
            val sinceSeq: Long? = when (val outcome = readCursorSafely(loopTag = "wsActivePollLoop")) {
                is CursorReadOutcome.Persisted -> outcome.seq
                CursorReadOutcome.NoCursor -> null
                CursorReadOutcome.ReadFailure -> {
                    val nominalDelay = POLL_FAIL_BACKOFF_MS
                    val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE ws_active_poll_call_skipped reason=cursor_read_fail " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }
            }

            val intervalMs = pollIntervalMs()
            log(
                "REST_TRACE ws_active_poll_call since_seq=${sinceSeq ?: -1L} " +
                    "long_poll_enabled=true",
            )
            val startMs = now()
            val outcome = runCatching {
                // Same L1 + L2 gating as the legacy poll site below —
                // both call sites of `transport.poll(...)` carry the
                // same Stage 2B-A header and timeout invariants.
                transport.poll(
                    url = "$baseUrl/relay/poll",
                    token = token,
                    sinceSeq = sinceSeq,
                    longPollOptIn = longPollEnabled,
                    readTimeoutMs = computeLongPollReadTimeoutMs(
                        longPollEnabled = longPollEnabled,
                        pollHoldSecs = _capabilities.value.pollHoldSecs,
                    ),
                )
            }
            val elapsed = now() - startMs

            if (outcome.isFailure) {
                val ex = outcome.exceptionOrNull()!!
                val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE ws_active_poll_fail reason=${ex::class.simpleName} " +
                        "elapsedMs=$elapsed next_delay_ms=$jitteredDelay",
                )
                delay(jitteredDelay)
                continue
            }

            val response = outcome.getOrThrow()
            if (response.statusCode == 401) {
                staleToken = token
                log(
                    "REST_TRACE ws_active_poll_unauthorised status=401 " +
                        "elapsedMs=$elapsed — will refresh token",
                )
                continue
            }
            if (response.statusCode !in 200..299 || response.bodyParsed == null) {
                val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE ws_active_poll_unexpected_status " +
                        "status=${response.statusCode} elapsedMs=$elapsed " +
                        "next_delay_ms=$jitteredDelay",
                )
                delay(jitteredDelay)
                continue
            }
            val envelopes = response.bodyParsed.envelopes
            log(
                "REST_TRACE ws_active_poll_ok " +
                    "envelopes=${envelopes.size} elapsedMs=$elapsed",
            )
            // Lock L5: emit envelopes to the same downstream as the
            // legacy poll. The new `seqMac` field rides through the
            // DTO unchanged. No MAC verification call site here
            // (lands in C4).
            //
            // L3 Step 1 — single insertion point for the pending-seq
            // mapping, mirrored on this loop. Populated BEFORE
            // emitting so the consumer can rely on
            // `ackInboundAndAdvanceCursor` finding the seq.
            //
            // Trek 2 Stage 2B-B (C4, L6 + L7) — same verify-and-emit
            // gate as the legacy `pollLoop` site above. Both REST
            // poll loops enforce IDENTICAL `seq_mac` verify
            // semantics; the bad-MAC posture's counter / latch /
            // suspension state is shared via [_inboundStateMutex],
            // so a fail observed on one loop drives both loops'
            // ingestion decisions.
            for (env in envelopes) {
                processInboundEnvelopeWithVerify(
                    env = env,
                    currentToken = token,
                    loopTag = "wsActivePollLoop",
                )
            }
            // L4: cursor advance happens inside
            // `ackInboundAndAdvanceCursor` after the relay 2xx's the
            // ack. NOT here from the poll response directly.
            val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4
            val jitteredDelay = (intervalMs * jitterFactor).toLong()
            delay(jitteredDelay)
        }
    }

    /**
     * Polling cadence based on recent traffic activity.
     */
    private fun pollIntervalMs(): Long {
        val sinceLastTraffic = now() - lastInboundOrSendAtMs
        return when {
            sinceLastTraffic < POLL_ACTIVE_WINDOW_MS -> POLL_ACTIVE_MS
            sinceLastTraffic < POLL_IDLE_WINDOW_MS -> POLL_IDLE_MS
            else -> POLL_LONG_IDLE_MS
        }
    }

    private fun pollMode(): String {
        val sinceLastTraffic = now() - lastInboundOrSendAtMs
        return when {
            sinceLastTraffic < POLL_ACTIVE_WINDOW_MS -> "active"
            sinceLastTraffic < POLL_IDLE_WINDOW_MS -> "idle"
            else -> "longidle"
        }
    }

    private fun delayForRetry(attemptIndex: Int): Long {
        // attemptIndex is 1-based. We use [attemptIndex - 1] into the
        // delay table for "wait BEFORE the next attempt".
        val idx = (attemptIndex - 1).coerceIn(0, SEND_RETRY_DELAYS_MS.lastIndex)
        return SEND_RETRY_DELAYS_MS[idx]
    }

    /**
     * Acquire a session bearer token under [tokenMutex], so concurrent
     * callers (poll, send retries, migration, ack) never race to call
     * [authSessionOnce] in parallel.
     *
     * Three paths:
     *   1. **CAS reuse** — caller passed a [staleToken] (their previous
     *      attempt got 401). If `cached != null && cached != staleToken`,
     *      another coroutine already refreshed while this one waited on
     *      the mutex. Return the fresh `cached` token without issuing a
     *      new `/auth/session`. Closes the concurrent-401 pinball:
     *      otherwise two coroutines each forced a refresh, the second
     *      invalidating the first.
     *   2. **Cache hit** — no [staleToken], cached token still valid
     *      (>[TOKEN_REFRESH_LEAD_MS] before expiry). Return it.
     *   3. **Refresh** — cache empty, expired, [forceRefresh] true, or
     *      [staleToken] equals current cached. Call [authSessionOnce]
     *      and update both token + [_capabilities]. Locked PR-D1c.1: also
     *      pushes refreshed capabilities through, so a server-side
     *      runtime change (e.g. relay flipping `restFallback=false`) is
     *      observable at the orchestrator boundary.
     *
     * Visibility: `internal` for unit tests in `commonTest`. Not part of
     * the public KMP transport API.
     */
    internal suspend fun acquireOrRefreshToken(
        reason: String,
        staleToken: String? = null,
        forceRefresh: Boolean = false,
    ): String? = tokenMutex.withLock {
        val cached = sessionToken
        val expiresInMs = tokenExpiresAt - now()

        if (!forceRefresh && staleToken != null && cached != null && cached != staleToken) {
            log("REST_TRACE token_reused reason=${reason}_cas expiresInMs=$expiresInMs")
            return@withLock cached
        }
        if (!forceRefresh && staleToken == null && cached != null && expiresInMs > TOKEN_REFRESH_LEAD_MS) {
            log("REST_TRACE token_reused reason=$reason expiresInMs=$expiresInMs")
            return@withLock cached
        }

        log(
            "REST_TRACE token_refresh_start reason=$reason force=$forceRefresh " +
                "stale=${staleToken != null}",
        )
        val response = authSessionOnce()
        if (response == null) {
            // PR-D1c.1: never leave a known-bad token in cache after a
            // refresh attempt fails. We reach this line only when caller
            // signalled the cached token is no longer trustworthy (force,
            // stale, expired, or empty cache). Keeping the stale entry
            // would let the next caller re-serve a revoked token and
            // produce an infinite 401 loop.
            sessionToken = null
            tokenExpiresAt = 0L
            log("REST_TRACE token_invalidated_after_failed_refresh reason=$reason")
            // Trek 2 Stage 2B-B (C4, L2 + L7 corollary) — publish
            // `RefreshOutcome.Failure` onto the verify-key state
            // machine. From `KeyPresent` this transitions to
            // `KeySuspended` (the locked corollary: no REST
            // ingestion under a stale-or-empty key after a failed
            // refresh); from `KeyAbsent` it stays at `KeyAbsent`
            // (bootstrap asymmetry — no relay observed yet); from
            // `KeySuspended` it stays at `KeySuspended`. L6 lock
            // order: we are INSIDE `tokenMutex`; `_inboundStateMutex`
            // is inner.
            _inboundStateMutex.withLock {
                _verifyKeyState = transition(_verifyKeyState, RefreshOutcome.Failure)
            }
            return@withLock null
        }
        sessionToken = response.token
        tokenExpiresAt = response.expiresAt
        _capabilities.value = response.toCapabilities()
        // Trek 2 Stage 2B-A (B3, L5) — cache the session-scoped
        // `seq_mac_verify_key` for Stage 2B-B to read without
        // a session-rotation handshake. Empty string when the
        // relay does not announce the field (old relay or Stage
        // 1.x deployment without `RELAY_SEQ_MAC_KEY` provisioned).
        // The cache is overwritten on every token refresh.
        _seqMacVerifyKey = response.seqMacVerifyKey
        // Trek 2 Stage 2B-B (C4, L2) — classify the relay-supplied
        // `seq_mac_verify_key` and publish the transition-matrix
        // outcome onto the verify-key state machine. The classifier
        // (Empty / Valid(hex) / Malformed) runs at the publication
        // site, NOT in the relay; the orchestrator never trusts the
        // relay to label the outcome. Same `_inboundStateMutex`
        // serialisation as the failure branch above.
        _inboundStateMutex.withLock {
            val outcome = classifyVerifyKeyResponse(response.seqMacVerifyKey)
            _verifyKeyState = transition(_verifyKeyState, outcome)
        }
        log(
            "REST_TRACE token_cached reason=$reason " +
                "expiresInMs=${response.expiresAt - now()} " +
                "rest_fallback=${response.restFallback}",
        )
        response.token
    }

    /**
     * Trek 2 Stage 2B-B (C3 review-fix round 3) — cancel-safe
     * insertion of `(env.id, env.seq)` into [_pendingSeqForAck]
     * followed by [_inbound] emission. If the emission suspends on
     * backpressure and is cancelled (typically by [stop] tearing
     * down the loop), the mapping is rolled back ONLY when the
     * entry still belongs to this caller (generation token still
     * matches). If a CONCURRENT call (e.g. the other REST poll
     * loop ingesting the same relay-redelivered envelope) overwrote
     * the entry while this caller was suspended in emit, the
     * cleanup detects the generation mismatch and leaves the
     * concurrent caller's mapping intact.
     *
     * Without the generation guard the rollback could delete a
     * legitimate concurrent mapping; the consumer's subsequent ack
     * would find `_pendingSeqForAck[env.id] == null` and return
     * `Acked` WITHOUT advancing the cursor — a silent regression
     * the suspending-emit P1.1 fix did not catch.
     *
     * `CancellationException` rethrows after cleanup so structured
     * concurrency teardown propagates correctly.
     */
    private suspend fun emitWithCancellationSafeRollback(env: PollEnvelope) {
        val myGeneration: Long
        _inboundStateMutex.withLock {
            _emitGenerationCounter += 1
            myGeneration = _emitGenerationCounter
            _pendingSeqForAck[env.id] = PendingEntry(env.seq, myGeneration)
        }
        try {
            _inbound.emit(env)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                _inboundStateMutex.withLock {
                    val current = _pendingSeqForAck[env.id]
                    // Conditional remove: only if entry still
                    // belongs to OUR attempt. A concurrent loop
                    // overwriting with its own attempt produces a
                    // different generation; leave their mapping
                    // intact.
                    if (current != null && current.generation == myGeneration) {
                        _pendingSeqForAck.remove(env.id)
                    }
                }
            }
            throw ce
        }
    }

    /**
     * Trek 2 Stage 2B-B (C4, L6) — per-envelope verify-and-emit
     * decision for BOTH REST poll loops. Returns `true` when the
     * envelope was emitted onto [_inbound] (caller should bump
     * `lastInboundOrSendAtMs`), `false` when the envelope was
     * dropped per the L2/L6/L7 posture.
     *
     * Decision tree (all snapshots taken under [_inboundStateMutex];
     * verify runs against snapshots, NOT live state — closes M-B25):
     *
     *   1. **Breaker `SuspendedOnPoison`** → drop, no emit, no ack,
     *      no cursor. Direct WSS stays operational (M-B15).
     *   2. **Verify-key state `KeySuspended`** → drop, no emit, no
     *      ack, no cursor.
     *   3. **Verify-key state `KeyAbsent`** → legacy unverified
     *      pass-through with structured log `reason=no_verify_key`
     *      (M17). The cursor still advances normally on the
     *      consumer's ack.
     *   4. **Verify-key state `KeyPresent(hex)`** → run the verifier
     *      with the SNAPSHOTTED hex. Outcomes:
     *        * `Verified` → emit; cursor advances on consumer ack.
     *        * `MacMismatch` → L7 bad-MAC posture with reason
     *          `mac_mismatch`.
     *        * `MalformedSeqMac` → L7 bad-MAC posture with reason
     *          `no_mac_field` (covers empty + non-hex + wrong-length).
     */
    private suspend fun processInboundEnvelopeWithVerify(
        env: PollEnvelope,
        currentToken: String,
        loopTag: String,
    ): Boolean {
        val (verifyKeyState, breakerState) = _inboundStateMutex.withLock {
            Pair(_verifyKeyState, _breakerState)
        }

        if (breakerState is LongPollBreakerState.SuspendedOnPoison) {
            log(
                "REST_TRACE inbound_drop_breaker_suspended " +
                    "id=${env.id.take(8)} loop=$loopTag",
            )
            return false
        }

        if (verifyKeyState is VerifyKeyState.KeySuspended) {
            log(
                "REST_TRACE inbound_drop_key_suspended " +
                    "id=${env.id.take(8)} loop=$loopTag",
            )
            return false
        }

        if (verifyKeyState is VerifyKeyState.KeyAbsent) {
            log(
                "REST_TRACE inbound_unverified id=${env.id.take(8)} " +
                    "reason=no_verify_key loop=$loopTag",
            )
            emitWithCancellationSafeRollback(env)
            return true
        }

        // KeyPresent — verify against the snapshotted hex. Per L6,
        // the hex is the value held at the moment of snapshot; a
        // refresh-vs-poll race that flips the state to a different
        // key mid-verify cannot torn-read because the snapshot is
        // immutable. M-B25 pins this.
        val verifyKeyHex = (verifyKeyState as VerifyKeyState.KeyPresent).hex
        val outcome = SeqMacVerifier.verify(
            identityHex = identityHex,
            seq = env.seq,
            envelopeId = env.id,
            sequenceTs = env.sequenceTs,
            seqMacHex = env.seqMac,
            verifyKeyHex = verifyKeyHex,
        )

        return when (outcome) {
            SeqMacVerifier.Outcome.Verified -> {
                log(
                    "REST_TRACE seq_mac_verified id=${env.id.take(8)} " +
                        "seq=${env.seq} loop=$loopTag",
                )
                emitWithCancellationSafeRollback(env)
                true
            }
            SeqMacVerifier.Outcome.MacMismatch -> {
                handleBadMacEnvelope(env, "mac_mismatch", currentToken, loopTag)
                false
            }
            SeqMacVerifier.Outcome.MalformedSeqMac -> {
                handleBadMacEnvelope(env, "no_mac_field", currentToken, loopTag)
                false
            }
        }
    }

    /**
     * Trek 2 Stage 2B-B (C4, L7) — bad-MAC posture handler. Four
     * locked steps per the scope-doc:
     *
     *   1. **Telemetry** — increment [_macFailCount] (under
     *      [_inboundStateMutex]); log
     *      `event=poll_mac_verify_repeat`.
     *   2. **Drop and continue** — caller returns false; no emit,
     *      no ack, no cursor advance.
     *   3. **One forced refresh, latched per envelope_id** — when
     *      the per-id counter reaches
     *      [MAC_REPEAT_REFRESH_THRESHOLD] and the latch
     *      [_macRefreshAttemptedFor] does not already record this
     *      envelope_id, set the latch and call
     *      [acquireOrRefreshToken] with the current token as
     *      `staleToken`. If the refresh itself fails, the L2
     *      corollary (KeyPresent + Failure → KeySuspended)
     *      automatically suspends subsequent ingestion via the
     *      classifier publication path inside
     *      [acquireOrRefreshToken].
     *   4. **Suspend BOTH REST poll loops on repeat-after-refresh**
     *      — if the latch already records this envelope_id, the
     *      current call IS the load-bearing "second failure after
     *      refresh" event: transition the breaker to
     *      [LongPollBreakerState.SuspendedOnPoison] (under the
     *      same `_inboundStateMutex` critical section as the count
     *      increment, so the state is observable atomically by
     *      both loops on their next snapshot).
     */
    private suspend fun handleBadMacEnvelope(
        env: PollEnvelope,
        reason: String,
        currentToken: String,
        loopTag: String,
    ) {
        // Step 1 + Step 4 under a single mutex critical section so
        // concurrent failures on the same envelope_id from both
        // loops cannot race past the latch check.
        val decision: BadMacDecision = _inboundStateMutex.withLock {
            val newCount = (_macFailCount[env.id] ?: 0) + 1
            _macFailCount[env.id] = newCount
            val alreadyLatched = env.id in _macRefreshAttemptedFor
            when {
                alreadyLatched -> {
                    // Step 4: repeat-after-refresh → suspension.
                    _breakerState = LongPollBreakerState.SuspendedOnPoison
                    BadMacDecision(newCount = newCount, action = BadMacAction.Suspend)
                }
                newCount >= MAC_REPEAT_REFRESH_THRESHOLD -> {
                    // Step 3: threshold reached, latch was empty —
                    // set the latch and signal the caller to
                    // trigger the refresh (outside the mutex; the
                    // refresh path takes `tokenMutex` outer).
                    _macRefreshAttemptedFor += env.id
                    BadMacDecision(newCount = newCount, action = BadMacAction.TriggerRefresh)
                }
                else -> {
                    BadMacDecision(newCount = newCount, action = BadMacAction.JustLog)
                }
            }
        }

        // Step 1: telemetry.
        log(
            "REST_TRACE poll_mac_verify_repeat id=${env.id.take(8)} " +
                "count=${decision.newCount} seq=${env.seq} " +
                "reason=$reason loop=$loopTag",
        )

        when (decision.action) {
            BadMacAction.JustLog -> { /* no additional action */ }
            BadMacAction.TriggerRefresh -> {
                // Step 3: forced refresh outside `_inboundStateMutex`
                // because `acquireOrRefreshToken` takes
                // `tokenMutex` outer + `_inboundStateMutex` inner.
                // The refresh response classifier transitions the
                // verify-key state machine onto the published
                // outcome (Valid / Empty / Malformed / Failure).
                acquireOrRefreshToken(
                    reason = "poll_mac_repeat",
                    staleToken = currentToken,
                )
            }
            BadMacAction.Suspend -> {
                log(
                    "REST_TRACE poll_mac_repeat_suspend " +
                        "reason=verify_fail_after_refresh source=$loopTag " +
                        "id=${env.id.take(8)}",
                )
            }
        }
    }

    private data class BadMacDecision(val newCount: Int, val action: BadMacAction)
    private enum class BadMacAction { JustLog, TriggerRefresh, Suspend }

    /**
     * Trek 2 Stage 2B-B (C3 review-fix) — defensive read of
     * [cursorRepository] used by both poll loops at iteration start.
     *
     * Discriminates THREE outcomes so the caller can choose the
     * right next action:
     *
     *   * [CursorReadOutcome.NoCursor] — the repository is unwired
     *     OR has never persisted a value for this identity (cold
     *     start). The poll loop proceeds with `since_seq = null`,
     *     which the server treats as `since_seq = 0`. This is the
     *     legitimate "first run" state, not an error condition.
     *
     *   * [CursorReadOutcome.Persisted] — the read succeeded with a
     *     non-null `seq` value. The poll loop forwards `seq` to the
     *     server.
     *
     *   * [CursorReadOutcome.ReadFailure] — the read threw a
     *     non-cancellation exception (transient SQLCipher I/O, file
     *     lock contention, transaction abort, schema mismatch). The
     *     caller MUST skip this poll iteration and back off rather
     *     than blindly polling with `since_seq = 0`. Polling with
     *     `since_seq = 0` under a transient storage failure would
     *     replay the entire retention window of envelopes back to
     *     the consumer on every iteration until storage recovers —
     *     a duplicate-traffic storm that the dedup ledger absorbs
     *     functionally but that wastes bandwidth and CPU. C3
     *     review-fix (P2) discriminates this case explicitly.
     *
     * `CancellationException` rethrows so structured-concurrency
     * shutdown still tears the loop down cleanly.
     */
    private suspend fun readCursorSafely(loopTag: String): CursorReadOutcome {
        if (cursorRepository == null) return CursorReadOutcome.NoCursor
        return try {
            val persisted = cursorRepository.getLastSeenSeq(identityHex)
            if (persisted == null) {
                CursorReadOutcome.NoCursor
            } else {
                CursorReadOutcome.Persisted(persisted)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            log(
                "REST_TRACE poll_cursor_read_fail loop=$loopTag " +
                    "reason=${t::class.simpleName} skipping_poll_iteration",
            )
            CursorReadOutcome.ReadFailure
        }
    }

    /** Outcome of [readCursorSafely]. See helper kdoc for the three-case discriminator. */
    internal sealed class CursorReadOutcome {
        /** Repository unwired or no value persisted yet — proceed with `since_seq = null`. */
        object NoCursor : CursorReadOutcome()
        /** Successful read with a persisted value. */
        data class Persisted(val seq: Long) : CursorReadOutcome()
        /** Storage read threw — caller MUST skip this poll iteration and back off. */
        object ReadFailure : CursorReadOutcome()
    }

    // ── Test seams (Trek 2 Stage 2B-B C3) ─────────────────────────────────────

    /**
     * Trek 2 Stage 2B-B (C3) — test-only seam that primes
     * [_pendingSeqForAck] directly. Production callers populate the
     * map exclusively from the orchestrator's poll-loop emit sites
     * (`pollLoop` and `wsActivePollLoop` under
     * [_inboundStateMutex]); this helper mirrors that exact
     * operation so commonTest can exercise
     * [ackInboundAndAdvanceCursor] in isolation without driving the
     * full state-machine + poll-loop pipeline.
     *
     * Visibility: `internal` — only the `:shared:core:transport`
     * module's tests can call it. The wire-up layer in
     * `apps/android` cannot reach it; production callers have no
     * legitimate use.
     */
    internal suspend fun primePendingSeqForAckForTest(envelopeId: String, seq: Long) {
        _inboundStateMutex.withLock {
            _emitGenerationCounter += 1
            _pendingSeqForAck[envelopeId] = PendingEntry(seq, _emitGenerationCounter)
        }
    }

    /**
     * Trek 2 Stage 2B-B (C3) — read-only test-only inspection of the
     * pending-seq map. Returns the current seq for [envelopeId] or
     * `null` when absent. Used in tests to assert that
     * [ackInboundAndAdvanceCursor]'s `finally` cleanup removed the
     * entry (or, in the cancellation tests, that no leak survived).
     *
     * Visibility: `internal` — same module-scope constraint as
     * [primePendingSeqForAckForTest].
     */
    internal suspend fun peekPendingSeqForAckForTest(envelopeId: String): Long? =
        _inboundStateMutex.withLock {
            _pendingSeqForAck[envelopeId]?.seq
        }

    /**
     * Trek 2 Stage 2B-B (C3 review-fix round 3) — test-only seam
     * that drives the cancel-safe emit pattern directly. Production
     * callers are the two poll-loop emit sites; commonTest uses
     * this seam to exercise the cancellation-rollback contract
     * without standing up a full poll-loop pipeline.
     */
    internal suspend fun emitWithCancellationSafeRollbackForTest(env: PollEnvelope) {
        emitWithCancellationSafeRollback(env)
    }

    /**
     * Trek 2 Stage 2B-B (C4) — peek the verify-key state machine
     * value under [_inboundStateMutex]. Used by tests to assert
     * publication-site outcomes at acquireOrRefreshToken success +
     * failure branches.
     */
    internal suspend fun peekVerifyKeyStateForTest(): VerifyKeyState =
        _inboundStateMutex.withLock { _verifyKeyState }

    /** Peek breaker state. */
    internal suspend fun peekBreakerStateForTest(): LongPollBreakerState =
        _inboundStateMutex.withLock { _breakerState }

    /** Peek per-envelope MAC verify-fail counter. Returns 0 if absent. */
    internal suspend fun peekMacFailCountForTest(envelopeId: String): Int =
        _inboundStateMutex.withLock { _macFailCount[envelopeId] ?: 0 }

    /** Peek refresh-attempted latch membership. */
    internal suspend fun peekMacRefreshAttemptedForTest(envelopeId: String): Boolean =
        _inboundStateMutex.withLock { envelopeId in _macRefreshAttemptedFor }

    /**
     * Test-only seam that drives the per-envelope verify-and-emit
     * decision directly without standing up a poll loop. Same
     * semantics as `processInboundEnvelopeWithVerify` (the
     * production callers from both REST poll loops).
     */
    internal suspend fun processInboundEnvelopeWithVerifyForTest(
        env: PollEnvelope,
        currentToken: String,
        loopTag: String,
    ): Boolean = processInboundEnvelopeWithVerify(env, currentToken, loopTag)

    /**
     * Test-only seam that publishes the verify-key state machine
     * directly. Mirrors what `acquireOrRefreshToken` does on its
     * publication branches; used by tests to drive the state into
     * `KeyPresent(hex)` / `KeySuspended` / etc. without standing up
     * a session lifecycle.
     */
    internal suspend fun setVerifyKeyStateForTest(state: VerifyKeyState) {
        _inboundStateMutex.withLock {
            _verifyKeyState = state
        }
    }

    /**
     * Test-only seam that publishes the breaker state directly.
     * Used by tests that exercise the `SuspendedOnPoison` gate
     * without driving the bad-MAC posture through the production
     * path.
     */
    internal suspend fun setBreakerStateForTest(state: LongPollBreakerState) {
        _inboundStateMutex.withLock {
            _breakerState = state
        }
    }

    /**
     * Trek 2 Stage 2B-B (C3 review-fix) — test-only seam that holds
     * [_inboundStateMutex] for the duration of [block] so commonTest
     * can deterministically trigger [ackInboundAndAdvanceCursor]
     * suspending at its Phase 1 mutex acquire. Used by M-B29 sub-cell
     * (a) to verify the cancellation-safety property at that
     * suspension point.
     *
     * Visibility: `internal` — only the `:shared:core:transport`
     * module's tests can call it. No production caller has a
     * legitimate use.
     */
    internal suspend fun <T> withInboundStateMutexHeldForTest(block: suspend () -> T): T =
        _inboundStateMutex.withLock { block() }

    /**
     * Public CAS facade for media token acquisition (PR-M1w).
     *
     * Delegates to [acquireOrRefreshToken] with the same CAS semantics:
     * - [staleToken] == null → return cached token if still fresh, else refresh.
     * - [staleToken] != null → if cached != staleToken, another caller already
     *   refreshed; return cached (CAS reuse). Otherwise call /auth/session.
     *
     * Called only by [RestMediaAuthTokenProvider]. Kept as a thin public facade
     * so the internal [acquireOrRefreshToken] method (and the tokenMutex it
     * holds) does not leak across module boundaries.
     */
    suspend fun acquireOrRefreshMediaToken(reason: String, staleToken: String?): String? =
        acquireOrRefreshToken(reason = reason, staleToken = staleToken)

    private suspend fun authSessionOnce(): AuthSessionResponse? {
        log("REST_TRACE session_request identity=${identityHex.take(8)}")
        val challengeHex = runCatching { getChallenge(identityHex) }
            .onFailure { ex ->
                log("REST_TRACE session_challenge_fail reason=${ex::class.simpleName}")
            }
            .getOrNull() ?: return null

        val signatureHex = runCatching {
            val challengeBytes = hexToBytes(challengeHex)
            val sigBytes = signChallenge(challengeBytes)
            bytesToHex(sigBytes)
        }
            .onFailure { ex ->
                log("REST_TRACE session_sign_fail reason=${ex::class.simpleName}")
            }
            .getOrNull() ?: return null

        val body = AuthSessionRequest(
            identityHex = identityHex,
            signingPubkeyHex = signingPubkeyHex,
            challengeHex = challengeHex,
            signatureHex = signatureHex,
        )

        val startMs = now()
        val outcome = runCatching {
            transport.authSession(url = "$baseUrl/auth/session", body = body)
        }
        val elapsed = now() - startMs

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            log("REST_TRACE session_fail reason=${ex::class.simpleName} elapsedMs=$elapsed")
            return null
        }
        val response = outcome.getOrThrow()
        if (response.statusCode !in 200..299 || response.bodyParsed == null) {
            log("REST_TRACE session_fail status=${response.statusCode} elapsedMs=$elapsed")
            return null
        }
        val parsed = response.bodyParsed
        log(
            "REST_TRACE session_response status=${response.statusCode} elapsedMs=$elapsed " +
                "rest_fallback=${parsed.restFallback} max_body=${parsed.maxSendBodyBytes}",
        )
        return parsed
    }

    companion object {
        /**
         * Trek 2 Stage 2B-A (B2) — minimum relay-advertised
         * `pollHoldSecs` value at which the client raises its read /
         * call timeout for `/relay/poll`. Mirrors lock L2 lower bound:
         * the server allows holds in `[1, 480]`, and a value of `0`
         * means the kill switch is active server-side and the client
         * MUST short-poll regardless of the flag.
         */
        const val MIN_POLL_HOLD_SECS: Int = 1

        /**
         * Trek 2 Stage 2B-A (B2) — maximum relay-advertised
         * `pollHoldSecs` value at which the client raises its timeout.
         * Mirrors the server's `MAX_POLL_HOLD_SECS_CAP` constant in
         * `services/relay/src/rest_fallback.rs`. A server advertising
         * a value above this is out of spec; the client falls back to
         * its legacy short-poll timeout rather than honouring the
         * advertised value.
         */
        const val MAX_POLL_HOLD_SECS_CAP: Int = 480

        /**
         * Trek 2 Stage 2B-A (B2) — extra seconds added on top of
         * `pollHoldSecs` to absorb TCP / TLS round-trip variance on
         * the long-poll response without ballooning the hung-request
         * budget on Tele2-class radios. Scope lock L2 pins this margin
         * inside `[2, 8]` seconds; values outside that band require a
         * re-lock. Five sits in the middle of the band and is the
         * shipped value.
         */
        const val POLL_HOLD_SAFETY_MARGIN_SECS: Int = 5

        /**
         * Trek 2 Stage 2B-A (B2) — legacy short-poll OkHttp read /
         * call timeout floor in milliseconds. Single source of truth
         * for the per-call ceilings the Android transport
         * (`AndroidNativeOkHttpRestFallbackTransport`'s
         * `READ_TIMEOUT_MS` and `CALL_TIMEOUT_MS`) applies on the
         * `/relay/poll`, `/relay/send`, `/relay/ack-deliver` and
         * `/auth/session` paths.
         *
         * The override returned by [computeLongPollReadTimeoutMs] is
         * floored at this value: an override that would LOWER the
         * timeout below the legacy floor (which can happen for tiny
         * `pollHoldSecs` values such as `1..4`, where the raw formula
         * `(hold + 5) * 1000 ≤ 9_000` is below the legacy 10_000 ms)
         * is clamped UP so the long-poll path is never less patient
         * than the legacy short-poll path. Override semantics must be
         * strictly monotonic: enabling long-poll can only EXTEND
         * timeouts, never shorten them.
         */
        const val LEGACY_SHORT_POLL_TIMEOUT_MS: Long = 10_000L

        /**
         * Trek 2 Stage 2B-A (B2) — compute the read / call timeout
         * override that the orchestrator passes to
         * [RestFallbackTransport.poll]'s `readTimeoutMs` parameter for
         * a single `/relay/poll` call.
         *
         * Returns the override in milliseconds when BOTH halves of L2
         * hold:
         *
         *   * [longPollEnabled] is `true` (`LONGPOLL_V2_ENABLED == "1"`),
         *     AND
         *   * [pollHoldSecs] is in `[MIN_POLL_HOLD_SECS,
         *     MAX_POLL_HOLD_SECS_CAP]` (inclusive).
         *
         * Returns `null` (legacy short-poll timeout) when either half
         * fails. `null` is the byte-identical-with-Stage-1 default
         * that the legacy `transport.poll(...)` call uses when the
         * parameter is omitted.
         *
         * The override value is
         * `maxOf((pollHoldSecs + POLL_HOLD_SAFETY_MARGIN_SECS) * 1000,
         *        LEGACY_SHORT_POLL_TIMEOUT_MS)`.
         *
         * The `maxOf` floor is load-bearing: for tiny `pollHoldSecs`
         * values (`1..4`) the raw formula yields `6_000..9_000` ms,
         * which is BELOW the legacy short-poll budget — without the
         * floor a flag-on client would be LESS patient than a legacy
         * short-poll client. L2's intent is that long-poll can only
         * lift budgets, never shorten them. The floor enforces that
         * monotonicity at the gate's only mathematical entry point.
         *
         * Pure function, no I/O — kept in the companion so M2's
         * matrix can hit it without standing up an orchestrator.
         */
        fun computeLongPollReadTimeoutMs(
            longPollEnabled: Boolean,
            pollHoldSecs: Int,
        ): Long? {
            if (!longPollEnabled) return null
            if (pollHoldSecs !in MIN_POLL_HOLD_SECS..MAX_POLL_HOLD_SECS_CAP) return null
            val candidateMs = (pollHoldSecs + POLL_HOLD_SAFETY_MARGIN_SECS).toLong() * 1000L
            return maxOf(candidateMs, LEGACY_SHORT_POLL_TIMEOUT_MS)
        }

        /**
         * Max attempts for a single [sendEnvelope] call. Five gives the
         * Tele2 middlebox five randomly-distributed windows to let a POST
         * succeed before we surface failure.
         */
        const val SEND_MAX_ATTEMPTS: Int = 5

        /**
         * Backoff between retry attempts. Indexed by (attempt - 1) so the
         * delay BEFORE the 2nd attempt is `[0] = 1s`, etc. Total worst-case
         * elapsed across all 5 attempts ≈ 92 s.
         */
        val SEND_RETRY_DELAYS_MS: LongArray = longArrayOf(
            // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): rebalanced from
            // {1, 3, 8, 20, 60} to {1, 3, 8, 15, 15} to match the new
            // 10 s per-call fail-fast ceilings. `delayForRetry(5)` is
            // never called by the retry-loop contract at :280-:282
            // (`delay(...)` only enters when `attempt < SEND_MAX_ATTEMPTS`),
            // so index [4] is dead — kept at 15_000L for cosmetic
            // consistency with the active tail. Index [3] carries the
            // actual effect (`attempt = 4` fail now waits 15 s instead
            // of 20 s before attempt = 5).
            1_000L, 3_000L, 8_000L, 15_000L, 15_000L,
        )

        /**
         * After this many ms of recent inbound or outbound traffic we
         * regard the orchestrator as "active" and poll aggressively.
         */
        const val POLL_ACTIVE_WINDOW_MS: Long = 30_000L

        /**
         * After this many ms with no traffic we shift to long-idle polling.
         */
        const val POLL_IDLE_WINDOW_MS: Long = 5 * 60_000L

        /** Poll interval when "active" (recent traffic). */
        const val POLL_ACTIVE_MS: Long = 2_000L

        /** Poll interval when "idle" (no traffic 30 s–5 min). */
        const val POLL_IDLE_MS: Long = 5_000L

        /** Poll interval when "long-idle" (no traffic >5 min). */
        const val POLL_LONG_IDLE_MS: Long = 15_000L

        /** Linear backoff on poll error. Coerce-at-least applied to active mode. */
        const val POLL_FAIL_BACKOFF_MS: Long = 5_000L

        /** Tiny delay between drain polls when server says `more:true`. */
        const val POLL_DRAIN_IMMEDIATE_MS: Long = 100L

        /** Backoff when we cannot get a token at all. */
        const val POLL_BACKOFF_NO_TOKEN_MS: Long = 5_000L

        /**
         * Refresh the session token this many ms before its hard expiry to
         * avoid a 401 during a critical send.
         */
        const val TOKEN_REFRESH_LEAD_MS: Long = 5 * 60_000L

        /**
         * Tick interval for the [RestMode.WsCandidate] alive timer. Faster
         * than 60 s so we converge to the commit threshold near 60 s, not
         * "at most 60 + tick" s.
         */
        const val CANDIDATE_TICK_MS: Long = 5_000L

        /**
         * Rough overhead added to `payloadBase64.length` to approximate
         * total wire body size (JSON keys + envelope_id + to + sequence_ts
         * + commas/quotes). Used only for the pre-flight oversize check;
         * the actual wire size is computed at the transport layer.
         */
        const val APPROX_SEND_BODY_OVERHEAD_BYTES: Int = 256

        /**
         * Trek 2 Stage 2B-B (C3, L3) — maximum cursor-write attempts
         * inside [ackInboundAndAdvanceCursor] after the relay's ack
         * has returned 2xx. One nominal + two retries. A SQLCipher
         * failure that survives three attempts is structural (disk
         * full, schema mismatch, file corruption); further retries
         * are a busy-loop and degrade overall ingestion.
         */
        const val CURSOR_WRITE_MAX_ATTEMPTS: Int = 3

        /**
         * Trek 2 Stage 2B-B (C3, L3) — backoff durations BETWEEN
         * cursor-write retry attempts. Indexed by `attemptIdx` for
         * waits before attempt `attemptIdx + 1`; `delay()` runs only
         * BETWEEN attempts, NEVER after the final attempt. By
         * contract, `CURSOR_WRITE_RETRY_BACKOFF_MS.size == CURSOR_WRITE_MAX_ATTEMPTS - 1`
         * — asserted in the orchestrator `init` block so a future
         * tuning of one constant without the other surfaces at
         * construction, including in the test suite.
         *
         * Values: tight 100 ms catches transient lock contention; the
         * second 500 ms waits longer for a transient I/O fault to
         * clear. A third attempt with no further wait keeps the
         * overall budget low — total worst-case 600 ms across three
         * attempts.
         */
        val CURSOR_WRITE_RETRY_BACKOFF_MS: LongArray = longArrayOf(100L, 500L)

        /**
         * Trek 2 Stage 2B-B (C4, L7 step 3) — repeat-count threshold
         * that triggers the orchestrator's one-shot forced session/
         * key refresh under the bad-MAC posture. When the per-
         * `envelope_id` MAC verify-fail counter reaches this value,
         * the orchestrator calls `acquireOrRefreshToken(reason =
         * "poll_mac_repeat", staleToken = currentToken)` exactly
         * once per envelope_id per orchestrator-session lifetime
         * (latched via `_macRefreshAttemptedFor`). A subsequent
         * verify failure on the same envelope_id transitions the
         * breaker to [LongPollBreakerState.SuspendedOnPoison].
         *
         * Locked value `2`: one initial failure is data noise; the
         * second is the signal to refresh. Lower (1) would refresh
         * on every transient; higher (3+) lets a stale verify-key
         * leak more bad-MAC outcomes before recovery.
         */
        const val MAC_REPEAT_REFRESH_THRESHOLD: Int = 2

        private fun defaultNowMs(): Long =
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun bytesToHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX_CHARS[v ushr 4]
                out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
            }
            return out.concatToString()
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd-length hex" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = hex[i * 2].digitToInt(16)
                val lo = hex[i * 2 + 1].digitToInt(16)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}

// ── Outcome types ────────────────────────────────────────────────────────────

sealed class SendOutcome {
    /** Status 201 — first delivery, server accepted and queued. */
    object Accepted : SendOutcome()

    /** Status 200 — duplicate idempotency key, server confirms previous delivery. */
    object Duplicate : SendOutcome()

    /** Capability flag is false (old relay) — REST send not attempted. */
    object DisabledByCapability : SendOutcome()

    /** Caller's payload exceeds the relay-advertised body cap. No retry helps. */
    data class OversizeBody(val bodyBytes: Int, val maxBytes: Int) : SendOutcome()

    /** Hard failure after all retries (or non-retryable status). */
    data class Failed(val statusCode: Int?, val reason: String) : SendOutcome()
}

sealed class AckOutcome {
    object Acked : AckOutcome()
    object DisabledByCapability : AckOutcome()
    data class Failed(val statusCode: Int?, val reason: String) : AckOutcome()
}
