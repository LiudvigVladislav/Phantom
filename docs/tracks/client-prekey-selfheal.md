# Track: `CLIENT-PREKEY-SELFHEAL` — mini-lock

**Type:** Docs / scope-lock. NOT a code change.
**Status:** DESIGN LOCKED 2026-07-16 (architect GREEN verdict).
**Scope:** client-side (Android / KMP shared-core). Server-side relay unchanged.
**Locked against master pin:** `47b1686a` (prekey code identical to post-PR-1a master `7b673288`).
**Orthogonal to server sequence:** PR-0 → PR-1b → Ops PR → PR-2 → PR-2.5 → PR-1c. This track does NOT block and is NOT blocked by any of them.

**Implementation:** requires **separate architect greenlight**. This mini-lock approves DESIGN ONLY — not the eventual Kotlin patch, not the Gradle task shape, not any deploy step.

---

## §1 Goal

Close the client-side prekey self-heal failure witnessed on Emu 2026-07-15: an SSL handshake failure on a single `/prekeys/status` GET dropped `verifyBundleOnRelay` silently, and because WebSocket transport stayed `Connected` throughout, no re-verify trigger fired. The gap persisted until the user manually force-stopped and relaunched the app. Fixing this requires client changes across three axes: transport-level retry with a bounded per-attempt deadline; a periodic verify safety ticker independent of transport-state changes; and a corrected budget/single-flight/cancellation state machine so the fix itself does not silently regress.

## §2 What this mini-lock is NOT

- **Not a server change.** Relay `/prekeys/status` contract unchanged. No `Retry-After` emission at server (separate follow-up).
- **Not a WorkManager / foreground-service migration.** `appScope` is alive throughout the observed failure; the defect is inside-scope logic. Tier-3 process-death hardening deferred.
- **Not the poison-envelope PR-1c track.** Different code path (inbound decrypt/ack loop), different failure mode. Do NOT conflate.
- **Not PR-2.5 scope.** Server-side prekey persist durability is a separate track. This client fix works regardless of whether the relay ever loses a stored bundle.

## §3 Code recon (verified 2026-07-15 against master `47b1686a`)

### §3.1 Actor inventory

- `PreKeyLifecycleService` — `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt` (585 lines).
- `PreKeyApiClient` — `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt` (784 lines).
- `AppContainer` wiring — `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` (2469 lines). Prekey sections: line 82 (appScope), 1974-2040 (24-h ticker), 2276-2299 (Connected collect hook).
- `TransportState` — `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportState.kt`. Sealed class; `Connected` is a singleton `object` so StateFlow deduplicates equal consecutive values.
- Relay contract — `services/relay/src/prekeys.rs:367-378` (`status()` returns `PreKeyStatus { remaining_opks: 0, signed_prekey_age_days: None }` iff no record); `services/relay/src/routes.rs:1743-1744` (`STATUS_RATE_LIMIT = 60` per `STATUS_RATE_WINDOW_SECS = 60` per requester bucket).

### §3.2 Structural gaps

Verified against master via read + grep (2026-07-15):

1. **`fetchStatus` is a single GET with no transient-class retry.** `PreKeyApiClient.kt:617-664` — bare `httpClient.get(url)` inside try/catch, throw propagates.
2. **`isRetryable()` does not match `SSLHandshakeException` or `ConnectException`.** `PreKeyApiClient.kt:538-551` — class-name string match on `SocketTimeoutException` or `IOException`/`SocketException`/`EOFException` with narrow message substring. TLS classes fall through as non-retryable.
3. **24-hour ticker does NOT call `verifyBundleOnRelay`.** `AppContainer.kt:2022-2040` calls only `maybeReplenishOneTimePreKeys` + processed-envelope TTL sweep + `maybeRotateSignedPreKey`.
4. **Only trigger for `verifyBundleOnRelay` is a distinct-value `TransportState.Connected` emission** on `transport.state.collect { }` at `AppContainer.kt:2276-2299`. On-failure branch is `Log.w` and drop; no retry, no reschedule.
5. **`MAX_FORCE_REPUBLISH_PER_SESSION = 5`** at `PreKeyLifecycleService.kt:556` is a per-process ceiling with no decrement, no reset semantic, and burns a slot BEFORE the publish attempt — so 5 transient publish failures exhaust the budget without a single bundle reaching the relay.
6. **OkHttp default `callTimeout = 0`** (no cap on total call). Connect/read/write timeouts are per-operation only. A hung request can hold `verifyMutex` indefinitely.
7. **`PreKeyPublishHttpResponse`** (`PreKeyPublishHttpTransport.kt:61-87`) has `statusCode`/`bodyText`/`elapsedMs`/`protocol` but no `Retry-After` header field.

## §4 Root cause + minimum invariant

**Root cause.** `verifyBundleOnRelay` is trigger-starved. On a REST-layer failure (SSL, timeout, DNS, IO reset) while WebSocket stays `Connected`, StateFlow emits no new value, and the only trigger path never re-fires. The 24-h ticker doesn't cover verify. The reconnect-hook's `runCatching {}.onFailure { logW }` swallows the failure. Bundle gap persists until process restart.

**Minimum invariant restored by this mini-lock**:

> **`Inv-VerifyEventuallyRerunsUnderTransient`**: given a valid local SPK and eventual network reachability, `verifyBundleOnRelay` will re-run within bounded wall time regardless of whether the transport re-emits `Connected`, and its bounded retry budget cannot be silently exhausted by transient network failures alone.

**Bounded wall time upper bound** (all constants derivable from §5):

```
UPPER_BOUND_MS
    = PERIODIC_VERIFY_INTERVAL_MS                                     // 900_000  (15 min)
    + FETCH_STATUS_MAX_ATTEMPTS × FETCH_STATUS_ATTEMPT_DEADLINE_MS    // 3 × 15_000 = 45_000
    + Σ (MAX_ATTEMPTS - 1) delays × (1 + jitter_max_bias)              // (500+1500) × 1.3 = 2_600
    + (MAX_ATTEMPTS - 1) capped Retry-After waits                      // 2 × 60_000 = 120_000
    ≈ 1_067_600 ms
    ≈ 17.8 min worst case
```

## §5 Locked design decisions

- **D1 — Single retry owner.** Retries live only at the transport layer. `fetchStatus`: 3 attempts / 2 sleeps under a per-attempt deadline. `publishWithRetry`: 3 attempts / 2 sleeps (existing shape, taxonomy extended per D5). Periodic 15-minute verify ticker (D3) handles long-tail recovery. **No outer whole-verify burst loop.**

- **D2 — Trigger inventory.** Two `VerifyTrigger` values — `Connected` and `Periodic` — cover three event cases, all routed through the single `verifyBundleOnRelay(trigger: VerifyTrigger)` entry point:
  1. **Initial `Connected`** (startup): `transport.state.collect { if (Connected) verifyBundleOnRelay(VerifyTrigger.Connected) }` at `AppContainer.kt:2276-2299`. First distinct-value emission of `Connected` after container init.
  2. **Subsequent `Connected`** (reconnect): the same `transport.state.collect { }` block. Every later distinct-value `Connected` emission fires an additional `VerifyTrigger.Connected` call through the same collector.
  3. **Periodic tick**: `PreKeyLifecycleService.onPeriodicTick()`, invoked by `appScope.launch { while (isActive) { delay(15 min); lifecycleService.onPeriodicTick() } }` in `AppContainer.initMessaging`. `onPeriodicTick()` emits the `verify_periodic_tick` marker in class scope (where `logObserver` is available) and delegates to `verifyBundleOnRelay(VerifyTrigger.Periodic)`. **First tick after 15 min** — the initial `Connected` case already fires the startup trigger; an immediate periodic tick would race.

- **D3 — Periodic ticker cadence.** 15 minutes. Fires 4 GETs/hour steady-state; up to 12 GET/hour under persistent transient failure (3 retries per tick). First observed hour includes startup trigger for a worst-case ≤15 GETs. All bounded well below relay's 60/60s server rate limit per requester.

- **D4 — Single-flight via `verifyMutex.tryLock`.** Concurrent triggers converge:
  ```kotlin
  private val verifyMutex = Mutex()

  suspend fun verifyBundleOnRelay(trigger: VerifyTrigger): Result<VerifyOutcome> {
      if (!verifyMutex.tryLock()) {
          trace("verify_skipped_in_flight trigger=$trigger")
          return Result.success(VerifyOutcome.SkippedInFlight)
      }
      return try {
          Result.success(verifyLocked(trigger))
      } catch (ce: CancellationException) {
          throw ce
      } catch (t: Throwable) {
          Result.failure(t)
      } finally {
          verifyMutex.unlock()
      }
  }
  ```
  Concurrent trigger receives `VerifyOutcome.SkippedInFlight` (distinct from `Success(false)` or `Republished`). **A new `Connected` emission MUST NOT cancel an in-flight verify**; it either acquires the mutex (fresh run) or observes SkippedInFlight (existing run handles the case).

- **D5 — Retry taxonomy** via a single classifier applied at both `fetchStatus` catch sites (request-read and body-read) AND at `publishWithRetry`:
  ```kotlin
  // shared/core/transport/src/commonMain/.../RetryClassification.kt
  enum class RetryDecision { RetryableTransient, TerminalTls, TerminalOther }
  expect fun classifyNetworkFailure(t: Throwable): RetryDecision
  ```
  **Actuals in three source sets** (verified layout): `shared/core/transport/src/{androidMain,jvmMain,iosMain}/`.
  - androidMain + jvmMain (identical body): walk cause chain (depth 5) checking `SSLPeerUnverifiedException` + `CertificateException` → `TerminalTls`. SSL classes (`SSLException`, `SSLHandshakeException`, `SSLProtocolException`) map to `RetryableTransient` **only if** a transient transport signal is present in the cause chain OR message (case-insensitive substring of `timeout`, `timed out`, `connection reset`, `connection closed`, `broken pipe`, `stream closed`, `closed connection`, `unexpected end of stream`, `eof`); otherwise `TerminalTls`. `SocketTimeoutException`, `ConnectException`, `NoRouteToHostException`, `BindException`, `UnknownHostException`, `EOFException`, Ktor `HttpRequestTimeoutException`, Ktor `ConnectTimeoutException`, `IOException` with transient message → `RetryableTransient`. `FetchStatusDeadlineExceededException` → `TerminalOther`. Everything else → `TerminalOther`.
  - iosMain: conservative placeholder — Ktor Darwin engine exceptions covered (`HttpRequestTimeoutException`, `ConnectTimeoutException`, `SocketTimeoutException`), all other network errors default to `TerminalOther`. Full NSURLError inspection deferred until iOS becomes a shipped target for prekey code (currently Android-only in production).

- **D6 — Retry-After handling.**
  - **`fetchStatus`**: parses `Retry-After` header from `HttpResponse.headers["Retry-After"]` when the response is 429, capped at 60 s. Falls back to `RATE_LIMIT_FALLBACK_MS = 5_000L` (jittered) if header absent.
  - **`publishWithRetry`**: uses `RATE_LIMIT_FALLBACK_MS` (jittered) unconditionally. `PreKeyPublishHttpResponse` DTO does not carry headers — plumbing `Retry-After` through requires touching 3 platform actuals + fake, deferred to the same follow-up PR that adds server-side `Retry-After` emission at the relay.
  - Server-side `Retry-After` emission is a separate follow-up PR (not this track). Client works correctly without it.

- **D7 — Budget accounting.** The `MAX_FORCE_REPUBLISH_PER_SESSION = 5` counter:
  - Increments **exclusively** in `verifyLocked` after `publishBundle(forceJoinInFlight = true)` returns `PublishExecutionOutcome.Stored`. Wrapped in `withContext(NonCancellable) { forceRepublishBudgetMutex.withLock { forceRepublishCount += 1 } }`; followed by `currentCoroutineContext().ensureActive()` so subsequent code still respects cancellation.
  - **Never mutated** by the generic `publishBundle` helper. Bootstrap / replenish / rotate all call `publishBundle(forceJoinInFlight = false)`; their `Stored` branch does NOT touch `forceRepublishCount`.
  - Resets to 0 **only** after a subsequent `verifyBundleOnRelay` observes `fetchStatus` return `signed_prekey_age_days != null` (relay confirmed the persist). **NEVER resets immediately on `Stored`** — a Stored return from the wire does not prove the relay didn't lose the write.
  - Semantic: 5 = max consecutive successful-but-unconfirmed force-republish cycles. A transient publish failure (throw) consumes no slot. Steady state of "publish → confirmed by next verify → reset" produces no accumulation.

- **D8 — Observability.** Every marker goes through a single class-local helper:
  ```kotlin
  private fun trace(marker: String, level: RelayLogLevel = RelayLogLevel.INFO) {
      relayLog(level, marker)   // production log
      logObserver?.invoke(marker) // test observer capture
  }
  ```
  Writes to **both** the production log and the test-observer seam. Callers include the exception class simpleName inline in the marker text; the helper accepts NO `cause: Throwable?` parameter — production logs also receive only class-only markers, no throwable, no message content. Stack-trace preservation is deferred to a future DEBUG-channel-stripped-by-ProGuard PR (out of scope).

## §6 Detailed design

### §6.1 `PreKeyApiClient.fetchStatus` — retry with pinned per-attempt deadline

```kotlin
// Constructor seams (test-injectable):
class PreKeyApiClient(
    // ... existing ctor params ...
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val jitter: (Long) -> Long = { it + (it * (Random.nextDouble(-0.3, 0.3))).toLong() },
    private val logObserver: ((String) -> Unit)? = null,
) : PreKeyApi

// Constants:
companion object {
    const val FETCH_STATUS_MAX_ATTEMPTS: Int = 3
    val FETCH_STATUS_RETRY_DELAYS_MS: LongArray = longArrayOf(500L, 1500L)  // MAX-1 delays
    const val FETCH_STATUS_ATTEMPT_DEADLINE_MS: Long = 15_000L
    const val RATE_LIMIT_FALLBACK_MS: Long = 5_000L
    val HTTP_TRANSIENT_STATUSES: Set<Int> = setOf(408, 425, 500, 502, 503, 504)
}

private data class ResponseAndBody(val response: HttpResponse, val body: String)

override suspend fun fetchStatus(
    identityPubkeyHex: String,
    requesterPubkeyHex: String?,
): PreKeyStatus {
    val url = buildStatusUrl(identityPubkeyHex, requesterPubkeyHex)
    val identityTag = identityPubkeyHex.take(16)
    val requesterTag = requesterPubkeyHex?.take(16)
    val cycleStartMs = nowMs()
    var lastException: Throwable? = null

    for (attempt in 1..FETCH_STATUS_MAX_ATTEMPTS) {
        val attemptStartMs = nowMs()
        trace("http_status_start identity=$identityTag… " +
              "requester=${requesterTag ?: "none"}… path=/prekeys/status " +
              "attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS}")

        val received: ResponseAndBody? = try {
            // Single window covers request + body read.
            withTimeoutOrNull(FETCH_STATUS_ATTEMPT_DEADLINE_MS) {
                val response = httpClient.get(url)
                ResponseAndBody(response, response.bodyAsText())
            }
        } catch (ce: CancellationException) {
            // External cancellation — withTimeoutOrNull returns null on its
            // own timeout, so any CE reaching here is parent-initiated.
            throw ce
        } catch (t: Throwable) {
            when (classifyNetworkFailure(t)) {
                RetryDecision.TerminalTls -> {
                    trace("http_status_fatal identity=$identityTag… type=${t::class.simpleName} " +
                          "elapsedMs=${nowMs()-attemptStartMs}", RelayLogLevel.ERROR)
                    trace("verify_terminal_shortcut attempt=$attempt " +
                          "reason=${t::class.simpleName} " +
                          "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                    throw t
                }
                RetryDecision.TerminalOther -> {
                    trace("verify_terminal_shortcut attempt=$attempt " +
                          "reason=${t::class.simpleName} " +
                          "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                    throw t
                }
                RetryDecision.RetryableTransient -> {
                    if (attempt < FETCH_STATUS_MAX_ATTEMPTS) {
                        val nextDelay = jitter(FETCH_STATUS_RETRY_DELAYS_MS[attempt - 1])
                        lastException = t
                        trace("verify_retry_scheduled attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS} " +
                              "delay_ms=$nextDelay reason=${t::class.simpleName}", RelayLogLevel.WARN)
                        delay(nextDelay)
                        continue
                    }
                    trace("verify_retry_exhausted total_attempts=$attempt " +
                          "last_reason=${t::class.simpleName} " +
                          "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                    throw t
                }
            }
        }

        // Own-deadline expiry: withTimeoutOrNull returned null.
        if (received == null) {
            if (attempt < FETCH_STATUS_MAX_ATTEMPTS) {
                val nextDelay = jitter(FETCH_STATUS_RETRY_DELAYS_MS[attempt - 1])
                trace("verify_retry_scheduled attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS} " +
                      "delay_ms=$nextDelay reason=attempt_deadline", RelayLogLevel.WARN)
                delay(nextDelay)
                continue
            }
            trace("verify_retry_exhausted total_attempts=$attempt " +
                  "last_reason=attempt_deadline " +
                  "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
            throw FetchStatusDeadlineExceededException()  // non-CE — see §6.4
        }

        val (response, bodyResult) = received
        when (val status = response.status) {
            HttpStatusCode.OK -> {
                when (classifyBodyTruncation(response, bodyResult)) {
                    BodyClass.Empty, BodyClass.TruncatedByLength -> {
                        if (attempt < FETCH_STATUS_MAX_ATTEMPTS) {
                            val nextDelay = jitter(FETCH_STATUS_RETRY_DELAYS_MS[attempt - 1])
                            trace("verify_retry_scheduled attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS} " +
                                  "delay_ms=$nextDelay reason=body_truncated", RelayLogLevel.WARN)
                            delay(nextDelay)
                            continue
                        }
                        trace("verify_retry_exhausted total_attempts=$attempt " +
                              "last_reason=body_truncated " +
                              "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                        throw ProtocolException("body truncated after $attempt attempts")
                    }
                    BodyClass.Complete -> {
                        val parsed = try {
                            json.decodeFromString(PreKeyStatus.serializer(), bodyResult)
                        } catch (se: SerializationException) {
                            trace("http_status_decode_fatal identity=$identityTag… " +
                                  "bodyLen=${bodyResult.length}", RelayLogLevel.ERROR)
                            trace("verify_terminal_shortcut attempt=$attempt " +
                                  "reason=decode_${se::class.simpleName} " +
                                  "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                            throw se
                        }
                        if (attempt > 1) {
                            trace("verify_retry_converged attempt=$attempt " +
                                  "total_elapsed_ms=${nowMs()-cycleStartMs}")
                        }
                        return parsed
                    }
                }
            }
            HttpStatusCode.TooManyRequests -> {
                if (attempt < FETCH_STATUS_MAX_ATTEMPTS) {
                    val serverRetryAfter = parseRetryAfterMs(response.headers["Retry-After"])
                    val nextDelay = serverRetryAfter?.coerceAtMost(60_000L)
                        ?: jitter(RATE_LIMIT_FALLBACK_MS)
                    trace("verify_retry_scheduled attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS} " +
                          "delay_ms=$nextDelay reason=http429", RelayLogLevel.WARN)
                    delay(nextDelay)
                    continue
                }
                trace("verify_retry_exhausted total_attempts=$attempt " +
                      "last_reason=http429 " +
                      "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                throw BundleFetchException.RateLimited(bodyResult)
            }
            else -> {
                if (status.value in HTTP_TRANSIENT_STATUSES && attempt < FETCH_STATUS_MAX_ATTEMPTS) {
                    val nextDelay = jitter(FETCH_STATUS_RETRY_DELAYS_MS[attempt - 1])
                    trace("verify_retry_scheduled attempt=$attempt/${FETCH_STATUS_MAX_ATTEMPTS} " +
                          "delay_ms=$nextDelay reason=http${status.value}", RelayLogLevel.WARN)
                    delay(nextDelay)
                    continue
                }
                if (status.value in HTTP_TRANSIENT_STATUSES) {
                    trace("verify_retry_exhausted total_attempts=$attempt " +
                          "last_reason=http${status.value} " +
                          "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                } else {
                    trace("verify_terminal_shortcut attempt=$attempt " +
                          "reason=http${status.value} " +
                          "total_elapsed_ms=${nowMs()-cycleStartMs}", RelayLogLevel.WARN)
                }
                throw BundleFetchException.Unexpected(status.value, bodyResult)
            }
        }
    }
    throw lastException ?: IllegalStateException("fetchStatus retry loop exit without result")
}
```

### §6.2 `PreKeyApiClient.publishWithRetry` — extended taxonomy

Modify existing `isRetryable()` at `PreKeyApiClient.kt:538-551`:

```kotlin
private fun Throwable.isRetryableForPublish(): Boolean =
    classifyNetworkFailure(this) == RetryDecision.RetryableTransient
```

Modify the caller's throw-catch site to route through the classifier (matching `fetchStatus` shape). Extend HTTP retry to include 425. Change 429 handling from terminal `PublishResult.Failure(RateLimited)` to retryable-with-wait using `RATE_LIMIT_FALLBACK_MS` (jittered) — no header consumption (see D6). Trim `PUBLISH_RETRY_DELAYS_MS` to 2 elements: `longArrayOf(500L, 1500L)` (dead third element removed). Apply `jitter()` wrapper.

Refactor return type: `publishBundle(forceJoinInFlight, requestProvider): PublishExecutionOutcome`, where

```kotlin
sealed interface PublishExecutionOutcome {
    data class Stored(val storedOpks: Int) : PublishExecutionOutcome
    data object Deferred : PublishExecutionOutcome
}
```

Existing throw semantics on `Failure` retained (typed `MigrationException.*`).

### §6.3 `PreKeyLifecycleService.verifyLocked` + `onPeriodicTick`

```kotlin
enum class VerifyTrigger { Connected, Periodic }

sealed class VerifyOutcome {
    data object AlreadyPublished : VerifyOutcome()
    data class Republished(val storedOpks: Int) : VerifyOutcome()
    data object BudgetExhausted : VerifyOutcome()
    data object NoLocalSpk : VerifyOutcome()
    data object SkippedInFlight : VerifyOutcome()
}

// Public entry from AppContainer (see D4 for full body).
suspend fun verifyBundleOnRelay(trigger: VerifyTrigger): Result<VerifyOutcome> { ... }

// New public entry for the periodic ticker (D2 trigger 3).
suspend fun onPeriodicTick() {
    trace("verify_periodic_tick trigger=periodic")
    verifyBundleOnRelay(VerifyTrigger.Periodic)
        .onFailure { t ->
            if (t !is CancellationException) {
                messagingLog(MessagingLogLevel.WARN,
                    "PREKEY_TRACE verify_periodic_result_failure type=${t::class.simpleName}")
            }
        }
}

private suspend fun verifyLocked(trigger: VerifyTrigger): VerifyOutcome {
    val identity = identityManager.getIdentity() ?: throw MigrationException.NoIdentity
    val signing = identityManager.loadSigningKeyPair() ?: throw MigrationException.NoIdentity
    val identityTag = identity.publicKeyHex.take(16)
    val spkEntity = signedPreKeyRepository.get() ?: run {
        trace("verify_skip_no_local_spk identity=$identityTag… trigger=$trigger")
        return VerifyOutcome.NoLocalSpk
    }

    trace("verify_start identity=$identityTag… trigger=$trigger")
    val status = preKeyApi.fetchStatus(identity.publicKeyHex, identity.publicKeyHex)
    trace("verify_status identity=$identityTag… trigger=$trigger " +
          "spk_age_days=${status.signed_prekey_age_days} opks_remaining=${status.remaining_opks}")

    if (status.signed_prekey_age_days != null || status.remaining_opks != 0) {
        if (status.signed_prekey_age_days != null) {
            forceRepublishBudgetMutex.withLock {
                if (forceRepublishCount > 0) {
                    trace("verify_bundle_confirmed_reset_budget prior=$forceRepublishCount")
                    forceRepublishCount = 0
                }
            }
        }
        return VerifyOutcome.AlreadyPublished
    }

    val withinBudget = forceRepublishBudgetMutex.withLock {
        forceRepublishCount < MAX_FORCE_REPUBLISH_PER_SESSION
    }
    if (!withinBudget) {
        trace("verify_republish_budget_exhausted count=$MAX_FORCE_REPUBLISH_PER_SESSION",
              RelayLogLevel.WARN)
        return VerifyOutcome.BudgetExhausted
    }

    trace("verify_republish_triggered identity=$identityTag… — relay has no record",
          RelayLogLevel.WARN)

    val outcome: PublishExecutionOutcome = publishBundle(
        identity.publicKeyHex, signing, spkEntity, forceJoinInFlight = true,
    ) { oneTimePreKeyRepository.getAll() }
    val storedOpks = when (outcome) {
        is PublishExecutionOutcome.Stored -> outcome.storedOpks
        PublishExecutionOutcome.Deferred -> throw IllegalStateException(
            "PublishExecutionOutcome.Deferred on force-join path — invariant violated"
        )
    }

    // Non-cancellable commit + explicit ensureActive to restore cancellation window.
    withContext(NonCancellable) {
        forceRepublishBudgetMutex.withLock { forceRepublishCount += 1 }
    }
    currentCoroutineContext().ensureActive()

    return VerifyOutcome.Republished(storedOpks)
}
```

### §6.4 New exception + KMP-native classification file

`shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RetryClassification.kt`:

```kotlin
package phantom.core.transport

/**
 * Internal-timeout signal for fetchStatus attempts. NOT a
 * CancellationException — so the outer verify layer's re-throw of
 * CancellationException does not accidentally kill the periodic ticker
 * / Connected collector. External coroutine cancellation retains its
 * normal CancellationException propagation.
 *
 * commonMain-compatible: extends kotlin.Exception (not IOException),
 * requires no expect/actual scaffolding.
 */
class FetchStatusDeadlineExceededException(
    cause: Throwable? = null,
) : Exception("fetchStatus attempt deadline exceeded", cause)

enum class RetryDecision { RetryableTransient, TerminalTls, TerminalOther }

expect fun classifyNetworkFailure(t: Throwable): RetryDecision

enum class BodyClass { Empty, TruncatedByLength, Complete }

fun classifyBodyTruncation(response: HttpResponse, body: String): BodyClass {
    if (body.isEmpty()) return BodyClass.Empty
    val encoding = response.headers["Content-Encoding"]?.lowercase()
    if (encoding != null && encoding != "identity") return BodyClass.Complete
    val declaredLenStr = response.headers["Content-Length"] ?: return BodyClass.Complete
    val declaredLen = declaredLenStr.toLongOrNull() ?: return BodyClass.Complete
    val receivedLen = body.encodeToByteArray().size.toLong()
    return if (declaredLen > receivedLen) BodyClass.TruncatedByLength else BodyClass.Complete
}

/**
 * Retry-After parser — narrow, deterministic contract:
 *   - Accepts non-negative decimal delta-seconds ONLY (RFC 7231 §7.1.3
 *     first form).
 *   - Caps the parsed value at 60 seconds BEFORE conversion to millis
 *     (protects against Long overflow AND enforces the client's
 *     own upper wait bound).
 *   - HTTP-date format (RFC 7231 second form) is NOT parsed in this PR.
 *   - Any of: null header, non-numeric, negative, or unparsable → returns
 *     null. Caller coalesces null to `jitter(RATE_LIMIT_FALLBACK_MS)`
 *     (5s fallback).
 *   - HTTP-date support may land in a follow-up PR if server-side
 *     Retry-After emission adopts the date form.
 */
fun parseRetryAfterMs(headerValue: String?): Long? {
    if (headerValue == null) return null
    val seconds = headerValue.trim().toLongOrNull() ?: return null
    if (seconds < 0L) return null
    val cappedSeconds = seconds.coerceAtMost(60L)
    return cappedSeconds * 1000L
}
```

`shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RetryClassificationAndroid.kt` (and identical body in `jvmMain/…/RetryClassificationJvm.kt`):

```kotlin
package phantom.core.transport

actual fun classifyNetworkFailure(t: Throwable): RetryDecision {
    // Terminal via cause chain.
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 5) {
        if (cur is javax.net.ssl.SSLPeerUnverifiedException) return RetryDecision.TerminalTls
        if (cur is java.security.cert.CertificateException) return RetryDecision.TerminalTls
        cur = cur.cause
        depth++
    }

    if (t is FetchStatusDeadlineExceededException) return RetryDecision.TerminalOther

    // SSL classes retryable only when transient signal present.
    if (t is javax.net.ssl.SSLException) {
        return if (hasTransientTransportSignal(t)) RetryDecision.RetryableTransient
               else RetryDecision.TerminalTls
    }

    return when {
        t is java.net.SocketTimeoutException -> RetryDecision.RetryableTransient
        t is java.net.ConnectException -> RetryDecision.RetryableTransient
        t is java.net.NoRouteToHostException -> RetryDecision.RetryableTransient
        t is java.net.BindException -> RetryDecision.RetryableTransient
        t is java.net.UnknownHostException -> RetryDecision.RetryableTransient
        t is java.io.EOFException -> RetryDecision.RetryableTransient
        t is io.ktor.client.plugins.HttpRequestTimeoutException -> RetryDecision.RetryableTransient
        t is io.ktor.network.sockets.ConnectTimeoutException -> RetryDecision.RetryableTransient
        t is java.io.IOException -> {
            val msg = t.message?.lowercase(java.util.Locale.ROOT) ?: ""
            if (TRANSIENT_MESSAGE_SIGNALS.any { it in msg }) RetryDecision.RetryableTransient
            else RetryDecision.TerminalOther
        }
        else -> RetryDecision.TerminalOther
    }
}

private val TRANSIENT_MESSAGE_SIGNALS = listOf(
    "timeout", "timed out", "connection reset", "connection closed",
    "broken pipe", "stream closed", "closed connection",
    "unexpected end of stream", "eof",
)

private fun hasTransientTransportSignal(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 5) {
        if (cur is java.net.SocketTimeoutException) return true
        if (cur is java.io.EOFException) return true
        if (cur is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        if (cur is io.ktor.network.sockets.ConnectTimeoutException) return true
        val msg = cur.message?.lowercase(java.util.Locale.ROOT)
        if (msg != null && TRANSIENT_MESSAGE_SIGNALS.any { it in msg }) return true
        cur = cur.cause
        depth++
    }
    return false
}
```

`shared/core/transport/src/iosMain/kotlin/phantom/core/transport/RetryClassificationIos.kt` (conservative placeholder):

```kotlin
package phantom.core.transport

actual fun classifyNetworkFailure(t: Throwable): RetryDecision = when {
    t is FetchStatusDeadlineExceededException -> RetryDecision.TerminalOther
    t is io.ktor.client.plugins.HttpRequestTimeoutException -> RetryDecision.RetryableTransient
    t is io.ktor.network.sockets.ConnectTimeoutException -> RetryDecision.RetryableTransient
    // TODO: NSURLError inspection when iOS becomes shipped prekey target.
    else -> RetryDecision.TerminalOther
}
```

### §6.5 `AppContainer` — collect hook + periodic ticker

Replace the existing collect-hook (`AppContainer.kt:2276-2299`) and add periodic ticker:

```kotlin
// Reconnect trigger (existing site, refactored to .onFailure discipline):
appScope.launch {
    transport.state.collect { st ->
        if (st !is phantom.core.transport.TransportState.Connected) return@collect
        lifecycleService.verifyBundleOnRelay(VerifyTrigger.Connected)
            .onFailure { t ->
                if (t is CancellationException) throw t
                android.util.Log.w("PreKeyLifecycle",
                    "verifyBundleOnRelay on reconnect failed: type=${t::class.simpleName}")
            }
        lifecycleService.maybeReplenishOneTimePreKeys()
            .onFailure { t ->
                if (t is CancellationException) throw t
                android.util.Log.w("PreKeyLifecycle",
                    "Replenish on reconnect failed: type=${t::class.simpleName}")
            }
        lifecycleService.maybeRotateSignedPreKey()
            .onFailure { t ->
                if (t is CancellationException) throw t
                android.util.Log.w("PreKeyLifecycle",
                    "Rotate on reconnect failed: type=${t::class.simpleName}")
            }
    }
}

// Periodic safety net (new — first tick after 15 min).
appScope.launch {
    while (isActive) {
        delay(PERIODIC_VERIFY_INTERVAL_MS)  // 15 * 60 * 1000L
        try {
            lifecycleService.onPeriodicTick()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w("PreKeyLifecycle",
                "Periodic tick failed: type=${t::class.simpleName}")
        }
    }
}
```

### §6.6 Adjacent hygiene: full-identity URL redaction

Replace `url=$url` at `PreKeyApiClient.kt:577` (`http_bundle_fetch_start`) and `:633` (`http_status_start`) with `path=<endpoint>` + separate `identityTag` + `requesterTag` (each `.take(16)`).

### §6.7 Build-time invariant enforcement (F26 — Option A LOCKED)

`shared/core/messaging/build.gradle.kts` — add and wire into `check`:

```kotlin
tasks.register("assertSinglePublishBundleImplementation") {
    doLast {
        val serviceFile = file(
            "src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt"
        )
        val matches = serviceFile.readText().lineSequence()
            .filter { it.matches(Regex("^\\s*(private\\s+)?suspend\\s+fun\\s+publishBundle\\b.*")) }
            .toList()
        check(matches.size == 1) {
            "F7 invariant: expected exactly one `publishBundle` implementation in " +
            "PreKeyLifecycleService.kt, found ${matches.size}:\n${matches.joinToString("\n")}"
        }
        check(!serviceFile.readText().contains("publishBundleForceJoin")) {
            "F7 invariant: `publishBundleForceJoin` must not exist as a separate function; " +
            "use PublishExecutionOutcome return type instead."
        }
    }
}

tasks.named("check").configure { dependsOn("assertSinglePublishBundleImplementation") }
```

**Supplementary reviewer grep-gate** (belt-and-braces — NOT an alternative):

```bash
# Before approving the implementation PR:
git grep -c "suspend fun publishBundle" shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt   # must return 1
git grep publishBundleForceJoin   # must return no matches
```

## §7 Acceptance test matrix

Test infrastructure prereqs (bundled in implementation PR):
- Constructor `logObserver: ((String) -> Unit)? = null` on `PreKeyLifecycleService` and `PreKeyApiClient`.
- Constructor `nowMs: () -> Long` and `jitter: (Long) -> Long` on `PreKeyApiClient`.
- `FakePreKeyApi.fetchStatusThrower: ((attempt: Int) -> Throwable?)?` for controlled failure injection at the service layer.

**Test source-set assignment**:

| Layer | Source set | Rationale |
|---|---|---|
| Transport retry / body-truncation / classifier / publish | `shared/core/transport/src/commonTest/` (mock httpClient via Ktor `MockEngine`) and `shared/core/transport/src/jvmTest/` (real JVM `SSLException` construction where needed) | Exercises the real `PreKeyApiClient.fetchStatus` retry loop; `FakePreKeyApi` would bypass the code under test |
| Service orchestration (verifyMutex, VerifyOutcome, budget, single-flight) | `shared/core/messaging/src/commonTest/` (uses `FakePreKeyApi`) | Verifies service-layer orchestration; classification behaviour already covered at transport layer |
| AppContainer wiring (ticker, Connected hook, StateFlow) | `androidUnitTest` (create source set if absent) | Exercises Android-specific wiring; requires `TestScope` for virtual time |

**Tests** (40 rows — every acceptance requirement from the design chain has a named test; split: 20 transport commonTest/jvmTest + 13 messaging commonTest + 7 androidUnitTest):

| ID | Name | Layer | Verifies |
|---|---|---|---|
| T1 | `fetchStatus_recoversAfterTransientSslWithinBoundedAttempts_returnsPreKeyStatus` | transport commonTest | 2 transient throws → attempt 3 success; exactly 3 GETs; final result `PreKeyStatus`; `verify_retry_converged` marker |
| T2-per-trigger | `fetchStatus_persistentTransientStopsAtCap` | transport commonTest | All 3 attempts transient; `verify_retry_exhausted` fires ONCE at attempt 3 (D5 strict AND) |
| T2-tick | `persistentTransient_retriesOnNextPeriodicTick_capsAt12PerHourSteady_15FirstHour` | androidUnitTest | Advance virtual time 1 hour; ≤15 first hour (initial Connected + ticks), ≤12/hour steady |
| T3 | `verifyBundleOnRelay_concurrentTriggers_producesOneActiveVerify_otherReturnsSkippedInFlight` | messaging commonTest | Concurrent Connected + Periodic → one runs, other returns `SkippedInFlight`; `trigger=` field in marker |
| T4 | `newConnectedDoesNotCancelActiveVerify` | androidUnitTest | Slow fetchStatus; new Connected → SkippedInFlight; no CE to original |
| T5 | `verifyBundleOnRelay_transientPublishFailure_doesNotConsumeBudget` | messaging commonTest | Zero-record + publish throws transient; 6 iterations; `forceRepublishCount == 0` after each |
| T6a | `Stored_consumesOneSlot_confirmedStatusResetsIt` | messaging commonTest | Stored → count 1; next verify age != null → count 0 + `verify_bundle_confirmed_reset_budget prior=1` |
| T6b | `5consecutiveUnconfirmed_exhaustBudget_recoveredOnConfirm` | messaging commonTest | 5 Stored + repeated zero-record → 6th `BudgetExhausted`; age != null → reset |
| T7a | `fetchStatus_emptyBody_retriesAndSucceeds` | transport commonTest | 200 OK empty body attempt 1, complete attempt 2 |
| T7b | `fetchStatus_truncatedBodyDetectedByContentLength_retriesAndSucceeds` | transport commonTest | `Content-Length: 100` + 50-byte body attempt 1 → `TruncatedByLength` → retry |
| T7c | `fetchStatus_nonEmptyMalformedJsonNoTruncation_terminatesImmediately` | transport commonTest | Content-Length matches body length + malformed JSON → SerializationException → `verify_terminal_shortcut` |
| T8-service | `verifyBundleOnRelay_CancellationExceptionPropagates_notWrappedInResult` | messaging commonTest | Cancel job during slow fetchStatus; CE thrown (NOT wrapped in Result.failure); verifyMutex released |
| T8-hook | `connectedHookCollectLambda_CancellationExceptionPropagates` | androidUnitTest | Cancel appScope during in-flight verify from Connected hook |
| T8-ticker | `periodicTicker_CancellationExceptionExitsLoop_viaWhileIsActive` | androidUnitTest | Cancel appScope during ticker delay; `while (isActive)` exits |
| T9-status | `httpStatusStart_doesNotLogFullIdentityHex` | transport commonTest | `fetchStatus` markers: no `/[0-9a-f]{64}/` match |
| T9-bundle | `httpBundleFetchStart_doesNotLogFullIdentityHex` | transport commonTest | `fetchBundle` markers: no `/[0-9a-f]{64}/` match |
| T10 | `noHidden3x3Retry_perTriggerMaxRequestCountBounded` | transport commonTest | Worst-case transient throws → exactly 3 GETs per single trigger |
| T11 | `periodicTicker_firstTickAfter15Min_notImmediate` | androidUnitTest | Container test: count = 1 at 5 min, count = 2 at 16 min |
| T12 | `classifyNetworkFailure_SSLPeerUnverifiedInCauseChain_returnsTerminalTls` | transport jvmTest | `SSLException(cause=SSLPeerUnverifiedException(...))` → `TerminalTls` |
| T12b | `classifyNetworkFailure_bareSSLExceptionNoTransientSignal_returnsTerminalTls` | transport jvmTest | Bare `SSLException("unknown SSL error")` → `TerminalTls` |
| T12c | `classifyNetworkFailure_SSLExceptionWithSocketTimeoutCause_returnsRetryableTransient` | transport jvmTest | `SSLException(cause=SocketTimeoutException(...))` → `RetryableTransient` |
| T13 | `publishWithRetry_retriesOnRetryableTransient_thenSucceeds` | transport commonTest / messaging commonTest | Publish transient attempt 1, success attempt 2 |
| T14 | `publishWithRetry_retriesOnConnectException_thenSucceeds` | transport commonTest | ConnectException attempt 1 |
| T15 | `publishWithRetry_429_retriesWithFallback` | transport commonTest | HTTP 429 no Retry-After, success attempt 2; delay ≥ RATE_LIMIT_FALLBACK_MS |
| T16 | `jitterInjection_returnsIdentityInTests_forDeterministicAssertions` | transport commonTest | Client constructed with `jitter = { it }`; delays match declared values |
| T17 | `budget_documented_via_unittest_semantics_matchesKDoc` | messaging commonTest | Property-style: transient×N + Stored×M + confirm×K → counter matches formula |
| T18 | `existing_raceRepro_test_still_passes_under_new_budget_semantics` | messaging commonTest | `PreKeyLifecycleServiceTest.kt:775` regression guard |
| T-P0-4 | `bootstrapReplenishRotate_Stored_doNOTConsumeForceRepublishBudget` | messaging commonTest | Bootstrap / replenish / rotate paths → Stored → `forceRepublishCount == 0` (D7 invariant) |
| T-P1-1 | `periodicTickerLog_doesNotContainMessage` | androidUnitTest | Log capture: no `.message` content leaks |
| T-F1a | `callerObservesResultFailure_andLogsClassOnly` | messaging commonTest + androidUnitTest | Both call sites observe Result.failure; `.onFailure` log captured with `type=<class>` |
| T-F1b | `serviceNeverReturnsResultFailureOfCancellationException` | messaging commonTest | Cancel during fetchStatus; CE thrown, NOT wrapped |
| T-F3 | `terminalFirstAttempt_emitsTerminalShortcut_neverExhausted` | transport commonTest | `TerminalOther` on attempt 1 → `verify_terminal_shortcut attempt=1`; NO `verify_retry_exhausted` |
| T-F3-service | `verifyBundleOnRelay_transportTerminal_returnsResultFailure` | messaging commonTest | Service orchestration for terminal transport outcomes |
| T-F4 | `requestAndBodyReadPaths_produceIdenticalRetryDecision_viaSharedClassifier` | transport commonTest | Same exception thrown from request-read AND body-read paths → same retry behaviour |
| T-F9 | `cancellationAfterStored_stillCommitsExactlyOneBudgetSlot` | messaging commonTest | Cancel during NonCancellable commit block → 1 slot committed, no second slot |
| T-F11a | `fetchStatus_hungRequest_attemptDeadlineFires_retriesUpToMax_throwsFetchStatusDeadlineExceededException` | transport commonTest | Hung request via `suspendCancellableCoroutine {}`; 3 attempts; final throw is non-CE `FetchStatusDeadlineExceededException` |
| T-F11b | `fetchStatus_trickleBodyRead_attemptDeadlineFires` | transport commonTest | Slow body read on real HttpResponse |
| T-F11c | `fetchStatus_externalCancellation_propagatesNotConvertedToTransient` | transport jvmTest | External cancel during `withTimeoutOrNull` → CE propagates; NOT converted to FetchStatusDeadlineExceededException |
| T-F15 | `replenishAndRotate_ResultFailure_isLoggedViaOnFailure_notSilentlyDiscarded` | androidUnitTest | Force replenish/rotate Result.failure; `.onFailure` log captured |
| T-F18 | `deadline_exhaustion_returns_Result_failure_not_CE_and_ticker_survives` | messaging commonTest | 3 attempt deadlines → `Result.failure(FetchStatusDeadlineExceededException)`; ticker `while (isActive)` still active + next tick fires |

**Not a test**: T-F7 (single `publishBundle` implementation) is enforced via the Gradle task in §6.7, not a unit test. Reviewer grep-gate supplementary per §6.7.

## §8 Explicit rejects (cumulative)

Every alternative below was considered during design deliberation and REJECTED with rationale.

- **Outer whole-verify burst retry** (5s / 30s / 3min wrapping `verifyBundleOnRelay`) — would multiply retry counts (up to 9 fetchStatus GETs per trigger); transport-level retry + periodic ticker close the same defect without multiplication.
- **`runCatching` around `verifyBundleOnRelay`** — Kotlin `Result.runCatching` catches `CancellationException`, defeating structured concurrency. Use explicit `try/catch(CancellationException) { throw ce } catch (Throwable)` OR `.onFailure {}` at call sites.
- **`Result.failure` swallowed by `catch (Throwable)`** — `catch (t: Throwable)` never fires for a `Result.failure` RETURN; use `.onFailure { }`. Applied consistently to `verifyBundleOnRelay`, `maybeReplenishOneTimePreKeys`, `maybeRotateSignedPreKey`.
- **`Result.success(false)` from SkippedInFlight** — indistinguishable from "verify saw good state"; introduce explicit `VerifyOutcome.SkippedInFlight`.
- **`Job.cancel` on active verify from new Connected event** — risks half-written HTTP state; report SkippedInFlight instead.
- **Immediate first periodic tick** — races with startup Connected trigger. First tick after 15 min.
- **WorkManager / foreground-service scope migration** — `appScope` is alive during the observed failure; scope creep for the tier-1 defect. Deferred to future tier-3 hardening.
- **Reuse `publishMutex` for verify single-flight** — `publishMutex` gates POSTs only; verify's GET+decide+publish trio needs its own primitive. Conflating regresses the historical debounce fix.
- **Post-publish confirmation (Fix D)** — deferred to PR-2.5 field data window. May land in follow-up client PR if server residuals persist.
- **Client-side `Retry-After` on publish path** — deferred to matching relay-side follow-up PR that also adds server-side `Retry-After` emission. Client uses `RATE_LIMIT_FALLBACK_MS` in the meantime.
- **Broad production `LogSink` abstraction** — narrow `logObserver: ((String) -> Unit)? = null` constructor seam only.
- **`.simpleName == "..."` string matching for classifier** — R8/ProGuard risk; use instance-based `t is Foo` via `expect/actual`.
- **Blanket-retryable `SSLException`** — retry only when `hasTransientTransportSignal(t)` matches enumerated signals; otherwise TerminalTls.
- **Retry on non-empty non-truncated malformed JSON** — protocol failure; terminal.
- **`3 attempts + 3 delays` (dead 3rd delay element)** — 3 attempts = exactly 2 sleeps. Both `FETCH_STATUS_RETRY_DELAYS_MS` and `PUBLISH_RETRY_DELAYS_MS` trimmed to 2 elements.
- **Relying on OkHttp defaults for per-attempt call bound** — pin explicit `withTimeoutOrNull(FETCH_STATUS_ATTEMPT_DEADLINE_MS)`. OkHttp's `callTimeout=0` default provides no upper bound. Use ONLY `withTimeoutOrNull` (never `withTimeout`) so the internal deadline cannot escape as a `CancellationException`.
- **`TimeoutCancellationException` as retry signal** — is a `CancellationException` subclass; can escape outer catch-CE re-throw. Use `withTimeoutOrNull` (returns null instead of throwing) + custom non-CE `FetchStatusDeadlineExceededException` for final-attempt throw.
- **`try/catch(Throwable)` for `maybeReplenishOneTimePreKeys` / `maybeRotateSignedPreKey`** — same Result-swallowing defect; use `.onFailure { }`.
- **Mixed `relayLog` + `logObserver` split for observability** — single `trace(marker, level)` helper writes to both.
- **`≤ 12 GETs/hour` blanket assertion** — first hour ≤ 15 (includes startup trigger), steady-state ≤ 12.
- **`FetchStatusDeadlineExceededException : java.io.IOException`** — JVM-only base. Use `: Exception(...)` for commonMain-native.
- **`expect/actual` for the deadline exception class** — unnecessary; `kotlin.Exception` works cross-target.
- **`trace(marker, level, cause: Throwable?)` with cause propagation to production log** — production is ALSO strict per D8. No throwable to either channel. Callers include class simpleName inline.
- **Single-endpoint T9 assertion** — split into T9-status (`fetchStatus`) + T9-bundle (`fetchBundle`).
- **F22 A/B choice deferred to implementation review** — Option A (Gradle task) LOCKED as binding. Option B (reviewer checklist) supplementary belt-and-braces.
- **`FakePreKeyApi` for transport-layer retry testing** — bypasses code under test. Use Ktor MockEngine at transport layer.
- **Plumbing `Retry-After` through `PreKeyPublishHttpResponse` in this PR** — DTO extension touches 3 platform actuals + fake; deferred to matching relay-side follow-up.

## §9 Accepted residual risks

Documented, non-blocking, acknowledged by architect at GREEN sign-off:

1. **`FETCH_STATUS_ATTEMPT_DEADLINE_MS = 15_000L`** is a first-cut value. Chosen to accommodate slow relay under load + one full body read without becoming a foot-gun on cellular. Revisit after field observation if 15s proves too tight OR too loose.
2. **iOS classifier is a conservative placeholder.** Full NSURLError inspection deferred until iOS becomes a shipped prekey target (currently Android-only in production). Ktor Darwin engine's own timeout/socket exceptions covered; other native network errors default to `TerminalOther` (safe default — no retry).
3. **Production logs intentionally omit stack traces.** D8 strict policy protects sensitive data. Stack-trace preservation is a future DEBUG-channel-stripped-by-ProGuard PR (separate track).

## §10 Implementation handoff

**Files touched by the implementation PR**:

| Path | Change |
|---|---|
| `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt` | fetchStatus retry loop + publishWithRetry taxonomy + `trace()` helper + constructor seams + URL redaction |
| `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RetryClassification.kt` | new file — enum, expect, BodyClass, helpers, deadline exception |
| `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RetryClassificationAndroid.kt` | new file — JVM/Android actual |
| `shared/core/transport/src/jvmMain/kotlin/phantom/core/transport/RetryClassificationJvm.kt` | new file — identical body to Android actual (OR shared internal helper; implementer decision) |
| `shared/core/transport/src/iosMain/kotlin/phantom/core/transport/RetryClassificationIos.kt` | new file — conservative placeholder |
| `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt` | verifyBundleOnRelay signature + VerifyOutcome + VerifyTrigger + verifyLocked + onPeriodicTick + budget accounting + `trace()` helper + constructor seams |
| `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` | reconnect hook `.onFailure` migration + new periodic ticker launch |
| `shared/core/messaging/build.gradle.kts` | Gradle task `assertSinglePublishBundleImplementation` + wire into `check` |
| Tests | 40 rows per §7 matrix (20 transport commonTest/jvmTest + 13 messaging commonTest + 7 androidUnitTest) |

**Reviewer PR checklist** (implementation PR):
1. `git grep -c "suspend fun publishBundle" shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt` returns exactly `1`.
2. `git grep publishBundleForceJoin` returns no matches.
3. `git grep -E "java\\.|javax\\." shared/core/transport/src/commonMain/` returns no matches attributable to prekey code.
4. `git grep "runCatching" apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` — verify no `runCatching { verifyBundleOnRelay() }` regressions.
5. Full `./gradlew :shared:core:transport:allTests :shared:core:messaging:allTests` passes.
6. `./gradlew :shared:core:messaging:check` triggers `assertSinglePublishBundleImplementation` successfully.

**Sequencing**: implementation PR opens on **separate architect greenlight**. This mini-lock does NOT authorise the code PR to open. Orthogonal to server-side sequence (PR-0 → PR-1b → Ops PR → PR-2 → PR-2.5 → PR-1c); does not block and is not blocked by any of them.

## §11 What this mini-lock does NOT touch

- **VPS / production / relay flags** — untouched.
- **Server-side code** (`services/relay/**`) — untouched. `Retry-After` server emission is a separate follow-up PR.
- **Locked server sequence** PR-0 → PR-1b → Ops PR → PR-2 → PR-2.5 → PR-1c — unchanged.
- **WorkManager / foreground-service scope** — no changes.
- **`PhantomWakeupReceiver`** — passive per PR-R0.4a; unchanged.
- **Bootstrap flow at `MainActivity.kt:428`** — untouched (Compose-scope hygiene noted as separate future work).
- **Migration flow** — untouched.
- **Durable memory** — MEMORY.md update parallel to this docs PR; no other memory writes.
- **Poison-envelope PR-1c** — separate track; do NOT conflate.

## §12 Design authority

This document (`docs/tracks/client-prekey-selfheal.md`) is the **sole binding, self-contained source** for the CLIENT-PREKEY-SELFHEAL design. Every locked decision, invariant, test row, and rejected alternative is inlined above; implementers do NOT need to consult any external artefact.

Earlier deliberation drafts (if any exist elsewhere) are **non-normative** and are not required to understand or implement this design. If a discrepancy arises between this document and any other artefact, this document governs.

**Design phase closed 2026-07-16.** Implementation PR requires separate architect greenlight.
