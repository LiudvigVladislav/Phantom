// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for the relay's `/prekeys` REST surface (publish, bundle,
 * status, opk-delete).
 *
 * Mirrors the wire types defined server-side in
 * `services/relay/src/prekeys.rs` (PR B + signing_pubkey_hex backport
 * from PR C commit 3). This client is used by:
 *
 *  - The lifecycle service (commit 13) for onboarding publish, weekly
 *    SPK rotation, and OPK pool refill at < 20 remaining.
 *  - The SessionManager initiator path (commit 10) to fetch a peer's
 *    [PreKeyBundle] before running X3DH 4-DH.
 *
 * The constructor takes an [HttpClient] so platform-specific configuration
 * (logging, retry, OkHttp interceptors) is injected from the AppContainer
 * rather than baked in here. The same client instance is reused across
 * the WebSocket transport and this REST client, matching the existing
 * pattern in [KtorRelayTransport].
 *
 * The `relayBaseUrl` is the http(s):// origin — the WebSocket origin is
 * ws(s):// and lives on the same Caddy/host pair, so callers typically
 * derive one from the other in the AppContainer wiring.
 */
/**
 * Surface that the prekey REST client exposes to its consumers
 * (lifecycle service, SessionManager bootstrap path via DMS). Extracted
 * as an interface in PR C commit 11 so DMS tests can swap in an
 * in-memory fake without spinning up an HttpClient + MockEngine.
 *
 * The HTTP-backed implementation is [PreKeyApiClient]; production code
 * wires a single instance via AppContainer.
 */
interface PreKeyApi {
    /**
     * Publish a freshly-built [PublishRequest] to the relay, retrying
     * transient transport / 408 / 5xx failures up to
     * [PreKeyApiClient.PUBLISH_MAX_ATTEMPTS] times.
     *
     * Sprint 2b L1 (publish-snapshot consistency): the request is
     * supplied as a factory lambda rather than a pre-built value. The
     * implementation invokes [requestProvider] **once per retry attempt**
     * so the OPK list serialized on attempt N reflects the local pool
     * state at the start of attempt N — including any OPK that was
     * locally consumed by an inbound bootstrap path between attempt N-1
     * and attempt N. Pre-Sprint-2b the request body was captured ONCE
     * before the retry loop and replayed identically; that allowed a
     * later retry to succeed server-side after a local consume, restoring
     * the relay-side pool to a stale snapshot that included the already-
     * consumed OPK — the root cause of the 2026-06-15 integration smoke
     * `errorClass=OpkNotFound action=fall_through_to_hold` shape (see
     * `docs/tracks/sprint-2b-opk-pending-session-scope.md` L1 + the
     * ADR-009 publish protocol amendment).
     */
    /**
     * RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25) amend: when
     * `forceJoinInFlight == true`, a debounce hit (another publish
     * already running for this identity) does NOT short-circuit with
     * [PublishResult.Deferred]. Instead the call blocks on the publish
     * mutex until the in-flight publish releases it, then runs its
     * own [PreKeyApiClient.PUBLISH_MAX_ATTEMPTS] retry cycle. Used by
     * the verify-driven republish path when the relay status check
     * reports zero record (`spk_age_days == null AND opks_remaining
     * == 0`) — that signal is unambiguous and the republish must not
     * be silently dropped by the debounce gate.
     *
     * When `forceJoinInFlight == false` (the default for all other
     * call sites — bootstrap, replenish, rotate), a debounce hit
     * returns [PublishResult.Deferred] without contention. Default
     * preserves the historical semantics of the non-force callers.
     */
    suspend fun publishBundle(
        forceJoinInFlight: Boolean = false,
        requestProvider: suspend () -> PublishRequest,
    ): PublishResult
    suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
    ): PreKeyBundle?
    suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
    ): PreKeyStatus
}

class PreKeyApiClient(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    /**
     * Native transport for POST /prekeys/publish. When non-null this
     * transport is used exclusively for the publish call; all other
     * endpoints keep using [httpClient].
     *
     * Production code passes [createPreKeyPublishHttpTransport()] here
     * (PR-R0.1). The native transport bypasses Ktor's body-streaming path
     * (which stalls at 8192 bytes on Android — Test #43, 2026-05-15) and
     * hands a pre-built ByteArray to OkHttp for a single-shot write.
     *
     * Null causes publishWithRetry() to throw — production code must always
     * supply a transport. Tests inject a fake implementation.
     *
     * @see createPreKeyPublishHttpTransport
     */
    private val publishTransport: PreKeyPublishHttpTransport? = null,
    /**
     * T2 carrier-ceiling instrumentation client-side gate (2026-06-16
     * Option A Item 3 scope-lock). When `true`, `publishWithRetry`
     * emits an additional `T2_DIAG_PUBLISH_TRACE` log line per attempt
     * carrying: client-side request_id, attempt N/M, negotiated HTTP
     * protocol from `PreKeyPublishHttpResponse.protocol`, body bytes,
     * elapsed_ms at success/failure, and the failure-class exception
     * simpleName on retry-triggering throws.
     *
     * Default `false` so unit tests and any non-Android consumer get
     * zero new log noise. Android AppContainer wires this from
     * `BuildConfig.DEBUG && BuildConfig.RELAY_T2_DIAG_CLIENT == "1"`.
     * Release builds (where `BuildConfig.DEBUG == false` AND
     * `BuildConfig.RELAY_T2_DIAG_CLIENT == "0"`) compute `false` from
     * either half of the AND and emit no T2 trace overhead.
     *
     * The existing `PREKEY_TRACE` log lines (`prekey_publish_start`,
     * `prekey_publish_retry`, `prekey_publish_ok`, etc.) are unchanged
     * regardless of this flag. The T2 trace is an additive diagnostic
     * channel.
     */
    private val t2DiagPublishTraceEnabled: Boolean = false,
) : PreKeyApi {

    // One in-flight publish per PreKeyApiClient instance at a time.
    //
    // WHY: Caddy access logs from Tele2 LTE Test #42 showed two parallel
    // POSTs from the same identity at the exact same millisecond (ports
    // 16889 + 16890). Both arrived from a parallel reconnect generation
    // calling publishPreKeys() simultaneously. Even after the body-stuck
    // fix, a race wastes bandwidth and battery and can cause the relay to
    // see two concurrent publishes for the same identity.
    //
    // The Mutex serialises all publishBundle calls on this client instance.
    // AppContainer wires a single PreKeyApiClient per identity, so this is
    // effectively per-identity serialisation. If a second call arrives while
    // one is in-flight it is debounced (logged and returns immediately) rather
    // than queued — queueing a publish that will be superseded by the one
    // in-flight is pointless and burns extra bandwidth. PR-R0.
    // internal for testing only — do not access from production call sites.
    internal val publishMutex = Mutex()

    /**
     * Publish (or refresh) the local SignedPreKey + a batch of OneTimePreKeys.
     *
     * The batch fully replaces any previously-published OPK pool for this
     * identity (relay semantics, see ADR-009 + PR B). The client is
     * responsible for re-uploading any OPKs it still holds locally if it
     * wants them all to remain available — the lifecycle service already
     * does this on every publish.
     *
     * PR-R0 / PR-R0.1 additions:
     *  - PR-R0.1: uses [publishTransport] (native OkHttp, pre-built ByteArray)
     *    to bypass the Ktor body-streaming 8192-byte stall (Test #43).
     *  - Mutex-guarded: at most one in-flight publish per client instance.
     *    Duplicate concurrent calls are debounced (logged + skipped).
     *  - Retry with exponential backoff on transient failures: up to
     *    [PUBLISH_MAX_ATTEMPTS] total, delays [PUBLISH_RETRY_DELAYS_MS].
     *    Retried: SocketTimeoutException, IOException (connection reset /
     *    broken pipe), HTTP 408, HTTP 5xx. NOT retried: HTTP 400/401/403/422.
     *
     * Sprint 2b L1 (publish-snapshot consistency): [requestProvider] is
     * invoked ONCE PER RETRY ATTEMPT inside [publishWithRetry]; the body
     * is no longer captured-once-before-the-loop. See [PreKeyApi.publishBundle]
     * KDoc + scope-doc L1 for the rationale.
     *
     * @return [PublishResult.Stored] with the count of OPKs the relay
     *         retained (after dedup + cap), or [PublishResult.Failure]
     *         describing why the relay rejected the bundle.
     */
    override suspend fun publishBundle(
        forceJoinInFlight: Boolean,
        requestProvider: suspend () -> PublishRequest,
    ): PublishResult {
        // Debounce: if another publish is already running for this identity,
        // the default path returns [PublishResult.Deferred] without queueing.
        // The in-flight publish will leave the relay in the correct state
        // when it succeeds; if it fails, the verify-driven republish path
        // (which calls back here with `forceJoinInFlight = true`) waits on
        // the mutex and runs its own attempt.
        //
        // PreKeyApiClient is wired per-identity by AppContainer (see comment
        // above publishMutex), so the debounce log omits the identity tag —
        // operator can correlate via the instance / process boundary.
        //
        // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25): the pre-fix shape
        // returned `PublishResult.Stored(storedOpks = 0)` from this branch.
        // That was a dishonest synthetic success: downstream consumers
        // (PreKeyLifecycleService) logged `PREKEY_TRACE upload_ok stored_opks=0`,
        // making the post-failure debounce look like a successful publish in
        // operator-readable telemetry. The 2026-06-25 baseline-blocker field
        // smoke proved the consequence — the verify-driven republish triggered
        // when the relay had zero record, got debounced as the in-flight POST
        // was already pending, was logged as success, and then the in-flight
        // POST died with `ConnectException ECONNREFUSED` seven seconds later
        // with no further attempt. Bundle never reached the relay. See
        // `docs/tracks/rc-prekey-publish-debounce-race.md` §8 for the full
        // timeline.
        if (!publishMutex.tryLock()) {
            if (!forceJoinInFlight) {
                relayLog(
                    RelayLogLevel.INFO,
                    "PREKEY_TRACE prekey_publish_debounced",
                )
                // Honest result: NOT stored. The in-flight publish may yet
                // fail. Callers that need a guaranteed publish must retry
                // with `forceJoinInFlight = true`.
                return PublishResult.Deferred
            }
            // Force-join path (verify-republish when relay has zero record).
            // Wait for the in-flight publish to release the mutex, then run
            // OUR OWN publish attempt. The in-flight publish may have just
            // succeeded (our attempt is redundant — one extra POST is the
            // cost of guaranteed correctness) or failed (our attempt is the
            // load-bearing one — without it the bundle stays missing from
            // the relay). The two states are indistinguishable from the
            // mutex alone, so we always re-publish on the force path.
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_debounced force_join=true",
            )
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_force_join_wait",
            )
            publishMutex.lock()
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_force_join_acquired",
            )
        }

        // Lock acquired via tryLock() OR via the force-join lock() above.
        // Run the work and unconditionally unlock in the finally block —
        // Mutex.tryLock() / Mutex.lock() do NOT use the owner token
        // mechanism, so we must call unlock() directly (not withLock{}).
        return try {
            publishWithRetry(requestProvider)
        } finally {
            publishMutex.unlock()
        }
    }

    private suspend fun publishWithRetry(
        requestProvider: suspend () -> PublishRequest,
    ): PublishResult {
        val url = "$relayBaseUrl/prekeys/publish"
        val totalStartMs = Clock.System.now().toEpochMilliseconds()
        var lastException: Throwable? = null
        // identity is stable across retries (same identity hex from the
        // lifecycle service); we capture the tag from the first attempt's
        // body for the give-up log. Initialised on attempt 1.
        var identityTag = ""
        // T2 carrier-ceiling instrumentation (2026-06-16 Option A Item 3).
        // Generate a single client-side request_id at the start of the
        // entire publishWithRetry call so ALL attempts (and any subsequent
        // T2_DIAG_PUBLISH_TRACE lines on retries) share the same id. An
        // operator correlates these with the server-side
        // `event=t2_diag_publish_chunk` / `_body_complete` / `_timeout`
        // lines by timestamp: the relay generates its own request_id per
        // POST, and the two sides cannot exchange ids transparently
        // through OkHttp + Caddy without a custom header. Timestamp
        // correlation is sufficient because only one publish per
        // identity can be in-flight at a time (the publishMutex).
        //
        // The id is 16 hex chars: epoch-millis low 32 bits + a random low
        // 32 bits. Enough entropy to uniquely identify one publish cycle
        // within a 30 s diagnostic window without a counter dependency.
        val t2DiagRequestId: String = if (t2DiagPublishTraceEnabled) {
            val high = (totalStartMs and 0xFFFFFFFFL).toString(16).padStart(8, '0')
            val low = kotlin.random.Random.Default.nextInt().toUInt()
                .toString(16).padStart(8, '0')
            "$high$low"
        } else {
            ""
        }

        val transport = publishTransport
            ?: error("PreKeyApiClient requires a publishTransport (PR-R0.1). " +
                "Pass createPreKeyPublishHttpTransport() from AppContainer.")

        for (attempt in 1..PUBLISH_MAX_ATTEMPTS) {
            // Sprint 2b L1: re-snapshot the PublishRequest on EVERY attempt.
            // Calling requestProvider() rebuilds the OPK list from the
            // backing repository (in PreKeyLifecycleService:
            // { oneTimePreKeyRepository.getAll() }), so attempt N's body
            // reflects any local OPK consumption that happened since
            // attempt N-1. Pre-Sprint-2b the body was captured once before
            // the loop and replayed identically — the 2026-06-15 smoke
            // root cause. See ADR-009 publish protocol amendment.
            val request = requestProvider()
            if (identityTag.isEmpty()) {
                identityTag = request.identity_pubkey_hex.take(16)
            }
            val bodyJson = json.encodeToString(PublishRequest.serializer(), request)
            // Pre-build the byte array so OkHttp can write it in one shot.
            // This is the PR-R0.1 fix: Ktor streams the body through a ByteWriteChannel
            // that stalls at 8192 bytes on Android; native OkHttp writes the full
            // ByteArray via okio.Buffer.writeAll() without any streaming.
            val bodyBytes = bodyJson.encodeToByteArray()
            if (bodyBytes.size >= 7800) {
                relayLog(
                    RelayLogLevel.WARN,
                    "PREKEY_TRACE prekey_publish_body_near_carrier_limit bodyBytes=${bodyBytes.size} threshold=7800 attempt=$attempt note=may_hit_tele2_8192_byte_cut",
                )
            }
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_start native=true identity=$identityTag… " +
                    "opks=${request.one_time_pre_keys.size} " +
                    "spk_key_id=${request.signed_pre_key.key_id} " +
                    "bodyBytes=${bodyBytes.size} attempt=$attempt/${PUBLISH_MAX_ATTEMPTS}",
            )
            // T2 diag — emit the gated start trace once per attempt. See
            // BuildConfig.RELAY_T2_DIAG_CLIENT (Android-side) for the wire.
            if (t2DiagPublishTraceEnabled) {
                relayLog(
                    RelayLogLevel.INFO,
                    "T2_DIAG_PUBLISH_TRACE phase=attempt_start " +
                        "request_id=$t2DiagRequestId identity=$identityTag… " +
                        "attempt=$attempt/${PUBLISH_MAX_ATTEMPTS} " +
                        "bodyBytes=${bodyBytes.size}",
                )
            }
            val attemptStartMs = Clock.System.now().toEpochMilliseconds()

            val nativeResp: PreKeyPublishHttpResponse = try {
                // T2 diagnostic round 2 — plumb the client-side
                // `request_id` through the transport so the five
                // `T2_PUBLISH_PHASE` log lines emitted inside the
                // Android impl correlate exactly with the existing
                // `T2_DIAG_PUBLISH_TRACE` lines by id. Empty string
                // disables phase trace emission inside the impl —
                // production release builds AND any test that didn't
                // opt into T2 diag trace will pass `""` here.
                transport.publish(
                    url = url,
                    bodyBytes = bodyBytes,
                    requestId = t2DiagRequestId,
                )
            } catch (t: Throwable) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - attemptStartMs
                lastException = t
                // T2 diag — emit the gated failure trace once per attempt
                // that throws (before the existing PREKEY_TRACE retry log
                // line fires). The failure-class simpleName is the
                // discriminator we need to attribute the stall: a
                // SocketTimeoutException means OkHttp's writeTimeout
                // fired client-side; a different exception class on a 60 s
                // budget would suggest a server-side or middlebox
                // truncation that arrived as a different signal.
                if (t2DiagPublishTraceEnabled) {
                    relayLog(
                        RelayLogLevel.INFO,
                        "T2_DIAG_PUBLISH_TRACE phase=attempt_threw " +
                            "request_id=$t2DiagRequestId identity=$identityTag… " +
                            "attempt=$attempt/${PUBLISH_MAX_ATTEMPTS} " +
                            "elapsedMs=$elapsed " +
                            "exception_class=${t::class.simpleName ?: "<anon>"} " +
                            "protocol=unknown_pre_response",
                    )
                }
                if (attempt < PUBLISH_MAX_ATTEMPTS && t.isRetryable()) {
                    val nextDelay = PUBLISH_RETRY_DELAYS_MS[attempt - 1]
                    relayLog(
                        RelayLogLevel.WARN,
                        "PREKEY_TRACE prekey_publish_retry identity=$identityTag… " +
                            "reason=${t::class.simpleName} attempt=$attempt " +
                            "next_delay_ms=$nextDelay elapsedMs=$elapsed",
                        t,
                    )
                    delay(nextDelay)
                    continue
                } else {
                    val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
                    relayLog(
                        RelayLogLevel.WARN,
                        "PREKEY_TRACE prekey_publish_fail_giving_up identity=$identityTag… " +
                            "total_elapsedMs=$totalElapsed attempts=$attempt " +
                            "last_exception=${t::class.simpleName}: ${t.message ?: "<null>"}",
                        t,
                    )
                    throw t
                }
            }

            // We have a response — check if it warrants a retry.
            val elapsed = Clock.System.now().toEpochMilliseconds() - attemptStartMs
            val statusValue = nativeResp.statusCode

            // T2 diag — emit the gated response trace once per attempt
            // that produced an HTTP response (even retryable 408/5xx).
            // The protocol field discriminates `http/1.1` / `h2` / `h3`:
            // a stall observed only on h2 + h1.1 but absent on h3 would
            // suggest the carrier mechanism is TCP byte-budget enforcement
            // bypassable by QUIC.
            if (t2DiagPublishTraceEnabled) {
                relayLog(
                    RelayLogLevel.INFO,
                    "T2_DIAG_PUBLISH_TRACE phase=attempt_response " +
                        "request_id=$t2DiagRequestId identity=$identityTag… " +
                        "attempt=$attempt/${PUBLISH_MAX_ATTEMPTS} " +
                        "status=$statusValue " +
                        "elapsedMs=$elapsed " +
                        "protocol=${nativeResp.protocol ?: "<absent>"}",
                )
            }

            // HTTP 408 or 5xx: server-side transient. Retry if budget remains.
            if ((statusValue == 408 || statusValue in 500..599) &&
                attempt < PUBLISH_MAX_ATTEMPTS
            ) {
                val nextDelay = PUBLISH_RETRY_DELAYS_MS[attempt - 1]
                relayLog(
                    RelayLogLevel.WARN,
                    "PREKEY_TRACE prekey_publish_retry identity=$identityTag… " +
                        "reason=http$statusValue attempt=$attempt " +
                        "next_delay_ms=$nextDelay elapsedMs=$elapsed",
                )
                delay(nextDelay)
                continue
            }

            // Non-retryable or final attempt — process the result.
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_ok native=true identity=$identityTag… " +
                    "status=$statusValue elapsedMs=${nativeResp.elapsedMs} attempt=$attempt",
            )
            return when (statusValue) {
                201 -> {
                    // T2 carrier-ceiling fix (2026-06-16, post Phase 1+2
                    // field evidence). The Android publish transport
                    // skips the response body read on 2xx because the
                    // Tele2 LTE carrier middlebox stalls the body
                    // bytes after the 201 headers, producing a 60 s
                    // `SocketTimeoutException` in `FixedLengthSource.read`.
                    // When `bodyText` is empty we synthesise
                    // `PublishResult.Stored(storedOpks = 0)` instead of
                    // calling `decodeFromString(PublishResponse, "")`
                    // which would throw `JsonDecodingException`. The
                    // `storedOpks` value is informational only — the
                    // server contract is "201 == stored, REPLACE
                    // wholesale" so the publish-success branch does
                    // not need the count. Non-Android transports
                    // (iOS / JVM stubs) preserve the JSON-decode path
                    // because they do not skip the body read.
                    if (nativeResp.bodyText.isEmpty()) {
                        PublishResult.Stored(storedOpks = 0)
                    } else {
                        val parsed = json.decodeFromString(
                            PublishResponse.serializer(),
                            nativeResp.bodyText,
                        )
                        PublishResult.Stored(parsed.storedOpks)
                    }
                }
                409 ->
                    PublishResult.Failure(
                        PublishResult.Reason.SigningKeyMismatch,
                        nativeResp.bodyText,
                    )
                429 ->
                    PublishResult.Failure(
                        PublishResult.Reason.RateLimited,
                        nativeResp.bodyText,
                    )
                400, 413 ->
                    PublishResult.Failure(
                        PublishResult.Reason.BadRequest,
                        nativeResp.bodyText,
                    )
                else ->
                    PublishResult.Failure(
                        PublishResult.Reason.Unexpected(statusValue),
                        nativeResp.bodyText,
                    )
            }
        }

        // Unreachable: the loop either returns or throws. Satisfy the compiler.
        val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
        val ex = lastException ?: IllegalStateException("publishWithRetry exhausted loop without result")
        relayLog(
            RelayLogLevel.WARN,
            "PREKEY_TRACE prekey_publish_fail_giving_up identity=$identityTag… " +
                "total_elapsedMs=$totalElapsed attempts=$PUBLISH_MAX_ATTEMPTS " +
                "last_exception=${ex::class.simpleName}: ${ex.message ?: "<null>"}",
        )
        throw ex
    }

    /**
     * Returns true if this throwable should trigger a publish retry.
     *
     * Retried:
     *  - SocketTimeoutException — body upload stall (Tele2 LTE bug, PR-R0)
     *    or read timeout on response headers.
     *  - IOException with "connection reset" or "broken pipe" in the message —
     *    TCP connection torn down by NAT/carrier mid-request.
     *
     * NOT retried (server says the request itself was bad):
     *  - HTTP 400 Bad Request, 401 Unauthorized, 403 Forbidden, 422
     *    Unprocessable Entity — retrying with the same payload won't help.
     *  - These do not reach this function; the HTTP-status branch in
     *    [publishWithRetry] handles them as non-retryable directly.
     *
     * Note: class-name matching is used instead of `is java.io.*` to keep
     * this function in commonMain (no JVM-only imports). The names
     * "SocketTimeoutException" and "IOException" are stable across JDK
     * versions and Android runtime versions.
     */
    private fun Throwable.isRetryable(): Boolean {
        val simpleName = this::class.simpleName ?: ""
        if (simpleName == "SocketTimeoutException") return true
        // Match IOException and its subclasses by checking the chain of
        // class simple names. SocketException, ConnectException, etc. are
        // all subclasses of IOException; we want them too when their message
        // indicates a network-layer teardown.
        val msg = message?.lowercase() ?: ""
        val isIoLike = simpleName.endsWith("IOException") ||
            simpleName.endsWith("SocketException") ||
            simpleName == "EOFException"
        if (isIoLike && ("connection reset" in msg || "broken pipe" in msg)) return true
        return false
    }

    /**
     * Fetch a peer's [PreKeyBundle] — the server atomically consumes one
     * OPK from the peer's pool and returns it together with the SPK.
     *
     * Returns null on 404 (peer has not published yet or pool was cleared).
     * Other non-2xx codes propagate as a [BundleFetchException] so callers
     * can distinguish "transient relay error" from "peer not on Alpha 2."
     */
    override suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): PreKeyBundle? {
        val url = buildString {
            append(relayBaseUrl)
            append("/prekeys/bundle/")
            append(identityPubkeyHex)
            if (requesterPubkeyHex != null) {
                append("?requester=")
                append(requesterPubkeyHex)
            }
        }
        val identityTag = identityPubkeyHex.take(16)
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_bundle_fetch_start identity=$identityTag… url=$url",
        )
        val startMs = Clock.System.now().toEpochMilliseconds()
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
            relayLog(
                RelayLogLevel.WARN,
                "PREKEY_TRACE http_bundle_fetch_fail identity=$identityTag… " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_bundle_fetch_done identity=$identityTag… status=${response.status.value} elapsedMs=$elapsed",
        )
        return when (response.status) {
            HttpStatusCode.OK ->
                json.decodeFromString(PreKeyBundle.serializer(), response.bodyAsText())
            HttpStatusCode.NotFound -> null
            HttpStatusCode.TooManyRequests ->
                throw BundleFetchException.RateLimited(response.bodyAsText())
            else ->
                throw BundleFetchException.Unexpected(
                    response.status.value,
                    response.bodyAsText(),
                )
        }
    }

    /**
     * Query how many OPKs the relay still holds for a given identity, and
     * how old its current SPK is in days. The lifecycle service uses this
     * to decide replenish (count < 20) and SPK rotation (age >= 7 days)
     * without uploading anything.
     */
    override suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): PreKeyStatus {
        val url = buildString {
            append(relayBaseUrl)
            append("/prekeys/status/")
            append(identityPubkeyHex)
            if (requesterPubkeyHex != null) {
                append("?requester=")
                append(requesterPubkeyHex)
            }
        }
        val identityTag = identityPubkeyHex.take(16)
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_status_start identity=$identityTag… url=$url",
        )
        val startMs = Clock.System.now().toEpochMilliseconds()
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
            relayLog(
                RelayLogLevel.WARN,
                "PREKEY_TRACE http_status_fail identity=$identityTag… " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_status_done identity=$identityTag… status=${response.status.value} elapsedMs=$elapsed",
        )
        return when (response.status) {
            HttpStatusCode.OK ->
                json.decodeFromString(PreKeyStatus.serializer(), response.bodyAsText())
            HttpStatusCode.TooManyRequests ->
                throw BundleFetchException.RateLimited(response.bodyAsText())
            else ->
                throw BundleFetchException.Unexpected(
                    response.status.value,
                    response.bodyAsText(),
                )
        }
    }

    companion object {
        /** Total publish attempts (1 initial + 2 retries). PR-R0. */
        const val PUBLISH_MAX_ATTEMPTS: Int = 3

        /**
         * Delays in milliseconds between consecutive publish attempts.
         * Index i is the delay BEFORE attempt i+2 (i.e., after attempt i+1 fails).
         * Length must be >= PUBLISH_MAX_ATTEMPTS - 1. PR-R0.
         */
        val PUBLISH_RETRY_DELAYS_MS: LongArray = longArrayOf(500L, 1500L, 3000L)
    }
}

// ── Wire types ───────────────────────────────────────────────────────────────

/**
 * Wire-format SignedPreKey published by the user. Mirrors the relay's
 * `SignedPreKeyPublicBundle` and the client's
 * `phantom.core.crypto.SignedPreKeyPublicBundle`.
 */
@Serializable
data class WireSignedPreKey(
    val key_id: Long,
    val public_key_hex: String,
    val created_at_ms: Long,
    val signature_hex: String,
)

@Serializable
data class WireOneTimePreKey(
    val key_id_hex: String,
    val public_key_hex: String,
)

@Serializable
data class PublishRequest(
    val identity_pubkey_hex: String,
    val signing_pubkey_hex: String,
    val signed_pre_key: WireSignedPreKey,
    val one_time_pre_keys: List<WireOneTimePreKey>,
)

@Serializable
internal data class PublishResponse(
    val stored_opks: Int,
) {
    val storedOpks: Int get() = stored_opks
}

@Serializable
data class PreKeyBundle(
    val identity_pubkey_hex: String,
    val signing_pubkey_hex: String,
    val signed_pre_key: WireSignedPreKey,
    val one_time_pre_key: WireOneTimePreKey? = null,
)

@Serializable
data class PreKeyStatus(
    val remaining_opks: Int,
    val signed_prekey_age_days: Long? = null,
)

// ── Result + exception types ─────────────────────────────────────────────────

sealed class PublishResult {
    data class Stored(val storedOpks: Int) : PublishResult()
    data class Failure(val reason: Reason, val serverMessage: String) : PublishResult()

    /**
     * RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25). Returned by
     * [PreKeyApi.publishBundle] when an in-flight publish is already
     * running for this identity AND the caller did NOT request the
     * force-join path (`forceJoinInFlight = false`). The caller MUST
     * NOT treat this as a successful storage — the in-flight publish
     * may yet fail with a transport error, leaving the relay without
     * the bundle. Pre-fix the implementation returned a synthetic
     * `Stored(storedOpks = 0)` here, which downstream consumers logged
     * as `PREKEY_TRACE upload_ok stored_opks=0`; that telemetry
     * obscured the failure mode behind the 2026-06-25 baseline-blocker
     * field-smoke verdict.
     *
     * Callers that need a guaranteed publish (e.g. verify-driven
     * republish after the relay reported zero record:
     * `spk_age_days=null AND opks_remaining=0`) MUST pass
     * `forceJoinInFlight = true` — that path waits for the in-flight
     * publish to release the mutex, then runs its own publish attempt
     * so the caller's intent is honoured regardless of the in-flight
     * outcome.
     */
    data object Deferred : PublishResult()

    sealed class Reason {
        /** Relay already bound a different signing key to this identity. */
        data object SigningKeyMismatch : Reason()
        data object RateLimited : Reason()
        data object BadRequest : Reason()
        data class Unexpected(val httpStatus: Int) : Reason()
    }
}

sealed class BundleFetchException(message: String) : Exception(message) {
    class RateLimited(serverMessage: String) :
        BundleFetchException("rate limited: $serverMessage")
    class Unexpected(val httpStatus: Int, serverMessage: String) :
        BundleFetchException("unexpected http $httpStatus: $serverMessage")
}

/**
 * Helper: build an [HttpClient] suitable for prekey REST calls. Platform
 * implementations (transport factory) usually share a single client across
 * WebSocket and REST surfaces; this helper exists for tests that want a
 * minimal wired client.
 */
fun buildJsonHttpClient(httpClient: HttpClient): HttpClient = httpClient.config {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
}
