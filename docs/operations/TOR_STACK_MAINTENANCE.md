<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Tor Stack Maintenance — Operational Guide

**Status:** Approved 2026-05-05 by Vladislav (founder)
**Scope:** How PHANTOM keeps its bundled Tor + pluggable transports current. Security advisories on the Tor network move fast; circumvention transports (Snowflake / WebTunnel) are an active arms race against TSPU and similar systems. Falling behind by even one or two releases is a real risk to users in censored networks.

**Companion docs:**
- [`docs/adr/ADR-016-tor-unified-push-hybrid-transport.md`](../adr/ADR-016-tor-unified-push-hybrid-transport.md) — Tor + UnifiedPush architecture, including the §"Why kmp-tor 2.6.0" rationale for choosing Briar's Tor stack over kmp-tor and the `no-go-checks/01-kmp-tor-arm32.md` research note
- [`docs/spec/PRIVACY_MODE_BEHAVIOR.md`](../spec/PRIVACY_MODE_BEHAVIOR.md) — what depends on this stack working
- `.github/dependabot.yml` — automation that creates the update PRs

---

## 1. The three layers we depend on

Each layer has its own release cadence and its own monitoring channel. Stay aware of all three.

### 1.1 Upstream Tor (the daemon)

- **Source:** Tor Project (`gitlab.torproject.org/tpo/core/tor`)
- **Cadence:** stable point-releases every 1–3 months. Security advisories ad-hoc, sometimes urgent.
- **Channels:**
  - **Blog:** https://blog.torproject.org/category/tags/security-advisories — PRIMARY. Subscribe to RSS.
  - **Mailing list:** `tor-announce@lists.torproject.org` — low-volume, security-only.
  - **Major-version migrations** (e.g. 0.4.x → 0.5.x): announced months in advance.

### 1.2 Lyrebird (pluggable transports — Snowflake / WebTunnel / obfs4 / meek)

- **Source:** Tor Project anti-censorship team (`gitlab.torproject.org/tpo/anti-censorship/lyrebird`)
- **Cadence:** roughly quarterly, plus emergency releases when Snowflake brokers / bridges get blocked or when a new DPI signature appears in the wild (e.g. TSPU updates).
- **Why we care more than for upstream Tor:** when TSPU adapts and an old Lyrebird stops bypassing it, our Russian users **silently lose access**. The bundled Snowflake / WebTunnel client logic must stay current.
- **Channels:**
  - **GitLab releases:** https://gitlab.torproject.org/tpo/anti-censorship/lyrebird/-/releases
  - **Mailing list:** `anti-censorship-team@lists.torproject.org` — moderate volume.
  - Anti-censorship community Slack / IRC for in-the-moment "is X being blocked right now" discussion.

### 1.3 Briar's packaging (`org.briarproject:tor-android`, `lyrebird-android`, `onionwrapper-android`)

- **Source:** Briar Project (`code.briarproject.org/briar/{tor-android,lyrebird-android,onionwrapper}`, GitHub mirrors at `github.com/briar/*`)
- **Cadence:** new Briar releases land 2–4 weeks after upstream Tor / Lyrebird stable. Briar pins all three artifacts as a triplet — partial upgrades are not supported.
- **Channels:**
  - **GitLab releases:** https://code.briarproject.org/briar/briar/-/releases
  - **GitHub releases (mirror):** https://github.com/briar/briar/releases — easier to subscribe via GitHub's "Watch → Custom → Releases" if you already use GitHub.
  - **Maven Central** publication is the actual trigger for our Dependabot stream. Publication usually within 1–2 days of GitLab tag.

---

## 2. Three monitoring channels (set up all three)

### 2.1 Channel A — Dependabot PRs (automation, primary)

`.github/dependabot.yml` is configured to scan weekly (Monday 08:00 Europe/Berlin) for new versions of all `org.briarproject:*` artifacts. When a release lands on Maven Central, Dependabot:

1. Opens a single grouped PR titled `chore(deps): bump org.briarproject:* group`
2. Updates `gradle/libs.versions.toml` for `briar-tor`, `briar-lyrebird`, `briar-onionwrapper`
3. Tags the PR with `dependencies`, `tor-stack`, `security`
4. Adds Vladislav as reviewer

**Email:** GitHub emails the reviewer automatically when watching is enabled. Configure once at https://github.com/settings/notifications:
- "Pull requests": Email checked
- "Watching" → for repo `LiudvigVladislav/Phantom`: enable "Releases" + "Pull requests"

This is the **routine** path. Most updates flow through here without you having to do anything except review the PR.

### 2.2 Channel B — Tor Project security advisories (RSS → email)

Dependabot only fires when Briar publishes a new artifact, which lags upstream by 2–4 weeks. For **upstream security advisories** you want awareness within hours, not weeks — so you can decide whether to:

- (a) Wait for Briar's bump (default, low effort)
- (b) Pin to a temporary fork of `tor-android` if the advisory is critical and Briar is slow

Set up RSS-to-email for `https://blog.torproject.org/feed.xml` using one of:

| Service | Cost | Notes |
|---|---|---|
| **Blogtrottr** (`blogtrottr.com`) | Free | Simplest. Paste feed URL → set delivery email → done. |
| **Feedrabbit** | Free tier | Same deal, slightly nicer UI. |
| **Mailbrew** | Free / paid | Aggregates multiple feeds into a single digest. Use if you also want HN, lobste.rs, etc. |
| **Self-hosted (Miniflux)** | Server cost | If you already run home services. |

Recommended for Vladislav: **Blogtrottr**, instant delivery, set delivery to `felixandterror@gmail.com`. Filter rules in Gmail to mark "[Tor Blog]" sender as Important.

### 2.3 Channel C — quarterly manual audit

A backstop for items that slip through automation. Calendar reminder every 90 days:

```
Q1 — March 1
Q2 — June 1
Q3 — September 1
Q4 — December 1
```

Each quarter, 30-minute review:

1. Open https://gitlab.torproject.org/tpo/core/tor/-/releases — note the latest stable version.
2. Open `gradle/libs.versions.toml` — compare our pinned `briar-tor` to upstream. If gap > 1 minor (e.g. we're on 0.4.7, upstream is 0.4.9) — flag for emergency review.
3. Open https://code.briarproject.org/briar/briar/-/releases — read the last 3 release notes for any breaking-change markers.
4. Scan https://blog.torproject.org/ for the last 90 days of security-tagged posts; cross-check against any open-issue tracker entries.
5. Update `docs/PROJECT_LOG.md` with a one-line audit entry: `2026-MM-DD — Tor stack quarterly: tor X.Y.Z, lyrebird X.Y.Z, onionwrapper X.Y.Z, no critical advisories outstanding`.

---

## 3. Emergency response protocol

When a critical security advisory fires (Channel B email arrives, or NVD CVE published with CVSS ≥ 7 against tor / lyrebird / onionwrapper):

### 3.1 Within 24 hours

1. Read the advisory in full. Determine the affected versions and the actual exploit conditions.
2. Check whether our pinned `briar-tor` / `briar-lyrebird` is in the affected range. If we are not affected — log "no action required" in `docs/PROJECT_LOG.md` and close.
3. If we ARE affected — proceed below.

### 3.2 Within 72 hours

4. Check whether Briar has already shipped a patched release. If yes — bump locally (do not wait for next Dependabot cycle), test, ship hotfix.
5. If Briar has not shipped a patch yet — open issue `code.briarproject.org/briar/briar/-/issues` referencing the upstream advisory. Briar typically responds quickly.
6. Decide: wait for Briar (recommended in most cases — they have CI infrastructure for the cross-platform builds we cannot reproduce easily) OR temporarily fork.

### 3.3 If we must temporarily fork

7. Create a private fork of `tor-android` (or whichever artifact) at the patched upstream tor version.
8. Build for all 4 ABIs locally (requires Android NDK + the upstream Tor build scripts).
9. Publish to a private Maven repo or vendor as a local AAR.
10. Pin our `gradle/libs.versions.toml` to the fork.
11. Document in `docs/operations/EMERGENCY_FORKS.md` with revert plan once Briar publishes the official fix.

This path is heavy and we want to avoid it. In nearly every case (3.2 step 4) Briar ships within days.

### 3.4 Public communication

When we ship a security hotfix to users:
- New release on GitHub + Codeberg with `security` label.
- One-paragraph note in the release description citing the upstream advisory + our affected version range.
- Push notification or in-app banner if the advisory is severe enough that users should update *now*. (Update this doc when the in-app banner mechanism exists — currently TBD in product roadmap.)

---

## 4. Triage policy for routine Dependabot PRs

Most Dependabot PRs are NOT security-critical. To keep review load reasonable:

| Bump type | Action |
|---|---|
| Patch (e.g. 0.4.8.22 → 0.4.8.23) | Smoke-test on emulator, merge same day. |
| Minor (e.g. 0.4.8 → 0.4.9) | Smoke-test + Test 6 (Tecno on МТС, with Snowflake) before merge. |
| Major (e.g. 0.4 → 0.5) | Read all release notes. Test 6 mandatory. May require code changes if Briar's API shifted. |

For the `org.briarproject:*` group bump specifically: check that the three pinned versions still form a valid triplet against Briar's own `gradle.properties` (the `tor_version` / `lyrebird_version` / `onionwrapper_version` values in their main repo). If Dependabot somehow proposes a non-triplet combination, edit the PR to align.

---

## 5. Records

Each merged tor-stack bump appends one line to `docs/PROJECT_LOG.md`:

```
YYYY-MM-DD — Tor stack bump: tor X.Y.Z (was X.Y.W), lyrebird A.B.C (was A.B.D), onionwrapper P.Q.R (was P.Q.S). No security advisories — routine. Smoke-tested on emulator + Tecno.
```

Each emergency hotfix appends one line:

```
YYYY-MM-DD — SECURITY HOTFIX: tor X.Y.Z patches CVE-2026-NNNNN (heap overflow in directory authority parser). Briar shipped at A.B.C; we bumped within H hours of advisory. Tested on all platforms.
```

This timestamped record is the audit trail when external reviewers, security auditors, or users ask "how do you handle Tor updates?".

---

*End of maintenance guide. Update this document if the monitoring channels, automation, or response protocol changes.*
