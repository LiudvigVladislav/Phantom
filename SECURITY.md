# Security Policy

Last updated: 2026-04-27

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

PHANTOM is pre-release. Only the `master` branch and the most recent
alpha release receive security fixes. There is no long-term support
window yet; LTS decisions will be made before the v1.0 public release.

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

- **TODO:** PGP key for `security@phntm.pro`. Until published, please
  keep reports plaintext-short and request an encrypted channel if
  details are sensitive. Alternative: use Signal at the contact
  address we will publish next to the PGP key.

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
- Issues in upstream `libsignal`, `libsodium`, `axum`, or Compose
  Multiplatform — please report those to the respective projects.
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

Release APKs are signed with the PHANTOM production keystore. The
SHA-256 fingerprint of the signing certificate:

```
AA:17:09:48:3E:BD:47:F1:21:CE:0B:D1:46:92:D1:D5:75:FD:28:A0:D6:5C:4B:2E:20:1A:BE:88:2C:AB:1F:02
```

You can verify this fingerprint against
`https://phntm.pro/.well-known/assetlinks.json` (served by the
deployed relay host) or by running:

```bash
apksigner verify --print-certs <downloaded.apk>
```

If the fingerprint in your copy of the APK does not match the value
above, do not install it and please notify us.
