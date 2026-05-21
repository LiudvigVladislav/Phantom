# Track: PR-UI-REC3 — SwipeCancel state visual

**Started:** 2026-05-21
**Branch (not yet opened):** `feat/pr-ui-rec3-swipecancel-visual`
**Layer:** UI
**Mini-lock author:** assistant, committed before the feature branch per `docs/WORKING_RULES.md` rule 3.

## Goal

Add the visual layer for the SwipeCancel state of the Recording Panel Matrix. The gesture itself (drag-left while holding the mic, 56 dp threshold, silent cancel on release) was already wired in PR-UI-REC2.4 as a behaviour shim. REC3 makes the swipe **visible** — the user sees the trail / threshold / trash glyph during the swipe and understands they are cancelling, instead of getting a silent drop.

## Scope

- **State 4 visual** from the Recording Panel Matrix HTML handoff (`Phantom - Messengers-handoff Recording Panel/phantom-messengers/project/Recording Panel Matrix.html` + `recording-panel.jsx`):
  - Trail gradient inside a swipe-zone (left → right `Danger.22 → Danger.04`).
  - Dashed threshold border at the 72 % point (1 px dashed `Danger.55`).
  - Trash glyph as the swipe handle on the right edge of the trail (`Danger` background, 32 dp circle, white trash icon at 14 px).
  - "SWIPE TO DISCARD" hint label (mono 10 sp, `Danger`, letter-spacing 0.1em, with leading `←` arrow).
  - Live distance indicator (`72 %` style, mono 9 sp, `Danger`, opacity 0.65).
  - Disabled-state visuals for the in-panel Pause/Resume + Send buttons while in SwipeCancel (`opacity 0.4`, `pointerEvents: none`).
- Transitions between Recording → SwipeCancel and SwipeCancel → Cancel/Resume animated per design brief easing (160 ms ease-out).
- Both **light** and **dark** themes per the design tokens already shipped in REC1 / REC2 (`PhantomTokens.Colors.*`, `recording-tokens.css`).
- One new `RecordingPanelState` render branch in `InputBar` for the SwipeCancel state.
- Regression check that the existing REC2.4 gesture-level cancel still works (no PASS / FAIL change on Test #76.5b scenario).

## Out of scope

- Any changes to gesture detection (`pointerInput` / `awaitEachGesture` / `swipeCancelArmed` flag) — already shipped in REC2.4.
- The `durationMs` undercount fix (separate follow-up: ticker vs `MediaMetadataRetriever`).
- The empty-voice race when `heldMs > 700 ms` but `durationMs ≤ 0` (separate follow-up, fix inside `finalizeAndSendVoice`).
- Notifications flakiness diagnostic PR.
- M2e early-manifest re-enable (depends on relay-side cancel protocol).
- Receiver-side download cancel (depends on new relay endpoints).
- Any transport, crypto, relay, persistence, or message-format changes.

## Test acceptance

- Visual matches the Recording Panel Matrix HTML handoff (state 4) reasonably closely — trail gradient, dashed threshold border at 72 %, trash glyph on right, "SWIPE TO DISCARD" hint, live distance indicator.
- Both themes render correctly (light derived per `recording-tokens.css` light-theme block, dark per design brief).
- Transition animation timing matches the 160 ms ease-out spec from the design annotations.
- Regression: after the visual layer lands, the existing REC2.4 swipe-left-to-cancel still fires `VOICE_REC hold_release_cancel_swipe_left heldMs=…` and produces no `MEDIA_TX upload_*` / no `media_chunks_gone` (i.e. Test #76.5b chain still passes).
- No `AndroidRuntime` / `IllegalStateException`.

## Parking conditions

- If Compose animation behaviour misbehaves architecturally (e.g. an `animatedFloatAsState` competes with the gesture's `change.position` flow and produces visible jitter) and **two** architectural approaches fail in a row → park per `docs/WORKING_RULES.md` rule 4, restart from master with a redesigned approach (likely a single `Animatable`-driven layer instead of multiple `animateFloatAsState` calls).
- If a transport / crypto / non-UI issue surfaces during testing → log to `PROJECT_LOG.md` per rule 7, don't fix in this PR.

## Last hand-off

**Closed 2026-05-21.** PR #205 merged to `master` as `770e61f4`. Five iterations on a single feature branch (`feat/pr-ui-rec3-swipecancel-visual`):

- **REC3** (`48a0f9fe`) — initial SwipeCancel state render branch: trail gradient, dashed threshold marker at 72 %, trash handle on the right, "SWIPE TO DISCARD" hint, live % indicator, side-control dim to 0.4, both themes. Wired into `InputBar` without touching the REC2.4 gesture-detector.
- **REC3.1** (`bb511e3c`) — Test #76.6 fix: replaced two `align()` overlays with a single Row + weight=1 ellipsis hint so the % stops overlapping the text; killed the latching `swipeCancelArmed` flag so the user can drag past 56 dp and back below it (release decision now uses live `finalDragLeftPx`).
- **REC3.2** (`1ff8a81a`) — shortened the hint to "discard" and added the animated arrow nudge (`rememberInfiniteTransition` + `animateFloat(targetValue = -3f)`) per Vladislav's screenshot review.
- **REC3.3** (`70990a38`) — Pause/Resume in-panel control hidden while in press-hold Recording (`isPressHoldRecording = recordingState == Recording && isMicHeld`). Reappears in Locked, Paused, and Resumed-from-Paused.
- **REC3.4** (`2e0b3260`) — Test #76.6c fix: stopped flipping `isMicHeld = false` in the swipe-haptic block (it was overloaded with chip-hiding but now also gates Pause via REC3.3, which made Pause appear at 100 %). Chip visibility moved to a `swipeDragLeftPx <= swipeVisibleThresholdPx` check on the Popup.

Test #76.6d (Tecno, real device) PASS — chip stays hidden through the swipe, Pause stays hidden through press-hold, cancel arms/disarms cleanly, lock + paused render unchanged.

Out-of-scope follow-ups from the mini-lock above remain open and are tracked in `docs/PROJECT_LOG.md → Open follow-ups`.
