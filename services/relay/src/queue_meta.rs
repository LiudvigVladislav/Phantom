// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — root queue-meta v2 with
//! phase state machine.
//!
//! Locked design:
//! - `mini-lock-draft-v4.md` §7 — first-install state machine
//!   (rows: {ABSENT, ABSENT} | {"initializing", ANY} | {"ready",
//!   queue/ ABSENT} | {"ready", queue/ PRESENT} | {ABSENT,
//!   queue/ PRESENT}).
//! - `v4.1-amendments.md` §5 L-N12 — meta-first phase transition
//!   ordering (INTENT → mkdir → DONE), each step atomic via
//!   tempfile+rename+parent-fsync.
//! - `v4.1-amendments.md` §3 V-M2 — root meta shape is 4 fields,
//!   the `initialized_at_ns_quantized_60s` timing field is
//!   removed.
//! - `v4.2-amendments.md` §1 B-1 — `.lock` is acquired BEFORE
//!   this module is invoked; caller (M4 boot loader) enforces
//!   that ordering. This module does NOT re-acquire the lock.
//! - Unknown `version` field → refuse-boot exit 3 (v4 §8).

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::atomic_write;

/// Filename inside `state_dir` housing the meta payload.
pub const META_FILENAME: &str = "queue-meta.v1";

/// Currently supported meta schema version. Reading a file with
/// a different value → refuse-boot exit 3.
pub const META_VERSION: u16 = 2;

/// Maximum permitted value of [`QueueMeta::boot_generation`]
/// (locked v4 §13 Q1 + PR-2 M3a round-1 review F2). Seq assembly
/// is `(u64::from(boot_generation) << 40) | counter`; the counter
/// consumes the low 40 bits, leaving 24 bits for the generation.
///
/// A generation `>= 2^24` shifted by 40 overflows the u64 space
/// and collides with a lower-generation namespace, silently
/// reusing seqs. The bump path in the boot loader must refuse to
/// advance past this ceiling with refuse-boot exit 4
/// (`EXIT_GENERATION_SATURATION`).
pub const MAX_BOOT_GENERATION: u32 = (1u32 << 24) - 1;

/// Phase field values (locked v4 §7 + v4.1 §5 L-N12).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    /// A previous boot crashed mid-init between the meta-first
    /// write and the queue/ subtree creation. The next boot
    /// completes the init.
    Initializing,
    /// Steady state: meta + queue/ subtree both present, boot
    /// generation bump + replay proceeds.
    Ready,
}

/// Persistent shape of `queue-meta.v1` (v4.1 §3 V-M2 — 4 fields).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueueMeta {
    /// Schema version. Reading a value != [`META_VERSION`] is a
    /// hard refuse-boot (v4 §8 unknown-schema-version).
    pub version: u16,
    /// Boot phase — governs the state machine at boot.
    pub phase: Phase,
    /// Monotonic generation counter. Locked design v4 §13 Q1:
    /// `seq = (boot_generation << 40) | counter`; saturation ≠ 0
    /// → refuse-boot exit 4. Bumped on every clean boot.
    pub boot_generation: u32,
    /// SHA-256 of the `RELAY_SEQ_MAC_KEY` bytes, first 16 hex
    /// chars (locked v4 §13 Q2). Mismatch with the running
    /// key's fingerprint + non-empty queue → refuse-boot exit 5.
    pub seq_mac_key_fingerprint: String,
}

/// Read outcome (locked v4 §7 first-install state machine rows).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MetaReadState {
    /// `queue-meta.v1` file does not exist. The queue/ subtree
    /// may exist independently — the caller (boot state machine)
    /// consults both to decide the row.
    Absent,
    /// File exists and parsed cleanly.
    Present(QueueMeta),
}

/// Error kinds surfaced by [`read_meta`].
///
/// A read that returned [`MetaReadState::Absent`] is NOT an
/// error — it is a legitimate boot input (fresh install row).
/// Every variant here maps to refuse-boot exit 3 (structural
/// error) at the boot-loader call site.
#[derive(Debug)]
pub enum MetaReadError {
    /// File exists but the underlying I/O failed (permission,
    /// symlink, etc.).
    Io(std::io::Error),
    /// File contents are not valid JSON.
    Json(serde_json::Error),
    /// `version` field disagrees with [`META_VERSION`]. Locked
    /// v4 §8 unknown-schema-version → refuse-boot exit 3.
    UnknownVersion { found: u16, expected: u16 },
    /// `seq_mac_key_fingerprint` field is not a valid 16-char
    /// lowercase hex string (a mangled meta file).
    MalformedFingerprint { raw: String },
}

impl std::fmt::Display for MetaReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MetaReadError::Io(e) => write!(f, "queue-meta.v1 io error: {e}"),
            MetaReadError::Json(e) => write!(f, "queue-meta.v1 json error: {e}"),
            MetaReadError::UnknownVersion { found, expected } => write!(
                f,
                "queue-meta.v1 unknown schema version {found}; expected {expected}"
            ),
            MetaReadError::MalformedFingerprint { raw } => write!(
                f,
                "queue-meta.v1 seq_mac_key_fingerprint {raw:?} is not 16 lowercase hex chars"
            ),
        }
    }
}

impl std::error::Error for MetaReadError {}

impl QueueMeta {
    /// Absolute path to the meta file inside `state_dir`.
    pub fn path_in(state_dir: &Path) -> PathBuf {
        state_dir.join(META_FILENAME)
    }
}

fn is_valid_fingerprint(s: &str) -> bool {
    s.len() == 16 && s.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

/// Read + validate the meta file. `None` on absence is NOT an
/// error (v4 §7 first-install row). Every other outcome is
/// bubbled as [`MetaReadError`] and the boot loader maps it to
/// exit 3.
pub fn read_meta(state_dir: &Path) -> Result<MetaReadState, MetaReadError> {
    let path = QueueMeta::path_in(state_dir);
    let raw = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Ok(MetaReadState::Absent);
        }
        Err(e) => return Err(MetaReadError::Io(e)),
    };
    let meta: QueueMeta = serde_json::from_slice(&raw).map_err(MetaReadError::Json)?;
    if meta.version != META_VERSION {
        return Err(MetaReadError::UnknownVersion {
            found: meta.version,
            expected: META_VERSION,
        });
    }
    if !is_valid_fingerprint(&meta.seq_mac_key_fingerprint) {
        return Err(MetaReadError::MalformedFingerprint {
            raw: meta.seq_mac_key_fingerprint,
        });
    }
    Ok(MetaReadState::Present(meta))
}

/// Write meta atomically (locked design v4.1 §5 L-N12 step 1
/// or step 3 depending on caller). Payload is serde_json
/// pretty-printed for operator legibility; the file is small.
pub fn write_meta(state_dir: &Path, meta: &QueueMeta) -> std::io::Result<()> {
    let path = QueueMeta::path_in(state_dir);
    let bytes = serde_json::to_vec_pretty(meta)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
    atomic_write::write_atomic(&path, &bytes)
}

/// Verdict of the boot-time state-machine classification (v4 §7).
///
/// The caller (boot loader) inspects both the meta read state and
/// whether `queue/` exists on disk, then dispatches to one of
/// these actions. This module ships the classifier as a pure fn
/// so it can be unit-tested independently of the actual state_dir.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BootAction {
    /// Row {ABSENT, ABSENT}: fresh install. Caller writes meta
    /// with phase=Initializing, mkdirs queue/, promotes to
    /// phase=Ready. All three atomic (L-N12).
    FreshInstall,
    /// Row {"initializing", ANY}: previous boot crashed mid-init.
    /// Caller ensures queue/ exists (idempotent mkdir), fsyncs,
    /// then promotes to phase=Ready.
    CompleteInit,
    /// Row {"ready", PRESENT}: normal boot. Caller bumps
    /// boot_generation, rewrites meta, proceeds to replay.
    NormalBoot(QueueMeta),
    /// Row {"ready", ABSENT}: subtree lost after commit — the
    /// meta claims Ready but queue/ is gone. Indistinguishable
    /// from an adversarial wipe from disk truth. Refuse-boot.
    RefuseSubtreeLost,
    /// Row {ABSENT, PRESENT}: meta missing while queue/ subtree
    /// present. Adversarial or partial-wipe. Refuse-boot.
    RefuseAdversarialPartialWipe,
}

/// Classify the boot state (locked v4 §7 five rows).
///
/// Pure function of `(meta_present, queue_subtree_present)`;
/// side-effect-free so the state machine is trivially unit-
/// testable. The boot loader is the only production caller.
pub fn classify_boot(meta: MetaReadState, queue_subtree_present: bool) -> BootAction {
    match (meta, queue_subtree_present) {
        (MetaReadState::Absent, false) => BootAction::FreshInstall,
        (MetaReadState::Absent, true) => BootAction::RefuseAdversarialPartialWipe,
        (MetaReadState::Present(m), _) if m.phase == Phase::Initializing => {
            BootAction::CompleteInit
        }
        (MetaReadState::Present(_), false) => BootAction::RefuseSubtreeLost,
        (MetaReadState::Present(m), true) => BootAction::NormalBoot(m),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn sample_meta() -> QueueMeta {
        QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: 42,
            seq_mac_key_fingerprint: "0123456789abcdef".to_string(),
        }
    }

    #[test]
    fn write_then_read_round_trip() {
        let dir = TempDir::new().unwrap();
        let meta = sample_meta();
        write_meta(dir.path(), &meta).unwrap();
        let state = read_meta(dir.path()).unwrap();
        assert_eq!(state, MetaReadState::Present(meta));
    }

    #[test]
    fn read_returns_absent_when_file_missing() {
        let dir = TempDir::new().unwrap();
        assert_eq!(read_meta(dir.path()).unwrap(), MetaReadState::Absent);
    }

    #[test]
    fn read_rejects_unknown_version() {
        let dir = TempDir::new().unwrap();
        // Hand-craft a payload with a future version tag.
        let bad = serde_json::json!({
            "version": 42,
            "phase": "ready",
            "boot_generation": 1,
            "seq_mac_key_fingerprint": "0123456789abcdef",
        });
        fs::write(
            QueueMeta::path_in(dir.path()),
            serde_json::to_vec(&bad).unwrap(),
        )
        .unwrap();
        let err = read_meta(dir.path()).unwrap_err();
        assert!(matches!(
            err,
            MetaReadError::UnknownVersion {
                found: 42,
                expected: 2
            }
        ));
    }

    #[test]
    fn read_rejects_malformed_fingerprint() {
        let dir = TempDir::new().unwrap();
        // Fingerprint must be exactly 16 lowercase hex chars.
        let bad = serde_json::json!({
            "version": META_VERSION,
            "phase": "ready",
            "boot_generation": 1,
            "seq_mac_key_fingerprint": "SHORT",
        });
        fs::write(
            QueueMeta::path_in(dir.path()),
            serde_json::to_vec(&bad).unwrap(),
        )
        .unwrap();
        let err = read_meta(dir.path()).unwrap_err();
        assert!(matches!(err, MetaReadError::MalformedFingerprint { .. }));
    }

    #[test]
    fn read_rejects_uppercase_fingerprint() {
        let dir = TempDir::new().unwrap();
        let bad = serde_json::json!({
            "version": META_VERSION,
            "phase": "ready",
            "boot_generation": 1,
            "seq_mac_key_fingerprint": "0123456789ABCDEF",
        });
        fs::write(
            QueueMeta::path_in(dir.path()),
            serde_json::to_vec(&bad).unwrap(),
        )
        .unwrap();
        let err = read_meta(dir.path()).unwrap_err();
        assert!(matches!(err, MetaReadError::MalformedFingerprint { .. }));
    }

    #[test]
    fn read_rejects_non_json_bytes() {
        let dir = TempDir::new().unwrap();
        fs::write(QueueMeta::path_in(dir.path()), b"not json").unwrap();
        let err = read_meta(dir.path()).unwrap_err();
        assert!(matches!(err, MetaReadError::Json(_)));
    }

    #[test]
    fn classify_row_fresh_install() {
        assert_eq!(
            classify_boot(MetaReadState::Absent, false),
            BootAction::FreshInstall
        );
    }

    #[test]
    fn classify_row_adversarial_partial_wipe() {
        assert_eq!(
            classify_boot(MetaReadState::Absent, true),
            BootAction::RefuseAdversarialPartialWipe
        );
    }

    #[test]
    fn classify_row_complete_init_from_initializing() {
        let mut m = sample_meta();
        m.phase = Phase::Initializing;
        assert_eq!(
            classify_boot(MetaReadState::Present(m.clone()), false),
            BootAction::CompleteInit
        );
        assert_eq!(
            classify_boot(MetaReadState::Present(m), true),
            BootAction::CompleteInit
        );
    }

    #[test]
    fn classify_row_refuse_subtree_lost() {
        assert_eq!(
            classify_boot(MetaReadState::Present(sample_meta()), false),
            BootAction::RefuseSubtreeLost
        );
    }

    #[test]
    fn classify_row_normal_boot() {
        let m = sample_meta();
        assert_eq!(
            classify_boot(MetaReadState::Present(m.clone()), true),
            BootAction::NormalBoot(m)
        );
    }

    #[test]
    fn phase_serde_uses_snake_case() {
        let m = sample_meta();
        let json = serde_json::to_string(&m).unwrap();
        assert!(json.contains("\"phase\":\"ready\""));

        let mut m2 = m.clone();
        m2.phase = Phase::Initializing;
        let json2 = serde_json::to_string(&m2).unwrap();
        assert!(json2.contains("\"phase\":\"initializing\""));
    }
}
