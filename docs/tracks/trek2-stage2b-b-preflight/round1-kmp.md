# Trek 2 Stage 2B-B — KMP Implementation Preflight (Round 1)

**Scope:** HMAC-SHA-256 `seq_mac` verification, cursor write seam, circuit breaker, 410
re-auth, cadence jitter.  
**Base:** master `0ac29cf5` (Stage 2B-A merged as PR #306).  
**Binding contracts:** Stage 2B-A scope mini-lock (`docs/tracks/trek2-stage2b-a-client-shell.md`)
and Stage 1.x server contract (`docs/tracks/trek2-stage1x-server-prereq.md`).

---

## 1. Implementation blockers (P1)

### P1-1 — `seq_mac` verification module belongs in `:shared:core:transport`, not `:shared:core:crypto`, but needs `Auth.authHmacSha256` from the crypto module

**Where:** `shared/core/transport/build.gradle.kts` already depends on `:shared:core:crypto`
(line 32), so the `HMAC-SHA-256` primitive via `com.ionspin.kotlin.crypto.auth.Auth` is on
the commonMain classpath of the transport module. No new gradle dependency is needed to
call `Auth.authHmacSha256`.

**Why it is a blocker if missed:** If an implementer places the MAC verification function
directly inside `:shared:core:crypto` (alongside `Hkdf.kt`) and then references it from
`:shared:core:transport`, no circular dependency arises — but it would expose a
transport-domain concern (the canonical input layout of `seq_mac`) inside a pure-crypto
module. More concretely: `Hkdf.kt` already uses `Auth.authHmacSha256` in commonMain
(`Hkdf.kt:29,34`), demonstrating the call compiles on all current targets
(androidTarget, jvm). The correct placement is a new `SeqMacVerifier.kt` inside
`:shared:core:transport`'s `commonMain`, calling `Auth.authHmacSha256` through the
existing module dependency.

**What to do:** Create `SeqMacVerifier` in `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/`.
Import `com.ionspin.kotlin.crypto.auth.Auth` directly (same as `Hkdf.kt`). Do not add any
new gradle dependency.

### P1-2 — `Auth.authHmacSha256` takes `UByteArray` arguments; commonMain does not have `java.nio.ByteBuffer`

**Where:** `Hkdf.kt:29-36` demonstrates the call site pattern:
`Auth.authHmacSha256(message = data.toUByteArray(), key = key.toUByteArray())`.

**Why it is a blocker:** The canonical MAC input requires big-endian encoding of `seq`
(u64), `envelope_id_len` (u16), and `sequence_ts` (u64). `java.nio.ByteBuffer` is
JVM-only and is not available in commonMain. Any implementation that reaches for
`ByteBuffer.putLong(...)` will fail to compile for `iosArm64`, `iosX64`, and
`iosSimulatorArm64` targets the moment iOS targets are added (already noted as
forthcoming in `build.gradle.kts:10`). Even under the current `androidTarget + jvm`
build, the code would silently be JVM-only and would not survive the first iOS
integration.

**What to do:** Encode all multi-byte integers using manual bit-shift arithmetic, which
is available identically in all Kotlin targets:

```
// u64 big-endian without ByteBuffer (commonMain-safe):
fun Long.toBeBytes(): ByteArray {
    val v = this
    return byteArrayOf(
        (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
}
```

The same pattern applies to the `u16` length prefix using `ushr 8` and a mask. These
helpers already exist partially in `RestFallbackOrchestrator.kt:1201-1219`
(`bytesToHex`, `hexToBytes`) as private companion functions — the same file is the right
place to add the BE encoding utilities or they can live in `SeqMacVerifier`.

### P1-3 — `_inbound` is `MutableSharedFlow(replay=0, extraBufferCapacity=32)`; MAC-rejected envelopes must not emit even a partial signal

**Where:** `RestFallbackOrchestrator.kt:147`. The current Stage 2B-A code (`wsActivePollLoop`,
line 843) calls `_inbound.tryEmit(env)` for every received envelope without verification.
Stage 2B-B must gate emission on MAC verification AND on storage dedup completing — in
that order. A rejected MAC must result in zero emission and zero cursor advancement.

**Why it is a blocker:** `MutableSharedFlow(replay=0)` means a missed emission is
permanently lost — there is no replay buffer for a subscriber that joins late. If
verification is done asynchronously or in a separate coroutine that races the `tryEmit`
call, an unverified envelope can reach downstream before the check completes. The Stage
2B-B implementation must keep the verify-then-emit sequence within a single suspension
point (no interleaving launch), so the MAC rejection short-circuits before `tryEmit`.

**What to do:** In `wsActivePollLoop` (and in `pollLoop` for the legacy path if MAC
gating is extended there), the pattern must be: verify MAC → on failure log and
`continue` (no emit, no cursor write) → on pass call storage dedup → emit to
`_inbound` → advance cursor.

### P1-4 — Kotlin `Long` is always signed; `seq` and `sequence_ts` are server-side `u64`

**Where:** `PollEnvelope.kt` models `seq: Long` and `sequenceTs: Long`
(`RestFallbackTransport.kt:325,326`). The server canonical input treats both as `u64`
(`seq_mac.rs:189,192`: `.to_be_bytes()` on `u64`).

**Why it is a blocker:** For values that fit in `[0, Long.MAX_VALUE]` (which all real
sequences will for years), `Long.ushr(n)` gives the same bit pattern as `u64 >> n`. The
danger is sign-extension: if `toLong()` is called on a `u64` value above `2^63 - 1`,
the Kotlin `Long` becomes negative, and `shr` (arithmetic shift) would sign-extend while
`ushr` (unsigned shift) would not. The implementation MUST use `ushr` throughout the
BE encoding helpers, never `shr`. This is not a current data problem but it is a
correctness contract that must be explicitly documented in the implementation.

---

## 2. Implementation concerns (P2)

### P2-1 — `acquireOrRefreshToken` holds `tokenMutex` for the entire auth-session round-trip; a 410 handler calling it re-entrantly would deadlock

**Where:** `RestFallbackOrchestrator.kt:911`. `tokenMutex.withLock { ... authSessionOnce() ... }`
— the lock is held while the HTTP round-trip to `/auth/session` executes. The
`wsActivePollLoop` (and `pollLoop`) already follow the CAS discipline: they pass
`staleToken` to signal "my token was rejected, please refresh". A 410 handler must
follow the exact same path — pass the current token as `staleToken` and let
`acquireOrRefreshToken` decide whether to refresh or CAS-reuse a concurrent refresh.
The handler must NOT call `acquireOrRefreshToken` from inside a scope that is itself
already holding `tokenMutex`.

**What to do:** In the 410 branch of `wsActivePollLoop`, set `staleToken = token` and
`continue` the loop. Do not introduce a new forced-refresh call. The existing `forceRefresh`
parameter path (used only by `bootstrap`) should not be called from the poll loop on 410.

### P2-2 — `wsActivePollLoop` runs on the orchestrator's `scope` which uses `Dispatchers.Default`; `upsertLastSeenSeq` switches to `Dispatchers.IO` internally

**Where:** `SqlDelightLastSeenSeqRepository.kt:42` (`withContext(Dispatchers.IO)`). The
orchestrator's `scope` is `CoroutineScope(SupervisorJob() + dispatcher)` where
`dispatcher` defaults to `Dispatchers.Default` (`RestFallbackOrchestrator.kt:126`). A
`suspend` call to `upsertLastSeenSeq` from within `wsActivePollLoop` will correctly
suspend and resume on `Default` after the IO work completes — this is safe. However,
the `LongPollCursorReader.getLastSeenSeq` SAM bridge in `AppContainer.kt:1009-1011`
delegates to the same `SqlDelightLastSeenSeqRepository.getLastSeenSeq` which also calls
`withContext(Dispatchers.IO)`. This is correct. The concern is that the full
verify-dedup-write sequence (MAC verify on Default, dedup DB read on IO, cursor write on
IO) will cross dispatcher boundaries twice per envelope. This is not a correctness
problem but it creates a subtle test concern: virtual-time tests (`TestCoroutineScheduler`)
do not advance real `Dispatchers.IO` work; any test that exercises the full
verify-write path must either use `UnconfinedTestDispatcher` or inject a fake repository
that does not switch dispatchers.

### P2-3 — `MutableSharedFlow` subscriber count and `tryEmit` vs `emit`

**Where:** `RestFallbackOrchestrator.kt:843` uses `tryEmit` in `wsActivePollLoop` versus
`emit` (which suspends) in the legacy `pollLoop` at line 685. `tryEmit` drops silently
if the buffer is full (`extraBufferCapacity=32`). After MAC verification is added,
the downstream subscriber must be able to keep up with the emit rate. On Tele2 LTE with
the long-poll hold, bursts of up to `POLL_MAX_ENVELOPES=1` (lock from the Trek 2
mini-lock) per poll iteration are expected, so buffer overflow in practice is
unlikely — but the implementation should document the drop-on-overflow semantic as
intentional and log a counter when `tryEmit` returns false.

### P2-4 — `RestStateMachine` is documented "not thread-safe" and assumes single-threaded event submission

**Where:** `RestStateMachine.kt:56`. The `wsActivePollJob` and the legacy `pollJob` both
run on the same `scope` under `Dispatchers.Default`, which may dispatch to multiple
threads. If both jobs observe a 410 and both call `submitEvent` concurrently, and if the
circuit breaker (Stage 2B-B) introduces new `Event` variants, state transitions may race.
The current implementation serialises through `scope.launch { stateMachine.state.collect }`,
which runs on Default but is single-coroutine. Adding new event submissions from
`wsActivePollLoop` must respect this: call `submitEvent(event)` (which calls
`stateMachine.onEvent(event)` directly, not via `launch`), and note that this is
inherently un-synchronized. For Stage 2B-B this is probably acceptable because the
state machine reads are idempotent in practice, but the thread-safety note should be
acknowledged in the PR doc-comment.

### P2-5 — Circuit breaker threshold timing with `delay` under cancellation

When the circuit breaker's "open" decision requires counting N consecutive REST failures
over a time window, `delay(...)` calls inside the loop are cancellation-transparent:
a `CancellationException` thrown inside `delay` propagates cleanly and the loop
terminates without hanging. However, if the breaker uses a separate timer coroutine
(spawned via `scope.launch { delay(threshold); submitEvent(...) }`), that coroutine
survives a `stop()` call if the job is not tracked. Any timer coroutine introduced by
the breaker must be stored in a tracked `Job` that is cancelled by `stop()`, following
the same pattern as `aliveTickJob` (`RestFallbackOrchestrator.kt:170,281`).

---

## 3. HMAC primitive availability

**Conclusion: HMAC-SHA-256 is available in commonMain today via
`com.ionspin.kotlin.crypto.auth.Auth.authHmacSha256`.**

Evidence:

- `Hkdf.kt:6` imports `com.ionspin.kotlin.crypto.auth.Auth`.
- `Hkdf.kt:29` calls `Auth.authHmacSha256(message = ..., key = ...)` and the comment
  on line 14 states explicitly: "libsodium's `Auth.authHmacSha256` is HMAC-SHA-256
  directly".
- The `:shared:core:crypto` module lists `implementation(libs.libsodium.bindings)` in
  `commonMain.dependencies` (`crypto/build.gradle.kts:16`).
- The `:shared:core:transport` module depends on `:shared:core:crypto`
  (`transport/build.gradle.kts:32`), so the `Auth` class is already on the transport
  module's commonMain classpath.

**What `Auth.authHmacSha256` produces:** The libsodium `crypto_auth_hmacsha256` function,
which is standard HMAC with SHA-256 as the hash. This matches the server's
`Hmac<Sha256>` (`seq_mac.rs:33`). The output is 32 bytes = 64 hex chars. The server
wire field is `seq_mac: String` of 64 lowercase hex chars. The client verify step
computes `Auth.authHmacSha256(message = canonicalInput, key = verifyKeyBytes)` and
hex-encodes the 32-byte result for constant-time comparison against `PollEnvelope.seqMac`.

**Note on `crypto_auth_hmacsha512256`:** libsodium also exposes
`crypto_auth_hmacsha512256` (HMAC-SHA-512 truncated to 32 bytes), accessible via
`Auth.authHmacSha512256`. This is NOT the same as HMAC-SHA-256. The server explicitly
uses `Hmac<Sha256>` (SHA-2 256-bit, not truncated 512). Using the wrong primitive would
produce a byte-for-byte mismatch on every verify call. The implementation must call
`authHmacSha256`, not `authHmacSha512256`.

**Recommended path:** Add a `SeqMacVerifier` object in `shared/core/transport/src/commonMain/`
that calls `Auth.authHmacSha256` directly. No new dependency. No BouncyCastle. No
additional platform-specific source set. The only cost is that tests that exercise
`SeqMacVerifier` must initialise libsodium (`LibsodiumInitializer.initialize()`) — the
same constraint already applies to `EnvelopeId.random()` tests in the transport module
(`transport/build.gradle.kts:60: implementation(libs.libsodium.bindings)`).

---

## 4. Canonical encoding implementation rules

The server canonical input, reproduced verbatim from `seq_mac.rs:155-164`:

```
SEQ_MAC_DOMAIN_TAG               19 bytes  b"phantom-seq-mac-v1\x00"
identity_hex                     64 bytes  lowercase ASCII
seq                               8 bytes  u64 big-endian
envelope_id_len                   2 bytes  u16 big-endian, byte length
envelope_id_bytes                variable  exact UTF-8 bytes
sequence_ts                       8 bytes  u64 big-endian (post-quantize)
```

The server feeds all fields as sequential `mac.update(slice)` calls
(`seq_mac.rs:185-192`), building a single HMAC digest over the concatenation.

**Field-by-field client implementation rules:**

**Domain tag (19 bytes):** `b"phantom-seq-mac-v1\x00"` is 18 ASCII bytes plus a NUL
terminator. In Kotlin: `"phantom-seq-mac-v1 ".encodeToByteArray()`. Length is 19
bytes. Verify with `assert(tag.size == 19)` in a unit test.

**identity_hex (64 bytes):** The value from `RestFallbackOrchestrator.identityHex`
(constructor parameter). Encode as UTF-8 bytes: `identityHex.encodeToByteArray()`. This
must be exactly 64 bytes because `identityHex` is lowercase ASCII hex (64 chars = 64
bytes in UTF-8). The implementation should assert length == 64 at the seam to catch
a misconfigured identity early rather than silently producing a wrong MAC.

**seq (8 bytes, u64 BE):** `PollEnvelope.seq` is `Long`. Use `ushr` (unsigned right
shift), never `shr` (arithmetic right shift) — see P1-4:
```kotlin
val seqBytes = ByteArray(8)
val s = env.seq
for (i in 0..7) seqBytes[i] = (s ushr (56 - i * 8)).toByte()
```
For all values the relay will realistically produce (counter starting at 0), `seq` is
non-negative, so `ushr` and `shr` are equivalent — but `ushr` is correct by contract
and must be used.

**envelope_id_len (2 bytes, u16 BE):** The byte length of `envelope_id` encoded as
UTF-8, not `String.length`. These differ for multi-byte characters. In practice
production `envelope_id` values are 32-char hex strings (32 ASCII bytes = 32 UTF-8
bytes), but the implementation must use `encodeToByteArray().size`, not `length`. The
overflow guard: if byte length > 65535, the verify call must return a hard `MacRejected`
without attempting to allocate. This mirrors the server check at `seq_mac.rs:178`.
Encoding: `val idBytes = envelopeId.encodeToByteArray(); val lenBytes = byteArrayOf((idBytes.size ushr 8).toByte(), idBytes.size.toByte())`.

**envelope_id_bytes (variable):** `idBytes` directly — the exact UTF-8 byte array from
the length step above. Do not re-encode; share the reference.

**sequence_ts (8 bytes, u64 BE):** `PollEnvelope.sequenceTs` is `Long`. Same `ushr`
rule as `seq`. The server quantizes `sequence_ts` to 60-second buckets before computing
the MAC (`sequence_ts = (raw_ts / 60_000) * 60_000` in milliseconds). The client
receives the already-quantized value from the wire — it must not re-quantize. The MAC
input uses the value exactly as received on `PollEnvelope.sequenceTs`. Any client-side
re-quantization would produce a MAC mismatch even for valid envelopes.

**Comparison:** The server produces a 32-byte MAC and hex-encodes it as 64 lowercase
chars in `seq_mac`. The client must hex-decode `PollEnvelope.seqMac` (64 chars → 32
bytes) and compare against its computed 32 bytes using a constant-time comparison.
`ByteArray.contentEquals` is NOT constant-time on all JVM implementations. A
`MessageDigest.isEqual`-style comparison (XOR all bytes, check zero) or a simple
loop-XOR is adequate for this threat model because the verify key is per-identity and
already known to the client; timing side-channels on the verify step do not materially
help an adversary who already has the verify key. However, the PR should document the
decision explicitly rather than silently using `contentEquals`.

---

## 5. Cursor seam evolution

**Option (a):** Extend `LongPollCursorReader` with an `upsertLastSeenSeq` method, dropping
the `fun interface` property.

Implementation cost: `LongPollCursorReader.kt` changes from a SAM interface to a regular
interface with two methods. The SAM constructor shorthand used in `AppContainer.kt:1009`
breaks and must be replaced by an anonymous object or a named adapter class. All existing
tests that construct `LongPollCursorReader { ... }` as a lambda must be updated. The
`RestFallbackOrchestrator` constructor parameter type changes, requiring a stub in every
test that constructs an orchestrator.

**Option (b):** Introduce `LongPollCursorRepository` (read + write) as a new interface;
keep `LongPollCursorReader` for the current read-only Stage 2B-A contract and deprecate
it as the orchestrator seam.

Implementation cost: one new file (`LongPollCursorRepository.kt` in transport module),
a new constructor parameter on `RestFallbackOrchestrator` (`cursorRepository:
LongPollCursorRepository? = null`), and the `AppContainer` bridge lambda upgraded to
implement the new interface. Stage 2B-A's existing `lastSeenSeqReader` parameter can
remain, deprecated, until the Stage 2B-B PR removes it — keeping the diff minimal and
preserving backward compat for any existing test that supplies the read-only seam.

**Recommendation: option (b).**

The SAM `fun interface` shape of `LongPollCursorReader` is explicitly called out in the
Stage 2B-A mini-lock as a structural enforcement of the read-only invariant
(`LongPollCursorReader.kt:22`: "making it STRUCTURALLY IMPOSSIBLE for Stage 2B-A code
to advance the cursor"). Dropping the SAM by adding a write method would remove that
structural guarantee retroactively, even for code that never calls Stage 2B-B. A
separate `LongPollCursorRepository` with both read and write preserves the intent: any
code that only receives a `LongPollCursorReader` literally cannot write, and any code
that receives a `LongPollCursorRepository` has explicitly opted into the full contract.
The incremental cost of option (b) over option (a) is one additional interface file and
one deprecated parameter — small relative to the clarity benefit.

The `SqlDelightLastSeenSeqRepository` already implements both `getLastSeenSeq` and
`upsertLastSeenSeq` (`LastSeenSeqRepository.kt:55,64`). The `AppContainer` bridge at
line 1009 can be replaced by an object expression that delegates both methods to
`lastSeenSeqRepo`.

---

## 6. Circuit breaker mechanism

**Does it fit in `RestStateMachine`?**

Partially. `RestStateMachine` already handles the conceptual equivalent for the
WS→REST direction (the `ActiveOutboundAckTimeout` and `InboundIdleTimeout` fast-path
events at `RestStateMachine.kt:202,232`). The breaker for Stage 2B-B is the
REST-poll-consecutive-failure signal, which would logically add to `RestStateMachine`
as a new `Event.RestPollConsecutiveFailure(count: Int)` and a new state or counter.

However, the REST-poll → WS-fallback direction (breaker "open" = REST is failing,
prefer WS) is different from what `RestStateMachine` currently models. The existing
state machine models WS health degradation. Adding the inverse (REST degradation
signaling WS preference) would couple two failure domains in one state machine, making
the transition table harder to reason about. It would also require new `RestMode` values
(e.g. `RestDegraded`) that interact with the existing three modes in non-obvious ways.

**Recommendation:** A lightweight, separate `LongPollBreakerState` (not a full class —
a simple sealed type + counter stored on the orchestrator, or a small dedicated state
machine) is cleaner. It can live as a private inner state on the orchestrator. Its
transitions feed into `submitEvent` on `RestStateMachine` as needed. This keeps
`RestStateMachine` focused on WS-health reasoning and the breaker focused on REST-poll
reliability reasoning.

**Inputs to the breaker (where they come from now):**

- WS ping timeouts: `RestStateMachine.Event.WsSessionEnded` with `inboundFrames == 0`
  is the existing signal (`RestStateMachine.kt:101-135`). WS-specific ping timeout
  events are emitted by `KtorRelayTransport.startIdleWatchdog` and forwarded via
  `HybridRelayTransport` (`RestStateMachine.kt:352`).
- REST consecutive failures: currently counted nowhere. Stage 2B-B must count them in
  `wsActivePollLoop` — the existing `outcome.isFailure` branch at line 800 and the
  non-2xx branch at line 821 are the accumulation sites.
- 410 Gone: a new terminal signal not currently handled; must be a distinct counter
  input.

**Behaviour rule:**

The breaker "open" state means REST poll has failed enough times in a row that it should
back off aggressively (e.g., increase backoff, potentially signal the UI). It does NOT
mean WS becomes primary — WS and REST are parallel per the multi-transport framing; the
breaker governs REST cadence, not WS mode. The existing `RestStateMachine`
`WsActive/WsCandidate/RestActive` semantics already govern WS primacy; the breaker
complements them by managing REST reliability independently.

Half-open: a standard approach is "after N failures, wait T seconds, issue one probe
poll, if it succeeds close the breaker, otherwise re-open with exponential backoff". The
existing `POLL_FAIL_BACKOFF_MS` and jittered delay already serve the backoff purpose.
The "half-open probe" is structurally equivalent to the next scheduled poll after a
`delay(backoff)`. A named `BREAKER_HALF_OPEN` state is probably unnecessary complexity —
the existing backoff doubling on `outcome.isFailure` already implements half-open
semantics implicitly.

---

## 7. Test seam recommendations

The following seams should be preserved or introduced so that Stage 2B-B tests can drive
behaviour deterministically without HTTP:

**T1 — `FakeLongPollCursorRepository`:** An in-memory implementation of the new
`LongPollCursorRepository` interface (option (b) above) with a `Mutex`-guarded map and
public `readCalls: Int` / `writeCalls: Int` counters. Tests assert that cursor
advancement happens exactly once per successfully verified+deduped envelope, and that a
MAC-rejected envelope increments neither counter.

**T2 — `FakeSeqMacVerifier` / injected verifier function:** `SeqMacVerifier` should
accept a key as a parameter or be a functional type rather than a singleton. This lets
tests inject a lambda `(PollEnvelope, String) -> Boolean` that returns deterministic
pass/fail without invoking libsodium. The production code paths exercise the real
`Auth.authHmacSha256` in instrumented tests.

**T3 — Virtual-time breaker threshold:** If the circuit breaker uses any
time-based threshold (half-open re-probe interval, backoff multiplier), it must read
time from the same `now: () -> Long` lambda injected into `RestFallbackOrchestrator`
(`RestFallbackOrchestrator.kt:65`). Tests can substitute `now` with a controllable
`AtomicLong` and advance it without real wall-clock `delay`.

**T4 — `FakeRestFallbackTransport` 410 extension:** The existing `FakeTransport` used
for Stage 2B-A tests should gain a response-queue capability so a test can pre-seed
a 410 response at a specific poll iteration. This lets the 410 → re-auth path be tested
without network.

**T5 — MAC verification contract test (byte-pinned vector):** One test should pin a
specific `(verifyKeyHex, identityHex, seq, envelopeId, sequenceTs, expectedSeqMacHex)`
tuple derived from the server's `seq_mac.rs` test vectors (`seq_mac.rs:339-388`). This
pins the encoding against any future drift in the canonical input construction. The
server test at `seq_mac.rs:339` uses root key all-zeros, identity `"a" * 64`, seq=1,
envelope_id="envelope-id", sequence_ts=60_000.

---

## 8. Open questions for the user

**OQ-1 — Constant-time MAC comparison.** The `seq_mac` verify step compares 32 bytes of
HMAC output against 32 bytes decoded from the wire field. Using a loop-XOR comparison
is adequate for the documented threat model (the verify key is already client-side; no
remote oracle is present). However, if the threat model is ever extended (e.g., a
scenario where a relay-side adversary can probe individual bytes), a constant-time
guarantee becomes important. Should the Stage 2B-B PR document explicitly that
`contentEquals` is acceptable here, or mandate a constant-time path regardless?

**OQ-2 — 410 Gone scope: both poll loops or `wsActivePollLoop` only?** The legacy
`pollLoop` (lines 571-710) currently has no 410 handling. Stage 2B-B presumably adds
re-auth on 410 to `wsActivePollLoop`. Should the same 410 handling be backfilled into
`pollLoop` in the same PR, or is `pollLoop` outside Stage 2B-B's scope?

**OQ-3 — Circuit breaker persistence.** The breaker counter resets on each
`RestFallbackOrchestrator` lifecycle (start/stop). After a cold-start the counter is
always zero. Should the breaker ever persist its state across restarts (e.g., to avoid
hammering a degraded relay immediately after an app restart), or is stateless (reset on
every start) the intended behaviour?

**OQ-4 — `seqMacVerifyKey` empty-string handling.** When the relay does not advertise
`seq_mac_verify_key` (old relay, empty string in `_seqMacVerifyKey`), should Stage 2B-B
skip verification entirely and still advance the cursor, or should it refuse to advance
the cursor until a non-empty key is present? The Stage 2B-A mini-lock (lock L5) defers
this decision to Stage 2B-B.

**OQ-5 — `POLL_MAX_ENVELOPES=1` and `more: true` interaction in `wsActivePollLoop`.** The
Stage 2B-A `wsActivePollLoop` (line 834-851) does not implement the `more: true` drain
loop that the legacy `pollLoop` has (lines 688-691). With MAC verification added, if
the server returns `more: true` and the single envelope fails MAC, the client will still
wait the full `jitteredDelay` before the next poll rather than draining. Is this
acceptable, or should the `wsActivePollLoop` inherit the drain-immediate logic on MAC
verify success?
