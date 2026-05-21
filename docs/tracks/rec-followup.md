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

(empty — track queued, not yet active)
