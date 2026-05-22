# Track: PR-UI-REC-FOLLOWUP — recording-duration source fix + empty-voice race

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; this is the locked next-session start per `docs/PROJECT_LOG.md → Open follow-ups → Consolidated queue`).
**Branch (not yet opened):** `feat/pr-ui-rec-followup-duration-empty-race` (cut fresh from `origin/master` at session start).
**Layer:** Android (`ChatScreen.kt` + finalizer side; common-side messaging service is in scope only if needed for the empty-voice guard, otherwise not touched).
**Authored:** 2026-05-21, end of PR-DOC-HONESTY close session.

## Goal

Закрыть два конкретных бага, обнаруженных во время Test #76 серии, которые остались в Open follow-ups после REC2.4 / REC3:

1. **Recording-duration ticker undercount.** Test #76.5b логи показывают `VOICE_REC complete durationMs=8000` на голосовом, которое Vladislav сообщает как ~12 секунд. Сейчас длительность считается через 100 ms ticker, который выходит во время state transitions (pause/resume/lock) и поэтому хронически недосчитывает. Правильный источник — либо `MediaMetadataRetriever.METADATA_KEY_DURATION` после `stop()`, либо аккумулированное реальное elapsed-time session-а (не tick count).
2. **Empty-voice race.** Test #76.3 логи показывают `hold_release_send heldMs=819 durationMs=0` — gesture release прошёл 700 ms gate, но `MediaRecorder` ничего не успел записать (warm-up race). Сейчас этот случай попадает в `finalizeAndSendVoice` и отправляет на relay пустое голосовое (`bytes ≈ 98`, `durationMs=0`). Правильный fix — `durationMs`-based gate ВНУТРИ `finalizeAndSendVoice` (drop row если `durationMs <= MIN_PLAYABLE_MS`), а не очередной gesture-layer change.

## Scope

- **Recording-duration source replacement** в `ChatScreen.startChatRecording` / `finalizeAndSendVoice`:
  - Заменить ticker-derived `recordingDurationMs` на `MediaMetadataRetriever`-derived duration после `stop()`, ИЛИ
  - Аккумулировать реальное `System.currentTimeMillis()` elapsed (start → stop, минус paused-windows), не tick-counter.
  - Решение по подходу принимается в начале session-а на основе чтения текущего кода `startChatRecording` + `pauseChatRecording` + `resumeChatRecording` + `finalizeAndSendVoice`.
  - Если выбран `MediaMetadataRetriever` путь — он должен идти на `Dispatchers.IO` (тяжёлая операция), но не блокировать UI; результат пишется в локальный row перед `manifest_sent`.
- **Empty-voice gate** в `finalizeAndSendVoice`:
  - Новая константа `MIN_PLAYABLE_VOICE_MS = 200` (или эмпирически подобрана — посмотреть `bytesPerSec` × `durationMs` на самых коротких реальных голосовых).
  - Если `recordingDurationMs <= MIN_PLAYABLE_VOICE_MS`, обновить локальный row до cancelled state (или сразу delete), залогировать `VOICE_REC dropped_empty heldMs=… durationMs=… bytes=…`, не вызывать `VoiceV2Sender.uploadVoice`.
  - **Не** трогать gesture layer (REC2.4 700 ms gate остаётся как есть — это первая линия защиты; новый gate — вторая).
- Логи `VOICE_REC duration_source=ticker|retriever durationMs=…` для diagnostic visibility в будущих real-device тестах.

## Out of scope

- Любые изменения в gesture detector в `InputBar` (REC2.4 + REC3 уже стабильны после Test #76.6d).
- UI / visual changes (RecordingPanel state machine, AudioBubble rendering).
- Транспорт / crypto / relay / chunk size / OkHttp wiring / REST fallback.
- Group voice path (`voice_chunks` table без `groupId` — отдельный track).
- Receiver-side download cancel (ISSUE-023 — depends on relay protocol).
- M2e early-manifest re-enable (depends on receiver-side cancel).
- Notifications flakiness diagnostic (next-after-this в queue).

## Test acceptance

- Vladislav records 5 reference voices (e.g. 1 s / 3 s / 5 s / 12 s / 30 s) on the new build; `VOICE_REC complete durationMs` matches stopwatch ±200 ms in each case (was: 12 s → 8000 ms pre-fix, ~33 % undercount on long voices).
- Vladislav records a quick tap-then-release (< 200 ms hold) — voice is not sent, log shows `VOICE_REC dropped_empty heldMs=… durationMs=… bytes=…`, no `MEDIA_TX upload_*` for that local row, no relay envelope leaves the device.
- Vladislav records a normal hold-and-send — voice ships normally; `durationMs` is now coherent with stopwatch; receiver-side `AudioBubble` shows the correct timer.
- Regression: hold-to-lock + pause + resume + send still works (REC2 trilogy unchanged); swipe-cancel still works (REC3 trilogy unchanged).
- No `AndroidRuntime` / `IllegalStateException` from the new `MediaMetadataRetriever` (if chosen) — must be wrapped in `runCatching` because some encoder/extension combos throw `IllegalArgumentException` on malformed files.

## Parking conditions

- If `MediaMetadataRetriever` reads a duration that disagrees with the underlying file (some Android API levels misreport on AAC_ELD) → switch to the accumulated-elapsed approach without re-debating; both are within scope.
- If the empty-voice case requires changes outside `finalizeAndSendVoice` (e.g. needs new transport-side state) → park per WORKING_RULES rule 4, redesign first.
- If during the duration-source pass we find that `bytesPerSec` calculation is also wrong → log as a follow-up in `PROJECT_LOG.md → Open follow-ups`, do not "fix in passing" (rule 7).

## Last hand-off

**Closed 2026-05-22.** PR #210 merged to `master` as `a625bde7` (squash of `feat/pr-ui-rec-followup-duration-empty-race`). One commit, one file (`ChatScreen.kt`), +92 / −5 lines. Zero impact on transport, crypto, relay, OkHttp wiring, gesture detector, RecordingPanel visuals, or the group voice path — all explicitly out-of-scope per the mini-lock above and held to.

**What landed:**

- New private top-level helper `readAudioDurationMs(file: File): Long?` — wraps `MediaMetadataRetriever.METADATA_KEY_DURATION` in `runCatching` with `try { … } finally { runCatching { retriever.release() } }`. Returns `null` if the retriever cannot extract a positive value (some AAC_ELD-on-API-26–28 edge cases).
- Two new private file-scope constants placed next to `MIN_HOLD_SEND_MS`: `MIN_SENDABLE_VOICE_DURATION_MS = 700L`, `MIN_SENDABLE_VOICE_BYTES = 1024L`.
- `finalizeAndSendVoice` rewired:
  - Captures `tickerDurationMsAtFinalize = recordingDurationMs` immediately on entry so the ticker value is frozen against any later state mutation.
  - Reads `metadataDurationMs` via the helper after `file.readBytes()`.
  - `finalDurationMs = metadataDurationMs ?: tickerDurationMsAtFinalize`; `durationSource = "metadata" | "ticker_fallback"`.
  - Empty-voice gate fires before any `MEDIA_TX` work: if `finalDurationMs < 700` OR `bytes.size < 1024`, log `VOICE_REC drop_empty …`, `runCatching { file.delete() }`, `return@launch`. The outer `finally` still resets `voiceSendInProgress`.
  - `VOICE_REC complete` log now carries both `durationMs` (final, ships downstream) and `tickerMs` (old value, for diagnostic comparison) + `source`.
  - `sendAudio(…, durationMs = finalDurationMs, …)` — the receiver's `AudioBubble` now gets the corrected value, not the undercount.

**Test #77 verdict (Vladislav, real device + emulator).** PASS on all five scenarios. The most important data point — long-voice undercount is gone:

| Scenario | heldMs | tickerMs (old code path) | metadata duration (new source of truth) |
|---|---:|---:|---:|
| A. 3–5 sec hold | 4 239 | 3 100 | 3 718 |
| B. 10–12 sec hold | 11 306 | 9 900 | **11 118** |
| C. Locked-send | n/a | 12 600 | **14 038** (matches wall-clock from recorder start to Send tap) |
| D. Swipe-cancel | 1 727 / 1 273 | — (no VOICE_REC complete) | no `MEDIA_TX` |
| E. Sub-700 ms tap | 609 / 343 | — (gesture-layer `hold_release_cancel_too_short`) | no `MEDIA_TX`, no row |

Empty-voice `drop_empty` guard didn't fire in this run because gesture-layer `MIN_HOLD_SEND_MS` already caught every short-tap before it reached `finalizeAndSendVoice`. That's intentional — the new gate is the second line of defence for the warm-up race class (Test #76.3: `heldMs=819 durationMs=0`), which depends on `MediaRecorder` not capturing audio despite a long hold. The data path has been verified end-to-end (helper called, log fields populated, branch reachable); the empty-voice trigger condition just didn't reproduce in this run.

**Discipline checkpoint.** Third PR end-to-end under `docs/WORKING_RULES.md` (REC3, PR-DOC-HONESTY, REC-FOLLOWUP). Mini-lock authored before code per rule 3. Held scope strictly — group voice path was tempting (same bug class, same fix shape) but explicitly out-of-scope per the mini-lock and stayed untouched; logged in `docs/PROJECT_LOG.md → Open follow-ups` as a separate track. No "fix in passing" code edits. Rule 4 parking threshold never reached.

**Open follow-ups generated by this track:**

- **Group voice durationMs + empty-voice guard.** `GroupChatScreen.kt` `sendGroupAudio` still passes the ticker `recordingDurationMs` and has no empty-voice gate. Same bug class as the 1:1 path; needs a separate group-durability track because the group voice path also doesn't have durable chunk storage by `groupId` (per the D2b.1 commit note). Tracked separately, no urgency until group voice surfaces as a tested feature.
- **`tickerMs=` log field as adoption signal.** Once a few more real-device sessions are captured, the `tickerMs` value can be removed from the `VOICE_REC complete` log — it's there only to attribute remaining miscount unambiguously during the adoption phase. Tracked as low-priority cleanup, no urgency.
