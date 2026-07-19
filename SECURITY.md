# Security Policy

Last updated: 2026-07-19

## Where to write

| Purpose | Address |
|---|---|
| **Security vulnerabilities** (this document) | `security@phntm.pro` |
| Privacy / GDPR data requests | `privacy@phntm.pro` |
| Legal correspondence, DMCA, lawful-process requests | `legal@phntm.pro` |
| Abuse reports per RFC 2142 (spam, harassment, illegal content) | `abuse@phntm.pro` |
| User support, general help | `support@phntm.pro` |
| Press / media inquiries | `press@phntm.pro` |

If a report mixes categories (e.g. a vulnerability that also has privacy implications), `security@phntm.pro` takes precedence and we will route internally.

## Supported versions

PHANTOM is pre-release. Security fixes land on `master` first. Tagged alpha
releases are historical snapshots and do not receive a long-term maintenance
window.

| Version | Security support |
|---|---|
| `master` | Supported; fixes land here first |
| `v0.1.0-alpha.2` | Limited; latest tagged pre-release snapshot |
| `v0.1.0-alpha.1` and older | Not supported |

If an issue affects Alpha 2, reporters should still use the private process
below. Users will be directed to a fixed build or a newer release rather than
to a patched Alpha 2 tag.

## Reporting a vulnerability

Please report security vulnerabilities privately to:

**security@phntm.pro**

We commit to:

- **Acknowledgement within 72 hours** of a reasonable report.
- **Initial assessment within 7 days** with a preliminary severity.
- **Fix or mitigation within 90 days** for high-severity issues, or
  a documented coordination timeline if a longer window is needed.
- **Credit in the release notes** for every valid report (unless the
  reporter requests anonymity).

Encrypted communication:

- We do not currently publish an OpenPGP key or Signal contact for this
  mailbox. Keep an initial email minimal and request an encrypted channel
  before sending sensitive reproduction details, logs, or exploit material.

## Scope

### In scope

- Cryptographic flaws in `shared/core/crypto/`: X3DH, Double Ratchet,
  Safety Numbers, Sealed Sender, memzero lapses.
- Protocol-level issues: replay, reorder, malformed envelopes,
  downgrade, key-rotation handling.
- Relay (`services/relay/`): metadata leaks in logs, rate-limit
  bypass, queue exhaustion, unauthorized access to the `/admin`
  surface, TLS mis-configuration on the deployed instance.
- Android client: intent hijacking, Universal Links spoofing,
  insecure storage, biometric-bypass on the app lock,
  Foreground-Service abuse.
- Build chain: tampering that would allow a malicious APK to be
  signed with the legitimate keystore identity.

### Out of scope (not a vulnerability)

- Social engineering against users outside the app.
- Physical access to an unlocked device.
- Denial of service against a single self-hosted relay (by design;
  anyone may run their own).
- Lack of hardening features that are explicitly planned for a
  later milestone (see [ROADMAP.md](ROADMAP.md)).
- Issues confined to upstream dependencies such as `libsodium`, `axum`,
  Compose Multiplatform, `kmp-tor`, or Xray — please report those to the
  respective projects.
  We are happy to coordinate on fixes that also affect PHANTOM.

## Coordinated disclosure

We will work with reporters to agree on a public disclosure date
after a fix is available. Default window is 90 days from initial
acknowledgement. We will not threaten legal action for good-faith
research.

## Hall of Fame

_No reports yet._ Researchers credited here will be listed with the
CVE (if applicable), a short description, and a link to the fix
commit. We'll happily include a preferred handle, website, or
mastodon/matrix/email contact — reporter's choice.

## Signature verification

APK assets published by PHANTOM are signed with the PHANTOM production keystore. The
SHA-256 fingerprint of the signing certificate:

```
AA:17:09:48:3E:BD:47:F1:21:CE:0B:D1:46:92:D1:D5:75:FD:28:A0:D6:5C:4B:2E:20:1A:BE:88:2C:AB:1F:02
```

Alpha 2 is currently a tag-and-notes pre-release and does not include an APK
asset. When an APK is attached to a release, verify its fingerprint against
`https://phntm.pro/.well-known/assetlinks.json` (served by the
deployed relay host) or by running:

```bash
apksigner verify --print-certs <downloaded.apk>
```

If the fingerprint in your copy of the APK does not match the value
above, do not install it and please notify us.
