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

**Closed 2026-05-21 (late).** PR #208 merged to `master` as `d953b131`. Single PR, three logical commits — exactly the layout Vladislav locked when greenlighting the plan:

- **`docs(known-issues): align with M1w/M2/REC reality (post-Alpha-1)`** (`1adc2d1e`) — title moved from "PHANTOM Alpha 1 — Known Issues" to "PHANTOM — Known Issues" (post-Alpha-1, no fixed release deadline). ISSUE-001 rewritten to cover the half-open TCP middlebox class + H1c/H1e Run C locked policy. ISSUE-004 marked ✅ RESOLVED by PR-H2b. ISSUE-006 marked ⚠️ PARTIALLY ADDRESSED (voice upload progress UI + cancel X glyph shipped; text status icons still queued). ISSUE-014 rewritten post-pivot (Tor demoted, Reality load-bearing, calls on RU LTE unproven). ISSUE-017 reframed under the M1w + receiver-tolerance window. New issues ISSUE-018 (Tele2 WS Frame.Text drop), ISSUE-019 (Tele2 POST response drop), ISSUE-020 (single-relay media ceiling), ISSUE-021 (native OkHttp pattern), ISSUE-022 (first-message bootstrap delay), ISSUE-023 (receiver-side media cancel unsupported) added.
- **`docs(adr,timeline): advance ADRs and kill superseded deadlines`** (`94bace93`) — ADR-011 `proposed` → `Accepted` with status block citing PR-H1c `e946caba` + PR-H1e `bcc501be` (locked Run C policy: `APP_LEVEL_PING_ENABLED=false`, OkHttp WS `pingInterval(15s)`, AlarmManager proactive at 45 s, server TCP `SO_KEEPALIVE`). ADR-023 `proposed` → `Accepted` with status block citing PR #56 `d862f3d0` + grep-verified implementation footprint (`KeystoreBlobCipher` / `AndroidKeystoreBlobCipher` / `SqlDelightLocal*PreKeyRepository.privateKeyCipher` / `AndroidKeystoreBlobCipherTest`). MASTER_TIMELINE: strikethrough + cause-line per Vladislav's lock #2 on every killed calendar item (June 1 release, June secondary funding, 25-day plan, NLnet draft V2, FLOSS submission, Tag `v0.1.0-alpha.2`, June/July-Sept/Oct-Dec/Q1-2027 phased calendars, Phase 5 UnifiedPush 2027 risk).
- **`docs(log): refresh current state and consolidate Open follow-ups`** (`ac15797c`) — new "What works today (master `8f4c68c9`, 2026-05-21)" block at the top of "Current state", with the Alpha-1 baseline preserved as historical context. New "Consolidated queue (Vladislav-locked order, 2026-05-21)" at the top of Open follow-ups matching the next-session locked sequence (REC-FOLLOWUP → notifications diag → D1e → network matrix → calls → voice A/B). "Deferred individual items" below the queue + "Historical / paused" preserved.

Mini-lock acceptance criteria all met:
- `git diff --stat origin/master..HEAD` shows ONLY `KNOWN_ISSUES.md`, `docs/PROJECT_LOG.md`, `docs/adr/ADR-011-*.md`, `docs/adr/ADR-023-*.md`, `docs/project/MASTER_TIMELINE_2026.md` — no `.kt`, `.rs`, `.gradle.kts`, docker, Caddyfile.
- `KNOWN_ISSUES.md` read top-to-bottom does not contradict the last 10 master commits.
- `MASTER_TIMELINE_2026.md` carries zero active post-pivot deadlines; every superseded item has explicit strikethrough + rationale.
- `PROJECT_LOG.md → Open follow-ups` matches reality; the consolidated queue + deferred individuals + historical-paused break it into reviewable groups.
- ADR-011 / ADR-023 statuses now point at the commits that shipped them.

**Discipline checkpoint.** Second PR worked end-to-end under `docs/WORKING_RULES.md` (REC3 was the first). Mini-lock authored before scope per rule 3. Three logical commits inside one PR per Vladislav's PR-format lock. Strikethrough-not-delete for dead deadlines per his cleanup lock. ADR-023 status decision gated on `git log --grep` + grep verification, not on optimism, per his ADR lock. Out-of-scope findings during the docs pass (e.g. the text-bubble status-icon item under ISSUE-006) stayed logged as deferred follow-ups, not "fixed in passing".

Next session's start: **PR-UI-REC-FOLLOWUP** per the locked queue at the top of `docs/PROJECT_LOG.md → Open follow-ups` — recording-duration source fix (`MediaMetadataRetriever` vs ticker undercount) + empty-voice race (`heldMs ≥ 700` but `durationMs ≤ 0`). Both have to be addressed inside `finalizeAndSendVoice`, not at the gesture layer.
