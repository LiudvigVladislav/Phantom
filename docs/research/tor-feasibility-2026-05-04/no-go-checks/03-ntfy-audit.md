# NO-GO check #3: ntfy self-host third-party dependency audit

**Verdict:** CONDITIONAL GO

ntfy is safe to self-host on `ntfy.phntm.pro` for PHANTOM's UnifiedPush
distributor role, provided we (a) ship a hardened `server.yml` that disables
every upstream-pointing knob, (b) front the bundled web UI with a Caddy CSP
that forbids cross-origin fetches, and (c) accept a single residual risk:
the upstream Google Fonts reference in the bundled web/docs assets, which is
trivially patched at the reverse proxy or by rebuilding static assets.

There is no embedded FCM/APNs client that fires automatically. FCM only
activates if `firebase-key-file` is configured. APNs only activates if
`upstream-base-url` is configured. Both are unset by default. The Go binary
links the Firebase Admin SDK as a normal dependency, but it is dormant code
unless we feed it a key file. That meets PHANTOM's "no third party in the
wakeup path" rule for Android (UnifiedPush direct-to-device); we are not
shipping iOS in Alpha, so APNs is moot.

## Telemetry / analytics

**No.** ntfy server has no telemetry, no analytics endpoint, no usage
reporting. The author maintains a position consistent with this in the
project privacy policy and FAQ; the SaaS at `ntfy.sh` does keep request
metadata, but that is the SaaS, not the server software. Self-hosted
binary makes zero outbound calls of its own volition. No opt-out is needed
because there is nothing to opt out of.

## Update check phone-home

**No automatic phone-home from the server.** The server does not poll
GitHub releases or anywhere else. There is a `/v1/version` endpoint
(admin-only, local), and the bundled web app surfaces a "new version
available" banner, but the version comparison is performed client-side in
the browser; the server itself never reaches out. We can safely block any
outbound HTTPS from the ntfy container at the firewall as a belt-and-
braces measure.

## FCM/APN embedded dependencies

**Present in binary, dormant by default, no build tag to strip them.** The
Firebase Admin SDK (Apache 2.0) is a normal Go module dependency, so the
code is in the binary. It is only invoked if `firebase-key-file` is set.
APNs forwarding is performed by relaying a `poll_request` to whatever URL
is in `upstream-base-url`; if unset, no APNs path exists. Neither library
opens a connection at startup. Recommendation: leave both unset, and add
egress firewall rules on the Hetzner box to drop outbound traffic to
`fcm.googleapis.com`, `*.firebaseio.com`, `*.push.apple.com`, and
`ntfy.sh` so that even an accidental config edit cannot leak.

There is no first-class build tag to compile FCM out. A custom fork patch
(remove the `firebase` import in `server/server_firebase.go` and replace
the dispatcher with a no-op) is feasible if we later want a "no FCM at
all" guarantee, but it is not required for Alpha.

## External CDN in web UI

**Mostly clean, one residual reference: Google Fonts.** The React web app
is built and embedded into the binary at compile time (`server/site/`).
All JS, CSS, and Material icons are bundled. Issue #554 in the upstream
repo confirms the bundled web UI and the docs site reference Google Fonts
(Roboto) via `fonts.googleapis.com`/`fonts.gstatic.com`. This is the only
external fetch from the bundled UI.

Mitigation, in order of effort:

1. Cheapest: add a Caddy `header` that injects
   `Content-Security-Policy: default-src 'self'; font-src 'self'; ...` for
   `ntfy.phntm.pro`, which makes the browser refuse to load Google Fonts.
   The UI falls back to system fonts, which is acceptable for an admin
   surface that PHANTOM users never see.
2. Patch-and-rebuild: replace the `<link href="fonts.googleapis.com...">`
   in `server/site/index.html` (and any equivalent in the React build)
   with a self-hosted copy or remove it entirely. Roughly a 10-line diff,
   re-applied per ntfy version bump.
3. Disable the web UI entirely: `web-root: disable` in `server.yml`. We
   only need ntfy as an HTTP push endpoint for the PHANTOM Android app;
   the web UI is not in our user-facing path. This is the cleanest answer.

We also do not expose docs.ntfy.sh; only the API. So the docs-site Google
Fonts issue does not affect our deployment.

## License compatibility

**Compatible.** ntfy is dual-licensed Apache 2.0 / GPLv2 (per README).
Apache 2.0 is one-way compatible with AGPL-3.0: PHANTOM AGPL code may
interact with a separately deployed Apache-2.0 ntfy server with no
licensing concern, since ntfy runs as a standalone service over HTTP, not
linked into the PHANTOM binary. Even if we forked ntfy and modified it,
Apache 2.0 permits redistribution under AGPL terms for the combined work.

## Configuration recommendations for PHANTOM deployment

`server.yml` for `ntfy.phntm.pro`:

- `base-url: "https://ntfy.phntm.pro"`
- `listen-http: ":80"` (behind Caddy; or `listen-https` if direct)
- `behind-proxy: true`
- `cache-file: "/var/lib/ntfy/cache.db"`
- `cache-duration: "12h"` (short, push-only)
- `auth-file: "/var/lib/ntfy/auth.db"`
- `auth-default-access: "deny-all"` (token-gated topics)
- `attachment-cache-dir:` *unset* (disable attachments; PHANTOM payloads
  are tiny ciphertexts)
- `attachment-file-size-limit: "0"` (belt and braces)
- `firebase-key-file:` *unset and never set*
- `upstream-base-url:` *unset and never set*
- `web-push-public-key / web-push-private-key:` *unset* (not needed for
  Android UnifiedPush)
- `enable-signup: false`
- `enable-login: true`
- `web-root: "disable"` (kill the web UI; removes the Google Fonts vector
  entirely)
- `visitor-message-daily-limit: 100000` (tune to PHANTOM scale)

Plus, on the host:

- UFW egress allow-list: only `:443` to package mirrors during apt-update,
  drop everything else. No outbound to Google/Apple/AWS endpoints.
- Caddy CSP header (defense in depth even with `web-root: disable`):
  `Content-Security-Policy: default-src 'self'; connect-src 'self'`.

## Risk if we proceed

- Firebase Admin SDK code is resident in the binary. If a future ntfy
  release silently changes default behavior to ping FCM at startup (very
  unlikely given the project's privacy posture, but not impossible), we
  would not catch it without a release-diff review. Mitigation: pin ntfy
  to a specific tagged image, review CHANGELOG before each bump, plus the
  egress firewall as backstop.
- Google Fonts reference in bundled assets, addressed by `web-root:
  disable` plus CSP.
- ntfy's own HTTP server is a substantial Go codebase exposed to the
  internet. Standard hardening applies (rate limits, auth tokens,
  fail2ban on Caddy logs); this is not a third-party-data-path concern,
  it is just normal server hygiene.

## Mitigation if CONDITIONAL/NO-GO

We are CONDITIONAL on the config above. If we later want a "zero
FCM-related code in the binary" guarantee:

- **Custom build path:** fork `binwiederhier/ntfy`, delete
  `server/server_firebase.go` and any FCM imports, vendor the result in a
  PHANTOM-controlled image. Estimated effort: half a day initial, ~1
  hour per upstream rebase. Worth doing only if a downstream auditor
  flags dormant FCM code as a finding.
- **Alternative distributors considered:** NextPush (Nextcloud-based) has
  a heavier server footprint and a broader attack surface (full Nextcloud
  stack); rejected for ops cost. Rustpush and Gotify are smaller but
  Gotify is not a UnifiedPush distributor by spec, and Rustpush is
  immature. ntfy remains the right pick.

## Sources

- [ntfy Configuration docs](https://docs.ntfy.sh/config/)
- [ntfy server.yml reference](https://github.com/binwiederhier/ntfy/blob/main/server/server.yml)
- [ntfy README and license](https://github.com/binwiederhier/ntfy)
- [ntfy Privacy policy](https://docs.ntfy.sh/privacy/)
- [ntfy FAQ - self-hosted vs SaaS](https://docs.ntfy.sh/faq/)
- [Issue #554: Avoid external Google fonts](https://github.com/binwiederhier/ntfy/issues/554)
- [Issue #1599: /v1/version endpoint](https://github.com/binwiederhier/ntfy/issues/1599)
- [iOS upstream-base-url poll_request mechanism](https://docs.ntfy.sh/config/#ios-instant-notifications)
- [F-Droid build without Firebase](https://docs.ntfy.sh/develop/)
