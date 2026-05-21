# Track: PR-DOC-HONESTY

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; the rule applies to docs work too — this track is the next session's starting point).
**Branch (not yet opened):** `docs/pr-doc-honesty` (cut fresh from `origin/master` at session start per the "Docs PR clean base" memory rule).
**Layer:** Documentation only.
**Authored:** 2026-05-21, end of REC3-close session.

## Goal

Привести документацию проекта в честное состояние после M1w / M2 / REC треков. Снять накопившийся drift между тем, что задокументировано, и тем, что реально живёт в master сегодня.

## Scope

- **`KNOWN_ISSUES.md` expansion** — добавить / переписать записи под текущую правду:
  - Tele2 Layer A (WS Frame.Text silently dropped upstream after 101 Upgrade; phone OkHttp pingInterval timeout = symptom not cause).
  - Tele2 Layer B (POST body OK upstream, но 18-byte response часто dropped downstream).
  - Tele2 media-path full-roundtrip ceiling ≈ 2400 bytes на Helsinki single-relay (см. `feedback_tele2_media_path_ceiling_2026_05_18.md`).
  - M1w voice-delivery state — production functional через encrypted media-upload + native OkHttp.
  - M2 trilogy outcome — production chunk size 3200, byte-based early manifest, ~3× headroom vs v2 ceiling.
  - REC trilogy outcome — Recording / Paused / Locked / SwipeCancel states full, REC2.4 swipe-cancel + REC3 visual + CANCEL2.1 upload-cancel wiring.
  - Reality+VPN gating (PR-A2) — design decision, not bug.
  - Tor demoted to text-only emergency fallback (cannot carry WebRTC for calls — no UDP through onion).
  - REST fallback (PR-D0r / PR-D1) live; current REST max-body 4096 b limits voice path to chunked media-upload.
- **ADR-011 / ADR-023 status advance** — продвинуть статусы из `Proposed` / `Draft` в реальное (`Accepted`, `Superseded by …`, `Implemented`) с явной ссылкой на коммиты, которые их зафиксировали.
- **Killed-deadlines cleanup** — пройтись по `MASTER_TIMELINE_2026.md` и убрать или явно отметить как `superseded` все строки, где остался hard-coded дедлайн (release 1 июня, secondary 30 июня, 25-day plan, NLnet 2026-06-01 и т.п.) — strategic pivot 2026-05-14 их все отменил, но местами в тексте они до сих пор живут.
- **Current production state summary** — короткий блок "What works today" в `PROJECT_LOG.md` (или в README, если решим выносить наружу — отдельное решение в начале session), обновлённый с учётом M1w + M2 + REC. Сейчас "What works" в `PROJECT_LOG.md` всё ещё описывает Alpha-1 (2026-04-27 baseline).
- **Deferred follow-ups queue consolidation** — собрать актуальный единый список открытых хвостов в `PROJECT_LOG.md → Open follow-ups`:
  - PR-UI-REC-FOLLOWUP (durationMs ticker vs MediaMetadataRetriever)
  - Empty-voice race when heldMs ≥ 700 ms но durationMs ≤ 0
  - M2e re-enable после relay-side receiver media-cancel protocol
  - Receiver-side download cancel (нужны новые relay endpoints)
  - Notifications flakiness diagnostic PR
  - PR-D1e contact bootstrap fast path
  - PR-R0.5 prekey GET-status fallback
  - PR-D0r sealed-sender empty-mirror
  - C-track (calls): C1 capabilities / C2 Reality endpoint pool / C3 TURN-TLS или Opus-over-Reality
  - PR-INFRA-MediaRO (second relay для route diversity на Tele2)
  - UI stale-bubble track
  - 93 non-docs [gone] local branches (broader prune awaiting greenlight)

## Out of scope

- Любые изменения в коде (Kotlin / Rust / Compose / native).
- Android UI / theming / tokens.
- Media transport / OkHttp wiring / chunk size.
- Recorder behaviour / MediaRecorder lifecycle.
- Notification path.
- Relay endpoints / capability flags / config.
- Любые "поправим по пути" фиксы вне scope выше — если что-то всплывёт, идёт в Open follow-ups через `PROJECT_LOG.md`, не в этот PR (правило 7 из WORKING_RULES).

## Test acceptance

- `git diff --stat origin/master..HEAD` показывает ТОЛЬКО docs-файлы (никаких `.kt` / `.rs` / `.gradle.kts` / `AndroidManifest.xml`).
- Чтение `KNOWN_ISSUES.md` сверху вниз не противоречит ни одному из последних 10 коммитов master.
- `MASTER_TIMELINE_2026.md` не содержит активных дедлайнов после strategic pivot 2026-05-14 без явной пометки `superseded`.
- `PROJECT_LOG.md → Open follow-ups` соответствует тому, что реально открыто (нет уже сделанных пунктов; нет пропущенных хвостов из M1w/M2/REC).
- ADR-011 / ADR-023 имеют корректные статусы со ссылкой на закрывшие их коммиты.

## Parking conditions

- Если по ходу обнаружится, что какая-то фактологическая запись требует переочистки **кода** (например, найдём, что код противоречит документу и код тоже неверен) → лог в `PROJECT_LOG.md` как новый follow-up, продолжаем docs scope, **не** дрейфуем в код.
- Если возникнет архитектурный спор о том, что считать "правдой" (например, "M2e disabled или re-enabled?") и две попытки сформулировать ничего не сошлось → park per WORKING_RULES rule 4, отдельная архитектурная мини-session перед docs.

## Last hand-off

(empty — track queued, not yet active)
