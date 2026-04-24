# PHANTOM Release Keystore

**This directory contains the production signing key for the PHANTOM
Android app. Losing this keystore means you cannot publish updates to
Google Play — the key IS the app's identity and must remain identical
for the lifetime of the app.**

## What is in this directory

| File | Committed? | Purpose |
|------|-----------|---------|
| `phantom-release.keystore` | NO (gitignored) | PKCS12 keystore with the RSA-4096 signing key |
| `signing.properties`       | NO (gitignored) | Store + key passwords for Gradle to load |
| `README.md`                | YES             | This documentation |

`.gitignore` covers `keystores/`, `*.jks`, `*.keystore`, `*.p12`, `*.pfx`,
and `signing.properties` — double verify after any edits.

## Key facts (as generated 2026-04-24)

- **Algorithm:** RSA 4096-bit
- **Validity:** 30 years (until 2056-04-16)
- **Distinguished Name:** `CN=PHANTOM Messenger, O=Willen LLC, C=US`
- **Alias:** `phantom`
- **SHA-256 fingerprint:**
  `AA:17:09:48:3E:BD:47:F1:21:CE:0B:D1:46:92:D1:D5:75:FD:28:A0:D6:5C:4B:2E:20:1A:BE:88:2C:AB:1F:02`

This fingerprint is pinned in `deploy/well-known/assetlinks.json` for
Android App Links verification on `https://phntm.pro/invite/`.

## Backup — mandatory before the first `assembleRelease`

1. Copy `phantom-release.keystore` to an **encrypted external drive**
   (VeraCrypt container, LUKS volume, macOS encrypted DMG).
2. Copy `signing.properties` to a **separate** medium (NOT the same drive
   as the keystore) — ideally printed paper in a safe, or 1Password.
3. Store passwords in **1Password** under "PHANTOM Release Keystore".
4. Verify recovery every 6 months: mount backup, run
   `keytool -list -keystore phantom-release.keystore -storepass <pw>`.
5. If any backup is corrupted, refresh it immediately.

## Re-extracting the SHA-256 fingerprint

```bash
keytool -list -v \
  -keystore keystores/phantom-release.keystore \
  -alias phantom \
  -storepass "$(awk -F= '/^storePassword/{print $2}' keystores/signing.properties)" \
  | grep "SHA256:" | awk '{print $2}'
```

## Rotating the key

**Don't.** Google Play does not allow changing the signing key on an
existing app listing (Play App Signing notwithstanding — that's a
separate flow). If the key is compromised, you will likely need to
publish a new app.

If rotation is truly necessary:
1. Enable Play App Signing **before** your first upload.
2. Upload the first release with this keystore as the upload key.
3. Google then holds the real signing key; your upload key can be
   rotated via the Play Console "Upload key" flow.

## Legal

Willen LLC (Wyoming, USA) is the holder of record for this key. Any
transfer of the PHANTOM app ownership must include a formal transfer
of this keystore and associated Play Console credentials.
