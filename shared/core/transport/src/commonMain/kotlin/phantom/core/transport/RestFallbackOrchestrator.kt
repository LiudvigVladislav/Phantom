// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Suppress("unused")
    private val longPollEnabled: Boolean = false,
    dispatcher: CoroutineContext = Dispatchers.Default,
) {
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

    private var sessionToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private var pollJob: Job? = null
    private var aliveTickJob: Job? = null
    private var stateObserverJob: Job? = null

    private var lastInboundOrSendAtMs: Long = 0L

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
        log("REST_TRACE orchestrator_started")
    }

    /**
     * Cancel all background jobs (poll loop, alive tick, state observer).
     * Idempotent. The orchestrator can be re-armed by another [start] call.
     */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        aliveTickJob?.cancel(); aliveTickJob = null
        stateObserverJob?.cancel(); stateObserverJob = null
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
        var lastSeenSeq: Long? = null
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
            log("REST_TRACE poll_call since_seq=${lastSeenSeq ?: -1L} mode=$pollMode")
            val startMs = now()
            val outcome = runCatching {
                transport.poll(url = "$baseUrl/relay/poll", token = token, sinceSeq = lastSeenSeq)
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
                        lastInboundOrSendAtMs = now()
                        lastSeenSeq = env.seq
                        _inbound.emit(env)
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
            return@withLock null
        }
        sessionToken = response.token
        tokenExpiresAt = response.expiresAt
        _capabilities.value = response.toCapabilities()
        log(
            "REST_TRACE token_cached reason=$reason " +
                "expiresInMs=${response.expiresAt - now()} " +
                "rest_fallback=${response.restFallback}",
        )
        response.token
    }

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
