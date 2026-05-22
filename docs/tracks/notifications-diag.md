# Track: PR-NOTIF-DIAG — notification flakiness diagnostic

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; this is the locked next-session start per `docs/PROJECT_LOG.md → Open follow-ups → Consolidated queue` row #3).
**Branch (not yet opened):** `feat/pr-notif-diag` (cut fresh from `origin/master` at session start).
**Layer:** Android (`PhantomNotificationManager.kt`, `PhantomApplication.kt`) + KMP common (`DefaultMessagingService.kt` — four `onNewMessageNotification?.invoke` call sites). No new Android surfaces.
**Authored:** 2026-05-22, end of PR-UI-REC-FOLLOWUP close session.

## Goal

Find out **where** the incoming-message notification disappears on flaky runs (Test #75 on REC1, Vladislav: "notifications sometimes appear and sometimes don't, with occasional disappearance after firing"). Add structured `PhantomNotif`-tagged logs at every point on the path from "envelope arrives" to "Android shows a heads-up". **No behaviour change** — this is observability only. The fix lands as a separate PR after the diag logs identify the leak point.

This is **a diagnostic-only PR**, modelled on PR-Diag (#143) for the WS transport path. Same posture: instrument first, fix second.

## Why we cannot guess the fix

Survey of master `afd4acaf` shows at least five possible failure points, none currently observable from logcat:

1. **`PhantomNotificationManager.showMessageNotification(...)` has zero internal logs.** Silent permission early-return on missing `POST_NOTIFICATIONS` (API 33+); silent `notify()` call at the end. Android may drop `notify()` on its own (rate-limit, Doze, channel disabled by user, system-level notifications disabled) and we never see it.
2. **`DefaultMessagingService` callback invocation is uneven across paths.** Text path logs "Invoking onNewMessageNotification callback (null=…)" (line ~2299). Legacy voice chunk path logs `VOICE_RX notification_start / notification_ok` (line ~2041). M1w voice manifest path (line ~2833) and the older assembled-voice path (line ~1410) have **no** logs around the invoke. So depending on which path the message takes, we may already have partial visibility or none at all.
3. **`onNewMessageNotification` callback may be null** if the wiring in `AppContainer.kt` fails or the service is in a torn-down state. The text path logs this; other paths don't.
4. **Channel may be disabled by the user.** `NotificationManagerCompat.from(context).areNotificationsEnabled()` and per-channel `getNotificationChannel(CHANNEL_ID).importance` are not checked or logged anywhere.
5. **Foreground service interaction.** `PhantomMessagingService` runs its own ongoing-notification on a separate channel; if the same `notificationId` collides or Android groups them together, the heads-up may be silently coalesced into the FG notification.

Without per-step logs we cannot say which of (1)–(5) is the actual cause on a given test run. The diag PR's job is to make every step visible so the next test run pinpoints the leak.

## Scope (do this, only this)

### A. `PhantomNotificationManager.kt` — make the show path fully visible

- **New tag constant:** `private const val LOG_TAG = "PhantomNotif"`.
- **Inside `showMessageNotification`** add structured logs:
  - On entry: `NOTIF show_called conv=<8-char-prefix> senderHash=<8-char-sender-hash> previewLen=<n>`.
  - Before the API-33 permission check: `NOTIF api_level sdk=<Build.VERSION.SDK_INT>`.
  - In the permission gate (API 33+): `NOTIF permission_check granted=<true|false>`. If `granted=false`, also log `NOTIF skipped reason=post_notifications_denied conv=<…>` and return.
  - After permission gate: log channel state via `NotificationManagerCompat`:
    `NOTIF channel_state app_enabled=<areNotificationsEnabled> channel_enabled=<getNotificationChannel(CHANNEL_ID)?.importance != IMPORTANCE_NONE> channel_importance=<int> channel_id=phantom_messages`. If `app_enabled=false` OR `channel_enabled=false`, also log `NOTIF skipped reason=app_or_channel_disabled` but **continue** to call `notify()` (Android will drop it; we want to see the drop in logs, not bypass it — that's a behaviour change).
  - Right before `NotificationManagerCompat.from(context).notify(...)`: `NOTIF notify_called id=<hash> conv=<…>`.
  - Right after: `NOTIF notify_returned id=<hash> conv=<…>` (this is the strongest signal — if `notify_called` is present but `notify_returned` is not, the call threw and the existing `runCatching` at the AppContainer wire site swallowed the exception).
- **Inside `createChannel`** add: `NOTIF create_channel sdk=<…> channelId=phantom_messages importance=<int>` after the `createNotificationChannel` call. The existing pre-O early-return path also gets a `NOTIF create_channel_skipped reason=pre_o sdk=<…>` line for parity.

### B. `PhantomApplication.kt` — one-time startup snapshot

- After `PhantomNotificationManager.createChannel(this)` in `onCreate`, dump current state to `PhantomNotif`:
  `NOTIF startup app_enabled=<areNotificationsEnabled> channel_enabled=<…> channel_importance=<…> post_notifications_perm=<granted|denied|n_a_pre_33> sdk=<Build.VERSION.SDK_INT>`.
- This gives every test session an immediate snapshot of the device's notification posture so we don't have to guess after-the-fact whether the user revoked POST_NOTIFICATIONS between sessions.

### C. `DefaultMessagingService.kt` — make all four callback invoke sites uniform

Currently:

- Line ~1410 (assembled-voice path): bare `runCatching { onNewMessageNotification?.invoke(...) }` — **no logs**.
- Line ~2043 (legacy voice chunk path): `VOICE_RX notification_start` / `VOICE_RX notification_ok` already wraps it.
- Line ~2299 (text path): `Invoking onNewMessageNotification callback (null=…)` log + `runCatching` + `onFailure` error log.
- Line ~2833 (M1w voice manifest path): bare `onNewMessageNotification?.invoke(...)` — **no logs**.

Bring all four sites to the same shape:

```
messagingLog(INFO, "NOTIF invoke_attempt path=<text|voice_v1_assembled|voice_v1_chunk|voice_v2_manifest> conv=<8> senderHash=<8> callbackNull=<true|false>")
runCatching {
    onNewMessageNotification?.invoke(conversationId, senderName, preview, senderPubKeyHex)
}.onFailure { e ->
    messagingLog(ERROR, "NOTIF invoke_threw path=<…> conv=<8> error=<class>:<message>", e)
}.onSuccess {
    messagingLog(INFO, "NOTIF invoke_ok path=<…> conv=<8>")
}
```

The four `path` values disambiguate which message type triggered the invoke. The `callbackNull` field surfaces the "AppContainer didn't wire the callback in time" failure mode.

The existing `VOICE_RX notification_start / notification_ok` lines stay in place — they're useful as a path-specific complement. The new `NOTIF invoke_*` lines run in parallel under a unified tag.

**Log tag routing.** `messagingLog(...)` writes under `PhantomMessaging` (silenced by the standard logcat filter). Per the same pattern PR-MEDIA-UPLOAD-CANCEL2.1 used for media-side logs, mirror the new `NOTIF invoke_*` lines via `android.util.Log.i("PhantomNotif", …)` from the AppContainer-level wrap of `onNewMessageNotification`. The simpler approach is to extend `AppContainer.kt` line 790 — wrap the callback assignment with one extra layer that logs `PhantomNotif notif_callback_entry conv=<8> senderHash=<8>` before invoking `PhantomNotificationManager.showMessageNotification`, and `PhantomNotif notif_callback_threw` on the existing `catch`. Then we don't need to plumb a `notifLog` parameter into DMS — the AppContainer-side wrap is the platform binding point.

**Pick the simpler approach.** The DMS code edits in section C become the unified `messagingLog(...)` lines under their existing tag; the AppContainer wrap in section A above takes care of `PhantomNotif`. So a logcat filter `PhantomNotif:V *:S` shows the Android-side path; `PhantomMessaging:V *:S` shows the common-side path; combined `PhantomNotif:V PhantomMessaging:V *:S` shows the full chain.

### D. Test #78 brief

After APK lands, Vladislav runs five scenarios on Tecno (real device) with logcat captured to `C:\temp\test78-tecno.log` using filter:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat PhantomNotif:V PhantomMessaging:V PhantomMedia:V PhantomUI:V *:S | Tee-Object -FilePath "C:\temp\test78-tecno.log"
```

Scenarios:
1. **App foreground, chat open** — send 3 messages from emu to Tecno. Expected: no heads-up (chat is open is a separate UX decision; the logs should still show `NOTIF show_called` + `NOTIF skipped reason=…` OR `NOTIF notify_called` regardless).
2. **App background (home screen)** — send 3 messages. Expected: 3 heads-ups, 3 `NOTIF notify_returned` lines.
3. **Screen locked** — send 3 messages. Expected: 3 lock-screen notifications, 3 `NOTIF notify_returned`.
4. **App swiped from recents (task killed)** — wait ~10 s for foreground-service to re-establish, send 3 messages. Expected: same as scenario 2.
5. **Doze idle** — let the device idle screen-off ~10 min, send a single message, wake screen. Expected: notification appears within the H1e AlarmManager 45 s recovery window; `NOTIF notify_called` + `NOTIF notify_returned` both visible.

For each scenario the log either shows the full chain (`NOTIF invoke_attempt` → `NOTIF invoke_ok` → `NOTIF show_called` → `NOTIF permission_check` → `NOTIF channel_state` → `NOTIF notify_called` → `NOTIF notify_returned`) or breaks at a specific step. The break point is the diagnostic finding.

## Out of scope (do NOT touch, log only)

- ❌ Behaviour of the permission silent-return path — leave the early-return; if `granted=false`, log and skip as today.
- ❌ Channel importance, vibration, lights, light color.
- ❌ `NotificationCompat.Builder` configuration (icon, action buttons, RemoteInput, PendingIntent flags) — pure observability PR, no UX shift.
- ❌ `PhantomMessagingService` foreground-service notification — separate channel, separate concern; if FG-notification interaction turns out to be the cause, that's the **fix** PR's job, not this one.
- ❌ The `_incomingMessages.emit(...)` flow.
- ❌ Storage / repos / ratchet / transport / OkHttp wiring.
- ❌ MainActivity intent handling (tap-to-open-chat path).
- ❌ QuickReplyReceiver (inline reply path).
- ❌ Group voice notification path beyond making the four-callback-invoke logs uniform.
- ❌ "Show a banner asking for POST_NOTIFICATIONS permission" — privacy / onboarding decision, separate concern.

## Test acceptance

- `git diff --stat origin/master..HEAD` shows changes only in: `apps/android/src/androidMain/kotlin/phantom/android/notifications/PhantomNotificationManager.kt`, `apps/android/src/androidMain/kotlin/phantom/android/PhantomApplication.kt`, `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` (one wrap of `onNewMessageNotification`), `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt` (four `NOTIF invoke_*` lines + unification of the existing `runCatching` blocks).
- No new files. No deletions.
- `assembleDebug` green.
- Running Test #78 scenario 2 (app in background, 3 messages from emu) produces in logcat:
  - 3 × `NOTIF invoke_attempt path=text`
  - 3 × `NOTIF invoke_ok path=text`
  - 3 × `NOTIF notif_callback_entry`
  - 3 × `NOTIF show_called`
  - 3 × `NOTIF api_level`
  - 3 × `NOTIF permission_check granted=true` (assuming permission granted on the device)
  - 3 × `NOTIF channel_state app_enabled=true channel_enabled=true …`
  - 3 × `NOTIF notify_called`
  - 3 × `NOTIF notify_returned`
  - + the existing `PhantomMessaging Invoking onNewMessageNotification callback…` lines and `VOICE_RX notification_start/ok` for voice paths.
- Notifications themselves behave **identically** to master `afd4acaf` — no new heads-ups, no missed heads-ups attributable to the diag PR; if the flakiness reproduces in the test, the logs reveal the break point and we record it in Test #78 verdict.

## Parking conditions

- If during instrumentation we accidentally need to change behaviour to log a fact (e.g. wrapping a `notify()` call that throws and was silently swallowed before) → **park** per WORKING_RULES rule 4, surface the call to Vladislav, decide whether to land the behaviour change separately.
- If `messagingLog(...)` cannot be cleanly tagged `PhantomNotif` without plumbing a new `notifLog` parameter into DMS → use the AppContainer-side wrap (section C "simpler approach") and log via `android.util.Log.i("PhantomNotif", …)` from the platform binding only; common-side stays on `PhantomMessaging` tag.
- If the survey reveals a fifth call site I missed (group-voice path, profile-update path, etc.) → log as a follow-up in `PROJECT_LOG.md → Open follow-ups`, do not bundle into this PR.

## Last hand-off

(empty — track queued, not yet active)
