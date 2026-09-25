# Android settings audit (2026-09-25)

Status: implementation inventory, not a claim that every setting is complete.
Source: `apps/android/src/androidMain/kotlin/phantom/android/screens/settings/SettingsScreen.kt`
at `origin/master` 766565b, plus the changes in this worktree.

| Surface | Actual behavior | Action before a public pilot |
| --- | --- | --- |
| Profile card / Profile | Opens profile | Test save, reopen, and identity display on device |
| Username | Displays current handle; edit opens a coming-soon message | Make read-only or implement a verified change flow |
| Plan | Opens Premium; payment is not live | Keep upgrade copy explicit about availability |
| Identity signing | Shows Ed25519, which signs identity material; not an encryption-protocol selector | Verify copy against identity implementation and translate it |
| Privacy Mode | Opens its detail screen; label reads the effective posture | Test switching, failure, restart, and no silent downgrade |
| Read Receipts | Actual send gate is `privacyModeCoordinator.state.maySendReadReceipts`; the old `read_receipts` preference has no effect | This worktree replaces the false toggle with an effective-state row linking to Privacy Mode; test both postures |
| Last Seen | No separate selector or verified audience policy found here | This worktree removes the unsupported "Contacts only" claim; do not restore it without an end-to-end test |
| Screenshot Protection | `MainActivity` sets `FLAG_SECURE` unconditionally; the old preference has no effect | This worktree shows a read-only local-device state; never promise control of a peer's device or external camera |
| Message Alerts | Uses the effective app, permission, and channel gate | Test permission denied, channel muted, opt-out, and restart |
| Call Alerts | Old `call_alerts` preference has no consumer in the call notification path | This worktree removes the false toggle; design a separate real incoming-call channel before re-enabling |
| Message sound | Existing message channel uses Android sound policy | This worktree opens its OS channel settings; test on API 26 and 33+ |
| Theme | Dark is locked; row only shows coming soon | Keep a read-only value until a working theme exists |
| Language | English-only today; row only shows coming soon | Implement the full EN/RU contract in `Localization_RU_Contract.md` |
| Local storage | Computes cache/database size, but does not clear anything | This worktree makes it read-only; audit what the number includes before advertising cache cleanup |
| Export Data | Coming-soon message; no export | Do not advertise a backup or export guarantee |
| Version | Build version, read-only | Verify release build value |
| Send Feedback | Opens mail client to support address | Verify activity fallback when no mail client is installed |
| Privacy Policy | Opens the site URL | Verify published policy matches app behavior |
| Diagnostics | Debug-only chunk probe | Assert absent in release APK |

The top-level audit is not a substitute for testing the Profile, Premium, and
Privacy Mode detail flows. Before pilot, each navigable row needs a device test
that returns to Settings without losing state. A setting is complete only when
the visible value matches the authority used by the underlying feature, the
change survives restart, and failure is observable to the user.

No preference keys are deleted here. Existing installs may contain obsolete
`read_receipts`, `screenshot_protection`, and `call_alerts` values; future code
must not silently re-interpret them as a new policy.
