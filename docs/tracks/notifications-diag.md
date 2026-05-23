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
  - On entry: `NOTIF show_entry source=<text|voice_v1_assembled|voice_v1_chunk|voice_v2_manifest> conv=<8-char-prefix> id=<notificationId> senderHash=<8-char-sender-hash> previewLen=<n>`. The `id` field is the `Int` notification id we pass to `notify()` (currently `conversationId.hashCode()`); logging it makes collision-perezatiranie diagnosable — see §3 below.
  - Before the API-33 permission check: `NOTIF api_level sdk=<Build.VERSION.SDK_INT>`.
  - In the permission gate (API 33+): `NOTIF permission_check granted=<true|false>`. If `granted=false`, also log `NOTIF skip reason=permission_denied conv=<…> id=<…>` and return. (Behaviour unchanged: silent return on missing POST_NOTIFICATIONS stays as today — only the log is new.)
  - After permission gate: log channel state via `NotificationManagerCompat`:
    `NOTIF channel_check app_enabled=<areNotificationsEnabled> channel_enabled=<getNotificationChannel(CHANNEL_ID)?.importance != IMPORTANCE_NONE> channel_importance=<int> channel_id=phantom_messages`. If `app_enabled=false` OR `channel_enabled=false`, also log `NOTIF skip reason=channel_disabled` OR `reason=notifications_disabled` but **continue** to call `notify()` (Android will drop it; we want to see the drop in logs, not bypass it — that's a behaviour change).
  - Right before `NotificationManagerCompat.from(context).notify(...)`: `NOTIF notify_called id=<notificationId> tag=<tag_or_null> conv=<…>` — `tag` field is logged even though the current call uses the untagged `notify(int, Notification)` overload, so it will be `tag=null` for now; the field is there so a future tagged-`notify` migration shows up automatically.
  - Right after: `NOTIF notify_returned id=<notificationId> tag=<tag_or_null> conv=<…>` (this is the strongest signal — if `notify_called` is present but `notify_returned` is not, the call threw and the existing `runCatching` at the AppContainer wire site swallowed the exception).
- **Inside `createChannel`** add:
  - On entry: `NOTIF channel_create_attempt sdk=<Build.VERSION.SDK_INT> channelId=phantom_messages`.
  - After `createNotificationChannel`: `NOTIF channel_created channelId=phantom_messages importance=<int>`.
  - Pre-O early-return: `NOTIF channel_create_skipped reason=pre_o sdk=<…>` for parity.
- **No-op tag note.** Don't introduce a `notify(tag, id, …)` migration in this PR — the `tag` log field is reportonly. Changing the call shape is a behaviour change and must wait for the fix PR.

### B. `PhantomApplication.kt` — one-time startup snapshot

- After `PhantomNotificationManager.createChannel(this)` in `onCreate`, dump current state to `PhantomNotif`:
  `NOTIF app_snapshot permissionGranted=<true|false|n_a_pre_33> channelExists=<true|false> channelEnabled=<true|false> channelImportance=<int|-1_if_pre_o> appNotificationsEnabled=<areNotificationsEnabled> sdk=<Build.VERSION.SDK_INT>`.
- This gives every test session an immediate snapshot of the device's notification posture so we don't have to guess after-the-fact whether the user revoked POST_NOTIFICATIONS between sessions or whether the OEM auto-disabled the channel.

### C. `DefaultMessagingService.kt` — make all four callback invoke sites uniform

Currently:

- Line ~1410 (assembled-voice path): bare `runCatching { onNewMessageNotification?.invoke(...) }` — **no logs**.
- Line ~2043 (legacy voice chunk path): `VOICE_RX notification_start` / `VOICE_RX notification_ok` already wraps it.
- Line ~2299 (text path): `Invoking onNewMessageNotification callback (null=…)` log + `runCatching` + `onFailure` error log.
- Line ~2833 (M1w voice manifest path): bare `onNewMessageNotification?.invoke(...)` — **no logs**.

Add a private helper inside the class:

```kotlin
private fun invokeIncomingNotificationCallback(
    source: String,             // "text" | "voice_v1_assembled" | "voice_v1_chunk" | "voice_v2_manifest"
    conversationId: String,
    senderName: String,
    preview: String,
    senderPubKeyHex: String,
) {
    val callbackNull = onNewMessageNotification == null
    messagingLog(
        MessagingLogLevel.INFO,
        "NOTIF invoke_attempt source=$source conv=${conversationId.take(8)} " +
            "sender=${senderPubKeyHex.take(8)} callbackNull=$callbackNull",
    )
    runCatching {
        onNewMessageNotification?.invoke(conversationId, senderName, preview, senderPubKeyHex)
    }.onSuccess {
        messagingLog(
            MessagingLogLevel.INFO,
            "NOTIF invoke_ok source=$source conv=${conversationId.take(8)}",
        )
    }.onFailure { e ->
        messagingLog(
            MessagingLogLevel.ERROR,
            "NOTIF invoke_threw source=$source conv=${conversationId.take(8)} " +
                "error=${e::class.simpleName}:${e.message}",
            e,
        )
    }
}
```

The four call sites switch to this helper. **Invariants** (called out explicitly so the diff stays observability-only):

- `runCatching` semantics unchanged — exceptions stay swallowed exactly as today.
- Callback is invoked at the **same** four code points; no new invoke sites, no removed ones.
- `senderName` / `preview` / `senderPubKeyHex` values passed through are the same expressions each site currently computes.
- No new transformations of `preview` or `senderName`. No truncation, no censoring, no enrichment.
- **No logging of secrets / ciphertext / tokens / full `mediaId`.** Only `conv.take(8)`, `senderPubKeyHex.take(8)`. For voice paths, the preview is always a literal "Voice message" / "🎤 Voice message" by design (never raw audio bytes).

The existing `VOICE_RX notification_start / notification_ok` lines stay in place — they're useful as a path-specific complement. The new `NOTIF invoke_*` lines run alongside under the same `PhantomMessaging` tag.

**Log tag routing.** `messagingLog(...)` writes under `PhantomMessaging` (silenced by the standard `*:S` logcat filter). The `PhantomNotif` tag is owned by the Android-side wrap in section A + the AppContainer wrap in §C-platform below. Combined filter `PhantomNotif:V PhantomMessaging:V *:S` shows the full chain.

### C-platform. `AppContainer.kt` — wrap the platform callback under `PhantomNotif`

`AppContainer.kt` line 790 currently does:

```kotlin
service.onNewMessageNotification = { convId, sender, preview, senderPubKeyHex ->
    try {
        PhantomNotificationManager.showMessageNotification(context, convId, sender, preview, senderPubKeyHex)
    } catch (e: Throwable) {
        android.util.Log.e("PhantomMessaging", "showMessageNotification threw …", e)
    }
}
```

Wrap with `PhantomNotif`-tagged entry/return logs around the existing try/catch:

```kotlin
service.onNewMessageNotification = { convId, sender, preview, senderPubKeyHex ->
    android.util.Log.i(
        "PhantomNotif",
        "NOTIF callback_invoked conv=${convId.take(8)} sender=${senderPubKeyHex.take(8)} previewLen=${preview.length}",
    )
    try {
        PhantomNotificationManager.showMessageNotification(context, convId, sender, preview, senderPubKeyHex)
        android.util.Log.i(
            "PhantomNotif",
            "NOTIF callback_returned conv=${convId.take(8)}",
        )
    } catch (e: Throwable) {
        android.util.Log.e(
            "PhantomNotif",
            "NOTIF callback_threw conv=${convId.take(8)} error=${e::class.simpleName}:${e.message}",
            e,
        )
        // Preserve the existing PhantomMessaging-tagged error log for backwards
        // compatibility with any old log triage habits.
        android.util.Log.e(
            "PhantomMessaging",
            "showMessageNotification threw (${e::class.simpleName}): ${e.message}",
            e,
        )
    }
}
```

Three logs per invocation: `callback_invoked` (always), `callback_returned` (success path), `callback_threw` (failure path). The pre-existing `PhantomMessaging` error log is retained so we don't break older diagnostic habits.

### D. Test #78 brief — core 3 + extended 2

After APK lands, Vladislav runs scenarios on Tecno (real device, Wi-Fi only since 2026-05-14) with logcat captured to `C:\temp\test78-tecno.log`:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat PhantomNotif:V PhantomMessaging:V PhantomMedia:V PhantomUI:V *:S | Tee-Object -FilePath "C:\temp\test78-tecno.log"
```

**Core (must run before merge):**

1. **App foreground, chat open.** Open the conversation with the emu identity; from the emu send 3 text messages. Expected behaviour matches whatever the current "chat open" policy is (probably no heads-up — UX call). Expected **logs**: 3 × `NOTIF invoke_attempt source=text`, 3 × `NOTIF invoke_ok source=text`, 3 × `NOTIF callback_invoked`, 3 × `NOTIF show_entry` … then either `NOTIF notify_called` / `NOTIF notify_returned` OR a `NOTIF skip reason=…` line if a suppression path is on the table. If neither shows up, the diagnostic has found a gap (the helper wasn't called or the platform wrap fell through).
2. **App background (home screen).** Press Home; from the emu send 3 text messages 2–3 seconds apart. Expected: 3 heads-ups (or 1 grouped notification — depends on Android's grouping heuristic and the `id=conversationId.hashCode()` collision behaviour). Expected logs: full chain × 3, including `NOTIF notify_called id=<…>` / `NOTIF notify_returned id=<…>` per message. If `id` is the same for all 3 (same conversation) — that's the perezatiranie smoking gun.
3. **Screen locked.** Lock the screen; from the emu send 3 text messages. Expected: 3 lock-screen notifications, full log chain × 3.

**Extended (run if time allows; do NOT block merge if OEM behaves oddly):**

4. **App swiped from recents (task killed).** Swipe PHANTOM from the recents stack. Wait ~10 s for the foreground service to re-establish. From the emu send 3 text messages. Same expected chain as scenario 2. OEM aggressive battery saver / "Phone Master"-class apps on Tecno HiOS may interfere here — if so, that's data, not a blocker.
5. **Doze idle.** Plug Tecno into power so the system trusts the device, let the screen go off for ~10 min, send a single text message from emu, wake the screen. Expected: notification appears within the H1e AlarmManager 45 s recovery window. Full chain visible.

**For each scenario** the log either shows the full chain (DMS-side `NOTIF invoke_attempt` → `NOTIF invoke_ok` → AppContainer `NOTIF callback_invoked` → `NOTIF callback_returned` → manager `NOTIF show_entry` → `NOTIF api_level` → `NOTIF permission_check` → `NOTIF channel_check` → `NOTIF notify_called` → `NOTIF notify_returned`) **or breaks at a specific step**. The break point is the diagnostic finding.

### Voice path Test #78 (sanity check, NOT a separate scenario)

Repeat scenario 2 (app in background) once with a 3-second **voice** message instead of text. Expected: `NOTIF invoke_attempt source=voice_v2_manifest` (M1w 1:1 voice goes through the manifest path), full chain. This isn't a separate test, just a one-shot check that the voice-manifest path produces the same log shape as the text path.

### Groups / channels are NOT in Test #78

Per Vladislav 2026-05-22: groups and channels (the PHANTOM-level features) are not yet configured in production, so this diag PR covers only the four current 1:1 notification paths (text + 3 voice variants). When the group / channel features land later, their notification sites will adopt the same `invokeIncomingNotificationCallback(...)` helper — but that wiring is the responsibility of those features' PRs, not this one.

## Out of scope (do NOT touch, log only)

- ❌ Behaviour of the permission silent-return path — leave the early-return; if `granted=false`, log and skip as today.
- ❌ Channel importance, vibration, lights, light color.
- ❌ `NotificationCompat.Builder` configuration (icon, action buttons, RemoteInput, PendingIntent flags) — pure observability PR, no UX shift.
- ❌ `PhantomMessagingService` foreground-service notification — separate channel, separate concern; if FG-notification interaction turns out to be the cause, that's the **fix** PR's job, not this one.
- ❌ The `_incomingMessages.emit(...)` flow.
- ❌ Storage / repos / ratchet / transport / OkHttp wiring.
- ❌ MainActivity intent handling (tap-to-open-chat path).
- ❌ QuickReplyReceiver (inline reply path).
- ❌ Group / channel notification paths — not configured yet in production (Vladislav 2026-05-22); they will adopt the helper when those features ship in their own PRs.
- ❌ "Show a banner asking for POST_NOTIFICATIONS permission" — privacy / onboarding decision, separate concern.

**Additional Vladislav-locked out-of-scope (2026-05-22 review of mini-lock v1):**

- ❌ **`notificationId` strategy.** Do not change from `conversationId.hashCode()`. Just log the value so collision-perezatiranie is diagnosable.
- ❌ **Unread-count logic.** No changes to how unread counters are incremented / cleared in conversation records.
- ❌ **"Chat open" suppression policy.** If there's a current code path that suppresses notifications when the user is already in the chat — leave it. If there isn't one, don't add one. Only log the active-conversation state if it's already available in the relevant scope.
- ❌ **App foreground / background detection.** No new `ProcessLifecycleOwner` listeners, no `ActivityLifecycleCallbacks`. If foreground state happens to be visible somewhere we already track, log it; don't introduce new tracking.

## Canonical log naming (Vladislav-locked 2026-05-22)

This is the exhaustive list of `NOTIF` lines this PR introduces. Builders MUST use exactly these names — anything not on this list is out of scope.

**Startup (PhantomApplication.onCreate, once per process):**

```
PhantomNotif: NOTIF app_snapshot permissionGranted=… channelExists=… channelEnabled=… channelImportance=… appNotificationsEnabled=… sdk=…
```

**Channel creation (PhantomNotificationManager.createChannel):**

```
PhantomNotif: NOTIF channel_create_attempt sdk=… channelId=phantom_messages
PhantomNotif: NOTIF channel_created channelId=phantom_messages importance=…
PhantomNotif: NOTIF channel_create_skipped reason=pre_o sdk=…
```

**Common-side invoke (DefaultMessagingService, four 1:1 sites unified):**

```
PhantomMessaging: NOTIF invoke_attempt source=text|voice_v1_assembled|voice_v1_chunk|voice_v2_manifest conv=… sender=… callbackNull=…
PhantomMessaging: NOTIF invoke_ok source=… conv=…
PhantomMessaging: NOTIF invoke_threw source=… conv=… error=…
```

**Platform callback wrap (AppContainer.kt):**

```
PhantomNotif: NOTIF callback_invoked conv=… sender=… previewLen=…
PhantomNotif: NOTIF callback_returned conv=…
PhantomNotif: NOTIF callback_threw conv=… error=…
```

**Show path (PhantomNotificationManager.showMessageNotification):**

```
PhantomNotif: NOTIF show_entry source=… conv=… id=… senderHash=… previewLen=…
PhantomNotif: NOTIF api_level sdk=…
PhantomNotif: NOTIF permission_check granted=true|false
PhantomNotif: NOTIF channel_check app_enabled=… channel_enabled=… channel_importance=… channel_id=phantom_messages
PhantomNotif: NOTIF skip reason=permission_denied|channel_disabled|notifications_disabled conv=… id=…
PhantomNotif: NOTIF notify_called id=… tag=… conv=…
PhantomNotif: NOTIF notify_returned id=… tag=… conv=…
```

**Privacy / safety invariants (apply to every line above):**

- Never log raw plaintext message bodies, ciphertext, tokens, keys, full pubkey hex, full mediaId.
- Allowed truncations: `conversationId.take(8)`, `senderPubKeyHex.take(8)`, `mediaId.take(8)`.
- `previewLen` is a length (Int), not the preview itself.
- `senderHash` is the first 8 chars of the sender's public key hex, same as we already log on the other paths.

**Source field values (closed enum, only these four):**

- `text` — incoming text message via `handleDeliver`.
- `voice_v1_assembled` — legacy voice path, full assembly path inside DMS (line ~1410).
- `voice_v1_chunk` — legacy voice chunk path with existing `VOICE_RX notification_*` lines (line ~2043).
- `voice_v2_manifest` — M1w 1:1 voice manifest path (line ~2833).

When groups / channels add their own paths in future PRs, they extend this enum with `group_text`, `group_voice_v2_manifest`, `channel_post`, etc. — but that's their PR's responsibility, not this one.

## Test acceptance

- `git diff --stat origin/master..HEAD` shows changes only in:
  - `apps/android/src/androidMain/kotlin/phantom/android/notifications/PhantomNotificationManager.kt`
  - `apps/android/src/androidMain/kotlin/phantom/android/PhantomApplication.kt`
  - `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` (one wrap of `onNewMessageNotification`)
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt` (new private helper + four call-site unifications)
- No new files. No deletions.
- `assembleDebug` green.
- **Test #78 core scenario 2** (app in background, 3 text messages from emu) produces in logcat:
  - 3 × `PhantomMessaging NOTIF invoke_attempt source=text`
  - 3 × `PhantomMessaging NOTIF invoke_ok source=text`
  - 3 × `PhantomNotif NOTIF callback_invoked`
  - 3 × `PhantomNotif NOTIF callback_returned`
  - 3 × `PhantomNotif NOTIF show_entry source=text`
  - 3 × `PhantomNotif NOTIF api_level sdk=…`
  - 3 × `PhantomNotif NOTIF permission_check granted=true` (assuming permission granted)
  - 3 × `PhantomNotif NOTIF channel_check app_enabled=true channel_enabled=true …`
  - 3 × `PhantomNotif NOTIF notify_called id=<same_hash_thrice>` (collision-perezatiranie indicator if all three IDs identical)
  - 3 × `PhantomNotif NOTIF notify_returned id=<…>`
  - + 1 × `PhantomNotif NOTIF app_snapshot …` at process start
  - + the existing `PhantomMessaging Invoking onNewMessageNotification callback…` (legacy line, retained) and `VOICE_RX notification_start/ok` lines for voice paths
- **Behaviour parity.** Notifications themselves behave **identically** to master `afd4acaf` — no new heads-ups, no missed heads-ups attributable to the diag PR. If the flakiness reproduces in the test, the logs reveal the break point and we record it in Test #78 verdict.
- **Extended scenarios (4 + 5)** are non-blocking. If OEM behaviour interferes or Doze takes too long to reproduce, log a note in the Test #78 verdict and merge the diag PR anyway — the core 3 already give us the chain visibility we need to root-cause.

## Parking conditions

- If during instrumentation we accidentally need to change behaviour to log a fact (e.g. wrapping a `notify()` call that throws and was silently swallowed before) → **park** per WORKING_RULES rule 4, surface the call to Vladislav, decide whether to land the behaviour change separately.
- If `messagingLog(...)` cannot be cleanly tagged `PhantomNotif` without plumbing a new `notifLog` parameter into DMS → use the AppContainer-side wrap (section C "simpler approach") and log via `android.util.Log.i("PhantomNotif", …)` from the platform binding only; common-side stays on `PhantomMessaging` tag.
- If the survey reveals a fifth call site I missed (group-voice path, profile-update path, etc.) → log as a follow-up in `PROJECT_LOG.md → Open follow-ups`, do not bundle into this PR.

## Last hand-off

(empty — track queued, not yet active)
