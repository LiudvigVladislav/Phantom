# TECNO HiOS Wi-Fi Parking — Research & Recommendation

**Audience:** PHANTOM founder (non-engineer)
**Date:** 2026-05-01
**Source basis:** training-data recall only. Confidence labels per claim. No fabricated file paths, line numbers, commit hashes, or upstream class names.

---

## 1. Executive summary (2026 state of the art)

How do mature Android messengers keep a long-lived WebSocket alive against aggressive OEM battery management? In practical 2026 terms, ranked best to worst:

1. **Use a push channel as the primary wakeup** (FCM for Play Store builds, UnifiedPush for FOSS). The websocket is treated as a *short-lived* tunnel opened *after* push wakes the device. This is the only approach that survives Doze + OEM "App Hibernation"/"Auto-Launch" controls reliably.
2. **Foreground service + persistent notification + WifiLock + MulticastLock + periodic ConnectivityManager network request**, kept alive by user manually whitelisting the app in the OEM battery panel. Works on Pixel/Samsung; degraded on Tecno/Infinix/Xiaomi/Huawei.
3. **AlarmManager `setExactAndAllowWhileIdle` + reconnect loop.** Wakes CPU, but the radio is independent — works on AOSP, fails on aggressive OEMs that park the Wi-Fi chipset.
4. **Pure long-lived WebSocket with WakeLock.** Loses to OEM radio parking within minutes. Confirmed in PHANTOM's own logs.

**Bottom line:** there is no clean "background-only, no push, FOSS-friendly, works on every phone" answer in 2026. The realistic path is push-as-wakeup for the 95% case and a documented "disable battery saver" requirement for the long tail (Briar's posture).

---

## 2. What is well-established (high confidence — no citations needed)

- **`WIFI_MODE_FULL_HIGH_PERF` is advisory.** OEMs may downgrade or ignore it. Tecno HiOS has been confirmed in PHANTOM's own diagnostics: `dumpsys wifi` reports the lock as type=3 but the radio still parks under HiOS power policy.
- **`AlarmManager.setExactAndAllowWhileIdle()` wakes the CPU and the AlarmManager subsystem.** It does **not** independently resume the Wi-Fi radio. If the modem is parked, the alarm fires, your code runs, but `recv()` on a socket bound to that radio still blocks until the radio comes back on its own schedule.
- **Linux kernel `recv()` syscalls do not honour Java `Thread.interrupt()`** until the syscall returns naturally. OkHttp's `executor.shutdownNow()` cannot unblock a thread parked in `recv()` while the radio is asleep — the JVM has no mechanism to inject a signal into a blocked native read.
- **A foreground service with persistent notification** signals "system thinks the user wants this app awake." It does not by itself wake the radio. It only protects the *process* from being killed; the radio is governed by an independent power domain on most modern SoCs.

---

## 3. Per-messenger analysis

> Confidence labels per claim: **[well-known]** = widely documented behaviour, safe to repeat. **[recall — verify before quoting]** = my best memory, treat as a hypothesis, not a citation.

### Signal
- **Approach:** FCM is the primary wakeup on Play Store builds. The websocket is opened *after* FCM fires and stays open only as long as Signal expects more messages. **[well-known]**
- **FOSS variant:** the Signal FOSS APK (no Play Services) falls back to a periodic websocket reconnect with WakeLock and AlarmManager. **[recall — verify before quoting]**
- **Aggressive-OEM behaviour:** known degraded on Xiaomi, Huawei, Oppo, Vivo, Tecno. Signal's public guidance has historically been "whitelist Signal in battery settings." **[well-known in spirit; specific wording — recall]**
- **Wakeup primary:** FCM. AlarmManager is a fallback, not the main mechanism. **[well-known]**

### Briar
- **Approach:** no push channel by design (Briar refuses Google services for threat-model reasons). Relies entirely on a foreground service + Tor + reconnect-on-network-change. **[well-known]**
- **Public posture:** Briar explicitly tells users their phone must be configured to leave Briar running — battery whitelist, no aggressive killer apps. The project documents this as a known limitation rather than trying to engineer around every OEM. **[well-known]**
- **Wakeup primary:** none — best-effort foreground service. Messages queue at the contact's relay if the recipient is parked. **[well-known]**

### Element (Matrix)
- **Approach:** FCM on Play Store builds; **UnifiedPush** on F-Droid/FOSS builds. Element was one of the early major adopters of UnifiedPush. **[well-known]**
- **Fallback:** if neither push channel is available, Element falls back to a polling/long-poll mode with significantly degraded latency and battery. **[recall — verify before quoting]**
- **Aggressive-OEM behaviour:** UnifiedPush with a self-hosted distributor has been reported to work on Chinese OEMs better than FCM in some cases (because the distributor itself runs as a foreground service the user has explicitly whitelisted). **[recall — verify before quoting]**

**Pattern across all three:** none of them solve the radio-parking problem with pure software running inside the messenger app. They either (a) outsource wakeup to a push channel, or (b) tell the user to fix it in OS settings.

---

## 4. Workarounds NOT yet tried by PHANTOM

Ranked by feasibility for a privacy-first FOSS-friendly messenger:

### a. WorkManager periodic work — **[recall: medium confidence]**
WorkManager dispatches through JobScheduler under the hood on modern Android, which uses a different scheduling path than raw AlarmManager. On some OEMs (notably older MIUI), WorkManager work fires when AlarmManager-only approaches don't. **Verdict:** worth trying as a parallel heartbeat alongside the existing alarm. Low effort.

### b. ConnectivityManager.requestNetwork — **[high confidence on API behaviour, medium on radio effect]**
Calling `requestNetwork(NetworkRequest.Builder().addCapability(NET_CAPABILITY_INTERNET).build(), callback)` tells the OS "I need an internet-capable network now." On stock Android this can prompt the connectivity service to bring up Wi-Fi or cellular if currently disconnected. **Whether it forces a parked-but-associated Wi-Fi radio out of low-power state on Tecno HiOS is unverified.** Low effort to test, and unlike WifiLock it goes through a higher-level API the OEM is less likely to ignore wholesale.

### c. MulticastLock in addition to WifiLock — **[recall: medium confidence]**
MulticastLock is normally used for mDNS/SSDP, but multiple Android forum reports (including specifically Tecno/Infinix devices) indicate that holding a MulticastLock changes the radio's idle profile — multicast reception requires the radio not to deep-park. **Verdict:** very low effort, unintrusive, possible measurable improvement. Worth trying.

### d. JobScheduler with `setRequiresNetwork(true)` — **[medium confidence]**
Explicit network dependency; the scheduler is supposed to defer the job until network is up, which on some OEMs triggers a radio resume rather than waiting for one. Same family as (a); pick one, not both.

### e. Periodic `WifiManager.startScan()` — **[low confidence as a fix, high confidence as a side-effect]**
A scan forces the radio into active state for the scan duration. Calling it every 30–60s would keep the radio warm. **Downsides:** Android 9+ heavily rate-limits foreground scans and forbids background scans for non-system apps. Likely to be silently throttled on modern Android. Not recommended as a primary tactic.

### f. `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (Android 14+) — **[recall: low-medium confidence]**
Android 14 split foreground service types into stricter categories. `connectedDevice` is intended for companion-device sessions and may receive different OEM treatment than the generic `dataSync` type PHANTOM likely uses today. Worth checking what type the current foreground service declares; if it's `dataSync`, switching to `connectedDevice` (where appropriate) may reduce OEM aggression. Small effort.

### g. UnifiedPush — **[high confidence on what it is, medium on PHANTOM-specific outcome]**
UnifiedPush is an open standard where a *distributor app* (e.g. ntfy, conversations.im distributor, or a self-hosted one) runs on the user's phone and receives pushes from a server, then dispatches them to subscribed apps over Android intents. **It does not require Google Play Services.** For PHANTOM:
- **Pros:** preserves "no FCM" stance for FOSS users; well-supported in Matrix/Element ecosystem; relay can implement the UnifiedPush server protocol with modest effort.
- **Cons:** introduces a runtime dependency on a separate distributor app; user has to install one; "self-hosted distributor" still runs a foreground service that the user must whitelist (so it just relocates the OEM battery problem to a different app, but only one app instead of every messenger).
- **Does it solve PHANTOM's radio-parking problem?** Yes, in the same way FCM does — because Google Play Services (or the UnifiedPush distributor) is exempted from OEM battery killers on most devices, including Tecno HiOS, since OEMs ship FCM-equivalent push as a system-level expectation.

---

## 5. What we should NOT bother trying

- **Larger / longer / partial+full WakeLocks.** WakeLocks govern the CPU, not the modem. Already proven insufficient.
- **More aggressive `OkHttpClient.dispatcher.cancelAll()` variants.** Cannot unblock a thread sitting in kernel `recv()`.
- **`connectionPool.evictAll()`.** Already proven by APK 12 logs to skip active connections — it only evicts idle ones from the pool.
- **`executor.shutdownNow()`.** Already proven by APK 14 logs to not unblock kernel `recv()`. Same root cause: Java thread interrupt does not propagate into a blocked native syscall.
- **Spinning more reconnect threads.** They will all park in the same `recv()` once the radio sleeps.
- **Increasing TCP keepalive frequency from userspace.** Keepalive runs in the kernel, but the kernel cannot send packets when the radio is parked; it queues them, the radio wakes on its own schedule, and by then the server has already declared the socket dead.

---

## 6. Recommendation for PHANTOM next sprint

The recommendation section is the part that matters. Three tiers, pick based on stomach for FCM dependency.

### Best (FCM-optional, FOSS-aligned) — **MEDIUM effort, MEDIUM risk**
**MulticastLock + ConnectivityManager.requestNetwork periodic from the alarm receiver.**

- Add `WifiManager.MulticastLock` acquired alongside the existing WifiLock in the foreground service.
- In the AlarmManager receiver that already fires on the heartbeat schedule, also call `ConnectivityManager.requestNetwork(...)` with an INTERNET-capability network request. Release the request after a short window (5–10s) so the system doesn't think we want a permanent dedicated network.
- Add WorkManager periodic work as a *second* heartbeat path independent of AlarmManager — different OS scheduler, sometimes fires when alarms don't on aggressive OEMs.

**Why this first:** it preserves PHANTOM's "no Google services required" posture, costs roughly 1 week of engineering, and addresses three independent failure modes (radio idle, single-scheduler dependency, and silent alarm suppression) at once. If it works on Tecno HiOS, it likely works on the rest of the aggressive-OEM long tail too.

**Risk:** none of the three primitives are guaranteed to defeat HiOS radio parking. There is a real chance this combination still fails and we need tier 2.

### Second-best (FCM-acceptable) — **LARGE effort, LOW risk**
**UnifiedPush integration, with a PHANTOM-hosted distributor as fallback.**

- Implement the UnifiedPush client API in the Android app.
- Implement the UnifiedPush server endpoints in the relay (it is a small protocol).
- Optionally ship a PHANTOM-branded distributor app for users who don't already have ntfy or similar installed; this is what ships push to the messenger via Android intents.

**Why this second:** it is the proven 2026 answer used by Element/Matrix and growing in the FOSS messenger ecosystem. It cleanly sidesteps the radio-parking problem because the distributor (or FCM, on Play Store builds) is exempted from OEM battery killers.

**Risk:** larger engineering scope, ~3-4 weeks. Ongoing maintenance of the distributor protocol on the relay side.

### Last resort — **SMALL effort, HIGH user-friction**
**Documented "disable battery saver for PHANTOM" requirement in onboarding.**

Same posture as Briar. If tier 1 fails on HiOS *and* we don't ship UnifiedPush in time for Alpha 2, the fallback is honest documentation: a one-screen onboarding step that detects aggressive-OEM devices (Xiaomi, Huawei, Oppo, Vivo, Tecno, Infinix, Realme) and walks the user through whitelisting PHANTOM in their specific OEM's battery panel.

**Why acceptable as fallback:** it is what Briar does, what Signal recommends in their FAQ, and what every serious FOSS messenger currently ships. It is not a workaround we should be ashamed of — it is the industry baseline for the FCM-free path.

**Risk:** non-trivial drop-off during onboarding for non-technical users on aggressive OEMs. But honest.

---

### Summary table

| Option | Effort | Risk | FCM required? |
|---|---|---|---|
| Tier 1: MulticastLock + requestNetwork + WorkManager | Medium (~1wk) | Medium (may not work on HiOS) | No |
| Tier 2: UnifiedPush + relay support | Large (~3-4wk) | Low (proven pattern) | No (FCM optional, parallel) |
| Tier 3: Documented battery-whitelist onboarding | Small (~2-3 days) | High (user friction) | No |

**Recommended sequence for Alpha 2:** ship Tier 1 + Tier 3 in parallel (one engineer-week + a few days of UX). Begin Tier 2 design work in parallel for Alpha 3. Do not block Alpha 2 on Tier 2.
