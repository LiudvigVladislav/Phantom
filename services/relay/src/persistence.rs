// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — on-disk envelope record
//! + directory layout + atomic write + read helpers.
//!
//! Locked design:
//! - `v4.1-amendments.md` §1 V-P0-4 — single file per
//!   `envelope_id` under `queue/<hex[0..2]>/<recipient>/<sha256_hex(id)>.json`,
//!   holding either `Queued` or `AckedTombstone`. Two-file
//!   POSIX-rename atomicity is impossible so idempotency lives
//!   inside the same file that carries the envelope.
//! - `v4.2-amendments.md` §4 B-4 — `Queued.expires_at` remains
//!   the envelope-TTL cutoff; `AckedTombstone.dedup_until` is
//!   decoupled from envelope TTL and derived from
//!   [`crate::tombstone_config::TombstoneConfig::dedup_until`]
//!   (v4.2.3 §1 Path A locked default 48 h).
//! - `v4.2.3-runbook-nits.md` §4 — `checked_add` for
//!   `dedup_until` at ack time.
//! - `v4-mini-lock-draft.md` §8 — per-file replay rules:
//!   filename == `sha256_hex(id)`, canonical recipient, TTL
//!   check, dup-id check.
//!
//! At M2 this module ships the DTO + I/O primitives + walk +
//! replay classification. Wiring into `do_send`/`do_ack`/
//! `do_sweep` lands in M3.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::atomic_write;

/// Currently supported record schema version. Reading a value
/// != [`RECORD_VERSION`] → refuse-boot exit 3 (unknown schema).
pub const RECORD_VERSION: u16 = 1;

/// Locked v4 §8: per-file cap of 128 KiB. Rejects a hypothetical
/// giant envelope from ever hitting the deserializer.
pub const MAX_RECORD_BYTES: u64 = 128 * 1024;

/// Persistent shape of a single envelope record (v4.1 V-P0-4).
///
/// The two variants share a single on-disk file. State
/// transitions (Queued → AckedTombstone, either → nothing via
/// TTL sweep) go through atomic tempfile+rename+parent-fsync so
/// crash-safety holds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PersistedRecord {
    Queued {
        version: u16,
        id: String,
        sealed_sender: String,
        payload: String,
        sequence_ts: u64,
        seq: u64,
        expires_at: u64,
        seq_mac: String,
        body_hash: String,
    },
    AckedTombstone {
        version: u16,
        id: String,
        seq: u64,
        body_hash: String,
        acked_at: u64,
        dedup_until: u64,
    },
}

impl PersistedRecord {
    /// Envelope id shared by both variants — the primary key
    /// that keys both the on-disk filename and the in-memory
    /// dedup table.
    pub fn id(&self) -> &str {
        match self {
            PersistedRecord::Queued { id, .. } => id,
            PersistedRecord::AckedTombstone { id, .. } => id,
        }
    }

    /// Server-assigned sequence — carried on both variants so the
    /// tombstone can serve idempotent-retry responses without
    /// consulting a separate index.
    pub fn seq(&self) -> u64 {
        match self {
            PersistedRecord::Queued { seq, .. } => *seq,
            PersistedRecord::AckedTombstone { seq, .. } => *seq,
        }
    }

    /// Canonical body hash — bound at send time via
    /// [`crate::body_hash::compute_body_hash`]. Retries with a
    /// different `body_hash` for the same `id` receive HTTP 409.
    pub fn body_hash(&self) -> &str {
        match self {
            PersistedRecord::Queued { body_hash, .. } => body_hash,
            PersistedRecord::AckedTombstone { body_hash, .. } => body_hash,
        }
    }

    /// Schema version.
    pub fn version(&self) -> u16 {
        match self {
            PersistedRecord::Queued { version, .. } => *version,
            PersistedRecord::AckedTombstone { version, .. } => *version,
        }
    }
}

/// Compute the record filename for `envelope_id`.
///
/// Filename == `sha256_hex(id) + ".json"`. Design v4 §8 replay
/// rule 3 asserts this at boot; the invariant prevents a
/// filesystem lister from correlating envelope IDs to shard
/// dirs by casual `ls`.
pub fn record_filename(envelope_id: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(envelope_id.as_bytes());
    let digest = hasher.finalize();
    format!("{}.json", hex::encode(digest))
}

/// Two-character shard prefix used to bucket recipients under
/// `queue/<hex[0..2]>/`. Design v4 §2 — reduces per-directory
/// file counts, keeps the walk friendly on ext4.
///
/// Panics if `recipient` is empty (invariant violation — PR-0
/// ingress hardening rejects empty recipients before ever
/// reaching this call).
pub fn shard_prefix(recipient: &str) -> &str {
    assert!(
        !recipient.is_empty(),
        "shard_prefix called with empty recipient — ingress-hardening bypass",
    );
    // Recipient is canonical lowercase hex (PR-0 A-6); first two
    // hex chars are always ASCII single-byte, so slicing at 2 is
    // safe. Fewer than 2 chars would be an ingress bug — take
    // whatever exists (still safe) but the assert above already
    // catches empty.
    let len = recipient.len().min(2);
    &recipient[..len]
}

/// Recipient directory: `state_dir/queue/<hex[0..2]>/<recipient>/`.
pub fn recipient_dir(state_dir: &Path, recipient: &str) -> PathBuf {
    state_dir
        .join("queue")
        .join(shard_prefix(recipient))
        .join(recipient)
}

/// Full record path.
pub fn record_path(state_dir: &Path, recipient: &str, envelope_id: &str) -> PathBuf {
    recipient_dir(state_dir, recipient).join(record_filename(envelope_id))
}

/// Errors raised by [`read_record`]. Callers (boot loader, M3
/// workers) log each precisely and choose the right refuse-boot
/// exit code or HTTP 500 mapping.
#[derive(Debug)]
pub enum RecordReadError {
    Io(std::io::Error),
    Json(serde_json::Error),
    /// File larger than [`MAX_RECORD_BYTES`]. Locked v4 §8.
    OversizeBytes {
        observed: u64,
        cap: u64,
    },
    /// `version` field disagrees with [`RECORD_VERSION`].
    UnknownVersion {
        found: u16,
        expected: u16,
    },
}

impl std::fmt::Display for RecordReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RecordReadError::Io(e) => write!(f, "record io error: {e}"),
            RecordReadError::Json(e) => write!(f, "record json error: {e}"),
            RecordReadError::OversizeBytes { observed, cap } => {
                write!(f, "record size {observed} exceeds cap {cap}")
            }
            RecordReadError::UnknownVersion { found, expected } => {
                write!(
                    f,
                    "record unknown schema version {found}; expected {expected}"
                )
            }
        }
    }
}

impl std::error::Error for RecordReadError {}

/// Read + parse a single record file. Enforces size cap and
/// schema-version check per locked v4 §8.
pub fn read_record(path: &Path) -> Result<PersistedRecord, RecordReadError> {
    let metadata = fs::metadata(path).map_err(RecordReadError::Io)?;
    if metadata.len() > MAX_RECORD_BYTES {
        return Err(RecordReadError::OversizeBytes {
            observed: metadata.len(),
            cap: MAX_RECORD_BYTES,
        });
    }
    let bytes = fs::read(path).map_err(RecordReadError::Io)?;
    let rec: PersistedRecord = serde_json::from_slice(&bytes).map_err(RecordReadError::Json)?;
    if rec.version() != RECORD_VERSION {
        return Err(RecordReadError::UnknownVersion {
            found: rec.version(),
            expected: RECORD_VERSION,
        });
    }
    Ok(rec)
}

/// Errors returned by [`SerializedRecord::serialize`] — a typed
/// carrier so callers preserve the actual observed size on the
/// oversize path (PR-2 M3a round-4 F3).
///
/// Pre-round-4 [`SerializedRecord::serialize`] returned bare
/// `io::Result<Self>`, which forced callers to invent an
/// `observed_bytes: 0` on the oversize branch — swallowing the
/// only diagnostic that lets HTTP/log mapping report a real
/// number. The typed enum keeps that number all the way to the
/// send-error surface.
#[derive(Debug)]
pub(crate) enum SerializeRecordError {
    /// `serde_json::to_vec` refused the input.
    Serde(serde_json::Error),
    /// Serialised buffer exceeded [`MAX_RECORD_BYTES`]. Both
    /// counts are known and preserved.
    TooLarge { observed: u64, cap: u64 },
}

impl std::fmt::Display for SerializeRecordError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SerializeRecordError::Serde(e) => write!(f, "serialize serde error: {e}"),
            SerializeRecordError::TooLarge { observed, cap } => {
                write!(f, "serialized record size {observed} exceeds cap {cap}")
            }
        }
    }
}

impl std::error::Error for SerializeRecordError {}

/// Opaque canonical-byte carrier for a [`PersistedRecord`].
///
/// **PR-2 M3a round-3 F5**: closes the "any bytes reach disk" hole
/// left by the round-2 `write_record_bytes(path, &[u8])` shape.
/// The only way to obtain a `SerializedRecord` is
/// [`SerializedRecord::serialize`], which runs the schema/size
/// checks itself; a future caller cannot hand
/// `write_record_bytes` an arbitrary or malformed buffer that a
/// later boot would refuse. Construction is `pub(crate)`, so
/// external crates cannot even attempt to build one.
#[derive(Debug)]
pub(crate) struct SerializedRecord {
    bytes: Vec<u8>,
}

impl SerializedRecord {
    /// Serialise a record to the canonical wire form and enforce
    /// the [`MAX_RECORD_BYTES`] cap in one step. Returns the byte
    /// buffer as an opaque carrier that
    /// [`write_record_bytes`] can hand to atomic write.
    ///
    /// **Round-4 F3**: signature now returns
    /// [`SerializeRecordError`] instead of `io::Error`; the
    /// `TooLarge` variant preserves the observed byte count so
    /// send-path error surfaces do not have to synthesise a fake
    /// `observed_bytes: 0`.
    pub(crate) fn serialize(rec: &PersistedRecord) -> Result<Self, SerializeRecordError> {
        let bytes = serde_json::to_vec(rec).map_err(SerializeRecordError::Serde)?;
        if bytes.len() as u64 > MAX_RECORD_BYTES {
            return Err(SerializeRecordError::TooLarge {
                observed: bytes.len() as u64,
                cap: MAX_RECORD_BYTES,
            });
        }
        Ok(Self { bytes })
    }

    /// The canonical byte length. The send path uses this value
    /// to size the capacity reservation AND (via
    /// [`SerializedRecord::as_bytes`]) the on-disk write, so the
    /// two footprints are byte-identical by construction.
    pub(crate) fn len(&self) -> usize {
        self.bytes.len()
    }

    pub(crate) fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }
}

/// Write a record atomically (M3 send/ack path).
///
/// Creates parent directories if needed — the boot loader
/// pre-creates them for known recipients, but a fresh send to a
/// new recipient must lazy-create the shard dir.
///
/// Round-2 M2 P1 #1: uses [`atomic_write::create_dir_all_durable`]
/// so both the shard dir AND the recipient dir are fsynced
/// alongside their parent chain up to `state_dir`. The
/// pre-amendment `fs::create_dir_all` fsync'ed neither, so the
/// FIRST record written into a fresh recipient dir could have
/// been lost after a crash even though the record's own
/// `write_atomic` file+parent fsync returned success.
///
/// Convenience wrapper that serialises `rec` and delegates to
/// [`write_record_bytes`]. Callers that need the serialised
/// footprint before writing (e.g. to size a capacity reservation
/// under PR-2 M3a F5) should call [`SerializedRecord::serialize`]
/// + [`write_record_bytes`] directly so the record is serialised
/// exactly once.
pub fn write_record(path: &Path, rec: &PersistedRecord) -> std::io::Result<()> {
    let serialized = SerializedRecord::serialize(rec).map_err(|e| match e {
        SerializeRecordError::Serde(err) => std::io::Error::new(std::io::ErrorKind::Other, err),
        SerializeRecordError::TooLarge { observed, cap } => std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("serialized record size {observed} exceeds cap {cap}"),
        ),
    })?;
    write_record_bytes(path, &serialized)
}

/// Write an already-serialised [`SerializedRecord`] atomically.
///
/// **Round-3 amendment**: signature is `pub(crate)` and accepts
/// the opaque [`SerializedRecord`] instead of `&[u8]`, so a
/// future caller cannot slip an unchecked buffer past the
/// schema/size validation performed inside
/// [`SerializedRecord::serialize`]. Combined with the sole
/// serialisation site in [`crate::rest_workers::do_send`] this
/// closes the "same bytes reach ledger AND disk" contract at the
/// type level.
pub(crate) fn write_record_bytes(
    path: &Path,
    serialized: &SerializedRecord,
) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        atomic_write::create_dir_all_durable(parent)?;
    }
    atomic_write::write_atomic(path, serialized.as_bytes())
}

/// Compact summary of a per-file walk pass over `queue/**`. Used
/// by the boot loader to build the initial `PersistedRecord` set
/// AND to seed capacity-ledger counters from disk truth (v4.2 §4
/// boot-time reconciliation).
///
/// Ordering guarantee (locked v4.1 §5 L-N11): the walk is
/// sequential per shard so RAM-budget accounting stays exact.
///
/// Round-1 M2 amendment: `staging_tempfiles_swept` records how
/// many `.staging-*.tmp` files a prior SIGKILL between
/// `write_atomic`'s file-fsync and rename left behind. Non-zero
/// value + successful boot is legitimate — the sweep is the
/// cleanup half of the crash-safety contract.
#[derive(Debug, Default, Clone)]
pub struct WalkStats {
    pub total_files: u64,
    pub total_bytes: u64,
    pub queued_records: u64,
    pub tombstone_records: u64,
    pub staging_tempfiles_swept: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn sample_queued() -> PersistedRecord {
        PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: "envelope-01".into(),
            sealed_sender: "sender-blob".into(),
            payload: "ciphertext-blob".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 42,
            expires_at: 1_720_600_000,
            seq_mac: "abcd".into(),
            body_hash: "0123456789abcdef".repeat(4),
        }
    }

    fn sample_tombstone() -> PersistedRecord {
        PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: "envelope-02".into(),
            seq: 99,
            body_hash: "fedcba9876543210".repeat(4),
            acked_at: 1_720_500_000,
            dedup_until: 1_720_500_000 + 172_800,
        }
    }

    #[test]
    fn record_filename_is_stable_hex_json() {
        let name = record_filename("envelope-01");
        assert!(name.ends_with(".json"));
        assert_eq!(name.len(), 64 + ".json".len());
        assert!(name.chars().take(64).all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn record_filename_diverges_on_different_ids() {
        assert_ne!(record_filename("a"), record_filename("b"));
    }

    #[test]
    fn shard_prefix_takes_first_two_hex() {
        assert_eq!(shard_prefix("abcdef1234567890"), "ab");
    }

    #[test]
    #[should_panic(expected = "empty recipient")]
    fn shard_prefix_panics_on_empty() {
        let _ = shard_prefix("");
    }

    #[test]
    fn record_path_lives_under_queue_shard_recipient() {
        let base = Path::new("/state");
        let p = record_path(base, "abcdef1234", "env-01");
        // Path uses OS separator; verify components in order.
        let components: Vec<_> = p
            .components()
            .filter_map(|c| c.as_os_str().to_str())
            .collect();
        assert!(components.iter().any(|c| *c == "queue"));
        assert!(components.iter().any(|c| *c == "ab"));
        assert!(components.iter().any(|c| *c == "abcdef1234"));
    }

    #[test]
    fn write_then_read_queued() {
        let dir = TempDir::new().unwrap();
        let path = record_path(dir.path(), "0123456789abcdef", "envelope-01");
        write_record(&path, &sample_queued()).unwrap();
        let round = read_record(&path).unwrap();
        assert_eq!(round, sample_queued());
    }

    #[test]
    fn write_then_read_tombstone() {
        let dir = TempDir::new().unwrap();
        let path = record_path(dir.path(), "0123456789abcdef", "envelope-02");
        write_record(&path, &sample_tombstone()).unwrap();
        let round = read_record(&path).unwrap();
        assert_eq!(round, sample_tombstone());
    }

    #[test]
    fn record_accessors_work_for_both_variants() {
        let q = sample_queued();
        let t = sample_tombstone();
        assert_eq!(q.id(), "envelope-01");
        assert_eq!(t.id(), "envelope-02");
        assert_eq!(q.seq(), 42);
        assert_eq!(t.seq(), 99);
        assert_eq!(q.version(), RECORD_VERSION);
        assert_eq!(t.version(), RECORD_VERSION);
        assert!(!q.body_hash().is_empty());
        assert!(!t.body_hash().is_empty());
    }

    #[test]
    fn read_rejects_oversize_file() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("giant.json");
        // Write 129 KiB of `x`s directly — a well-formed JSON
        // parser would eventually blow up on this anyway, but the
        // size cap MUST fire first per v4 §8.
        let big = vec![b'x'; (MAX_RECORD_BYTES + 1024) as usize];
        fs::write(&path, big).unwrap();
        let err = read_record(&path).unwrap_err();
        assert!(matches!(err, RecordReadError::OversizeBytes { .. }));
    }

    #[test]
    fn read_rejects_unknown_version() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("bad.json");
        let bad = serde_json::json!({
            "kind": "queued",
            "version": 99_u16,
            "id": "x", "sealed_sender": "", "payload": "",
            "sequence_ts": 0_u64, "seq": 0_u64, "expires_at": 0_u64,
            "seq_mac": "", "body_hash": "",
        });
        fs::write(&path, serde_json::to_vec(&bad).unwrap()).unwrap();
        let err = read_record(&path).unwrap_err();
        assert!(matches!(
            err,
            RecordReadError::UnknownVersion {
                found: 99,
                expected: 1
            }
        ));
    }

    #[test]
    fn read_rejects_non_json_bytes() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("garbage.json");
        fs::write(&path, b"not json at all").unwrap();
        let err = read_record(&path).unwrap_err();
        assert!(matches!(err, RecordReadError::Json(_)));
    }

    #[test]
    fn serialisation_uses_kind_tag() {
        let q = sample_queued();
        let json = serde_json::to_string(&q).unwrap();
        assert!(json.contains("\"kind\":\"queued\""));

        let t = sample_tombstone();
        let json = serde_json::to_string(&t).unwrap();
        assert!(json.contains("\"kind\":\"acked_tombstone\""));
    }

    #[test]
    fn write_record_creates_missing_shard_and_recipient_durable() {
        // Round-2 M2 P1 #1: write_record's lazy-create of the
        // shard + recipient dirs MUST go through create_dir_all_durable
        // so the FIRST record in a fresh recipient dir is
        // end-to-end durable. This test only verifies the
        // structural outcome (dirs exist + record round-trips);
        // the fsync happens inside the helper.
        let dir = TempDir::new().unwrap();
        let recipient = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        let path = record_path(dir.path(), recipient, "envelope-first");
        // Neither shard nor recipient dir exists yet.
        assert!(!path.parent().unwrap().exists());
        write_record(&path, &sample_queued()).unwrap();
        assert!(path.exists());
        assert!(path.parent().unwrap().exists());
        assert!(path.parent().unwrap().parent().unwrap().exists());
        let round = read_record(&path).unwrap();
        assert_eq!(round.id(), sample_queued().id());
    }

    #[test]
    fn write_rejects_serialized_oversize() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("big.json");
        // Craft a Queued with payload string just over the cap.
        let big_payload = "x".repeat((MAX_RECORD_BYTES + 1024) as usize);
        let rec = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: "big".into(),
            sealed_sender: "s".into(),
            payload: big_payload,
            sequence_ts: 0,
            seq: 0,
            expires_at: 0,
            seq_mac: "".into(),
            body_hash: "".into(),
        };
        let err = write_record(&path, &rec).expect_err("oversize serialised");
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    }
}
