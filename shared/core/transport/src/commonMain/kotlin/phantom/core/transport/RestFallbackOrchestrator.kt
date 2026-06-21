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
import phantom.core.crypto.Csprng
import phantom.core.crypto.LibsodiumCsprng

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
     * Trek 2 Stage 2B-B (C5, L9; review-fix P2 surfaced the
     * actual invocation) — telemetry hook fired when the REST
     * poll breaker enters [LongPollBreakerState.Open]. Passed
     * through to [RestStateMachine.onRestPollDegraded] which
     * fires after the
     * `REST_TRACE rest_poll_degraded reason=...` log line on
     * every [RestStateMachine.Event.RestPollDegraded]. Lets the
     * AppContainer wire-up mirror the typed reason
     * ([BreakerOpenReason.ConsecutiveRestFailures] /
     * [BreakerOpenReason.Status410Storm]) into the existing
     * [phantom.core.transport.WsDegradationDetector] stream
     * without parsing log substrings. Optional and defaults to
     * no-op for backward compatibility with existing call sites.
     */
    private val onRestPollDegraded: ((BreakerOpenReason) -> Unit)? = null,
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
     * Trek 2 Stage 2B-B (C6 review-fix round 5 P2) — debug-mode
     * gate for [forceBreakerTripForS6TestTrigger]. Defence-in-depth:
     * the helper itself is public so the Android `AppContainer` can
     * route an ADB-broadcast intent to it without depending on the
     * `internal` visibility (which does not cross Gradle module
     * boundaries), but a release-mode binary that accidentally
     * carried a call site would still no-op because this flag
     * defaults to `false`. The Android wire-up sets it to
     * `phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`;
     * release-mode APKs therefore have a hard `false` from BOTH the
     * BuildConfig pin AND the orchestrator-side gate.
     */
    private val s6DebugTriggerEnabled: Boolean = false,
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
    /**
     * Trek 2 Stage 2B-B (C6, L10, M15) — single CSPRNG source for
     * every jitter draw in the orchestrator. Replaces the
     * pre-C6 `kotlin.random` jitter sites with a uniformly-
     * sampled draw from [Csprng.uniformLong].
     *
     * Defaults to [LibsodiumCsprng] (libsodium-backed `getrandom(2)`
     * on Linux/Android, BCryptGenRandom on Windows desktop). Tests
     * inject a deterministic recording fake so M15 can assert the
     * exact draw count + bucket against a known scenario.
     *
     * Pinned by the M15 grep gate: no legacy RNG reference
     * may remain in this file after Stage 2B-B.
     */
    private val csprng: Csprng = LibsodiumCsprng,
    /**
     * 3.6 Fast REST degradation gate (2026-06-18). Pass-through to the
     * [RestStateMachine] constructor — see the field doc on
     * `RestStateMachine.mode2FastPathEnabled` for the full mechanism.
     *
     * Wired through from `phantom.android.di.AppContainer` based on
     * `BuildConfig.MODE_2_FAST_PATH_ENABLED == "1"` (no
     * `BuildConfig.DEBUG` conjunction). The release-side `"0"` literal
     * pin in `apps/android/build.gradle.kts` is the SOLE production-side
     * guard — production stays off until a separate named PR flips the
     * release literal from `"0"` to `"1"`. Default `false` keeps
     * existing callers and tests source-compatible.
     */
    private val mode2FastPathEnabled: Boolean = false,
    /**
     * R3.6 Sticky-per-route Fast REST degradation gate (2026-06-20).
     * Pass-through to [RestStateMachine]. When `true`, a Mode-2 fast-path
     * transition arms a sticky REST window; only `ws_alive_60s` on a new
     * WS session after a route change clears it.
     *
     * Build-time invariant: requires [mode2FastPathEnabled] to also be `true`
     * (enforced in [RestStateMachine.init]). Wired through from `AppContainer`
     * based on `BuildConfig.MODE_2_STICKY_ENABLED == "1"`. Default `false`.
     */
    private val mode2StickyEnabled: Boolean = false,
) {

    /**
     * Trek 2 Stage 2B-B (C6, L10) — draw a single jitter factor
     * from [Csprng.uniformLong] mapped onto the existing
     * `[0.8, 1.2)` band. Replacement for the pre-C6 jitter
     * idiom that appeared at every backoff site.
     *
     * Discretisation: [JITTER_RESOLUTION] = 10_000 buckets give
     * a step of ~0.00004 (~0.004 % of the multiplier band). That
     * is finer than the ms-resolution `delay(...)` consumer would
     * ever resolve, so the discrete output is operationally
     * indistinguishable from the prior continuous double draw.
     *
     * Pure call (no other side-effect) so the test recording
     * fake can pin every draw site by drawing-call count.
     */
    private fun nextJitterFactor(): Double =
        jitterFactorFor(csprng.uniformLong(JITTER_RESOLUTION.toLong()))

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
        onRestPollDegraded = onRestPollDegraded,
        mode2FastPathEnabled = mode2FastPathEnabled,
        mode2StickyEnabled = mode2StickyEnabled,
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
     *
     * Round 11 (council follow-up) — defence-in-depth against an
     * unbounded-map growth path. Even with the server-side
     * `POLL_MAX_ENVELOPES = 1` contract from Stage 1.x, a malicious
     * relay can drive ~1 fabricated envelope_id per poll. Without a
     * client-side cap, the map grows linearly over the orchestrator
     * lifetime. The map is now bounded at
     * [BAD_MAC_TRACKED_ENVELOPE_CAP] entries with insertion-order
     * eviction of the oldest first-seen envelope_id. The companion
     * [_macRefreshStatus] map is evicted on the same key
     * synchronously so the two maps never disagree about whether an
     * envelope_id is tracked. Eviction never re-arms a suspended
     * breaker — the [LongPollBreakerState.SuspendedOnPoison]
     * transition has already been published by the time eviction
     * matters.
     */
    private val _macFailCount: MutableMap<String, Int> = LinkedHashMap()

    /**
     * Trek 2 Stage 2B-B (C4, L7 step 3 + C4 review-fix) — per-
     * envelope_id refresh attempt status. THREE states differentiate
     * "no refresh attempted yet" from "refresh in flight" from
     * "refresh completed". Only the [MacRefreshStatus.Completed]
     * state allows a subsequent verify failure to transition the
     * breaker to [LongPollBreakerState.SuspendedOnPoison] (step 4).
     *
     * Original C4 design used a `Set<String>` latch that was set
     * BEFORE the refresh fired; a third bad-MAC arriving while the
     * refresh was still in flight saw the latch and incorrectly
     * triggered suspension before the refresh had a chance to
     * recover the verify-key state. The three-state machine closes
     * the gap: `InFlight` drops the envelope (telemetry only) and
     * waits for the refresh to land before counting toward
     * suspension.
     *
     * Resets only on orchestrator restart. Accessed only under
     * [_inboundStateMutex]. Shared by BOTH REST poll loops per L6.
     */
    internal enum class MacRefreshStatus {
        /** No refresh has been attempted for this envelope_id yet. */
        NotAttempted,
        /** Refresh is in flight; subsequent fails drop without suspending. */
        InFlight,
        /** Refresh has completed; the next fail suspends both REST loops. */
        Completed,
    }
    private val _macRefreshStatus: MutableMap<String, MacRefreshStatus> = LinkedHashMap()

    /**
     * Trek 2 Stage 2B-B (C4, L9; extended in C5) — current breaker
     * state. Both REST poll loops snapshot this under
     * [_inboundStateMutex] and gate ingestion accordingly. C4 used
     * only [LongPollBreakerState.Closed] and
     * [LongPollBreakerState.SuspendedOnPoison]; C5 adds the full
     * circuit-breaker [LongPollBreakerState.Open] +
     * [LongPollBreakerState.HalfOpen] states.
     *
     * Initial state is [LongPollBreakerState.Closed]. Reset on
     * every [start] (scope §L9: "the breaker state itself is reset
     * to Closed on the next start() because Stage 2B-B does NOT
     * persist breaker state across orchestrator lifecycles").
     */
    private var _breakerState: LongPollBreakerState = LongPollBreakerState.Closed

    /**
     * Trek 2 Stage 2B-B (C5, L9) — running count of consecutive
     * network-class REST poll failures observed since the last
     * success. Accessed only under [_inboundStateMutex]. Reset on
     * orchestrator restart AND on each successful poll observation
     * (any HTTP response 200-499 / 5xx / IOException paths covered
     * via [recordRestSuccess] / [recordRestFailure]).
     *
     * When the count reaches [BREAKER_CONSECUTIVE_FAIL_THRESHOLD]
     * the breaker transitions to
     * `Open(ConsecutiveRestFailures, _breakerCurrentCooldownMs)`
     * and the counter is reset to 0.
     */
    private var _breakerFailCount: Int = 0

    /**
     * Trek 2 Stage 2B-B (C5 round-4 review-fix P1.2) —
     * monotonically increasing generation token that closes the
     * ABA race the round-3 Boolean `isProbe` could not.
     *
     * The breaker can cycle `Closed → Open → HalfOpen → Closed`
     * in less wall-clock time than a single poll's
     * round-trip. A loop that called [gateBreakerForIteration]
     * in the OLD `Closed` and returned with a response into the
     * NEW `Closed` would, under the previous design, see
     * `_breakerState = Closed` and apply its (stale) result as
     * authoritative.
     *
     * Epoch contract:
     *   * [_breakerEpoch] increments on every state-class
     *     change ([LongPollBreakerState.Closed] ↔
     *     [LongPollBreakerState.Open] ↔
     *     [LongPollBreakerState.HalfOpen] ↔
     *     [LongPollBreakerState.SuspendedOnPoison]). Probe-permit
     *     flip inside `HalfOpen(probeInFlight = false →
     *     probeInFlight = true)` does NOT increment — it is a
     *     gate-time CAS, not a state-class change.
     *   * [gateBreakerForIteration] returns
     *     `Proceed(epoch)` / `Probe(epoch)` carrying the value
     *     observed at gate time.
     *   * [recordRestSuccess] / [recordRestFailure] /
     *     [handle410] take the iteration's epoch and compare it
     *     to [_breakerEpoch] under [_inboundStateMutex]. On
     *     mismatch the call is a full no-op — the response was
     *     issued against a stale lifecycle generation.
     *
     * Accessed only under [_inboundStateMutex].
     */
    private var _breakerEpoch: Long = 0L

    /**
     * Trek 2 Stage 2B-B (C5, L9) — current cooldown for a new
     * [LongPollBreakerState.Open] opening (excluding the
     * [BreakerOpenReason.Status410Storm] reason, which pins to
     * [BREAKER_410_STORM_COOLDOWN_MS]). Starts at
     * [BREAKER_INITIAL_COOLDOWN_MS]; doubles per scope-locked
     * [BREAKER_COOLDOWN_GROWTH_FACTOR] on a failed [HalfOpen] probe;
     * capped at [BREAKER_COOLDOWN_CEILING_MS]; resets to the initial
     * value when the breaker returns to [LongPollBreakerState.Closed]
     * (either via a successful probe or via an
     * [recordRestSuccess] call from a normal Closed-state poll).
     */
    private var _breakerCurrentCooldownMs: Long = BREAKER_INITIAL_COOLDOWN_MS

    /**
     * Trek 2 Stage 2B-B (C5, L9) — breaker cooldown timer.
     * Launched into [scope] when the breaker enters
     * [LongPollBreakerState.Open]; sleeps for the open `cooldownMs`;
     * under [_inboundStateMutex] transitions
     * [LongPollBreakerState.Open] →
     * `HalfOpen(probeInFlight = false)` so the next REST poll
     * iteration on either loop can claim the probe permit.
     *
     * Cancelled and joined as part of [cancelAndJoinAll]. Same
     * lifecycle discipline as [aliveTickJob] from Stage 2A. The
     * job self-terminates after a single tick — no `while` loop.
     */
    private var _breakerTimerJob: Job? = null

    /**
     * Trek 2 Stage 2B-B (C5, L8) — current 410 reauth backoff
     * value. Scope §L8: "Backoff is capped exponential with a
     * 5-second floor and a 60-second ceiling. The first 410 backs
     * off 5 s; each subsequent 410 doubles up to 60 s."
     *
     * Reset to [BREAKER_INITIAL_COOLDOWN_MS] (5 s floor) on every
     * successful (200-OK) poll observation via [recordRestSuccess]
     * AND on orchestrator restart. Doubled on each 410 (capped at
     * [BREAKER_410_STORM_COOLDOWN_MS] = 60 s) inside [handle410].
     */
    private var _current410BackoffMs: Long = BREAKER_INITIAL_COOLDOWN_MS

    /**
     * Trek 2 Stage 2B-B (C5, L8 + L9) — wall-clock timestamps of
     * the recent `410 Gone` poll responses. Used by [handle410] to
     * detect the L9 [BreakerOpenReason.Status410Storm] trigger:
     * [BREAKER_410_STORM_THRESHOLD] = 3 consecutive 410s within
     * [BREAKER_410_STORM_WINDOW_MS] = 30 s.
     *
     * Pruned to entries within the window on every 410 observation.
     * Cleared on every successful poll via [recordRestSuccess] AND
     * on orchestrator restart. Accessed only under
     * [_inboundStateMutex].
     */
    private val _status410StormTimestamps: MutableList<Long> = mutableListOf()

    private var sessionToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private var pollJob: Job? = null
    private var aliveTickJob: Job? = null
    private var stateObserverJob: Job? = null

    /**
     * Trek 2 Stage 2B-B (C4 review-fix round 3 P1.1, round 5 P1.1
     * extended, round 6 P1 split) — serialises the lifecycle
     * methods [start] / [stop] / [close] so a second `start()`
     * cannot run its `cancelAndJoinAll()` while a prior `start()`
     * is still resetting state under [_inboundStateMutex]. The
     * lifecycle mutex is strictly OUTER to [_inboundStateMutex];
     * never acquired the other way round.
     *
     * Round 6 P1: ASYMMETRIC cancellation semantics across the
     * three entry points.
     *
     *   * [stop] / [close] — the ENTIRE mutex hold (including the
     *     acquire itself) runs under [NonCancellable]. A caller
     *     cancelled while waiting for the mutex still gets the
     *     cleanup once the mutex frees. Rationale: "ensure
     *     cleanup even on caller cancel."
     *
     *   * [start] — the acquire is CANCELLABLE. A caller cancelled
     *     BEFORE the mutex is acquired is a true no-op; a
     *     working orchestrator is NOT torn down by a cancelled
     *     re-arm. Once the mutex is held, the
     *     teardown+reset+spawn sequence runs as ONE atomic
     *     `withContext(NonCancellable)` transaction so partial
     *     application is impossible. Rationale: "do not destroy a
     *     working transport on a pre-acquire cancel."
     */
    private val _lifecycleMutex: Mutex = Mutex()

    /**
     * Trek 2 Stage 2B-B (C4 review-fix round 5 P1.2) — terminal
     * flag set by [close]. Subsequent [start] / [stop] calls
     * become no-ops; an unguarded `start()` after `close()` would
     * happily `scope.launch { ... }` into the already-cancelled
     * scope, immediately dying and leaving log noise that misleads
     * the operator into thinking the orchestrator is alive.
     *
     * Read and written ONLY under [_lifecycleMutex]; the lifecycle
     * methods are the sole entry points that observe or mutate it.
     */
    private var _closed: Boolean = false

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
     * session verify key for in-module tests only. The value is empty
     * until [bootstrap] succeeds against a Stage 1.x-deployed relay.
     *
     * Round 13 (Stage 2B-B post-review) — restricted from `public val`
     * to `internal val`. Production Stage 2B-B verification reads the
     * key off [_verifyKeyState] under [_inboundStateMutex] (the C4
     * state-machine path); no production caller needs the bare hex
     * string. Keeping the getter `public` exposed 64 chars of raw
     * per-identity verify-key material to any caller / crash reporter
     * / exception path that incidentally read it, even though no
     * production caller actually did so. `internal` keeps the seam
     * available to `commonTest` (`WsActivePollJobLifecycleTest`)
     * without making it part of the module's public surface.
     */
    internal val seqMacVerifyKey: String get() = _seqMacVerifyKey

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
    suspend fun start() {
        // Trek 2 Stage 2B-B (C4 review-fix round 3 P1.1, extended in
        // round 4 to cover stop/close, round-5 hardening then split
        // in round 6 P1) — serialise the lifecycle reset under
        // [_lifecycleMutex]. The mutex is strictly OUTER to
        // [_inboundStateMutex]; the order is never inverted.
        //
        // Round 6 P1: ASYMMETRIC cancellation semantics between
        // `start()` and `stop()`/`close()`. `start()` re-arms a
        // running orchestrator; if the caller is cancelled BEFORE
        // re-arm actually begins, the right outcome is "do nothing,
        // leave the running orchestrator alone." Under round 5 the
        // acquire ran inside `withContext(NonCancellable)` — a
        // cancelled `start()` waiter would still acquire the mutex,
        // run `cancelAndJoinAll`, reset state, and only THEN skip
        // the spawn. Net effect: the cancellation destroyed a
        // healthy transport.
        //
        // Round 6 resolution: keep the acquire CANCELLABLE for
        // `start()` so a pre-acquire cancel is a true no-op. Once
        // the mutex is held, the teardown+reset+spawn sequence
        // runs under `withContext(NonCancellable)` as ONE atomic
        // transaction — no half-state. After the NonCancellable
        // block exits, the function returns; any pending caller
        // cancellation manifests on the caller side at the next
        // suspension. `stop()` and `close()` retain the round-5
        // shape (full body under NonCancellable) because their
        // contract is "ensure cleanup even on caller cancel."
        _lifecycleMutex.withLock {
            // Round 5 P1.2: terminal-state guard. `start()` after
            // `close()` must NOT spawn new jobs into the cancelled
            // scope.
            if (_closed) {
                log("REST_TRACE orchestrator_start_skipped reason=closed")
                return@withLock
            }
            if (!_capabilities.value.restFallback) {
                log("REST_TRACE orchestrator_start_skipped reason=capability_disabled")
                return@withLock
            }
            // Atomic re-arm transaction. Once we enter this block
            // the orchestrator's teardown+reset+spawn happens
            // together; partial application is impossible.
            withContext(NonCancellable) {
                cancelAndJoinAll()
                _inboundStateMutex.withLock {
                    _breakerState = LongPollBreakerState.Closed
                    _macFailCount.clear()
                    _macRefreshStatus.clear()
                    // Trek 2 Stage 2B-B (C5, L9) — scope §L9: "Stage
                    // 2B-B does NOT persist breaker state across
                    // orchestrator lifecycles." Reset the C5 counters
                    // alongside the C4 poison state.
                    _breakerFailCount = 0
                    _breakerCurrentCooldownMs = BREAKER_INITIAL_COOLDOWN_MS
                    _current410BackoffMs = BREAKER_INITIAL_COOLDOWN_MS
                    _status410StormTimestamps.clear()
                    // Round-4 P1.2: bump the epoch so any
                    // in-flight pre-start iteration's response
                    // (impossible in practice — cancelAndJoinAll
                    // joined them — but defensive) carries a
                    // stale epoch.
                    _breakerEpoch += 1
                }
                log("REST_TRACE poison_state_reset_on_start")
                stateObserverJob = scope.launch {
                    stateMachine.state.collect { mode -> onModeChanged(mode) }
                }
                // Trek 2 Stage 2B-A (B3, L3) — spawn the parallel
                // REST poll job iff `LONGPOLL_V2_ENABLED == "1"`
                // (gated through the `longPollEnabled` Boolean
                // computed by the wire-up layer). The job runs in
                // parallel with the legacy `pollJob` AND with the
                // Direct WSS fast path; it never stops on
                // `RestMode.WsActive` (unlike [pollLoop]) — see L3
                // in `docs/tracks/trek2-stage2b-a-client-shell.md`.
                if (longPollEnabled) {
                    wsActivePollJob = scope.launch { wsActivePollLoop() }
                    log("REST_TRACE ws_active_poll_started long_poll_enabled=true")
                } else {
                    log("REST_TRACE ws_active_poll_skipped long_poll_enabled=false")
                }
                // Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1) —
                // the Tele2 LTE smoke runbook (`trek2-stage2b-b-
                // tele2-smoke.md`) requires the literal substring
                // `LONGPOLL_V2_ENABLED=<0|1>` at orchestrator
                // construction so a logcat grep can prove the
                // BuildConfig flag value the runtime was built with.
                // The Boolean `long_poll_enabled=` field is kept for
                // back-compatible parsers; the literal string form
                // is the smoke-pin shape.
                val longPollV2EnabledFlagValue = if (longPollEnabled) "1" else "0"
                log(
                    "REST_TRACE orchestrator_started long_poll_enabled=$longPollEnabled " +
                        "LONGPOLL_V2_ENABLED=$longPollV2EnabledFlagValue",
                )
            }
        }
    }

    /**
     * Cancel all background jobs (poll loop, alive tick, state
     * observer) AND wait for each to fully unwind. Idempotent. The
     * orchestrator can be re-armed by another [start] call (unless
     * [close] was called — see [_closed]).
     *
     * Trek 2 Stage 2B-B (C4 review-fix round 4 P1, round 5 P1.1
     * hardening) — `stop()` is `suspend` and the ENTIRE mutex hold
     * (including the acquire itself) runs under [NonCancellable].
     * Under round 4 only the body inside the mutex was non-
     * cancellable; a caller cancelled while waiting for the mutex
     * abandoned the cleanup entirely. With NonCancellable around
     * the acquire the cleanup ALWAYS lands once the mutex is
     * eventually free, regardless of caller cancellation state.
     */
    suspend fun stop() {
        withContext(NonCancellable) {
            _lifecycleMutex.withLock {
                // Idempotent: after `close()` everything is already
                // cancelled. Logging here avoids surprising the
                // operator with a "stopped" trace on a dead
                // orchestrator.
                if (_closed) {
                    return@withLock
                }
                cancelAndJoinAll()
            }
        }
    }

    /**
     * Trek 2 Stage 2B-B (C4 review-fix round 2 P1.1, hardened in
     * round 3; C5-C round-2 review-fix P1.2 added the third
     * phase) — cancel every prior job AND wait for it to finish.
     * Used by [start] to guarantee no orphan job can race the
     * lifecycle reset (mutex write to `_breakerState`,
     * `_macFailCount`, `_macRefreshStatus`).
     *
     * Three-phase teardown:
     *
     *   * **Phase 1:** capture, null, cancel, and JOIN
     *     [stateObserverJob]. The observer is the spawning parent
     *     for `pollJob` and `aliveTickJob` (via [onModeChanged]
     *     reacting to state-machine transitions). After Phase 1
     *     returns no further `onModeChanged` callback is possible.
     *   * **Phase 2:** snapshot + cancel + JOIN every POLL
     *     PRODUCER ([pollJob], [aliveTickJob], [wsActivePollJob])
     *     and fully unwind them. C5-C round-2 P1.2: this MUST
     *     finish BEFORE Phase 3. A cancelled poll loop that had
     *     already received a 5xx response BEFORE the cancel
     *     signal arrived can call `recordRestFailure` during its
     *     unwinding, which spawns a fresh `_breakerTimerJob`. The
     *     previous (two-phase) teardown snapshot grabbed
     *     `_breakerTimerJob` at the same instant as the poll
     *     producers; the new timer spawned during their unwinding
     *     was therefore an orphan that outlived `stop()`.
     *   * **Phase 3:** with all poll producers gone, no more
     *     spawn paths into `_breakerTimerJob` exist. Snapshot the
     *     current value (which includes anything the unwinding
     *     poll producers spawned right before terminating),
     *     cancel + JOIN it. Single-pass: read the field
     *     post-Phase-2 and tear down whatever is there.
     *
     * Callers MUST already hold [_lifecycleMutex].
     */
    private suspend fun cancelAndJoinAll() {
        // Phase 1 — stop the spawning parent first.
        val observer = stateObserverJob
        stateObserverJob = null
        if (observer != null) {
            observer.cancel()
            try {
                observer.join()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Diagnostic; join() guarantees coroutine body has
                // fully unwound regardless of completion outcome.
            }
        }
        // Phase 2 — observer is dead; whatever it spawned is now
        // captured by re-reading the fields. Cancel + join the
        // poll producers and let them FULLY UNWIND before moving
        // on to Phase 3 (the breaker timer). A poll loop unwinding
        // here may legitimately call `recordRestFailure` on a 5xx
        // response received before the cancel arrived, which
        // spawns a new `_breakerTimerJob`. We MUST drain that
        // spawn path before snapshotting the timer field.
        val producers = listOfNotNull(pollJob, aliveTickJob, wsActivePollJob)
        pollJob = null
        aliveTickJob = null
        wsActivePollJob = null
        for (job in producers) {
            job.cancel()
        }
        for (job in producers) {
            try {
                job.join()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Diagnostic; same rationale as Phase 1.
            }
        }
        // Phase 3 — poll producers are fully dead and no other
        // spawn paths into `_breakerTimerJob` remain (the timer is
        // spawned exclusively from `transitionToOpenUnderMutex`,
        // which is called from the producers or the timer itself).
        // Re-read the field; whatever value is there is the final
        // one. Cancel + join.
        val timer = _breakerTimerJob
        _breakerTimerJob = null
        if (timer != null) {
            timer.cancel()
            try {
                timer.join()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Diagnostic; same rationale as Phase 1.
            }
        }
    }

    /**
     * Fully tear down. After this the orchestrator is dead — construct a
     * fresh instance to re-arm. Subsequent [start] / [stop] calls
     * become no-ops via the [_closed] terminal flag.
     *
     * Trek 2 Stage 2B-B (C4 review-fix round 4 P1, round 5 P1.1
     * hardening + round 5 P1.2 terminal flag) — `close()` is
     * `suspend`; the ENTIRE mutex hold (including the acquire
     * itself) runs under [NonCancellable]; the [_closed] flag is
     * set BEFORE `scope.cancel()` so a concurrent `start()` that
     * was queued on the same mutex sees the terminal state and
     * skips spawning into a dying scope.
     *
     * Idempotent: a second `close()` is a no-op.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            _lifecycleMutex.withLock {
                if (_closed) {
                    return@withLock
                }
                cancelAndJoinAll()
                _closed = true
                scope.cancel()
            }
        }
    }

    /**
     * Forward an event into the state machine.
     *
     * RC-RECONNECT-QUIESCENCE1 (2026-06-22): SUSPEND so the
     * state-machine's gate-mutating event handlers can acquire their
     * single gateLock for atomic compute-then-publish transitions.
     */
    suspend fun submitEvent(event: RestStateMachine.Event) {
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
                    val jitterFactor = nextJitterFactor()
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
                        // Trek 2 Stage 2B-B (C5, L8 + M-B24) — on 429
                        // the relay may advertise a `Retry-After`
                        // value. Honour it but clamp at
                        // `RETRY_AFTER_HARD_CAP_SECONDS = 120` BEFORE
                        // multiplying by 1_000L (see
                        // `clampRetryAfterMs`). The clamp is
                        // overflow-safe: a hostile relay sending
                        // `Retry-After: 86400` (one day) cannot lock
                        // the client out for a day, and no input
                        // value can overflow `Long` arithmetic.
                        // For 5xx / 408 / 429-without-Retry-After we
                        // fall back to the existing jittered
                        // `delayForRetry(attempt)` budget.
                        val clampedRetryAfterMs = if (response.statusCode == 429) {
                            clampRetryAfterMs(response.retryAfterSeconds)
                        } else {
                            null
                        }
                        val (nominalDelay, jitteredDelay) = if (clampedRetryAfterMs != null) {
                            // Honour `Retry-After` verbatim (post-
                            // clamp). No jitter applied — the relay
                            // has explicitly requested this beat;
                            // herding is not a concern because the
                            // relay's wait suggestion is its scheduling
                            // discipline.
                            clampedRetryAfterMs to clampedRetryAfterMs
                        } else {
                            // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30):
                            // jittered backoff per the design note shape.
                            val nominal = delayForRetry(attempt)
                            val jitterFactor = nextJitterFactor()
                            nominal to (nominal * jitterFactor).toLong()
                        }
                        log(
                            "REST_TRACE send_retry id=${envelopeId.take(8)} " +
                                "reason=$lastReason attempt=$attempt " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay " +
                                "status=${response.statusCode} " +
                                "retry_after_secs=${response.retryAfterSeconds ?: -1L}",
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
                        val jitterFactor = nextJitterFactor()
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
                        val outcome = cursorRepository.upsertLastSeenSeq(
                            identityHex = identityHex,
                            seq = pendingSeq!!,
                            nowMs = now(),
                        )
                        upsertOk = true
                        // Trek 2 Stage 2B-B (C6 review-fix round 2
                        // P1.2) — discriminate `Advanced` from
                        // `NoChange`. The Tele2 smoke runbook treats
                        // `REST_TRACE cursor_advanced seq=<n>` as
                        // PROOF that the persisted row changed —
                        // round-1 emitted it on every successful
                        // return, including the silent-no-op branch
                        // (relay redelivery past the cursor), which
                        // made the smoke evidence unfalsifiable.
                        // After this commit, `cursor_advanced` fires
                        // ONLY on a genuine forward write; the
                        // monotonicity-noop branch emits a distinct
                        // `cursor_noop` line.
                        when (outcome) {
                            is CursorUpsertOutcome.Advanced -> log(
                                "REST_TRACE cursor_advanced seq=${outcome.storedSeq} " +
                                    "id=${envelopeId.take(8)} attempt=${attemptIdx + 1}",
                            )
                            is CursorUpsertOutcome.NoChange -> log(
                                "REST_TRACE cursor_noop existing_seq=${outcome.existingSeq} " +
                                    "rejected_seq=$pendingSeq id=${envelopeId.take(8)} " +
                                    "attempt=${attemptIdx + 1}",
                            )
                        }
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
                val jitterFactor = nextJitterFactor()
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
            // Trek 2 Stage 2B-B (C4 review-fix P1.2, extended in C5,
            // L9) — breaker gate BEFORE `transport.poll(...)`. The
            // C4 gate covered only [LongPollBreakerState.SuspendedOnPoison];
            // C5 adds [LongPollBreakerState.Open] +
            // [LongPollBreakerState.HalfOpen] via
            // [gateBreakerForIteration], which also atomically claims
            // the HalfOpen probe permit under [_inboundStateMutex]
            // when applicable. M-B28 sub-cell (a) pins the
            // permit's exclusivity across the two loops.
            val breakerDecision = gateBreakerForIteration()
            if (breakerDecision is BreakerIterationDecision.Skip) {
                val nominalDelay = POLL_FAIL_BACKOFF_MS
                val jitterFactor = nextJitterFactor()
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE poll_call_skipped reason=${breakerDecision.reason} " +
                        "loop=pollLoop next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }
            val isProbe = breakerDecision is BreakerIterationDecision.Probe
            // Round-4 P1.2: capture the iteration's epoch so the
            // response handlers can detect a stale lifecycle
            // generation.
            val iterationEpoch: Long = when (breakerDecision) {
                is BreakerIterationDecision.Proceed -> breakerDecision.epoch
                is BreakerIterationDecision.Probe -> breakerDecision.epoch
                is BreakerIterationDecision.Skip -> error("unreachable: Skip handled above")
            }
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
                    val jitterFactor = nextJitterFactor()
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE poll_call_skipped reason=cursor_read_fail " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }
            }
            // Trek 2 Stage 2B-B (C5, L9, M-B28 sub-cell (b)) —
            // wrap the transport.poll call + response handling in
            // a try / finally so a mid-flight cancellation on a
            // HalfOpen probe releases the probe permit under
            // `withContext(NonCancellable)`. Non-probe iterations
            // pay no cost beyond the empty finally branch.
            try {
                // Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1
                // headers + P1.2 probe) — Tele2 smoke pin. The
                // `X-Phantom-Long-Poll=...` and `X-Phantom-Padded-
                // Poll=...` literals expose the headers actually
                // emitted on this call so the runbook's grep finds
                // them inside `REST_TRACE` without re-running the
                // request through a packet capture. The `probe=`
                // flag distinguishes the HalfOpen single-probe
                // iteration from regular ones — S6 pass criterion
                // 4 needs to greppably identify the probe.
                val lpHeaderValue = if (longPollEnabled) "1" else "absent"
                val ppHeaderValue = if (longPollEnabled) "1" else "absent"
                // Round 12 step 2 — emit the server-advertised
                // `pollHoldSecs` as a structured field so a logcat
                // grep can answer "was the kill switch on?" without
                // walking back to the `session_request` line. The
                // S6 council on d395f682 found that the observability
                // gap of this single field caused a 30-minute field
                // run to be invalidated post-hoc.
                val holdSecsField = _capabilities.value.pollHoldSecs
                log(
                    "REST_TRACE poll_call since_seq=${lastSeenSeq ?: -1L} mode=$pollMode " +
                        "probe=$isProbe " +
                        "X-Phantom-Long-Poll=$lpHeaderValue X-Phantom-Padded-Poll=$ppHeaderValue " +
                        "hold_secs=$holdSecsField",
                )
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
                    // Trek 2 Stage 2B-B (C5-C review-fix P1.1) —
                    // `runCatching` swallows `CancellationException`
                    // along with everything else. A pollJob cancelled
                    // mid-suspend in `transport.poll(...)` would land
                    // here with `outcome.isFailure = true`, the CE
                    // would be classified as a transport-class
                    // failure, and `recordRestFailure` would
                    // (a) increment `_breakerFailCount` ignorantly
                    // toward the next Open trigger or
                    // (b) on a HalfOpen probe, transition the
                    // breaker BACK to Open and spawn a fresh timer
                    // Job. The new timer Job spawns AFTER the Phase-1
                    // observer cancellation, so the Phase-2 tail
                    // snapshot (`pollJob`, `aliveTickJob`,
                    // `wsActivePollJob`, `_breakerTimerJob`) does NOT
                    // include it ⇒ orphan timer.
                    //
                    // Re-throw CE so the iteration's `finally`
                    // releases the probe permit and the pollLoop
                    // body unwinds cleanly without touching breaker
                    // state.
                    if (ex is CancellationException) {
                        throw ex
                    }
                    // Trek 2 Stage 2B-B (C5, L9) — record network-class
                    // failure for the breaker. May trip Closed → Open
                    // (5th consecutive) OR HalfOpen → Open (failed probe).
                    recordRestFailure(iterationEpoch = iterationEpoch, isProbe = isProbe)
                    // PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): jittered
                    // backoff. `next_delay_ms` is the actual wait;
                    // `nominal_delay_ms` is the un-jittered source.
                    val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                    val jitterFactor = nextJitterFactor()
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE poll_fail reason=${ex::class.simpleName} elapsedMs=$elapsed " +
                            "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }

                val response = outcome.getOrThrow()
                // Trek 2 Stage 2B-B (C5 round-5 review-fix P2) —
                // route through [classifyPollResponse] so both poll
                // loops dispatch on the same enumerated set and a
                // future drift requires an enum edit + audit of
                // both loops.
                when (classifyPollResponse(response.statusCode)) {
                    PollResponseClass.TokenStale -> {
                        // 401 is a token issue, not a transport
                        // failure. The relay answered.
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        staleToken = token
                        val nominalDelay = POLL_FAIL_BACKOFF_MS
                        val jitterFactor = nextJitterFactor()
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE poll_401_token_stale elapsedMs=$elapsed " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                        )
                        delay(jitteredDelay)
                        continue
                    }
                    PollResponseClass.Ok200 -> {
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = true)
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
                            val emitted = processInboundEnvelopeWithVerify(
                                env = env,
                                currentToken = token,
                                loopTag = "pollLoop",
                            )
                            if (emitted) {
                                lastInboundOrSendAtMs = now()
                            }
                        }
                        if (parsed.more) {
                            delay(POLL_DRAIN_IMMEDIATE_MS)
                            continue
                        }
                        delay(intervalMs)
                    }
                    PollResponseClass.RateLimit -> {
                        // 429 — Retry-After dance.
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        val clampedRetryAfterMs = clampRetryAfterMs(response.retryAfterSeconds)
                        val (nominalDelay, effectiveDelay) = if (clampedRetryAfterMs != null) {
                            clampedRetryAfterMs to clampedRetryAfterMs
                        } else {
                            val nominal = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                            val jitterFactor = nextJitterFactor()
                            nominal to (nominal * jitterFactor).toLong()
                        }
                        log(
                            "REST_TRACE poll_429 elapsedMs=$elapsed " +
                                "next_delay_ms=$effectiveDelay nominal_delay_ms=$nominalDelay " +
                                "retry_after_secs=${response.retryAfterSeconds ?: -1L} " +
                                "loop=pollLoop",
                        )
                        delay(effectiveDelay)
                    }
                    PollResponseClass.ServerError -> {
                        // 5xx — transport failure.
                        recordRestFailure(iterationEpoch = iterationEpoch, isProbe = isProbe)
                        val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                        val jitterFactor = nextJitterFactor()
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE poll_5xx status=${response.statusCode} " +
                                "elapsedMs=$elapsed " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                        )
                        delay(jitteredDelay)
                    }
                    PollResponseClass.GoneReauth -> {
                        // 410 — L8 reauth dance + L9 storm detection.
                        val nextDelayMs = handle410(token = token, iterationEpoch = iterationEpoch, isProbe = isProbe, loopTag = "pollLoop")
                        delay(nextDelayMs)
                    }
                    PollResponseClass.Other -> {
                        // 4xx-other — drop + log.
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                        val jitterFactor = nextJitterFactor()
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE poll_unexpected_status status=${response.statusCode} " +
                                "elapsedMs=$elapsed " +
                                "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                        )
                        delay(jitteredDelay)
                    }
                }
            } finally {
                if (isProbe) {
                    // Trek 2 Stage 2B-B (C5, L9, M-B28 sub-cell (b))
                    // — cancellation-safe permit release. Idempotent:
                    // a no-op when the probe already resolved into
                    // Closed (via recordRestSuccess) or Open (via
                    // recordRestFailure). The NonCancellable wrapper
                    // guarantees the reset itself cannot be cancelled
                    // inside its own critical section.
                    withContext(NonCancellable) {
                        releaseProbePermitIfStillHeld(iterationEpoch = iterationEpoch)
                    }
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
                val jitterFactor = nextJitterFactor()
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE ws_active_poll_call_skipped reason=no_token " +
                        "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }
            staleToken = null

            // Trek 2 Stage 2B-B (C4 review-fix P1.2, extended in C5,
            // L9) — same shape as the legacy pollLoop above. The C4
            // gate covered only [LongPollBreakerState.SuspendedOnPoison];
            // C5 adds [LongPollBreakerState.Open] +
            // [LongPollBreakerState.HalfOpen] via
            // [gateBreakerForIteration], which atomically claims the
            // HalfOpen probe permit under [_inboundStateMutex] when
            // applicable. M-B28 sub-cell (a) pins the permit's
            // exclusivity across both loops.
            val breakerDecision = gateBreakerForIteration()
            if (breakerDecision is BreakerIterationDecision.Skip) {
                val nominalDelay = POLL_FAIL_BACKOFF_MS
                val jitterFactor = nextJitterFactor()
                val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                log(
                    "REST_TRACE ws_active_poll_call_skipped reason=${breakerDecision.reason} " +
                        "next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay",
                )
                delay(jitteredDelay)
                continue
            }
            val isProbe = breakerDecision is BreakerIterationDecision.Probe
            // Round-4 P1.2: capture the iteration's epoch.
            val iterationEpoch: Long = when (breakerDecision) {
                is BreakerIterationDecision.Proceed -> breakerDecision.epoch
                is BreakerIterationDecision.Probe -> breakerDecision.epoch
                is BreakerIterationDecision.Skip -> error("unreachable: Skip handled above")
            }
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
                    val jitterFactor = nextJitterFactor()
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
            // Trek 2 Stage 2B-B (C5, L9, M-B28 sub-cell (b)) — wrap
            // the transport.poll call + response handling in a try /
            // finally so a mid-flight cancellation on a HalfOpen
            // probe releases the probe permit under
            // `withContext(NonCancellable)`. Non-probe iterations
            // pay no cost beyond the empty finally branch.
            try {
                // Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1
                // headers + P1.2 probe) — see the matching block in
                // [pollLoop] above for the smoke-pin rationale.
                // `long_poll_enabled=true` is preserved (the parallel
                // loop only spawns when the flag is on, so the
                // literal is constant here) so back-compat parsers
                // see no shape change; the new fields are appended.
                val lpHeaderValue = if (longPollEnabled) "1" else "absent"
                val ppHeaderValue = if (longPollEnabled) "1" else "absent"
                // Round 12 step 2 — emit `hold_secs` on the parallel
                // loop site too. Both poll_call origins carry the same
                // field so a single grep covers both.
                val holdSecsField = _capabilities.value.pollHoldSecs
                log(
                    "REST_TRACE ws_active_poll_call since_seq=${sinceSeq ?: -1L} " +
                        "long_poll_enabled=true " +
                        "probe=$isProbe " +
                        "X-Phantom-Long-Poll=$lpHeaderValue X-Phantom-Padded-Poll=$ppHeaderValue " +
                        "hold_secs=$holdSecsField",
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
                    // Trek 2 Stage 2B-B (C5-C review-fix P1.1) —
                    // see the legacy `pollLoop` site above for the
                    // full rationale. Same orphan-timer risk
                    // applies symmetrically here.
                    if (ex is CancellationException) {
                        throw ex
                    }
                    recordRestFailure(iterationEpoch = iterationEpoch, isProbe = isProbe)
                    val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                    val jitterFactor = nextJitterFactor()
                    val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                    log(
                        "REST_TRACE ws_active_poll_fail reason=${ex::class.simpleName} " +
                            "elapsedMs=$elapsed next_delay_ms=$jitteredDelay",
                    )
                    delay(jitteredDelay)
                    continue
                }

                val response = outcome.getOrThrow()
                // Trek 2 Stage 2B-B (C5 round-5 review-fix P2) —
                // unified dispatch on [classifyPollResponse]. Same
                // enum as the legacy pollLoop's `when`.
                when (classifyPollResponse(response.statusCode)) {
                    PollResponseClass.TokenStale -> {
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        staleToken = token
                        log(
                            "REST_TRACE ws_active_poll_unauthorised status=401 " +
                                "elapsedMs=$elapsed — will refresh token",
                        )
                        continue
                    }
                    PollResponseClass.RateLimit -> {
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        val clampedRetryAfterMs = clampRetryAfterMs(response.retryAfterSeconds)
                        val (nominalDelay, effectiveDelay) = if (clampedRetryAfterMs != null) {
                            clampedRetryAfterMs to clampedRetryAfterMs
                        } else {
                            val nominal = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                            val jitterFactor = nextJitterFactor()
                            nominal to (nominal * jitterFactor).toLong()
                        }
                        log(
                            "REST_TRACE ws_active_poll_429 elapsedMs=$elapsed " +
                                "next_delay_ms=$effectiveDelay nominal_delay_ms=$nominalDelay " +
                                "retry_after_secs=${response.retryAfterSeconds ?: -1L}",
                        )
                        delay(effectiveDelay)
                        continue
                    }
                    PollResponseClass.ServerError -> {
                        recordRestFailure(iterationEpoch = iterationEpoch, isProbe = isProbe)
                        val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                        val jitterFactor = nextJitterFactor()
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE ws_active_poll_5xx status=${response.statusCode} " +
                                "elapsedMs=$elapsed next_delay_ms=$jitteredDelay",
                        )
                        delay(jitteredDelay)
                        continue
                    }
                    PollResponseClass.GoneReauth -> {
                        val nextDelayMs = handle410(token = token, iterationEpoch = iterationEpoch, isProbe = isProbe, loopTag = "wsActivePollLoop")
                        delay(nextDelayMs)
                        continue
                    }
                    PollResponseClass.Other -> {
                        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = false)
                        val nominalDelay = intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)
                        val jitterFactor = nextJitterFactor()
                        val jitteredDelay = (nominalDelay * jitterFactor).toLong()
                        log(
                            "REST_TRACE ws_active_poll_unexpected_status " +
                                "status=${response.statusCode} elapsedMs=$elapsed " +
                                "next_delay_ms=$jitteredDelay",
                        )
                        delay(jitteredDelay)
                        continue
                    }
                    PollResponseClass.Ok200 -> {
                        // Fall through to the 200..299 envelope-
                        // processing block below.
                    }
                }
                // 200..299 branch — round-4 P2 symmetric with
                // legacy pollLoop. isOkResponse = true regardless
                // of body shape. Body-null / empty-envelope-list
                // is logged as `ws_active_poll_empty` and the
                // loop delays for `intervalMs`.
                recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = true)
                val parsedBody = response.bodyParsed
                if (parsedBody == null || parsedBody.envelopes.isEmpty()) {
                    log(
                        "REST_TRACE ws_active_poll_empty elapsedMs=$elapsed " +
                            "body_null=${if (parsedBody == null) "true" else "false"}",
                    )
                    delay(intervalMs)
                    continue
                }
                val envelopes = parsedBody.envelopes
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
                //
                // Round 11 (council follow-up) — drain-immediate
                // symmetry with `pollLoop`. When the relay sets
                // `more=true` on the response, both poll loops MUST
                // short-cycle through `POLL_DRAIN_IMMEDIATE_MS`
                // (~100 ms) rather than the jittered `intervalMs`
                // (~2 s active). Without this branch the parallel
                // loop falls back to the regular jittered delay,
                // which leaves backlog draining ~20× slower than the
                // legacy loop. The symmetry break also leaves the
                // parallel loop holding the CPU/dispatcher slot
                // longer than necessary when a burst is in
                // progress — a latent battery / wakelock cost on
                // Android Doze-eligible devices.
                if (parsedBody.more) {
                    delay(POLL_DRAIN_IMMEDIATE_MS)
                    continue
                }
                val jitterFactor = nextJitterFactor()
                val jitteredDelay = (intervalMs * jitterFactor).toLong()
                delay(jitteredDelay)
            } finally {
                if (isProbe) {
                    withContext(NonCancellable) {
                        releaseProbePermitIfStillHeld(iterationEpoch = iterationEpoch)
                    }
                }
            }
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
                val priorState = _verifyKeyState
                _verifyKeyState = transition(_verifyKeyState, RefreshOutcome.Failure)
                // Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1) —
                // Tele2 smoke pin: `seq_mac_verify_key_state=<name>`
                // ≥ 1× before envelope ingest. `from=` captures the
                // pre-transition state for diagnostic triage. The
                // key payload is NOT logged: VerifyKeyState.toString
                // already redacts; `logName()` is a literal class
                // identifier with zero per-key bits.
                log(
                    "REST_TRACE seq_mac_verify_key_state=${verifyKeyStateLogName(_verifyKeyState)} " +
                        "from=${verifyKeyStateLogName(priorState)} outcome=Failure",
                )
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
            val priorState = _verifyKeyState
            _verifyKeyState = transition(_verifyKeyState, outcome)
            // Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1) — Tele2
            // smoke pin: see the matching block on the Failure path
            // above. `outcome::class.simpleName` resolves to
            // `Empty`/`Valid`/`Malformed`; `Valid` redacts the hex
            // via its companion-locked `toString` so a future log-
            // line tweak printing `outcome=$outcome` would also be
            // safe — we keep the literal class name here for the
            // smoke grep.
            log(
                "REST_TRACE seq_mac_verify_key_state=${verifyKeyStateLogName(_verifyKeyState)} " +
                    "from=${verifyKeyStateLogName(priorState)} outcome=${outcome::class.simpleName}",
            )
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
    // ── Trek 2 Stage 2B-B (C5, L9) — breaker mechanism ──────────────────────

    /**
     * Trek 2 Stage 2B-B (C5, L9) — per-iteration decision returned
     * by [gateBreakerForIteration]. Both REST poll loops consume
     * this exclusive decision at the top of each iteration:
     *
     *   * [Proceed] — breaker is [LongPollBreakerState.Closed].
     *     Normal flow; record success / failure on response.
     *   * [Probe] — breaker is
     *     `HalfOpen(probeInFlight = false)`. THIS loop has claimed
     *     the permit (CAS-style under `_inboundStateMutex`) and
     *     must run exactly one probe call wrapped in a
     *     `try { ... } finally { withContext(NonCancellable) {
     *     releaseProbePermitIfStillHeld() } }` block so a
     *     mid-probe cancellation does not strand the permit at
     *     `probeInFlight = true`.
     *   * [Skip] — breaker is in a non-pollable state
     *     ([LongPollBreakerState.SuspendedOnPoison],
     *     [LongPollBreakerState.Open], or
     *     `HalfOpen(probeInFlight = true)` — another loop owns the
     *     probe permit). Caller logs the [reason] and delays before
     *     the next iteration.
     *
     * The decision sealed type is `private` so the breaker
     * mechanics never leak across the orchestrator's wire surface.
     */
    /**
     * Trek 2 Stage 2B-B (C5 round-4 review-fix P2) — unified
     * status-code classification for poll responses. Both REST
     * poll loops route their response handling through these
     * branches so a given status code is treated identically
     * regardless of which loop received it.
     *
     * The classification is on STATUS CODE ONLY. Body shape
     * (parseable, null, empty envelope list) is NOT part of the
     * classification — a 200 with an unparseable body still
     * counts as `Ok200`. Round-3 had a subtle asymmetry: the
     * legacy pollLoop passed `isOkResponse = true` for every
     * 200..299 (body checked AFTER), while the parallel
     * wsActivePollLoop bundled the body-null check with the
     * non-200 fall-through and passed `isOkResponse = false`.
     * Round-4 P2 unifies on status-code-only semantics.
     */
    internal enum class PollResponseClass {
        Ok200,        // 200..299 — successful response (isOkResponse = true)
        TokenStale,   // 401 — token refresh required (isOkResponse = false)
        RateLimit,    // 429 — Retry-After dance (isOkResponse = false)
        GoneReauth,   // 410 — L8 reauth + L9 Status410Storm dance
        ServerError,  // 5xx — transport failure (recordRestFailure)
        Other,        // 4xx-other — drop + log (isOkResponse = false)
    }

    internal fun classifyPollResponse(statusCode: Int): PollResponseClass = when (statusCode) {
        401 -> PollResponseClass.TokenStale
        410 -> PollResponseClass.GoneReauth
        429 -> PollResponseClass.RateLimit
        in 200..299 -> PollResponseClass.Ok200
        in 500..599 -> PollResponseClass.ServerError
        else -> PollResponseClass.Other
    }

    private sealed class BreakerIterationDecision {
        data class Proceed(val epoch: Long) : BreakerIterationDecision()
        data class Probe(val epoch: Long) : BreakerIterationDecision()
        data class Skip(val reason: String) : BreakerIterationDecision()
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9; round-4 review-fix P1.2 carries
     * epoch) — single CAS-style entry that snapshots
     * [_breakerState] under [_inboundStateMutex] and, if the
     * state is `HalfOpen(probeInFlight = false)`, atomically
     * claims the probe permit (flips to
     * `HalfOpen(probeInFlight = true)`) before returning [Probe].
     *
     * Returns the iteration's epoch value (in [Proceed.epoch] /
     * [Probe.epoch]) so the caller can present it to the
     * response-handling helpers ([recordRestSuccess] /
     * [recordRestFailure] / [handle410]). On epoch mismatch
     * those helpers treat the response as stale and no-op.
     */
    private suspend fun gateBreakerForIteration(): BreakerIterationDecision {
        return _inboundStateMutex.withLock {
            when (val current = _breakerState) {
                is LongPollBreakerState.Closed -> BreakerIterationDecision.Proceed(_breakerEpoch)
                is LongPollBreakerState.SuspendedOnPoison ->
                    BreakerIterationDecision.Skip("breaker_suspended_on_poison")
                is LongPollBreakerState.Open ->
                    BreakerIterationDecision.Skip("breaker_open_${current.reason}")
                is LongPollBreakerState.HalfOpen -> {
                    if (current.probeInFlight) {
                        BreakerIterationDecision.Skip("breaker_half_open_probe_in_flight")
                    } else {
                        // Probe permit claim is a gate-time CAS,
                        // NOT a state-class change. Epoch is NOT
                        // bumped here — the probe owner observes
                        // the same epoch the timer-driven Open →
                        // HalfOpen transition published.
                        _breakerState = LongPollBreakerState.HalfOpen(probeInFlight = true)
                        BreakerIterationDecision.Probe(_breakerEpoch)
                    }
                }
            }
        }
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9; round-2 review-fix P1.1
     * extended to take [isProbe]) — increment the consecutive-fail
     * counter and, if the counter reached
     * [BREAKER_CONSECUTIVE_FAIL_THRESHOLD] (or this call is the
     * probe-owning loop on a failed HalfOpen probe), transition
     * the breaker to `Open(ConsecutiveRestFailures, cooldownMs)`
     * and spawn the breaker timer.
     *
     * Called from BOTH poll loops on transport-class failures:
     * `IOException` / read-timeout / HTTP 5xx. Status 401 / 410 /
     * 429 / 4xx-other are NOT transport failures per scope §L9 and
     * route through [recordRestSuccess] instead (they prove the
     * transport works even if the relay refuses to serve us).
     *
     * **Probe ownership contract (round-2 review-fix P1.1):**
     *   * A loop with `isProbe = false` may only mutate breaker
     *     state while [_breakerState] is [LongPollBreakerState.Closed]
     *     — its failure increments the consecutive-fail counter
     *     and may trip Closed → Open. A late non-probe failure
     *     arriving while the breaker is already
     *     [LongPollBreakerState.Open] / [LongPollBreakerState.HalfOpen] /
     *     [LongPollBreakerState.SuspendedOnPoison] is a no-op —
     *     the cooldown timer / probe permit / poison handler own
     *     those state transitions, not a stale normal loop.
     *   * A loop with `isProbe = true` is the probe owner. It MAY
     *     resolve `HalfOpen(probeInFlight = true)` to `Open` on
     *     failure (the cooldown doubles).
     */
    private suspend fun recordRestFailure(iterationEpoch: Long, isProbe: Boolean) {
        // Round 13 — capture both the transition reason AND the
        // cooldown value inside the single critical section so the
        // log line below reads consistent state without re-acquiring
        // [_inboundStateMutex]. The earlier double-acquire pattern
        // raced a concurrent breaker transition and violated the L6
        // discipline ("mutex held only across in-memory operations,
        // never re-entered for log interpolation").
        var capturedCooldownMs = 0L
        val openedReason: BreakerOpenReason? = _inboundStateMutex.withLock {
            // Trek 2 Stage 2B-B (C5 round-4 review-fix P1.2) —
            // epoch gate. If the breaker has transitioned since
            // this iteration's gate call, the response is from a
            // stale lifecycle generation and must NOT mutate
            // current bookkeeping. Closes the ABA race that
            // Boolean `isProbe` alone (round-3) could not.
            if (iterationEpoch != _breakerEpoch) {
                return@withLock null
            }
            when (val current = _breakerState) {
                is LongPollBreakerState.Closed -> {
                    // Authoritative — normal operating mode.
                    // Scope §L9: a non-410 response breaks the
                    // consecutive 410 sequence.
                    _status410StormTimestamps.clear()
                    _breakerFailCount += 1
                    if (_breakerFailCount >= BREAKER_CONSECUTIVE_FAIL_THRESHOLD) {
                        transitionToOpenUnderMutex(
                            BreakerOpenReason.ConsecutiveRestFailures,
                            _breakerCurrentCooldownMs,
                        )
                        capturedCooldownMs = _breakerCurrentCooldownMs
                        BreakerOpenReason.ConsecutiveRestFailures
                    } else null
                }
                is LongPollBreakerState.HalfOpen -> {
                    // Epoch matches and we are in HalfOpen ⇒ this
                    // call IS the probe owner (the gate's
                    // probeInFlight CAS happened in the same
                    // epoch). `isProbe` is consistency-checked
                    // defensively.
                    if (isProbe && current.probeInFlight) {
                        _status410StormTimestamps.clear()
                        val nextCooldown = (_breakerCurrentCooldownMs.toDouble() * BREAKER_COOLDOWN_GROWTH_FACTOR)
                            .toLong()
                            .coerceAtMost(BREAKER_COOLDOWN_CEILING_MS)
                        _breakerCurrentCooldownMs = nextCooldown
                        transitionToOpenUnderMutex(
                            BreakerOpenReason.ConsecutiveRestFailures,
                            nextCooldown,
                        )
                        capturedCooldownMs = nextCooldown
                        BreakerOpenReason.ConsecutiveRestFailures
                    } else {
                        // Inconsistency (epoch matches HalfOpen
                        // but `isProbe` says non-probe). Defensive
                        // no-op.
                        null
                    }
                }
                is LongPollBreakerState.Open, is LongPollBreakerState.SuspendedOnPoison -> {
                    // Can't actually reach here with a matching
                    // epoch (transitions bump the epoch). Defensive
                    // no-op.
                    null
                }
            }
        }
        if (openedReason != null) {
            log("REST_TRACE breaker_open reason=$openedReason cooldown_ms=$capturedCooldownMs")
            stateMachine.onEvent(RestStateMachine.Event.RestPollDegraded(openedReason))
        }
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9) — caller holds [_inboundStateMutex].
     * Drops the consecutive-fail counter, replaces the current
     * breaker state with [LongPollBreakerState.Open], cancels any
     * pre-existing timer, and spawns a fresh timer that transitions
     * `Open → HalfOpen(probeInFlight = false)` after [cooldownMs]
     * wall-clock (virtual under tests).
     */
    private fun transitionToOpenUnderMutex(reason: BreakerOpenReason, cooldownMs: Long) {
        _breakerFailCount = 0
        _breakerState = LongPollBreakerState.Open(reason, cooldownMs)
        // Round-4 P1.2: every state-class change bumps the epoch
        // so in-flight iteration responses with the old epoch
        // are detected as stale at record* / handle410 time.
        _breakerEpoch += 1
        _breakerTimerJob?.cancel()
        _breakerTimerJob = scope.launch {
            delay(cooldownMs)
            _inboundStateMutex.withLock {
                if (_breakerState is LongPollBreakerState.Open) {
                    _breakerState = LongPollBreakerState.HalfOpen(probeInFlight = false)
                    _breakerEpoch += 1
                    log("REST_TRACE breaker_half_open")
                }
            }
        }
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9) — reset the consecutive-fail
     * counter and, if the breaker was in
     * [LongPollBreakerState.Open] or [LongPollBreakerState.HalfOpen],
     * transition back to [LongPollBreakerState.Closed]. Resets
     * [_breakerCurrentCooldownMs] to [BREAKER_INITIAL_COOLDOWN_MS]
     * so a future open cycle starts fresh (scope §L9: "On entering
     * Closed from HalfOpen-success, the cooldown resets to
     * BREAKER_INITIAL_COOLDOWN_MS").
     *
     * Cancels the breaker timer if a transition fires — once we're
     * Closed there is no Open → HalfOpen tick to drive. Does NOT
     * touch [LongPollBreakerState.SuspendedOnPoison] (qualitatively
     * different recovery path per C4 §L7).
     */
    /**
     * Trek 2 Stage 2B-B (C5, L9; round-2 review-fix P1.1
     * extended to take [isProbe]) — record a non-failure poll
     * response (200 / 401 / 429 / 4xx-other).
     *
     * **Probe ownership contract (round-2 review-fix P1.1):**
     *   * A loop with `isProbe = false` resets only `Closed`-state
     *     bookkeeping (consecutive-fail counter +
     *     [_current410BackoffMs] floor + storm timestamps). A
     *     non-probe success arriving while the breaker is
     *     [LongPollBreakerState.Open] / [LongPollBreakerState.HalfOpen] /
     *     [LongPollBreakerState.SuspendedOnPoison] MUST NOT
     *     transition state. Otherwise a stale poll that succeeded
     *     against a transient relay window before the network
     *     broke would short-circuit the cooldown and re-arm
     *     polling against a now-broken transport.
     *   * A loop with `isProbe = true` is the probe owner. It MAY
     *     resolve `HalfOpen(probeInFlight = true)` to Closed on
     *     success — the cooldown resets and the timer is
     *     cancelled.
     *
     * `Closed`-state counter / 410-floor / storm-window resets
     * happen unconditionally (regardless of [isProbe]) under any
     * non-failure observation because the reset semantic is "this
     * loop saw the relay answer at this moment" — which a
     * non-probe loop's response also evidences.
     */
    private suspend fun recordRestSuccess(iterationEpoch: Long, isProbe: Boolean, isOkResponse: Boolean) {
        val transitioned: Boolean = _inboundStateMutex.withLock {
            // Trek 2 Stage 2B-B (C5 round-4 review-fix P1.2) —
            // epoch gate. Closes the ABA race round-3 missed.
            if (iterationEpoch != _breakerEpoch) {
                return@withLock false
            }
            // The [isOkResponse] flag discriminates a real
            // `200 OK` response (which per scope §L8 is the ONLY
            // signal that resets [_current410BackoffMs] to the
            // 5 s floor) from `401` / `429` / `4xx-other`
            // responses (which prove the transport works but do
            // NOT entitle the 410 dance to reset).
            when (val current = _breakerState) {
                is LongPollBreakerState.Closed -> {
                    _status410StormTimestamps.clear()
                    _breakerFailCount = 0
                    if (isOkResponse) {
                        _current410BackoffMs = BREAKER_INITIAL_COOLDOWN_MS
                    }
                    false
                }
                is LongPollBreakerState.HalfOpen -> {
                    if (isProbe && current.probeInFlight) {
                        // Probe owner's success closes the breaker.
                        // Bump the epoch on the HalfOpen → Closed
                        // transition.
                        _breakerState = LongPollBreakerState.Closed
                        _breakerEpoch += 1
                        _breakerFailCount = 0
                        _status410StormTimestamps.clear()
                        _breakerCurrentCooldownMs = BREAKER_INITIAL_COOLDOWN_MS
                        if (isOkResponse) {
                            _current410BackoffMs = BREAKER_INITIAL_COOLDOWN_MS
                        }
                        _breakerTimerJob?.cancel()
                        _breakerTimerJob = null
                        true
                    } else {
                        // Defensive: inconsistent ownership state.
                        false
                    }
                }
                is LongPollBreakerState.Open, is LongPollBreakerState.SuspendedOnPoison -> {
                    // Can't reach with matching epoch.
                    false
                }
            }
        }
        if (transitioned) {
            log("REST_TRACE breaker_closed")
        }
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9, M-B28) — cancellation-safe permit
     * release. Called from the `finally` block of a probe call. If
     * the breaker state is STILL
     * `HalfOpen(probeInFlight = true)` at the moment of the finally
     * (i.e., the probe was cancelled mid-flight before its outcome
     * resolved the state), reset to
     * `HalfOpen(probeInFlight = false)` so the next poll iteration
     * on either loop can claim the permit. If the probe already
     * resolved into [LongPollBreakerState.Closed] or
     * [LongPollBreakerState.Open] (via [recordRestSuccess] or
     * [recordRestFailure]) before the `finally`, this method is a
     * no-op.
     *
     * MUST be called inside `withContext(NonCancellable)` so the
     * reset itself cannot be cancelled inside its critical section.
     */
    private suspend fun releaseProbePermitIfStillHeld(iterationEpoch: Long) {
        _inboundStateMutex.withLock {
            // Trek 2 Stage 2B-B (C5 round-5 review-fix P1) —
            // epoch-gate the permit release. Round-4 caught
            // recordRest* / handle410 ABA but the probe-permit
            // finally was not threaded through. Scenario:
            //   1. Probe A's iteration captured epoch N.
            //   2. A's transport.poll receives the 3rd 410.
            //   3. handle410 trips Open(Status410Storm) → epoch N+1.
            //   4. A's finally runs the legacy 60 s delay then
            //      exits the try; the next iteration would be
            //      gated by the new state.
            //   5. Meanwhile the storm cooldown timer fires:
            //      Open → HalfOpen(probeInFlight=false) at epoch N+2.
            //   6. Loop B claims the new probe permit at
            //      epoch N+2 — flips to
            //      HalfOpen(probeInFlight=true).
            //   7. A's finally fires `releaseProbePermitIfStillHeld`.
            //      Without epoch gating, the release sees
            //      HalfOpen(probeInFlight=true) and flips it
            //      back to false — DROPPING B's permit. Either
            //      another loop now double-claims (parallel
            //      probes against the relay) or B's probe
            //      proceeds without permit (state torn).
            //
            // Fix: release ONLY if the iteration's epoch matches
            // current. The release is a no-op for any prior
            // generation's owner.
            if (iterationEpoch != _breakerEpoch) {
                return@withLock
            }
            val current = _breakerState
            if (current is LongPollBreakerState.HalfOpen && current.probeInFlight) {
                _breakerState = LongPollBreakerState.HalfOpen(probeInFlight = false)
                log("REST_TRACE breaker_probe_permit_released_on_cancel")
            }
        }
    }

    /**
     * Trek 2 Stage 2B-B (C5, L8 + L9) — `410 Gone` poll-response
     * handler. Performs the L8 reauth dance (capped exponential
     * backoff + token refresh) and the L9 storm detection
     * (3 consecutive 410s within 30 s ⇒
     * `Open(Status410Storm, BREAKER_410_STORM_COOLDOWN_MS)`).
     *
     * Mutex discipline:
     *   * The state-update critical section runs under
     *     [_inboundStateMutex] and either bumps
     *     [_current410BackoffMs] (≤ [BREAKER_410_STORM_COOLDOWN_MS]
     *     ceiling) OR fires the storm transition via
     *     [transitionToOpenUnderMutex].
     *   * The token refresh ([acquireOrRefreshToken]) runs OUTSIDE
     *     [_inboundStateMutex] — it takes `tokenMutex` outer +
     *     `_inboundStateMutex` inner per the L6 lock order, so we
     *     never invert.
     *
     * Returns the milliseconds delay the caller should apply
     * before the next iteration. Storm trips return the
     * `BREAKER_410_STORM_COOLDOWN_MS` ceiling (60 s) immediately;
     * non-storm 410s return the post-double-and-clamp
     * `_current410BackoffMs`.
     *
     * Per scope §L8 + L9: the dance applies to `/relay/poll`
     * responses ONLY. The `/relay/ack-deliver` 410 path uses the
     * existing self-healing fall-through (Stage 1.x Lock-3
     * "T1 ack after T2 → 401, burning a fresh token there is
     * wrong"); M14 sub-cell (e) pins this asymmetry.
     */
    private suspend fun handle410(token: String, iterationEpoch: Long, isProbe: Boolean, loopTag: String): Long {
        val nowMs = now()
        var openedStorm = false
        var effectiveDelayMs: Long = POLL_FAIL_BACKOFF_MS
        var authoritative = false
        // Round 13 — capture storm count inside the single critical
        // section so the log line below reads consistent state
        // without re-acquiring [_inboundStateMutex]. The earlier
        // double-acquire pattern raced a concurrent `recordRestSuccess`
        // /`recordRestFailure` and violated the L6 discipline.
        var capturedStormCount = 0
        _inboundStateMutex.withLock {
            // Trek 2 Stage 2B-B (C5 round-4 review-fix P1.1) —
            // epoch gate + state-class gate. handle410 now
            // shares the same ownership contract as
            // recordRestSuccess / recordRestFailure: a stale
            // 410 in Open / SuspendedOnPoison / HalfOpen
            // non-probe is a FULL no-op. The dangerous scenario
            // round-3 left open: `SuspendedOnPoison` (set by L7
            // poison handling) + three late 410s overwriting
            // state to Open(Status410Storm) and re-arming
            // polling after cooldown. Epoch+state gate forecloses.
            if (iterationEpoch != _breakerEpoch) {
                // Stale: epoch mismatch. effectiveDelayMs stays
                // at POLL_FAIL_BACKOFF_MS as a safe default;
                // the next iteration's gate Skip will dominate.
                return@withLock
            }
            when (val current = _breakerState) {
                is LongPollBreakerState.Closed -> { authoritative = true }
                is LongPollBreakerState.HalfOpen -> {
                    // Probe owner only (epoch + isProbe +
                    // probeInFlight).
                    if (isProbe && current.probeInFlight) {
                        authoritative = true
                    } else {
                        return@withLock
                    }
                }
                is LongPollBreakerState.Open, is LongPollBreakerState.SuspendedOnPoison -> {
                    // Stale (epoch matches but state cannot match
                    // gate decision — defensive). No-op.
                    return@withLock
                }
            }
            // Authoritative path: bookkeeping + potential storm
            // transition.
            _status410StormTimestamps.removeAll { it < nowMs - BREAKER_410_STORM_WINDOW_MS }
            _status410StormTimestamps.add(nowMs)
            if (_status410StormTimestamps.size >= BREAKER_410_STORM_THRESHOLD) {
                _current410BackoffMs = BREAKER_410_STORM_COOLDOWN_MS
                transitionToOpenUnderMutex(
                    BreakerOpenReason.Status410Storm,
                    BREAKER_410_STORM_COOLDOWN_MS,
                )
                openedStorm = true
                effectiveDelayMs = BREAKER_410_STORM_COOLDOWN_MS
            } else {
                // Trek 2 Stage 2B-B (C5-C review-fix P1.2) — scope §L8
                // pins the sequence as "The first 410 backs off 5 s;
                // each subsequent 410 doubles up to 60 s." Return the
                // CURRENT backoff (5 s on the first 410), then double
                // FOR THE NEXT call.
                effectiveDelayMs = _current410BackoffMs
                _current410BackoffMs = (_current410BackoffMs * 2).coerceAtMost(BREAKER_410_STORM_COOLDOWN_MS)
            }
            capturedStormCount = _status410StormTimestamps.size
        }
        if (openedStorm) {
            log(
                "REST_TRACE breaker_open reason=${BreakerOpenReason.Status410Storm} " +
                    "cooldown_ms=$BREAKER_410_STORM_COOLDOWN_MS loop=$loopTag",
            )
            stateMachine.onEvent(RestStateMachine.Event.RestPollDegraded(BreakerOpenReason.Status410Storm))
        }
        if (authoritative) {
            // Refresh the token only on authoritative path. A
            // stale 410's data does not imply the current token
            // is stale (the response was issued against an old
            // lifecycle generation).
            acquireOrRefreshToken(reason = "poll_410", staleToken = token)
        }
        log(
            "REST_TRACE poll_410 next_delay_ms=$effectiveDelayMs " +
                "authoritative=${if (authoritative) "true" else "false"} " +
                "storm=${if (openedStorm) "true" else "false"} " +
                "storm_count=$capturedStormCount " +
                "loop=$loopTag",
        )
        return effectiveDelayMs
    }

    /**
     * Trek 2 Stage 2B-B (C6 review-fix round 1 P1.1) — literal
     * class identifier used by the `seq_mac_verify_key_state=...`
     * smoke pin log. The verify key payload is intentionally NOT
     * surfaced: even a leading-prefix disclosure on the 64-char hex
     * is enough for an offline correlator to link sessions of the
     * same identity across logs. The class name carries zero
     * per-key bits.
     */
    private fun verifyKeyStateLogName(state: VerifyKeyState): String = when (state) {
        VerifyKeyState.KeyAbsent -> "KeyAbsent"
        is VerifyKeyState.KeyPresent -> "KeyPresent"
        VerifyKeyState.KeySuspended -> "KeySuspended"
    }

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
        // loops cannot race past the status check.
        val decision: BadMacDecision = _inboundStateMutex.withLock {
            val newCount = (_macFailCount[env.id] ?: 0) + 1
            _macFailCount[env.id] = newCount
            // Round 11 (council follow-up) — bounded-map eviction.
            // After the insert, if the count map exceeds the cap,
            // drop the oldest first-seen envelope_id from both the
            // count and status maps to keep them consistent. The
            // current envelope_id was just inserted and is now the
            // most recently added, so it is never evicted by its
            // own arrival. The decision returned to the caller still
            // reflects the just-updated state for [env.id]; eviction
            // affects a different (older) id only.
            val evictedId: String? = if (_macFailCount.size > BAD_MAC_TRACKED_ENVELOPE_CAP) {
                val eldest = _macFailCount.keys.first()
                _macFailCount.remove(eldest)
                _macRefreshStatus.remove(eldest)
                eldest
            } else {
                null
            }
            val currentStatus = _macRefreshStatus[env.id] ?: MacRefreshStatus.NotAttempted
            when (currentStatus) {
                MacRefreshStatus.Completed -> {
                    // Step 4: repeat AFTER refresh completed →
                    // suspension. The refresh got its chance to
                    // recover the verify-key state and the failure
                    // persisted; suspend both REST loops.
                    _breakerState = LongPollBreakerState.SuspendedOnPoison
                    // Round-4 P1.2: state-class change bumps epoch.
                    _breakerEpoch += 1
                    BadMacDecision(newCount = newCount, action = BadMacAction.Suspend, evictedId = evictedId)
                }
                MacRefreshStatus.InFlight -> {
                    // C4 review-fix (P1.1): refresh is still in
                    // flight; the verify-key state may still be
                    // stale. DO NOT suspend yet — telemetry only
                    // and drop. When the in-flight refresh lands
                    // it will mark status=Completed; subsequent
                    // failures from that point onward suspend.
                    BadMacDecision(newCount = newCount, action = BadMacAction.JustLog, evictedId = evictedId)
                }
                MacRefreshStatus.NotAttempted -> {
                    if (newCount >= MAC_REPEAT_REFRESH_THRESHOLD) {
                        // Step 3: threshold reached, no refresh yet.
                        // Mark InFlight under the SAME critical
                        // section so a concurrent loop racing past
                        // here observes InFlight and JustLogs.
                        _macRefreshStatus[env.id] = MacRefreshStatus.InFlight
                        BadMacDecision(newCount = newCount, action = BadMacAction.TriggerRefresh, evictedId = evictedId)
                    } else {
                        BadMacDecision(newCount = newCount, action = BadMacAction.JustLog, evictedId = evictedId)
                    }
                }
            }
        }

        // Step 1: telemetry.
        log(
            "REST_TRACE poll_mac_verify_repeat id=${env.id.take(8)} " +
                "count=${decision.newCount} seq=${env.seq} " +
                "reason=$reason loop=$loopTag",
        )
        // Round 11 — eviction telemetry. Emitted outside the mutex
        // critical section. The 8-char id prefix keeps the log free
        // of full-id PII consistent with other REST_TRACE lines.
        decision.evictedId?.let { id ->
            log("REST_TRACE mac_state_evicted id=${id.take(8)} reason=lru_cap")
        }

        when (decision.action) {
            BadMacAction.JustLog -> { /* no additional action */ }
            BadMacAction.TriggerRefresh -> {
                // Step 3: forced refresh outside `_inboundStateMutex`
                // because `acquireOrRefreshToken` takes `tokenMutex`
                // outer + `_inboundStateMutex` inner. The refresh
                // response classifier transitions the verify-key
                // state machine onto the published outcome (Valid /
                // Empty / Malformed / Failure).
                //
                // C4 review-fix round 2 (P1.2), round-4 P3 wording
                // alignment — two exit paths, ONE rollback semantic
                // for every non-normal exit:
                //
                //   * Normal completion → status = `Completed`.
                //     The L7 step 4 trigger ("repeat-after-refresh
                //     → suspend") fires on subsequent failures.
                //   * Cancellation OR any other throwable → status =
                //     `NotAttempted` (rollback). The next bad-MAC
                //     must retry the refresh rather than falsely
                //     suspend. This is safe in both cases: on a
                //     non-CE refresh failure, `acquireOrRefreshToken`
                //     has already published the L2 failure outcome
                //     (Failure → KeySuspended) so subsequent
                //     ingestion is already gated by the verify-key
                //     state machine — the breaker does not need to
                //     also fire.
                //
                // The status update runs under
                // `withContext(NonCancellable)` so a caller
                // cancellation cannot itself prevent the
                // status-publication step from completing.
                var refreshNormallyCompleted = false
                try {
                    acquireOrRefreshToken(
                        reason = "poll_mac_repeat",
                        staleToken = currentToken,
                    )
                    refreshNormallyCompleted = true
                } finally {
                    withContext(NonCancellable) {
                        _inboundStateMutex.withLock {
                            _macRefreshStatus[env.id] = if (refreshNormallyCompleted) {
                                MacRefreshStatus.Completed
                            } else {
                                // Cancellation OR other throw: roll
                                // back to NotAttempted so the next
                                // poll cycle can retry the refresh.
                                MacRefreshStatus.NotAttempted
                            }
                        }
                    }
                }
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

    private data class BadMacDecision(
        val newCount: Int,
        val action: BadMacAction,
        // Round 11 (council follow-up) — id evicted by the
        // bounded-map LRU pass on this insert, if any. Used by the
        // caller to emit a `mac_state_evicted` log line outside the
        // mutex critical section. Null when no eviction fired.
        val evictedId: String? = null,
    )
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

    /**
     * Peek the per-envelope_id refresh-attempt status. Returns
     * [MacRefreshStatus.NotAttempted] when no entry exists.
     * The status drives the L7 step 4 suspension trigger:
     * `Completed` + verify failure → `SuspendedOnPoison`;
     * `InFlight` + verify failure → telemetry only.
     */
    internal suspend fun peekMacRefreshStatusForTest(envelopeId: String): MacRefreshStatus =
        _inboundStateMutex.withLock { _macRefreshStatus[envelopeId] ?: MacRefreshStatus.NotAttempted }

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
            // Round-4 P1.2: seeding state via the seam simulates
            // a real transition, so bump the epoch. A test that
            // calls record* / handle410 with the post-seam
            // `peekBreakerEpochForTest()` value will match;
            // calls with a stale captured value will be rejected
            // as in production.
            _breakerEpoch += 1
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
     * Trek 2 Stage 2B-B (C4 review-fix round 5 P2) — test-only seam
     * that holds [_lifecycleMutex] for the duration of [block] so
     * commonTest can deterministically force a concurrent [stop] /
     * [start] to suspend on the lifecycle mutex acquire. Used by
     * the round-5 cancelled-waiter regression pin: a `stop()`
     * suspended on the mutex with its caller cancelled must still
     * complete the teardown once the mutex frees, because the
     * acquire itself runs under `withContext(NonCancellable)`.
     *
     * Visibility: `internal` — only the `:shared:core:transport`
     * module's tests can call it.
     */
    internal suspend fun <T> withLifecycleMutexHeldForTest(block: suspend () -> T): T =
        _lifecycleMutex.withLock { block() }

    /**
     * Trek 2 Stage 2B-B (C5, L9) — test-only peek for the current
     * breaker cooldown value (used by M13 sub-cells to assert the
     * doubling-and-reset semantic of [_breakerCurrentCooldownMs]).
     * Reads under [_inboundStateMutex] for consistency with the
     * production read path.
     */
    internal suspend fun peekBreakerCooldownMsForTest(): Long =
        _inboundStateMutex.withLock { _breakerCurrentCooldownMs }

    /**
     * Trek 2 Stage 2B-B (C5, L9, M-B28 sub-cell (a)) — string-typed
     * decision exposing the production [gateBreakerForIteration]
     * path so commonTest can pin the atomic-permit-claim semantic
     * without driving two real poll loops to wake on the same
     * virtual tick. Each call mutates state exactly as the real
     * loops do; returns the bucket of the production sealed type.
     */
    internal suspend fun gateBreakerForIterationForTest(): String =
        when (val d = gateBreakerForIteration()) {
            is BreakerIterationDecision.Proceed -> "proceed"
            is BreakerIterationDecision.Probe -> "probe"
            is BreakerIterationDecision.Skip -> "skip:${d.reason}"
        }

    /**
     * Trek 2 Stage 2B-B (C5, L9, M-B28 sub-cell (b)) — direct
     * exposure of [releaseProbePermitIfStillHeld] so commonTest can
     * pin the cancellation-safe permit release without standing up
     * a controllable suspending transport for the claimant loop.
     */
    internal suspend fun releaseProbePermitIfStillHeldForTest(iterationEpoch: Long) {
        releaseProbePermitIfStillHeld(iterationEpoch = iterationEpoch)
    }

    /**
     * Trek 2 Stage 2B-B (C5 round-2 review-fix P1.1; round-3
     * gained [isOkResponse]) — direct entry to [recordRestSuccess]
     * so commonTest can pin the probe-owner gating contract AND
     * the 200-OK-only backoff-reset contract.
     */
    internal suspend fun recordRestSuccessForTest(iterationEpoch: Long, isProbe: Boolean, isOkResponse: Boolean) {
        recordRestSuccess(iterationEpoch = iterationEpoch, isProbe = isProbe, isOkResponse = isOkResponse)
    }

    /**
     * Trek 2 Stage 2B-B (C5 round-3 review-fix P1) — read-only
     * peek for [_current410BackoffMs] so commonTest can pin the
     * scope §L8 "only 200 OK resets the 5 s floor" semantic
     * across the recordRestSuccess paths.
     */
    internal suspend fun peekCurrent410BackoffMsForTest(): Long =
        _inboundStateMutex.withLock { _current410BackoffMs }

    /**
     * Trek 2 Stage 2B-B (C5 round-3 review-fix P1) — read-only
     * peek for the snapshot of [_status410StormTimestamps] so
     * commonTest can pin the "stale non-probe response in Open
     * MUST NOT pollute the storm window" semantic.
     */
    internal suspend fun peekStatus410StormTimestampsForTest(): List<Long> =
        _inboundStateMutex.withLock { _status410StormTimestamps.toList() }

    /**
     * Trek 2 Stage 2B-B (C5 round-2 review-fix P1.1) — direct
     * entry to [recordRestFailure] so commonTest can pin the
     * probe-owner gating contract (non-probe failure during
     * HalfOpen is a no-op; probe owner reopens with doubled
     * cooldown).
     */
    internal suspend fun recordRestFailureForTest(iterationEpoch: Long, isProbe: Boolean) {
        recordRestFailure(iterationEpoch = iterationEpoch, isProbe = isProbe)
    }

    /**
     * Trek 2 Stage 2B-B (C5 round-4 review-fix P1.2) — read-only
     * peek for [_breakerEpoch] so commonTest can drive
     * record* / handle410 with current OR stale epoch values to
     * pin the ABA-protection contract.
     */
    internal suspend fun peekBreakerEpochForTest(): Long =
        _inboundStateMutex.withLock { _breakerEpoch }

    /**
     * Trek 2 Stage 2B-B (C5 round-2 review-fix P1.2) — read-only
     * peek for `_breakerTimerJob != null` so commonTest can pin
     * the three-phase cancelAndJoinAll contract (after stop, the
     * timer field MUST be null — no orphan timer).
     */
    internal fun peekHasBreakerTimerForTest(): Boolean =
        _breakerTimerJob != null

    /**
     * Trek 2 Stage 2B-B (C6 review-fix round 1 P1.2, hardened in
     * round 2) — S6 controllable trigger. Simulates
     * [BREAKER_CONSECUTIVE_FAIL_THRESHOLD] consecutive REST poll
     * failures by forcing the breaker into
     * [LongPollBreakerState.Open] under the inbound-state mutex and
     * emitting the `breaker_test_trigger_fired` log the Tele2 LTE
     * smoke runbook greps for.
     *
     * The runbook (`docs/tracks/trek2-stage2b-b-tele2-smoke.md`,
     * scenario S6) accepts this helper iff natural Mode-2 does not
     * reproduce inside the 30-minute Tele2 LTE time-box. The PR
     * description must quote the helper invocation line AND the
     * natural-trigger absence note.
     *
     * **Visibility and production safety.** Public so the Android
     * `AppContainer` can route an ADB-broadcast intent receiver
     * call into it (the round-1 `internal` modifier blocked cross-
     * module access — `AppContainer` lives in `phantom.android.di`,
     * the orchestrator in `phantom.core.transport`, both in
     * separate Gradle modules). The defence-in-depth gate now lives
     * in the constructor parameter [s6DebugTriggerEnabled]:
     *
     *   * Release-mode wire-up passes `false`; the helper logs
     *     `REST_TRACE breaker_test_trigger_refused
     *     reason=disabled_in_release` and returns WITHOUT touching
     *     any breaker state. A release APK that accidentally
     *     carried a call site is therefore a no-op.
     *   * Trigger-enabled wire-up passes `true` (from
     *     `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`); the helper
     *     executes normally.
     *
     * **Effect (when enabled).** Bumps `_breakerFailCount` to the
     * threshold and routes through the regular
     * [transitionToOpenUnderMutex] so the resulting state —
     * `Open(ConsecutiveRestFailures, cooldownMs)` with a fresh timer
     * Job, epoch bumped, fail counter reset — is byte-identical to
     * what a natural 5-failure trip produces. Subsequent pollLoop
     * iterations skip with `breaker_open_ConsecutiveRestFailures`;
     * the existing cooldown / HalfOpen / probe / re-Open / re-Close
     * machinery exercises exactly as in production.
     */
    suspend fun forceBreakerTripForS6TestTrigger() {
        if (!s6DebugTriggerEnabled) {
            // Refusal log fires on EVERY call attempt regardless of
            // build mode so an operator with logcat access can verify
            // the gate held. The release pin is the load-bearing
            // safety; this line is observability.
            log(
                "REST_TRACE breaker_test_trigger_refused " +
                    "reason=disabled_in_release",
            )
            return
        }
        // Emit the trigger log BEFORE acquiring the mutex so the
        // line appears in logcat even if the transition itself
        // contends momentarily — the runbook needs the line
        // grep-discoverable regardless of contention timing.
        log(
            "REST_TRACE breaker_test_trigger_fired " +
                "reason=${BreakerOpenReason.ConsecutiveRestFailures} " +
                "threshold=$BREAKER_CONSECUTIVE_FAIL_THRESHOLD",
        )
        // Round 13 — capture the cooldown inside the single critical
        // section so the log line below reads consistent state without
        // re-acquiring [_inboundStateMutex]. The earlier double-acquire
        // pattern raced a concurrent breaker transition.
        var capturedCooldownMs = 0L
        _inboundStateMutex.withLock {
            // Synthesize the threshold-1 fail count then bump the
            // last one through `transitionToOpenUnderMutex` so the
            // log shape matches the natural-trip path's
            // `REST_TRACE breaker_open reason=... cooldown_ms=...`
            // line. Resetting `_breakerFailCount` to 0 (which
            // `transitionToOpenUnderMutex` does) maintains the
            // same invariant the production trip ends with.
            _breakerFailCount = BREAKER_CONSECUTIVE_FAIL_THRESHOLD
            _status410StormTimestamps.clear()
            transitionToOpenUnderMutex(
                BreakerOpenReason.ConsecutiveRestFailures,
                _breakerCurrentCooldownMs,
            )
            capturedCooldownMs = _breakerCurrentCooldownMs
        }
        log(
            "REST_TRACE breaker_open " +
                "reason=${BreakerOpenReason.ConsecutiveRestFailures} " +
                "cooldown_ms=$capturedCooldownMs",
        )
        stateMachine.onEvent(
            RestStateMachine.Event.RestPollDegraded(BreakerOpenReason.ConsecutiveRestFailures),
        )
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
        // Trek 2 Stage 2B-B (C4 review-fix round 2 P1.2) — explicit
        // `try { ... } catch (CancellationException) { throw }`
        // pattern. The earlier `runCatching` calls swallowed
        // `CancellationException` as if it were an ordinary network
        // error, breaking structured-concurrency teardown and
        // letting the L7 bad-MAC path leak a refresh attempt that
        // was cancelled mid-flight.
        val challengeHex = try {
            getChallenge(identityHex)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            log("REST_TRACE session_challenge_fail reason=${ex::class.simpleName}")
            return null
        }

        val signatureHex = try {
            val challengeBytes = hexToBytes(challengeHex)
            val sigBytes = signChallenge(challengeBytes)
            bytesToHex(sigBytes)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            log("REST_TRACE session_sign_fail reason=${ex::class.simpleName}")
            return null
        }

        val body = AuthSessionRequest(
            identityHex = identityHex,
            signingPubkeyHex = signingPubkeyHex,
            challengeHex = challengeHex,
            signatureHex = signatureHex,
        )

        val startMs = now()
        val response: RestFallbackResponse<AuthSessionResponse> = try {
            transport.authSession(url = "$baseUrl/auth/session", body = body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            val elapsed = now() - startMs
            log("REST_TRACE session_fail reason=${ex::class.simpleName} elapsedMs=$elapsed")
            return null
        }
        val elapsed = now() - startMs
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

        /**
         * Round 11 (council follow-up) — upper bound on the number of
         * distinct `envelope_id` entries tracked by [_macFailCount]
         * and [_macRefreshStatus] across the orchestrator session
         * lifetime. The server-side `POLL_MAX_ENVELOPES = 1` cap
         * (Stage 1.x lock) limits a non-malicious relay to ~1 entry
         * per poll, but a malicious relay can still drive ~1
         * fabricated envelope_id per poll for the entire orchestrator
         * lifetime; without a client-side cap, the maps grow linearly
         * over time. A bounded value here is defence-in-depth.
         *
         * The cap of 256 is comfortably above the longest realistic
         * window of in-flight envelope_ids the L7 four-step posture
         * tracks at once (the threshold is 2 failures per id, plus a
         * one-shot forced refresh, and the relay tears down
         * everything older as the sliding window of unacked envelopes
         * advances), and small enough that worst-case heap cost stays
         * negligible: 256 String keys + 256 Int counters + 256
         * MacRefreshStatus enums ≈ ~30 KB across both maps. Eviction
         * is insertion-order (oldest first-seen evicted first) — see
         * [handleBadMacEnvelope] for the eviction site.
         */
        const val BAD_MAC_TRACKED_ENVELOPE_CAP: Int = 256

        // ── Trek 2 Stage 2B-B (C5, L8 + L9 + D11) — breaker constants ────

        /**
         * L9 — N consecutive REST poll failures before the breaker
         * trips to [LongPollBreakerState.Open] with reason
         * [BreakerOpenReason.ConsecutiveRestFailures]. Mirrors the
         * existing [SEND_MAX_ATTEMPTS] send-side budget.
         */
        const val BREAKER_CONSECUTIVE_FAIL_THRESHOLD: Int = 5

        /**
         * L9 — initial cooldown for [LongPollBreakerState.Open] from
         * a [BreakerOpenReason.ConsecutiveRestFailures] trigger.
         * Matches [POLL_FAIL_BACKOFF_MS].
         */
        const val BREAKER_INITIAL_COOLDOWN_MS: Long = 5_000L

        /**
         * L9 — growth factor on failed half-open probe. The next
         * cooldown is
         * `min(currentCooldownMs * BREAKER_COOLDOWN_GROWTH_FACTOR, BREAKER_COOLDOWN_CEILING_MS)`.
         */
        const val BREAKER_COOLDOWN_GROWTH_FACTOR: Double = 2.0

        /**
         * L9 + D11 — hard ceiling on any breaker cooldown regardless
         * of growth factor. A relay-derived signal cannot push the
         * cooldown above this value.
         */
        const val BREAKER_COOLDOWN_CEILING_MS: Long = 120_000L

        /**
         * L8 + L9 — K consecutive `410 Gone` responses within
         * [BREAKER_410_STORM_WINDOW_MS] trips the breaker to
         * [LongPollBreakerState.Open] with reason
         * [BreakerOpenReason.Status410Storm].
         */
        const val BREAKER_410_STORM_THRESHOLD: Int = 3

        /**
         * L8 + L9 — wall-clock window in milliseconds within which
         * [BREAKER_410_STORM_THRESHOLD] 410s constitute a storm.
         * Mirrors the L8 "three consecutive 410s within 30 s →
         * ceiling" trigger so the breaker and L8 fire on the same
         * condition.
         */
        const val BREAKER_410_STORM_WINDOW_MS: Long = 30_000L

        /**
         * L8 + L9 — cooldown for a [BreakerOpenReason.Status410Storm]
         * opening. Matches the L8 410-reauth-interval ceiling so the
         * breaker exit timer aligns with the L8 backoff exit timer.
         */
        const val BREAKER_410_STORM_COOLDOWN_MS: Long = 60_000L

        /**
         * L9 — number of probe polls issued per
         * [LongPollBreakerState.HalfOpen] entry. Exactly one. The
         * permit is enforced by the `probeInFlight` Boolean under
         * `_inboundStateMutex`; the constant exists so the M-B18
         * pin can grep-match the value.
         */
        const val BREAKER_HALFOPEN_PROBE_BUDGET: Int = 1

        /**
         * L8 + D11 + M-B24 — hard cap on a relay-supplied
         * `Retry-After` header value, in seconds. The orchestrator
         * clamps `retryAfterSeconds` to this value BEFORE multiplying
         * by 1_000L so the multiplication can never overflow `Long`
         * regardless of the advertised value, AND a misconfigured or
         * hostile relay sending `Retry-After: 86400` (one day) cannot
         * lock the client out for a day. 120 s matches
         * [BREAKER_COOLDOWN_CEILING_MS] expressed in seconds — the
         * 429 cap and the breaker cap share one ceiling.
         */
        const val RETRY_AFTER_HARD_CAP_SECONDS: Long = 120L

        /**
         * Trek 2 Stage 2B-B (C5, M-B24) — clamp a relay-supplied
         * `Retry-After` value (already parsed to seconds; `null` when
         * absent / malformed) to a safe millisecond delay.
         *
         * Returns `null` when [retryAfterSeconds] is `null` so the
         * caller can fall back to its own backoff. Returns the
         * milliseconds delay equal to
         * `retryAfterSeconds.coerceAtMost(RETRY_AFTER_HARD_CAP_SECONDS) * 1000L`
         * when the value is non-null.
         *
         * Clamping FIRST then multiplying is load-bearing:
         * `Long.MAX_VALUE * 1000L` would overflow, but
         * `120L * 1000L` is safe. The clamp must run on the seconds
         * value, not on the milliseconds value, because the wire
         * value arrives in seconds and the overflow risk is on the
         * multiplication.
         *
         * Pure function so M-B24's overflow-safety sub-cell can
         * exercise the clamp with `Long.MAX_VALUE` without standing
         * up a transport.
         */
        fun clampRetryAfterMs(retryAfterSeconds: Long?): Long? {
            if (retryAfterSeconds == null) return null
            val clamped = retryAfterSeconds.coerceAtMost(RETRY_AFTER_HARD_CAP_SECONDS)
            return clamped * 1_000L
        }

        /**
         * Trek 2 Stage 2B-B (C5, L8) — parse a raw `Retry-After`
         * header value into a non-negative `Long` seconds count, or
         * `null` for any malformed or non-numeric input.
         *
         * Normalisation rules (M-B24 sub-cell (b)):
         *   * `null` / empty / whitespace-only → `null`.
         *   * Non-numeric (HTTP-date form, alphabetic, mixed) → `null`.
         *   * Negative or zero → `null` (callers fall back to their
         *     own backoff rather than poll the relay immediately).
         *   * Non-negative integer → the parsed value.
         *
         * Kept on the companion so the Android transport's parse
         * path and tests share one source of truth. Per scope §L8
         * the orchestrator clamps the parsed value via
         * [clampRetryAfterMs] before applying it.
         */
        fun parseRetryAfterHeader(rawHeader: String?): Long? {
            if (rawHeader.isNullOrBlank()) return null
            val parsed = rawHeader.trim().toLongOrNull() ?: return null
            if (parsed <= 0L) return null
            return parsed
        }

        private fun defaultNowMs(): Long =
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

        /**
         * Trek 2 Stage 2B-B (C6, L10) — discretisation resolution
         * for the CSPRNG-backed jitter draw. 10_000 buckets give
         * step ≈ 0.00004 (~0.004 % of the multiplier band),
         * finer than ms-resolution `delay(...)` would resolve.
         */
        internal const val JITTER_RESOLUTION: Int = 10_000

        /**
         * Trek 2 Stage 2B-B (C6, L10) — map a [Csprng.uniformLong]
         * draw in `[0, JITTER_RESOLUTION)` onto the jitter band
         * `[0.8, 1.2)`. Pure function so M15 can pin the mapping
         * without standing up an orchestrator.
         */
        internal fun jitterFactorFor(value: Long): Double {
            require(value in 0L until JITTER_RESOLUTION.toLong()) {
                "jitter draw out of range [0, $JITTER_RESOLUTION): $value"
            }
            return 0.8 + (value.toDouble() / JITTER_RESOLUTION) * 0.4
        }

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
