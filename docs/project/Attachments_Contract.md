# Encrypted photos and files: implementation contract

Status: proposed Alpha work, not implementation authority for a new wire field,
schema migration, or crypto format. Voice media already uses the relay media
pipeline; general file attachments do not yet have a verified end-to-end
product flow.

## Product scope

The first complete slice is one-to-one image and file sending, with upload,
receive, preview where safe, open/share through Android's scoped grants, and
explicit download. Groups are a later, separately tested slice. The website
explains the feature and its limits; it is not a file browser or plaintext
storage service.

The relay currently caps a media object at 4 MiB, its total media store at
256 MiB, and media TTL at 7 days by default (`services/relay/src/media.rs`,
`services/relay/src/config.rs`). Those are current limits, not an accepted
general-file product policy. Before implementation, measure the chosen file
sizes and concurrency against those limits and decide whether to segment
files, change caps, or explicitly refuse larger files. Do not silently raise
the global quota or expose an unbounded upload endpoint.

## Required behavior

- Encrypt content on the sender before upload. The relay must not receive
  plaintext content, filename, preview, or MIME metadata. Authenticate and
  validate encrypted metadata at the recipient before use.
- Treat sender-supplied MIME type and filename as untrusted. Avoid executable
  auto-open and path traversal; use scoped Android storage access.
- Show distinct states for preparing, uploading, submitted, downloading,
  available, expired, and failed. Do not label a socket write or relay upload
  as recipient delivery. A failed upload keeps the local draft and retry path.
- Resume or restart after network interruption without duplicate visible
  messages or abandoned unbounded chunks. Enforce size and storage quotas on
  both client and relay, and clean partial uploads by bounded retention.
- Explain relay expiry separately from local retention. Deleting local media
  and clearing cache must not claim to delete a peer's copy. Logs and metrics
  must omit content, filenames, and stable recipient-linked identifiers.

## Acceptance matrix

Test small image, large image, document, wrong MIME/extension, zero-byte and
over-limit file, interrupted upload, interrupted download, relay restart,
expired object, duplicate retry, full local storage, low memory, and malicious
filename. Verify exact decrypted bytes and preview safety on phone and
emulator over Wi-Fi and LTE. Add group fan-out tests only when group semantics
and per-recipient outcomes are defined.

Any required new wire field, persisted state, schema migration, or change to
the cryptographic envelope stops this slice for an explicit design and owner
decision before implementation.
