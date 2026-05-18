# Voice-Delivery Audit — 2026-05-18

> Audit of PHANTOM voice-note delivery after Tests #56-#65. Asks: how do we
> make voice **quality excellent, transfer fast, delivery reliable** without
> blowing up the Tele2-hostile-network constraint?
>
> No code changes proposed in this doc — it produces options + a recommended
> phased plan. Vladislav decision required before any of the proposals turn
> into a PR.

## TL;DR

The current `1700-byte chunks × N round-trips` architecture is **3× more
aggressive than any secure messenger surveyed.** Signal, WhatsApp, Session,
Threema, and SimpleX all ship voice notes as effectively **single-blob
uploads** (SimpleX caps a chunk at 64 KiB, used as one chunk for any voice
under ~30 sec). PHANTOM's design is fighting Tele2's 8 KB POST cap by going
to the opposite extreme — many tiny chunks — which trades body-size risk for
RTT count, and Tests #62/#64/#65 prove the RTT count is now the dominant
cost (~100 sec for a 35-sec voice on Tele2 LTE).

Two compounding bugs surfaced during M2b:
1. **VBR overshoot.** Recorder targets 24 kbps but observed 30-35 kbps real
   wire bitrate — produces 30-65 % more chunks than the math predicts.
2. **Per-chunk RTT cost on Tele2 is high enough that even parallelism=2
   doesn't rescue long voices.** Some chunks reliably hit ~10 sec transient
   timeouts, which is normal on hostile carriers but compounds with
   chunk count to make 5-7 minute downloads observable.

The recommended path is **fewer, bigger chunks** (or single blob if Tele2
lets us), **tighter codec output** (fix the VBR overshoot), and **honest
paused-with-retry UX** for the cases where the network genuinely cannot
finish in one session. Three phased PRs land this without rewriting M1w/M2a.

---

## Section 1 — Current state baseline (Tests #56-#65)

### What works today (master HEAD, no M2b)

| Property | Status |
|---|---|
| End-to-end encrypted voice delivery 1:1 | ✅ |
| Recorder profile | OPUS 24 kbps mono 16 kHz (API 29+), AAC_ELD same (26-28) |
| Chunk size on wire | ~2388 B per HTTP POST/GET |
| Native OkHttp HTTP/1.1 fresh-client-per-call | ✅ |
| Manifest envelope via Double Ratchet | ✅ |
| Token CAS refresh under concurrent 401 | ✅ |
| SQLCipher encryption of `manifest_json` at rest | ✅ |
| Chats unread badge + notification with contact name | ✅ |
| Pre-existing audio_chunk path preserved | ✅ |

### What's broken / slow

| Voice length | Plaintext bytes | Wire bytes | Chunks (observed) | Download on Tele2 |
|---|---|---|---|---|
| 5 sec | ~15 KB | ~21 KB | 14 (Test #62) | ~10-15 sec |
| 35 sec | ~105 KB | ~150 KB | 89 (Test #62) | ~100 sec (sequential), Tests #64/#65 showed worse with parallelism due to transient failures |
| 40-45 sec | ~120 KB | ~170 KB | 116 (Test #64) | >7 minutes — observed FAIL |

### The VBR overshoot

OPUS at "24 kbps target" should produce ~3 KB/sec encoded ≈ 9 chunks for 5
sec / 62 chunks for 35 sec. The real numbers are **14 / 89 / 116** — between
30 % and 65 % over theory. Possible causes:

- `MediaRecorder.setAudioEncodingBitRate` is **a hint** for VBR encoders;
  the actual bitrate depends on signal complexity. Voice in a normal speaking
  voice often peaks at 30-40 kbps.
- OGG container overhead is small but non-zero (~few KB total).
- Ciphertext base64 inflation 1.333× plus JSON wrapper ~120 B per chunk are
  baked in.

This means the codec config alone makes the architecture do ~50 % more RTTs
than the math predicts. Fixing this is a cheap, isolated win.

---

## Section 2 — Bottleneck analysis

### RTT count dominates

The per-chunk wire latency on Tele2 LTE is **~100-200 ms** when the network
is calm. Even with parallelism=2 working perfectly:

```
5-sec voice:  14 chunks ÷ 2 = 7 RTT cycles × 200 ms = 1.4 sec ← acceptable
35-sec voice: 89 chunks ÷ 2 = 45 RTT cycles × 200 ms = 9 sec  ← OK if no transients
35-sec voice with 5 % transient rate: 4-5 retries × 10 sec timeout = +40-50 sec
60-sec voice: 150 chunks ÷ 2 = 75 cycles × 200 ms = 15 sec, +60-100 sec on transients
```

Tests #64/#65 actually saw worse than this because **certain chunk indices
reliably hit timeouts** on Tele2 — once a transient hits, the sequential
fallback in M2b.1 has to drain a 10-second-timeout-per-attempt cost on those
slots before declaring the task PENDING.

### Where time is spent

| Stage | Cost on 35-sec voice | Tunable? |
|---|---|---|
| Recording | 35 sec real-time | No — bound by voice length |
| Encrypt | ~10 ms | No (libsodium is fast) |
| Chunk | ~5 ms | No |
| **Upload 89 chunks sequential** | **~50-90 sec (Tele2 LTE)** | **Yes — fewer chunks** |
| Manifest envelope | ~1 sec (ratchet) | No |
| **Download 89 chunks parallel=2** | **~30-90 sec depending on transients** | **Yes — fewer chunks, adaptive parallelism** |
| Decrypt + sha256 + write file | ~50 ms | No |
| UI update | <100 ms | No |

Upload + download together account for ~95 % of user-observable latency.
**Reducing chunk count is the single highest-impact lever.**

### Tele2 body-cap headroom

Empirical limits from Tests #45-#48:

| Direction | Verified maximum | Documented relay cap | Tele2 hard cap |
|---|---|---|---|
| POST upload-chunk body | 5863 B (Test #45) | 4096 B (D0r REST envelope cap) | ~8192 B (curtain attack threshold) |
| GET chunk response body | tested at 2.4 KB; ceiling unknown | not enforced — relay returns what it stored | not tested >2.4 KB |

**Significance:** PHANTOM raises chunk size from 1700 raw → 5500 raw safely
under the 8 KB Tele2 cap. That would cut the RTT count by ~3×.

The download direction is even more interesting — the cap on Tele2 GET
response is **not measured**. It could be 8 KB. It could be 100 KB. A
diagnostic PR to probe this is a prerequisite for any "single-blob download"
plan.

---

## Section 3 — Industry comparison

| App | Upload arch | Chunk? | Codec | Bitrate | Failure UX |
|---|---|---|---|---|---|
| **Signal** | Single blob via TUS resumable | No (offset-resume, not parallel) | AAC mono | ~36 kbps (Android) | Auto-retry on reconnect |
| **WhatsApp** | Single HTTPS POST blob | Not documented | Opus OGG mono 16 kHz | ~16 kbps | "Tap to retry" arrow |
| **Session** | Single padded blob via onion request | No (size-padding only) | Not documented | Not documented | Onion path retry |
| **Briar** | P2P over Tor/BT/sneakernet | N/A | **No voice support yet** | N/A | N/A |
| **SimpleX (XFTP)** | Per-chunk independent uploads | **Yes — 64 KiB / 256 KiB / 1 MiB / 4 MiB** | MP4 AAC mono | Not stated; ~92.5 KB total cap | Per-chunk retry |
| **Threema** | Single HTTPS POST blob | Not documented | Opus OGG | Not documented | HTTPS retry |
| **PHANTOM today** | **~62-116 chunks at 1700 B raw each** | **Yes, extreme** | OPUS mono 16 kHz | 24 kbps target / 30-35 kbps real | Forever-spinner on transient |

### Key insights from the industry data

1. **Nobody else chunks voice notes the way PHANTOM does.** Only SimpleX
   chunks at all, and the smallest chunk size is **64 KiB** — 37× larger
   than ours. For typical voice-note size (10-200 KB) SimpleX uses one
   single-chunk upload.

2. **Signal/WhatsApp/Threema all rely on CDN-fronted upload paths** that
   bypass the most aggressive carrier middlebox restrictions. PHANTOM's
   relay is a single VPS without CDN — we can't offload upload to a
   different IP/SNI path without infrastructure work.

3. **All apps with documented failure UX show "tap to retry" or auto-retry
   on foreground, not forever-spinner.** PHANTOM today shows
   `[AUDIO_DOWNLOADING]` indefinitely on transient failure — even after
   M2b.1's "leave PENDING" semantics, the user has no way to know whether
   to wait or to retry. This is a UX gap independent of transport speed.

4. **Codecs:** WhatsApp at 16 kbps Opus is half PHANTOM's 24 kbps target.
   That tracks: WhatsApp is the size-aggressive end of the spectrum,
   Signal at 36 kbps AAC is the quality-aggressive end. PHANTOM at 24 kbps
   is exactly between them, which is reasonable for a "premium voice"
   positioning. **Do not drop bitrate below 24 kbps** (Vladislav decision,
   2026-05-18 — already memory-locked).

---

## Section 4 — Architectural options

Four options, ranked by effort. Each can land independently or as a
sequence.

### Option A — Bigger chunks ("M2c" — small, immediate)

**Change:** Raise relay `/media/upload-chunk` body cap from 4096 to 7168
(safely under 8 KB Tele2 curtain). Raise client `TARGET_RAW_CHUNK_BYTES`
from 1700 to 5000. Probe one more time on Tele2 to confirm no Layer A/B
side effects.

**Impact:**
- Chunks per 35-sec voice: 89 → ~30 (3× fewer).
- Sequential time: ~90 sec → ~30 sec on healthy LTE.
- Parallel=2 time: ~30 sec → ~15 sec.
- VBR overshoot still in play but compounded against a smaller multiplier.

**Risk:** Test against Tele2 once. If 7 KB POST fails the way 8 KB does in
PR-R0.2, fall back to 5500 raw / 6500 wire and re-test.

**Effort:** ~1 day. 2 files (`MediaChunker` + relay config), 1 test on
Tele2.

**Dependencies:** None. Drops in independently of M2b.

---

### Option B — Fix VBR overshoot ("M2a.2" — small, isolated)

**Change:** Replace `setAudioEncodingBitRate(24_000)` with an explicit CBR
configuration. Android `MediaRecorder` does not expose a CBR/VBR switch for
OPUS directly; experiment with:

- Lower target bitrate (16-20 kbps) and accept the VBR peak still tops out
  around 24 kbps (the Vladislav-locked quality floor).
- Switch to MediaCodec API (more verbose, more control), explicit CBR.
- Add a post-record re-encode pass with `ffmpeg-kit` library — heavyweight
  dependency, probably not worth it.

**Impact:**
- Real bitrate matches target → 30-50 % fewer chunks for the same voice
  length.
- Combined with Option A: 35-sec voice at ~20 chunks instead of 89.

**Risk:** OPUS at 16 kbps target may sound below the "premium" bar.
Vladislav's locked rule is 24 kbps minimum. Solution: keep 24 kbps target
but tune `MediaRecorder` to enforce ceiling. Test by ear on Tecno-class
mic.

**Effort:** ~1-2 days. 1 file (`ChatScreen.startChatRecording` +
`GroupChatScreen.startGroupRecording`), real-device A/B listening test.

**Dependencies:** None. Can land before or after Option A.

---

### Option C — Adaptive parallelism + timeout budget ("M2b.2" — medium)

**Change:** Implement the architect's M2b.2 design from the Test #65 review:

```
per-voice download budget: 60-90 sec (~2× the voice length, capped)
per-chunk transient budget: 1-2 failed attempts
when budget exceeded:
  task stays PENDING (no markFailed)
  message text → "[AUDIO_DOWNLOAD_PAUSED:<reason>]"
  UI shows "Tap to retry" button
adaptive parallelism:
  start at 1
  +1 after every 5 consecutive successful chunks (cap 4)
  -1 after every transient failure (floor 1)
```

**Impact:**
- Eliminates the 7+ minute observed downloads — task pauses at budget.
- User has actionable retry button.
- Adaptive parallelism converges to network reality.

**Risk:** Bigger refactor than M2b. UI changes. New string. Pauses with
explicit user-recovery flow is a real product feature, not just an
optimisation.

**Effort:** ~3-4 days. 4 files (`VoiceV2DownloadOrchestrator`, `ChatScreen
AudioBubble`, `strings.xml`, plus a test file).

**Dependencies:** Should land **after** Option A so that the budget
realistically covers the new chunk count.

---

### Option D — Single-blob upload protocol ("M3" — large, future)

**Change:** Add a new relay endpoint `/media/blob-upload` accepting a
single multipart POST up to 1 MB. Receive side: `/media/blob-download`
streaming response with HTTP Range header support. Sender chooses path
based on voice length:

- ≤ 60 KB encrypted (≈ 20 sec voice) → single blob POST
- > 60 KB → fall back to current chunked path

**Impact:**
- Short voices: 1 RTT for upload, 1-3 RTTs for download.
- Long voices: same as today (chunked).

**Risk:**
- **Tele2 cap unknown for blob uploads.** Need empirical test of POST body
  >8 KB on the relay's domain from Tele2 LTE.
- Relay-side complexity: temporary blob staging, streaming response, Range
  parsing.
- Failure semantics on partial transfer mid-blob (resumable upload protocol
  needed: Tus or custom).

**Effort:** ~2-3 weeks. New relay endpoint + Rust handler. New Android
transport path. New manifest variant. Significant test surface.

**Dependencies:** Empirical Tele2 cap test first. Don't start work blind.

---

## Section 5 — Recommended phased plan

### Phase 1 — Land A + B in next session (low risk, big win)

1. **M2c — Raise chunk size to 5000 B raw** (~7 KB wire). Test on Tele2.
   3× fewer RTTs for free.
2. **M2a.2 — Investigate VBR overshoot** and either lower target bitrate
   to 20 kbps (still above WhatsApp 16 kbps) or switch to MediaCodec for
   CBR. A/B listening test on Tecno.

**Expected result after Phase 1:** 35-sec voice ≈ 15-25 chunks (was 89),
download time ≈ 5-10 sec sequential or 3-5 sec parallel=2. Long-voice UX
becomes usable without further work.

### Phase 2 — Land C if Phase 1 still has long-voice transients

3. **M2b.2 — Adaptive parallelism + budget + paused-state UX.** Real
   product UX change: voices that can't finish in 60-90 sec show
   "Tap to retry" instead of a forever-spinner.

This becomes important only if Phase 1 doesn't already make voices land
fast enough. Test #65-equivalent measurements after Phase 1 tell us.

### Phase 3 — Land D only if Phase 1+2 still hit a ceiling

4. **M3 — Single-blob upload protocol.** Significant infra work; only
   justified if (a) we hit the Tele2 chunk-count wall again, AND
   (b) empirical test proves Tele2 accepts >8 KB POSTs to our relay.

### What does NOT land in this audit's plan

- **No bitrate drop below 24 kbps** (memory-locked decision).
- **No CDN integration** yet — would require new infra ($, deployment
  complexity). Defer until product traction justifies it.
- **No background WorkManager voice download** — durable task in DB is
  sufficient for now; WorkManager adds OEM-restriction headaches.
- **No mass-merge of M2b** — current draft #176 is paused; if Phase 1
  closes the gap, M2b may not be needed at all. If Phase 2 is needed,
  rebase M2b's adaptive variant on top of Phase 1.

---

## Section 6 — Decision summary (Vladislav-locked 2026-05-18)

The five open questions surfaced by the audit have been resolved. The
direction changes from "iterate on parallelism" to "reduce RTT count by
making chunks bigger". Parallelism without smaller chunk count is treating
the symptom; bigger chunks treat the cause.

| # | Question | Locked decision |
|---|---|---|
| 1 | "Tap to retry" for long voices? | Acceptable, but only as Phase 2 fallback. Normal path must auto-complete. If hostile network can't finish within budget, honest "Tap to retry" beats forever-spinner. |
| 2 | VBR overshoot fix | **Do not drop main profile below 24 kbps.** Quality floor stays. If fixed, only through MediaCodec/CBR research or isolated experiment — not by lowering the 24 kbps target. Defer to M2a.2 after M2c, only if M2c alone doesn't close the gap. |
| 3 | Tele2 chunk-cap re-test before bumping | **Required.** Run Test #45-class diagnostic with POST body sizes 5500 / 6500 / 7168 / 7500 / 8500 from Tecno on Tele2 LTE before any production bump. Don't go in blind. |
| 4 | Phase ordering | **Option A alone, first.** Bigger chunks ship as PR-M2c in isolation. Test result decides whether B or C are needed. Don't bundle A+B in one PR. |
| 5 | PR #176 fate | **Closed / parked.** Local commits 12f7c7bd + 1c497c47 stay on remote branch as reference. M2b.2 may be reopened as a fresh PR only if Phase 1 doesn't close the long-voice UX gap. The current draft's review context would be stale after M2c rebase. |

### Locked rationale

> The voice-delivery bottleneck is **RTT count**, not bitrate, not codec,
> not transport. Tests #62-#65 confirmed this empirically: relay serves
> chunks in single-digit milliseconds (VPS log proof for mediaId=CluGa7GK),
> but the client has to pay an HTTP round-trip per chunk, and we're doing
> 60-116 chunks per voice when the industry standard is 1. Bigger chunks
> first (Option A). Fix the VBR overshoot second only if needed (Option B).
> Revisit parallelism + UX third only if needed (Option C). Single-blob is
> a future infra investment, not the next move (Option D).

## Section 7 — Locked plan for the next session

### PR-M2c.0 — Tele2 cap probe (diagnostic-only)

Empirical Test #45-class diagnostic before any production change. Probe
the relay's `/media/upload-chunk` endpoint with body sizes:

- 5500 bytes
- 6500 bytes
- 7168 bytes
- 7500 bytes
- 8500 bytes (expected to fail per the curtain attack threshold; included
  as the "negative control")

Minimum 3-5 attempts per size from Tecno on Tele2 LTE. Record status,
elapsedMs, timeout signature. No production change yet — this is purely
a feasibility test.

**Decision rule for the production cap:**

| Largest stable body | Production target `TARGET_RAW_CHUNK_BYTES` |
|---|---|
| 7168 B stable | ~5000 (margin for base64 + JSON wrapper) |
| 6500 B stable | ~4500 |
| 5500 B stable | ~3800 |
| 7500 or 8500 B fail | Expected, not a blocker — confirms the cap |

### PR-M2c — Bigger chunks (production change)

After M2c.0 confirms the safe ceiling.

**Scope:**

- `MediaChunker.TARGET_RAW_CHUNK_BYTES` raised per M2c.0 result.
- Relay `/media/upload-chunk` body cap raised matchingly (config or
  constant).
- New tests for the larger cap.
- Diagnostic log: `MEDIA_TX chunk_split chunkSizeBytes=...` per voice
  upload — so Test #66 can confirm the new chunk count.

**Locks (do not touch):**

- Recorder profile / codec / bitrate (24 kbps mono 16 kHz)
- MediaCrypto
- Manifest format
- AndroidNativeOkHttpMediaUploadTransport (HTTP/1.1 fresh client per call)
- Download parallelism (stays at 1 — M2b is parked)
- UI / notifications / Chats unread
- Legacy `audio_chunk` path

### Test #66 acceptance (after M2c lands)

- 35-sec voice: `chunkCount` drops from 89 to ~25-30.
- Download time on Tele2 LTE: target ≤ 30 sec (was ~100 sec sequential
  in Test #62, indefinite with parallelism transients in #64-#65).
- Voice playable, no `sha256_mismatch`, no `decrypt_failed`, no false
  `media_chunks_gone`.
- Test #61 + #62 UX preserved.

### Phase 2 (only if Phase 1 is not enough)

- **M2a.2** VBR overshoot fix — MediaCodec/CBR research, no bitrate drop.
- **M2b.2** Adaptive parallelism + timeout budget + tap-to-retry UX —
  rebased fresh, not built on the parked #176.

End of audit.
