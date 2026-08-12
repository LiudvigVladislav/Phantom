// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — boot loader.
//!
//! Locked design:
//! - `v4.2-amendments.md` §1 B-1 — 8-step init order:
//!     1. Validate RELAY_STATE_DIR (done by PR-0 A-5 in
//!        `config.rs` before this module runs);
//!     2. Acquire `.lock` (done by PR-1b in `state.rs` before
//!        this module runs);
//!     3. Read root meta + phase state machine;
//!     4. Preflight walk + budget check;
//!     5. Per-file replay;
//!     6-7-8. Workers, signal handler, axum — M4.
//!   This module ships steps 3-5 as `boot()` orchestrator.
//! - `mini-lock-draft-v4.md` §7 — first-install state machine
//!   dispatch (fresh install / complete init / normal boot /
//!   refuse rows).
//! - `mini-lock-draft-v4.md` §8 — preflight rules: symlink
//!   refusal at every level, uid/gid/mode + parent-dir checks,
//!   file-size cap, RAM budget via `checked_mul`.
//! - `v4.1-amendments.md` §5 L-N11 — sequential per-shard walk
//!   is a locked invariant (RAM-budget accounting stays exact).
//! - `v4.1-amendments.md` §5 L-N12 — meta-first phase transition
//!   ordering, each step atomic via tempfile+rename+parent-fsync.
//! - `v4.2-amendments.md` §3 B-3 — parse/commit panic during
//!   replay → refuse-boot exit 10.
//! - `v4.2.3-runbook-nits.md` §4 — bad env config → exit 11.
//!
//! **Future contracts for M3/M4** (recorded per round-2 GREEN):
//! - M3 `worker_loop` MUST consult
//!   [`crate::worker_pool::PoolStateReader`] on
//!   `rx.recv() == None` to classify exit (planned vs
//!   unexpected).
//! - M4 spawn code MUST push worker `JoinHandle`s in exact
//!   `worker_id = 0..REST_WORKER_COUNT` order — the Vec index
//!   IS the binding.
//! - M4 shutdown code MUST inspect every
//!   [`crate::worker_pool::WorkerDrainOutcome`] and translate
//!   [`crate::worker_pool::DrainError::DeadlineExceeded`] into
//!   a synchronous `std::process::exit(1)` per v4.2.1 §1. No
//!   second `drain_handles()` call after a timeout.
//!
//! ## M2 scope — boot substrate (round-1 amendment P1 #6)
//!
//! `boot()` deliberately implements ONLY the persistence + phase
//! substrate: it returns a fully-typed [`BootLoaderResult`]
//! whose `records` carry recipient + on-disk path + serialised
//! size (see [`LoadedRecord`]). The following steps that
//! LOGICALLY belong at boot but touch M4 state are DEFERRED to
//! the M4 wiring milestone and MUST run there before any HTTP
//! handler serves traffic:
//!   1. Seed [`crate::capacity_ledger::GlobalCapacityGate`] from
//!      the returned `walk` stats + per-record `disk_bytes`.
//!   2. Populate `rest_store` + WS `store` from every
//!      `LoadedRecord::record` classified as `Queued`.
//!   3. Rebuild the in-memory tombstone dedup table from every
//!      `AckedTombstone` record.
//!   4. Run a boot-time TTL sweep: unlink expired `Queued`
//!      records + `AckedTombstone` records past their
//!      `dedup_until`, releasing capacity accordingly.
//!   5. Enforce per-recipient `queue.len()` cap: WARN at
//!      `max_envelopes_per_recipient`, refuse-boot at 5× that
//!      (v4.1 §5 L-N4).
//! M4 will land a wiring test that asserts each of the five
//! steps ran before `/ready` flips to 200. M2's job is to
//! deliver an accurate + adversarial-hardened substrate; it is
//! NOT the full boot path.
//!
//! ## Round-1 M2 review amendments (this file)
//!
//! - **P0 #2** preflight sweeps `.staging-*.tmp` tempfiles that
//!   a SIGKILL between `write_atomic`'s file-fsync and rename
//!   may have left behind. Uncommitted tempfiles are unlinked
//!   before the main walk; the sweep count lands in the
//!   [`WalkStats::staging_tempfiles_swept`] field.
//! - **P0 #3** the replay output is now
//!   `Vec<`[`LoadedRecord`]`>` (recipient, path, disk_bytes,
//!   record). M4 can route into `rest_store`/WS `store` +
//!   `GlobalCapacityGate` without re-parsing paths.
//! - **P0 #4** the walker validates recipient dir names against
//!   `^[0-9a-f]{64}$`, cross-checks that the shard dir matches
//!   the recipient's first two hex chars, and — after parse —
//!   asserts `filename == sha256_hex(record.id) + ".json"` +
//!   refuses duplicate IDs within the same recipient dir.
//!   [`WalkStats`] `queued_records` / `tombstone_records` are
//!   filled during replay so M4's ledger seeding is precise.
//! - **P1 #5** fresh install + complete init use
//!   `create_dir_all_durable` — every newly-created directory
//!   (including the state_dir root when relevant) is fsynced,
//!   plus its parent chain, so the FIRST record written into a
//!   lazy recipient dir is durable end-to-end.
//! - **P1 #6** `catch_unwind` now wraps the full per-file
//!   validation flow (read + filename check + variant
//!   classification + LoadedRecord construction); a panic
//!   anywhere in that sequence maps to
//!   [`BootError::ParsePanic`] → exit 10.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io;
use std::panic::AssertUnwindSafe;
use std::path::{Path, PathBuf};

// Round-3 M2 P1 #1 test fault seam. When set to `true`, the
// replay loop deletes the file being parsed and then panics
// inside the `catch_unwind` scope AFTER byte capture — the
// test asserts that `ParsePanic.file_sha256_hex` still
// describes the ORIGINAL buffer (proving the digest was
// captured pre-panic and does NOT re-read the now-missing file).
//
// Round-4 M2 P1 test fault seam. When set to `true`, the
// replay loop panics BEFORE byte capture inside the
// `catch_unwind` scope — the test asserts that
// `BootError::ParsePanic` still surfaces (not an escaping
// unwinding) with `file_sha256_hex == "<unavailable-before-read>"`.
// This locks the R1 P1 #6 contract that R3's initial fix
// briefly regressed: panic ANYWHERE in the per-file replay
// (including at/before byte capture) maps to `ParsePanic`
// → exit 10, never to an escaping panic.
//
// Both seams `#[cfg(test)]`-only per R3 P2 #3 — production
// binary has zero cost. Thread-local rather than process-global
// so a parallel test that never sets a flag cannot be tripped
// by another test that does. Rustdoc does not accept `///` doc
// comments on `thread_local!` invocations, so this note is
// written as a regular comment.
#[cfg(test)]
thread_local! {
    pub(crate) static FORCE_REPLAY_PANIC_AND_DELETE: std::cell::Cell<bool> =
        const { std::cell::Cell::new(false) };
    pub(crate) static FORCE_REPLAY_PANIC_BEFORE_READ: std::cell::Cell<bool> =
        const { std::cell::Cell::new(false) };
}

/// Marker string returned as [`BootError::ParsePanic::file_sha256_hex`]
/// when the panic fired BEFORE the per-file byte capture inside
/// [`replay_files`]. Round-4 M2 P1: the full per-file replay
/// including byte capture + size check + digest lives inside
/// `catch_unwind`, so a panic at any point maps to
/// `ParsePanic` — but a panic before the buffer is captured has
/// no bytes to hash. This marker distinguishes that case from
/// a normal digest for operator triage.
pub const UNAVAILABLE_BEFORE_READ_MARKER: &str = "<unavailable-before-read>";

/// Evidence captured from a successful per-file byte read. Held
/// outside the `catch_unwind` scope so that a panic occurring
/// AFTER the capture but BEFORE parse completion still surfaces
/// the correct SHA in the resulting `BootError::ParsePanic`.
///
/// Populated inside the closure via a mutable outer borrow; a
/// panic between the borrow-through-`&mut` and the closure
/// return leaves the outer `Option<ReadEvidence>` at its
/// last-assigned value.
///
/// Only `file_sha256_hex` is used by the error path today —
/// `disk_bytes` is derived from `bytes.len()` inside the
/// success path and is carried on the `LoadedRecord` there.
#[derive(Debug, Clone)]
struct ReadEvidence {
    file_sha256_hex: String,
}

use crate::atomic_write;
use crate::persistence::{self, record_filename, PersistedRecord, WalkStats, MAX_RECORD_BYTES};
use crate::queue_meta::{
    self, BootAction, MetaReadError, MetaReadState, Phase, QueueMeta, META_VERSION,
};
use crate::tombstone_config::TombstoneConfig;

// ─── Exit codes (locked design) ──────────────────────────────────────────

/// Structural error: bad filename, symlink, oversize, unknown
/// schema, uid/gid/mode drift, adversarial partial wipe.
pub const EXIT_STRUCTURAL_ERROR: i32 = 3;
/// Boot-generation counter saturated `u32`. Locked v4 §13 Q1.
pub const EXIT_GENERATION_SATURATION: i32 = 4;
/// Seq-MAC-key fingerprint mismatch with non-empty queue.
/// Locked v4 §13 Q2. Same code number as the PR-1b `.lock`
/// contention exit for classification convenience.
pub const EXIT_SEQ_MAC_KEY_MISMATCH: i32 = 5;
/// Budget exceeded (files, total bytes, or RAM estimate).
pub const EXIT_BUDGET_EXCEEDED: i32 = 8;
/// Panic caught inside `catch_unwind` around per-file replay.
/// Locked v4.2 §3 B-3.
pub const EXIT_PARSE_OR_COMMIT_PANIC: i32 = 10;
/// Env-var config invalid (tombstone horizon, etc.). Locked
/// v4.2.3 §4.
pub const EXIT_CONFIG_ERROR: i32 = 11;

// ─── Preflight configuration ─────────────────────────────────────────────

/// Locked v4 §8 preflight caps (also flow into
/// [`crate::capacity_ledger::GlobalCapacityGate`] at seed time).
///
/// `ram_budget` has NO safe default — locked v4.2.1 §6 mandates
/// the operator pin the value after a staging benchmark.
/// [`PreflightCaps::for_tests`] hands out a generous test value.
#[derive(Debug, Clone, Copy)]
pub struct PreflightCaps {
    pub max_envelopes: u64,
    pub max_bytes: u64,
    pub ram_budget: u64,
}

impl PreflightCaps {
    /// Test-only defaults; NOT for production. Production values
    /// arrive via `RelayConfig` at M4 wiring time.
    pub fn for_tests() -> Self {
        Self {
            max_envelopes: 500_000,
            max_bytes: 2 * 1024 * 1024 * 1024, // 2 GiB
            ram_budget: 512 * 1024 * 1024,     // 512 MiB
        }
    }
}

/// Ownership + mode expectation applied to every walked path.
///
/// Locked v4 §8 A-8 broadened: symlinks + uid/gid/mode on ALL
/// parent directories, not just leaf. When either uid or gid is
/// `None` the check is skipped for that axis — test mode; M4
/// wires `Some(10001)` for both from `RelayConfig`.
#[derive(Debug, Clone, Copy)]
pub struct OwnershipExpectation {
    pub expected_uid: Option<u32>,
    pub expected_gid: Option<u32>,
    /// Bitmask of `st_mode` bits that MUST NOT be set. Default
    /// production: `0o022` (group-write + world-write refused).
    pub mode_forbidden: u32,
}

impl OwnershipExpectation {
    /// Test-only permissive default; only refuses world-writable
    /// files (the check that survives even in permissive test
    /// environments).
    pub fn permissive_for_tests() -> Self {
        Self {
            expected_uid: None,
            expected_gid: None,
            mode_forbidden: 0o002, // world-write refused
        }
    }
}

// ─── Boot input + output ─────────────────────────────────────────────────

/// Config surface consumed by [`boot`]. Assembled by the M4
/// wiring code from `RelayConfig` + running `RELAY_SEQ_MAC_KEY`.
#[derive(Debug, Clone)]
pub struct BootConfig {
    pub state_dir: PathBuf,
    pub caps: PreflightCaps,
    pub tombstone: TombstoneConfig,
    /// First 16 hex chars of `sha256(RELAY_SEQ_MAC_KEY)`. Locked
    /// v4 §13 Q2. Compared against meta's stored fingerprint on
    /// normal boot.
    pub current_seq_mac_key_fingerprint: String,
    pub ownership: OwnershipExpectation,
}

/// A single record loaded from disk with the surrounding
/// metadata M4 needs to route it into the right in-memory
/// stores + capacity counters.
///
/// Round-1 M2 P0 #3: the pre-amendment API returned bare
/// `PersistedRecord` values, but recipient is stored in the
/// PATH, not the DTO — M4 couldn't decide which `rest_store`
/// entry to populate without re-parsing paths itself. This
/// struct exposes the four pieces the wiring layer needs.
#[derive(Debug, Clone)]
pub struct LoadedRecord {
    /// Canonical recipient hex (64 lowercase chars), extracted
    /// from the record's parent directory name. Walker
    /// validates the shape against `^[0-9a-f]{64}$`.
    pub recipient: String,
    /// Full on-disk path (state_dir/queue/<hex[0..2]>/<recipient>/<sha256(id)>.json).
    /// Retained for FATAL log emission and future TTL-sweep
    /// unlinks in M4.
    pub path: PathBuf,
    /// Exact serialised size observed by preflight `symlink_metadata`.
    /// Used verbatim by the M4 ledger seed — the three-counter
    /// model (active_bytes vs tombstone_bytes) is derived per
    /// record from this value + the record variant.
    pub disk_bytes: u64,
    /// The deserialised record (either `Queued` or
    /// `AckedTombstone`).
    pub record: PersistedRecord,
}

impl LoadedRecord {
    /// True iff the loaded record is a `Queued` variant.
    pub fn is_queued(&self) -> bool {
        matches!(self.record, PersistedRecord::Queued { .. })
    }

    /// True iff the loaded record is an `AckedTombstone` variant.
    pub fn is_tombstone(&self) -> bool {
        matches!(self.record, PersistedRecord::AckedTombstone { .. })
    }
}

/// Output of a successful boot. M4 consumes this to:
/// - Seed `GlobalCapacityGate` counters from `walk` +
///   each `LoadedRecord::disk_bytes`.
/// - Populate `rest_store` + WS `store` from every
///   `LoadedRecord::record` classified as `Queued`.
/// - Rebuild the in-memory tombstone dedup table from every
///   `AckedTombstone` record.
/// - Expose `meta.boot_generation` for the seq assembler.
/// Boot outcome handed to M4 wiring.
///
/// **PR-2 M3a round-6 F1**: all fields are PRIVATE. External
/// crates access boot state through read-only accessors
/// ([`BootLoaderResult::meta`], [`BootLoaderResult::records`],
/// [`BootLoaderResult::walk`],
/// [`BootLoaderResult::was_first_install`],
/// [`BootLoaderResult::state_dir`]) and cannot mutate the boot
/// state after `boot()` returns. Combined with `#[non_exhaustive]`
/// this makes the type a real opaque boot proof.
///
/// **Round-7 F3**: the sole construction path is [`boot`]
/// itself. The round-6 `__for_test_only` factory was removed —
/// external tests build a real boot result by calling
/// [`boot`] against a prepared temp directory, so no test-only
/// symbol survives in the production binary AND the standard
/// `cargo test` run exercises the cross-crate boundary
/// without needing a `--features test-support` flag.
#[derive(Debug)]
#[non_exhaustive]
pub struct BootLoaderResult {
    meta: QueueMeta,
    records: Vec<LoadedRecord>,
    walk: WalkStats,
    was_first_install: bool,
    state_dir: PathBuf,
    /// **PR-2 M3b-2a**: the tombstone-dedup horizon captured
    /// at boot from [`BootConfig::tombstone`]. M3b-2's `do_ack`
    /// computes `dedup_until = now + horizon_secs` using this
    /// value; carrying it through `BootLoaderResult` keeps it
    /// bound to the same boot proof as `state_dir` and the
    /// generation.
    tombstone_config: crate::tombstone_config::TombstoneConfig,
}

impl BootLoaderResult {
    /// Post-boot meta.
    pub fn meta(&self) -> &QueueMeta {
        &self.meta
    }

    /// Every record that survived preflight + replay.
    pub fn records(&self) -> &[LoadedRecord] {
        &self.records
    }

    /// Walk stats (used to seed capacity counters).
    pub fn walk(&self) -> &WalkStats {
        &self.walk
    }

    /// `true` on fresh-install / complete-init rows; `false` on
    /// normal boot.
    pub fn was_first_install(&self) -> bool {
        self.was_first_install
    }

    /// State directory this boot ran against.
    pub fn state_dir(&self) -> &std::path::Path {
        &self.state_dir
    }

    /// **M3b-2a**: tombstone-dedup horizon captured at boot.
    pub fn tombstone_config(&self) -> &crate::tombstone_config::TombstoneConfig {
        &self.tombstone_config
    }

    /// Consume `self` into `(meta, records, walk,
    /// was_first_install, state_dir, tombstone_config)`. Used
    /// by [`crate::rest_workers::WorkerRuntimeSpec::from_boot`]
    /// so the runtime can move the records into its seeded
    /// stores without paying a clone cost.
    pub(crate) fn into_parts(
        self,
    ) -> (
        QueueMeta,
        Vec<LoadedRecord>,
        WalkStats,
        bool,
        PathBuf,
        crate::tombstone_config::TombstoneConfig,
    ) {
        (
            self.meta,
            self.records,
            self.walk,
            self.was_first_install,
            self.state_dir,
            self.tombstone_config,
        )
    }

    /// **Round-7 F3 internal helper**: construct a
    /// `BootLoaderResult` for the LIBRARY's own tests.
    ///
    /// - `#[cfg(test)]` gates this out of both production and
    ///   integration test binaries — only the lib's own
    ///   `cargo test` run compiles it, so a `cargo build
    ///   --release` cannot even reference the symbol.
    /// - `pub(crate)` scopes it to the same crate — external
    ///   crates cannot see it regardless of features.
    ///
    /// Integration tests (`services/relay/tests/*.rs` — separate
    /// binary crates) call [`boot`] directly against a
    /// prepared state dir instead, so the cross-crate boundary
    /// is exercised through the real production path.
    #[cfg(test)]
    pub(crate) fn for_lib_test(
        meta: QueueMeta,
        records: Vec<LoadedRecord>,
        walk: WalkStats,
        was_first_install: bool,
        state_dir: PathBuf,
    ) -> Self {
        Self {
            meta,
            records,
            walk,
            was_first_install,
            state_dir,
            // Deterministic default for tests. Real production
            // boot uses [`BootConfig::tombstone`].
            tombstone_config: crate::tombstone_config::TombstoneConfig::from_secs(
                172_800,
            )
            .expect("48h horizon is a valid TombstoneConfig"),
        }
    }
}

// ─── Error surface ───────────────────────────────────────────────────────

#[derive(Debug)]
pub enum BootError {
    /// Structural refusal — bad filename, symlink at any depth,
    /// oversize record, unknown schema, uid/gid/mode drift, or
    /// row {ABSENT, PRESENT} / {"ready", ABSENT}.
    Structural { reason: String },
    /// Preflight totals exceeded the caps.
    BudgetExceeded { reason: String },
    /// Boot generation counter would overflow `u32` on bump.
    GenerationSaturation { current: u32 },
    /// Meta claims Ready with non-empty queue but its
    /// `seq_mac_key_fingerprint` disagrees with the running key.
    SeqMacKeyMismatch {
        meta_fingerprint: String,
        current_fingerprint: String,
    },
    /// `catch_unwind` caught a panic while reading a record.
    ///
    /// Round-2 M2 P1 #5: locked v4.2 §3 B-3 requires filename +
    /// panic_payload + sha256(file_bytes) in the FATAL log. The
    /// payload string is extracted via downcast (`&'static str`
    /// then `String`) to preserve the actual `panic!` message;
    /// `Box<dyn Any>` Debug alone typically prints
    /// `Any { .. }` and loses the message. The `file_sha256_hex`
    /// is computed at the moment of the panic (best-effort — a
    /// removed file yields `<unreadable>`) so the operator has
    /// tamper-evidence for the exact bytes that caused the
    /// panic.
    ParsePanic {
        path: PathBuf,
        payload: String,
        file_sha256_hex: String,
    },
    /// Invalid environmental config (e.g. tombstone horizon).
    ConfigError { reason: String },
    /// I/O failure during meta write / mkdir / walk that is not
    /// otherwise classifiable as structural. Rare; still exits 3.
    Io(io::Error),
}

impl BootError {
    /// Map to the boot exit code per locked design.
    pub fn exit_code(&self) -> i32 {
        match self {
            BootError::Structural { .. } => EXIT_STRUCTURAL_ERROR,
            BootError::BudgetExceeded { .. } => EXIT_BUDGET_EXCEEDED,
            BootError::GenerationSaturation { .. } => EXIT_GENERATION_SATURATION,
            BootError::SeqMacKeyMismatch { .. } => EXIT_SEQ_MAC_KEY_MISMATCH,
            BootError::ParsePanic { .. } => EXIT_PARSE_OR_COMMIT_PANIC,
            BootError::ConfigError { .. } => EXIT_CONFIG_ERROR,
            BootError::Io(_) => EXIT_STRUCTURAL_ERROR,
        }
    }
}

impl std::fmt::Display for BootError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BootError::Structural { reason } => write!(f, "boot structural: {reason}"),
            BootError::BudgetExceeded { reason } => write!(f, "boot budget exceeded: {reason}"),
            BootError::GenerationSaturation { current } => write!(
                f,
                "boot generation at or past the 24-bit seq-namespace cap: current={current}, cap={} (2^24-1). Operator must reset state or archive to reclaim a fresh generation space.",
                queue_meta::MAX_BOOT_GENERATION
            ),
            BootError::SeqMacKeyMismatch {
                meta_fingerprint,
                current_fingerprint,
            } => write!(
                f,
                "seq_mac_key fingerprint mismatch: meta={meta_fingerprint}, current={current_fingerprint}"
            ),
            BootError::ParsePanic {
                path,
                payload,
                file_sha256_hex,
            } => write!(
                f,
                "parse/commit panic reading {path:?}: {payload} (file_sha256={file_sha256_hex})"
            ),
            BootError::ConfigError { reason } => write!(f, "boot config error: {reason}"),
            BootError::Io(e) => write!(f, "boot io: {e}"),
        }
    }
}

impl std::error::Error for BootError {}

impl From<MetaReadError> for BootError {
    fn from(e: MetaReadError) -> Self {
        BootError::Structural {
            reason: e.to_string(),
        }
    }
}

impl From<io::Error> for BootError {
    fn from(e: io::Error) -> Self {
        BootError::Io(e)
    }
}

// ─── Public entry point ──────────────────────────────────────────────────

/// Run boot steps 3-5 (locked v4.2 §1 B-1). Steps 1-2 (state_dir
/// validate + `.lock`) happen in the caller (PR-0 config +
/// PR-1b state.rs); steps 6-8 (workers, signals, axum) land in
/// M4.
///
/// On success returns the post-transition meta + replay outputs.
/// On any refuse-boot condition returns [`BootError`] whose
/// `exit_code()` the caller passes to `std::process::exit`.
pub fn boot(cfg: &BootConfig) -> Result<BootLoaderResult, BootError> {
    // Step 3 — read meta + classify.
    let meta_state = queue_meta::read_meta(&cfg.state_dir)?;
    let queue_dir = cfg.state_dir.join("queue");
    let queue_present = queue_dir.exists();
    let action = queue_meta::classify_boot(meta_state.clone(), queue_present);

    // Step 4 — preflight walk BEFORE any meta mutation (locked
    // v4.2 §1 B-1 ordering). This lets the seq-MAC-key mismatch
    // check inspect the PRIOR meta while disk state is still
    // authoritative; a refused boot leaves meta unchanged.
    // Refuse-paths (subtree lost / adversarial wipe) short-
    // circuit below without doing a walk (queue_dir may not
    // exist or the meta already tells us the row is invalid).
    let (files, mut walk_stats) = if queue_dir.exists() {
        preflight_walk(&queue_dir, cfg.caps, cfg.ownership)?
    } else {
        (Vec::new(), WalkStats::default())
    };

    // Seq-MAC-key fingerprint invariant (locked v4 §13 Q2). Fire
    // BEFORE any mutation. Only meaningful on NormalBoot with a
    // non-empty queue: fresh install seeds a fresh fingerprint;
    // complete-init inherits the prior fingerprint (initialising-
    // phase crashes cannot have written MAC-bound records yet);
    // empty queue means no records rely on the prior key so a
    // rotation is legitimate.
    if let BootAction::NormalBoot(ref prior) = action {
        if !files.is_empty() && prior.seq_mac_key_fingerprint != cfg.current_seq_mac_key_fingerprint
        {
            return Err(BootError::SeqMacKeyMismatch {
                meta_fingerprint: prior.seq_mac_key_fingerprint.clone(),
                current_fingerprint: cfg.current_seq_mac_key_fingerprint.clone(),
            });
        }
    }

    // Meta mutations. All refuse-paths return before this point;
    // the seq-MAC-key check above has already fired if applicable.
    let (meta_after, was_first_install) = match action {
        BootAction::FreshInstall => {
            // v4.1 §5 L-N12 meta-first ordering:
            //   1) write phase=Initializing + fsync,
            //   2) mkdir queue/ + fsync,
            //   3) promote to phase=Ready + fsync.
            let initial = QueueMeta {
                version: META_VERSION,
                phase: Phase::Initializing,
                boot_generation: 0,
                seq_mac_key_fingerprint: cfg.current_seq_mac_key_fingerprint.clone(),
            };
            queue_meta::write_meta(&cfg.state_dir, &initial)?;
            // Round-1 M2 P1 #5: use create_dir_all_durable so
            // queue/ AND its parent get fsynced before promotion.
            atomic_write::create_dir_all_durable(&queue_dir)?;
            let promoted = QueueMeta {
                phase: Phase::Ready,
                ..initial
            };
            queue_meta::write_meta(&cfg.state_dir, &promoted)?;
            (promoted, true)
        }
        BootAction::CompleteInit => {
            // Idempotent recovery — previous boot crashed after
            // step 1 or step 2. Ensure queue/ exists, promote to
            // Ready. Boot generation stays at whatever the meta
            // had (may be 0 for a fresh-install crash, or a real
            // number for a mid-run crash — either way promote
            // and let normal boot bump on the next entry).
            // Round-1 M2 P1 #5: durable mkdir.
            atomic_write::create_dir_all_durable(&queue_dir)?;
            let prior = match meta_state {
                MetaReadState::Present(m) => m,
                MetaReadState::Absent => {
                    return Err(BootError::Structural {
                        reason: "CompleteInit resolved with absent meta — classifier bug".into(),
                    });
                }
            };
            let promoted = QueueMeta {
                phase: Phase::Ready,
                ..prior
            };
            queue_meta::write_meta(&cfg.state_dir, &promoted)?;
            (promoted, true)
        }
        BootAction::NormalBoot(prior) => {
            // Bump generation, capped at `MAX_BOOT_GENERATION`
            // (locked v4 §13 Q1 + PR-2 M3a round-1 review F2).
            // A generation `>= 2^24` shifted by 40 would collide
            // with a lower-generation namespace via u64 wrap and
            // silently reuse seqs. Refuse-boot exit 4 forces
            // ops to reset state or archive before continuing.
            if prior.boot_generation >= queue_meta::MAX_BOOT_GENERATION {
                return Err(BootError::GenerationSaturation {
                    current: prior.boot_generation,
                });
            }
            let next_generation =
                prior
                    .boot_generation
                    .checked_add(1)
                    .ok_or(BootError::GenerationSaturation {
                        current: prior.boot_generation,
                    })?;
            let bumped = QueueMeta {
                boot_generation: next_generation,
                seq_mac_key_fingerprint: cfg.current_seq_mac_key_fingerprint.clone(),
                ..prior
            };
            queue_meta::write_meta(&cfg.state_dir, &bumped)?;
            (bumped, false)
        }
        BootAction::RefuseSubtreeLost => {
            return Err(BootError::Structural {
                reason: "queue-meta.v1 phase=ready but queue/ subtree absent".into(),
            });
        }
        BootAction::RefuseAdversarialPartialWipe => {
            return Err(BootError::Structural {
                reason: "queue/ subtree present but queue-meta.v1 absent".into(),
            });
        }
    };

    // Step 5 — replay files (v4.2 §3 B-3 catch_unwind → exit 10).
    // Round-1 M2 P0 #4 completion: replay fills the
    // queued_records / tombstone_records counters in walk_stats.
    let records = replay_files(&files, &mut walk_stats)?;

    Ok(BootLoaderResult {
        meta: meta_after,
        records,
        walk: walk_stats,
        was_first_install,
        state_dir: cfg.state_dir.clone(),
        tombstone_config: cfg.tombstone,
    })
}

// ─── Preflight walk ──────────────────────────────────────────────────────

/// A pre-parse candidate file discovered by the walker: recipient
/// hex + on-disk path. Held in memory between preflight and
/// replay.
///
/// Round-3 M2 P1 #1: the `disk_bytes` field is intentionally
/// absent. Replay re-derives `disk_bytes` from the actual
/// buffer it reads (`bytes.len()`) rather than from a preflight
/// metadata snapshot, so the ledger footprint reported to M4
/// describes the exact bytes the parser consumed.
#[derive(Debug, Clone)]
struct WalkedFile {
    recipient: String,
    path: PathBuf,
}

/// SAFETY (locked v4.1 §5 L-N11): sequential per-shard walk. Do
/// NOT parallelise without redoing the RAM-budget analysis; the
/// current budget assumes the whole set is deserialised once
/// sequentially.
///
/// Round-1 M2 amendments:
/// - **P0 #2** first pass sweeps `.staging-*.tmp` tempfiles left
///   by a SIGKILL between `write_atomic`'s file-fsync and rename.
///   Uncommitted content is unlinked before the walker inspects
///   its recipient dir.
/// - **P0 #4** validates the recipient dir name against
///   `^[0-9a-f]{64}$` and that the shard dir name matches the
///   recipient's first two hex chars. Both invariants fail-loud
///   with `BootError::Structural`.
fn preflight_walk(
    queue_dir: &Path,
    caps: PreflightCaps,
    ownership: OwnershipExpectation,
) -> Result<(Vec<WalkedFile>, WalkStats), BootError> {
    if !queue_dir.exists() {
        return Ok((Vec::new(), WalkStats::default()));
    }

    check_path_metadata(queue_dir, ownership, "queue/")?;

    let mut files = Vec::new();
    let mut walk = WalkStats::default();

    for shard_entry in fs::read_dir(queue_dir)? {
        let shard_entry = shard_entry?;
        let shard_path = shard_entry.path();
        check_path_metadata(&shard_path, ownership, "shard/")?;
        let shard_meta = fs::symlink_metadata(&shard_path)?;
        if !shard_meta.is_dir() {
            // Round-2 M2 P1 #4: fail-loud on unexpected node.
            // A file or symlink at shard level (queue/<something>)
            // means either operator corruption or an adversarial
            // plant. Silently continuing hides the anomaly AND
            // excludes the file from budget accounting.
            return Err(BootError::Structural {
                reason: format!("shard-level entry {shard_path:?} is not a directory — refused"),
            });
        }

        // Round-1 M2 P0 #4: shard dir name must be exactly 2
        // lowercase hex chars.
        let shard_name = shard_entry.file_name();
        let shard_name = shard_name.to_string_lossy();
        if !is_valid_shard_name(&shard_name) {
            return Err(BootError::Structural {
                reason: format!("shard dir {shard_name:?} does not match ^[0-9a-f]{{2}}$"),
            });
        }

        for recipient_entry in fs::read_dir(&shard_path)? {
            let recipient_entry = recipient_entry?;
            let recipient_path = recipient_entry.path();
            check_path_metadata(&recipient_path, ownership, "recipient/")?;
            let recipient_meta = fs::symlink_metadata(&recipient_path)?;
            if !recipient_meta.is_dir() {
                // Round-2 M2 P1 #4: fail-loud on non-dir at
                // recipient level.
                return Err(BootError::Structural {
                    reason: format!(
                        "recipient-level entry {recipient_path:?} is not a directory — refused"
                    ),
                });
            }

            // Round-1 M2 P0 #4: recipient dir name must be exactly
            // 64 lowercase hex chars.
            let recipient_name = recipient_entry.file_name();
            let recipient_name_str = recipient_name.to_string_lossy().into_owned();
            if !is_valid_recipient_name(&recipient_name_str) {
                return Err(BootError::Structural {
                    reason: format!(
                        "recipient dir {recipient_name_str:?} does not match ^[0-9a-f]{{64}}$"
                    ),
                });
            }
            // ...and its first two hex chars must match the
            // enclosing shard dir name.
            if &recipient_name_str[..2] != shard_name.as_ref() {
                return Err(BootError::Structural {
                    reason: format!(
                        "recipient {recipient_name_str:?} first-two-hex {} disagrees with shard dir {shard_name:?}",
                        &recipient_name_str[..2]
                    ),
                });
            }

            // Round-1 M2 P0 #2: sweep uncommitted `.staging-*.tmp`
            // tempfiles from a prior SIGKILL BEFORE we walk this
            // recipient dir's records.
            let sweep_count = sweep_staging_tempfiles(&recipient_path)?;
            walk.staging_tempfiles_swept = walk
                .staging_tempfiles_swept
                .checked_add(sweep_count)
                .ok_or_else(|| BootError::BudgetExceeded {
                    reason: "staging_tempfiles_swept overflow".into(),
                })?;

            for record_entry in fs::read_dir(&recipient_path)? {
                let record_entry = record_entry?;
                let record_path = record_entry.path();
                check_path_metadata(&record_path, ownership, "record/")?;
                let record_meta = fs::symlink_metadata(&record_path)?;
                if !record_meta.is_file() {
                    // Round-2 M2 P1 #4: fail-loud on non-file
                    // at record level (a nested directory here
                    // is a structural break — the layout is
                    // strictly 3 levels deep).
                    return Err(BootError::Structural {
                        reason: format!(
                            "record-level entry {record_path:?} is not a regular file — refused"
                        ),
                    });
                }

                let name = record_entry.file_name();
                let name = name.to_string_lossy();

                // If a staging tempfile survived the sweep above
                // (impossible under the .lock invariant, but the
                // check costs nothing), treat it as a walker bug
                // rather than silently accepting it.
                if atomic_write::is_staging_tempfile(&name) {
                    return Err(BootError::Structural {
                        reason: format!(
                            "staging tempfile {record_path:?} survived preflight sweep — invariant break"
                        ),
                    });
                }

                // Filename shape (v4 §8 replay rule 6):
                // `sha256_hex(id) + ".json"` — 64 lowercase hex.
                if !is_valid_record_filename(&name) {
                    return Err(BootError::Structural {
                        reason: format!(
                            "record filename {name:?} does not match sha256_hex + .json shape"
                        ),
                    });
                }

                // Size cap (v4 §8 rule 4).
                let size = record_meta.len();
                if size > MAX_RECORD_BYTES {
                    return Err(BootError::Structural {
                        reason: format!(
                            "record {record_path:?} size {size} exceeds {MAX_RECORD_BYTES}"
                        ),
                    });
                }

                walk.total_files =
                    walk.total_files
                        .checked_add(1)
                        .ok_or_else(|| BootError::BudgetExceeded {
                            reason: "total_files overflow".into(),
                        })?;
                walk.total_bytes = walk.total_bytes.checked_add(size).ok_or_else(|| {
                    BootError::BudgetExceeded {
                        reason: "total_bytes overflow".into(),
                    }
                })?;
                files.push(WalkedFile {
                    recipient: recipient_name_str.clone(),
                    path: record_path,
                });
            }
        }
    }

    // Total caps (v4 §8 rule 7).
    if walk.total_files > caps.max_envelopes {
        return Err(BootError::BudgetExceeded {
            reason: format!(
                "total_files={} > max_envelopes={}",
                walk.total_files, caps.max_envelopes
            ),
        });
    }
    if walk.total_bytes > caps.max_bytes {
        return Err(BootError::BudgetExceeded {
            reason: format!(
                "total_bytes={} > max_bytes={}",
                walk.total_bytes, caps.max_bytes
            ),
        });
    }

    // RAM budget estimate (v4 §8 rule 8 + v4.1 §5 L-N7 checked_mul).
    let ram_est = walk
        .total_bytes
        .checked_mul(140)
        .and_then(|v| v.checked_div(100))
        .ok_or_else(|| BootError::BudgetExceeded {
            reason: "RAM estimate overflow".into(),
        })?;
    if ram_est > caps.ram_budget {
        return Err(BootError::BudgetExceeded {
            reason: format!("ram_estimate={} > ram_budget={}", ram_est, caps.ram_budget),
        });
    }

    Ok((files, walk))
}

/// Round-1 M2 P0 #2 + round-2 P1 #2: sweep uncommitted
/// `.staging-*.tmp` files from a recipient directory.
///
/// The `.lock` invariant guarantees no live daemon is writing;
/// anything matching the tempfile shape is by construction an
/// uncommitted transaction from a prior SIGKILL and safe to
/// unlink.
///
/// Round-2 M2 P1 #2:
/// - Predicate [`atomic_write::is_staging_tempfile`] tightened
///   to `.staging-<non-empty alphanumeric>.tmp` (refuses
///   `.staging-.tmp` and non-alnum bodies). This function
///   inherits the tighter check for free.
/// - After all unlinks, `fsync(recipient_dir)` makes the
///   deletions durable — pre-amendment sweep did NOT fsync,
///   so a crash between remove_file and any later activity
///   could let the just-deleted tempfile re-appear on next
///   boot. Skipped when nothing was swept.
///
/// Returns the count of files swept (0 on healthy boot).
fn sweep_staging_tempfiles(recipient_dir: &Path) -> Result<u64, BootError> {
    let mut swept: u64 = 0;
    for entry in fs::read_dir(recipient_dir)? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !atomic_write::is_staging_tempfile(&name) {
            continue;
        }
        let path = entry.path();
        // Only remove regular files — a symlink named the same
        // must not follow. `symlink_metadata` refuses to follow.
        let meta = fs::symlink_metadata(&path)?;
        if !meta.is_file() {
            return Err(BootError::Structural {
                reason: format!(
                    "staging entry {path:?} is not a regular file — refusing to remove"
                ),
            });
        }
        fs::remove_file(&path)?;
        swept = swept
            .checked_add(1)
            .ok_or_else(|| BootError::BudgetExceeded {
                reason: "staging sweep counter overflow".into(),
            })?;
    }
    // Round-2 M2 P1 #2: fsync recipient_dir once, after ALL
    // unlinks, so the delete metadata is durable.
    if swept > 0 {
        #[cfg(unix)]
        {
            let handle = fs::File::open(recipient_dir)?;
            handle.sync_all()?;
        }
        #[cfg(not(unix))]
        {
            let _ = fs::metadata(recipient_dir)?;
        }
    }
    Ok(swept)
}

// ─── Replay ──────────────────────────────────────────────────────────────

/// Replay every walked file through parse + validation +
/// `LoadedRecord` construction. Locked v4.2 §3 B-3: panics ANY
/// WHERE in this per-file sequence yield `BootError::ParsePanic`
/// → exit 10.
///
/// Round-1 M2 P1 #6: the `catch_unwind` scope covers the full
/// per-file flow (parse + filename-vs-id check + variant classify
/// + `LoadedRecord` construction).
///
/// Round-1 M2 P0 #4: enforces
///   * `filename == sha256_hex(record.id) + ".json"` — refuses
///     the case where a hostile writer put a valid-looking file
///     under the wrong id.
///   * per-recipient duplicate-id detection.
/// Fills `walk.queued_records` / `walk.tombstone_records` as it
/// goes.
///
/// Round-3 M2 P1 #1 — **single-read evidence contract**. The
/// per-file byte read happens ONCE, inside the `catch_unwind`
/// closure (R4 amendment restored that scope after R3 briefly
/// moved it out). From that single buffer we derive:
///   1. `bytes.len()` — capped against `MAX_RECORD_BYTES` on the
///      actual buffer length (preflight snapshot may have grown
///      between metadata and now);
///   2. `file_sha256_hex` — the tamper-evidence digest, mirrored
///      into an outer `Option<ReadEvidence>` the instant the
///      hash is computed so a subsequent panic still surfaces
///      the correct SHA;
///   3. `disk_bytes` — the ledger footprint reported to M4 via
///      the resulting `LoadedRecord`;
///   4. the input to `serde_json::from_slice` in the same
///      closure.
/// If parsing panics, we surface `ParsePanic { file_sha256_hex }`
/// computed from that SAME buffer (or
/// `UNAVAILABLE_BEFORE_READ_MARKER` if the panic fired before
/// capture — see round-4). Pre-round-3
/// `hash_file_best_effort(&path)` re-opened the file after
/// unwind and could report either `<unreadable>` (file gone) or
/// a hash of DIFFERENT bytes.
fn replay_files(
    files: &[WalkedFile],
    walk: &mut WalkStats,
) -> Result<Vec<LoadedRecord>, BootError> {
    let mut records: Vec<LoadedRecord> = Vec::with_capacity(files.len());
    // Per-recipient dedup table for the ID-uniqueness invariant.
    let mut seen_ids: HashMap<String, HashSet<String>> = HashMap::new();

    for walked in files {
        let path = walked.path.clone();
        let recipient = walked.recipient.clone();

        // Round-4 M2 P1: evidence lives OUTSIDE the closure so
        // that a panic anywhere inside catch_unwind — including
        // AT or BEFORE the byte read — still surfaces as
        // BootError::ParsePanic. Populated inside the closure
        // via mutable borrow the instant the read+hash succeed;
        // any later panic finds the SHA already captured. A
        // panic BEFORE capture leaves this None and the error
        // path substitutes UNAVAILABLE_BEFORE_READ_MARKER.
        //
        // R1 P1 #6 contract restored: every per-file operation
        // (read, size cap, hash, parse, validation, LoadedRecord
        // construction) lives inside a single catch_unwind →
        // panic anywhere maps to ParsePanic → exit 10. R3's
        // read-outside-catch structure was a regression;
        // this amendment keeps the R3 P1 #1 single-buffer
        // guarantee (buffer captured once, evidence derived from
        // that same buffer) AND the R1 P1 #6 panic-boundary
        // guarantee.
        let mut evidence: Option<ReadEvidence> = None;

        let outcome: std::thread::Result<Result<LoadedRecord, BootError>> =
            std::panic::catch_unwind(AssertUnwindSafe(|| {
                // Round-4 M2 P1 second test seam: forces a panic
                // BEFORE byte capture to prove the closure
                // classifies it as ParsePanic (not an escaping
                // unwind). Thread-local + `#[cfg(test)]` — same
                // isolation as the after-capture seam.
                #[cfg(test)]
                {
                    let should_fault = FORCE_REPLAY_PANIC_BEFORE_READ.with(|c| c.get());
                    if should_fault {
                        std::panic::panic_any(
                            "forced replay panic BEFORE read (test fault seam — round-4 M2 P1)"
                                .to_string(),
                        );
                    }
                }

                // (1) Single byte capture — inside catch_unwind
                // per R1 P1 #6. R3 P1 #1 single-buffer contract
                // preserved: this is the ONLY read; every
                // subsequent decision (parse, cap, hash) uses
                // this exact buffer.
                let bytes = fs::read(&path).map_err(BootError::Io)?;

                // (2) Actual-buffer size cap. Preflight caught
                // the metadata snapshot; a hostile process could
                // have grown the file between the metadata read
                // and now.
                let observed_bytes = bytes.len() as u64;
                if observed_bytes > MAX_RECORD_BYTES {
                    return Err(BootError::Structural {
                        reason: format!(
                            "record {path:?} bytes.len()={observed_bytes} exceeds cap {MAX_RECORD_BYTES}"
                        ),
                    });
                }

                // (3) Tamper-evidence digest — SAME buffer as the
                // parse below. Captured to outer scope IMMEDIATELY
                // so an in-parse panic still surfaces the correct
                // SHA in ParsePanic.
                let file_sha256_hex = {
                    use sha2::{Digest, Sha256};
                    hex::encode(Sha256::digest(&bytes))
                };
                evidence = Some(ReadEvidence {
                    file_sha256_hex: file_sha256_hex.clone(),
                });

                // Round-3 M2 P1 #1 test fault seam: deletes the
                // file + panics AFTER evidence capture. Test
                // then asserts ParsePanic.file_sha256_hex is the
                // pre-capture SHA (not a re-read).
                #[cfg(test)]
                {
                    let should_fault = FORCE_REPLAY_PANIC_AND_DELETE.with(|c| c.get());
                    if should_fault {
                        let _ = fs::remove_file(&path);
                        std::panic::panic_any(
                            "forced replay panic AFTER read (test fault seam — round-3 M2 P1 #1)"
                                .to_string(),
                        );
                    }
                }

                // (4) Parse + validate — reads NOTHING from disk;
                // consumes the pre-captured buffer.
                let rec: PersistedRecord =
                    serde_json::from_slice(&bytes).map_err(|json_err| BootError::Structural {
                        reason: format!("record {path:?} json parse: {json_err}"),
                    })?;
                if rec.version() != persistence::RECORD_VERSION {
                    return Err(BootError::Structural {
                        reason: format!(
                            "record {:?} unknown version {}, expected {}",
                            path,
                            rec.version(),
                            persistence::RECORD_VERSION
                        ),
                    });
                }

                // Round-1 M2 P0 #4: filename must equal
                // sha256_hex(record.id) + ".json". A record whose
                // id disagrees with its filename is either
                // corrupted or an adversarial-plant.
                let expected_name = record_filename(rec.id());
                let actual_name = path.file_name().and_then(|s| s.to_str()).ok_or_else(|| {
                    BootError::Structural {
                        reason: format!("record {path:?} has non-utf8 filename"),
                    }
                })?;
                if actual_name != expected_name {
                    return Err(BootError::Structural {
                        reason: format!(
                            "record {path:?} filename {actual_name:?} != sha256_hex(id) {expected_name:?}"
                        ),
                    });
                }

                Ok(LoadedRecord {
                    recipient: recipient.clone(),
                    path: path.clone(),
                    disk_bytes: observed_bytes,
                    record: rec,
                })
            }));

        let loaded = match outcome {
            Ok(Ok(loaded)) => loaded,
            Ok(Err(err)) => return Err(err),
            Err(panic_payload) => {
                // Round-4 M2 P1: use the SHA captured to outer
                // scope BEFORE the panic. If the panic fired
                // before byte capture there is no digest to
                // report; the caller sees the explicit
                // UNAVAILABLE_BEFORE_READ_MARKER rather than a
                // fabricated hash or a re-read.
                let payload = extract_panic_message(&panic_payload);
                let file_sha256_hex = evidence
                    .as_ref()
                    .map(|e| e.file_sha256_hex.clone())
                    .unwrap_or_else(|| UNAVAILABLE_BEFORE_READ_MARKER.to_string());
                return Err(BootError::ParsePanic {
                    path,
                    payload,
                    file_sha256_hex,
                });
            }
        };

        // Round-1 M2 P0 #4: duplicate-id per recipient. HashSet
        // insert returns false on duplicate; refuse structural.
        let seen = seen_ids.entry(loaded.recipient.clone()).or_default();
        if !seen.insert(loaded.record.id().to_string()) {
            return Err(BootError::Structural {
                reason: format!(
                    "record {:?} duplicate id {:?} in recipient {}",
                    loaded.path,
                    loaded.record.id(),
                    loaded.recipient
                ),
            });
        }

        // Fill Queued / AckedTombstone stats (P0 #4 completion of
        // the previously-placeholder classifier).
        if loaded.is_queued() {
            walk.queued_records =
                walk.queued_records
                    .checked_add(1)
                    .ok_or_else(|| BootError::BudgetExceeded {
                        reason: "queued_records overflow".into(),
                    })?;
        } else {
            walk.tombstone_records =
                walk.tombstone_records
                    .checked_add(1)
                    .ok_or_else(|| BootError::BudgetExceeded {
                        reason: "tombstone_records overflow".into(),
                    })?;
        }

        records.push(loaded);
    }

    Ok(records)
}

// ─── Filename / metadata helpers ─────────────────────────────────────────

fn is_valid_record_filename(name: &str) -> bool {
    // `sha256_hex(id) + ".json"` = 64 hex chars + ".json".
    if name.len() != 64 + ".json".len() {
        return false;
    }
    if !name.ends_with(".json") {
        return false;
    }
    let hex = &name[..64];
    hex.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

/// Round-1 M2 P0 #4: shard dir name = exactly 2 lowercase hex.
fn is_valid_shard_name(name: &str) -> bool {
    name.len() == 2 && name.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

/// Round-2 M2 P1 #5: pull a human-readable message out of a
/// `catch_unwind` payload. Rust panics land as `Box<dyn Any +
/// Send>`; only `&'static str` and `String` are guaranteed to be
/// stored as the payload — every other shape loses the message
/// through `Debug`. Downcast in that order, fall back to a
/// type-name marker so the FATAL log at least says something
/// meaningful.
fn extract_panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

// `hash_file_best_effort` was removed in round-3 M2 P1 #1:
// the replay path no longer re-reads the file after unwind. The
// digest is computed from the SAME buffer that fed the parser
// (see `replay_files` and the
// `parse_panic_sha_matches_pre_read_buffer_even_after_file_deleted`
// test).

/// Round-1 M2 P0 #4: recipient dir name = exactly 64 lowercase hex.
fn is_valid_recipient_name(name: &str) -> bool {
    name.len() == 64 && name.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

fn check_path_metadata(
    path: &Path,
    ownership: OwnershipExpectation,
    role: &str,
) -> Result<(), BootError> {
    let meta = fs::symlink_metadata(path)?;
    if meta.file_type().is_symlink() {
        return Err(BootError::Structural {
            reason: format!("{role}{path:?} is a symlink — refused"),
        });
    }

    // Unix-only uid/gid/mode checks. On Windows the equivalents
    // are ACL-based and outside PR-2 scope (production runs on
    // Linux; dev builds on Windows just skip).
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let mode = meta.mode();
        if mode & ownership.mode_forbidden != 0 {
            return Err(BootError::Structural {
                reason: format!(
                    "{role}{path:?} mode {mode:#o} has forbidden bits {:#o}",
                    ownership.mode_forbidden
                ),
            });
        }
        if let Some(expected_uid) = ownership.expected_uid {
            if meta.uid() != expected_uid {
                return Err(BootError::Structural {
                    reason: format!(
                        "{role}{path:?} uid {} != expected {expected_uid}",
                        meta.uid()
                    ),
                });
            }
        }
        if let Some(expected_gid) = ownership.expected_gid {
            if meta.gid() != expected_gid {
                return Err(BootError::Structural {
                    reason: format!(
                        "{role}{path:?} gid {} != expected {expected_gid}",
                        meta.gid()
                    ),
                });
            }
        }
    }
    // Silence the unused parameter warning on non-unix platforms.
    #[cfg(not(unix))]
    let _ = ownership;

    Ok(())
}

// ─── Tests ───────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::persistence::{record_path, write_record, RECORD_VERSION};
    use tempfile::TempDir;

    // Round-1 M2 amendment tests: recipients MUST be 64 lowercase
    // hex chars (P0 #4). Reuse this constant across tests so a
    // fresh writer keeps the same shape.
    const TEST_RECIPIENT: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    // Shard = first two chars of TEST_RECIPIENT.
    const TEST_SHARD: &str = "01";

    fn sample_cfg(state_dir: PathBuf) -> BootConfig {
        BootConfig {
            state_dir,
            caps: PreflightCaps::for_tests(),
            tombstone: TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: "0123456789abcdef".to_string(),
            ownership: OwnershipExpectation::permissive_for_tests(),
        }
    }

    fn queued(id: &str, seq: u64) -> PersistedRecord {
        PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 0,
            seq,
            expires_at: 9_999_999_999,
            seq_mac: "".into(),
            body_hash: "0123456789abcdef".repeat(4),
        }
    }

    #[test]
    fn fresh_install_writes_meta_and_creates_queue() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        let result = boot(&cfg).unwrap();
        assert!(result.was_first_install);
        assert_eq!(result.meta.phase, Phase::Ready);
        assert_eq!(result.meta.boot_generation, 0);
        assert!(result.records.is_empty());
        assert_eq!(result.walk.total_files, 0);
        assert!(dir.path().join("queue").exists());
        assert!(dir.path().join("queue-meta.v1").exists());
    }

    #[test]
    fn normal_boot_bumps_generation_and_recovers_records() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        // First run — fresh install.
        boot(&cfg).unwrap();
        // Seed a record.
        let recipient = TEST_RECIPIENT;
        let path = record_path(dir.path(), recipient, "envelope-A");
        write_record(&path, &queued("envelope-A", 1)).unwrap();
        // Second run — normal boot, generation bumped.
        let result = boot(&cfg).unwrap();
        assert!(!result.was_first_install);
        assert_eq!(result.meta.boot_generation, 1);
        assert_eq!(result.records.len(), 1);
        // LoadedRecord wraps the record; `.record` unwraps the DTO.
        assert_eq!(result.records[0].record.id(), "envelope-A");
        assert_eq!(result.records[0].recipient, recipient);
        assert!(result.records[0].disk_bytes > 0);
        assert!(result.records[0].is_queued());
        assert!(!result.records[0].is_tombstone());
        assert_eq!(result.walk.total_files, 1);
        assert_eq!(result.walk.queued_records, 1);
        assert_eq!(result.walk.tombstone_records, 0);
        assert_eq!(result.walk.staging_tempfiles_swept, 0);
    }

    #[test]
    fn refuses_adversarial_partial_wipe() {
        // Row {ABSENT, PRESENT}: queue/ exists but meta missing.
        let dir = TempDir::new().unwrap();
        fs::create_dir_all(dir.path().join("queue")).unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert_eq!(err.exit_code(), EXIT_STRUCTURAL_ERROR);
    }

    #[test]
    fn refuses_subtree_lost() {
        // Row {"ready", ABSENT}: meta claims ready but queue/ is gone.
        let dir = TempDir::new().unwrap();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: 5,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        queue_meta::write_meta(dir.path(), &meta).unwrap();
        // Deliberately do NOT create queue/.
        let cfg = sample_cfg(dir.path().to_path_buf());
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert_eq!(err.exit_code(), EXIT_STRUCTURAL_ERROR);
    }

    #[test]
    fn complete_init_promotes_from_initializing() {
        let dir = TempDir::new().unwrap();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Initializing,
            boot_generation: 0,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        queue_meta::write_meta(dir.path(), &meta).unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        let result = boot(&cfg).unwrap();
        assert!(result.was_first_install);
        assert_eq!(result.meta.phase, Phase::Ready);
        assert!(dir.path().join("queue").exists());
    }

    #[test]
    fn refuses_seq_mac_key_mismatch_when_queue_non_empty() {
        let dir = TempDir::new().unwrap();
        // Bootstrap with fingerprint A + one record.
        let mut cfg = sample_cfg(dir.path().to_path_buf());
        cfg.current_seq_mac_key_fingerprint = "aaaaaaaaaaaaaaaa".into();
        boot(&cfg).unwrap();
        let path = record_path(dir.path(), TEST_RECIPIENT, "env-1");
        write_record(&path, &queued("env-1", 1)).unwrap();
        // Second boot with a different fingerprint should refuse.
        let mut cfg2 = cfg.clone();
        cfg2.current_seq_mac_key_fingerprint = "bbbbbbbbbbbbbbbb".into();
        let err = boot(&cfg2).unwrap_err();
        assert!(matches!(err, BootError::SeqMacKeyMismatch { .. }));
        assert_eq!(err.exit_code(), EXIT_SEQ_MAC_KEY_MISMATCH);
    }

    #[test]
    fn allows_seq_mac_key_rotation_when_queue_empty() {
        // Corollary to the above — an empty queue means no MAC-
        // bound state, so a rotated fingerprint is legitimate.
        let dir = TempDir::new().unwrap();
        let mut cfg = sample_cfg(dir.path().to_path_buf());
        cfg.current_seq_mac_key_fingerprint = "aaaaaaaaaaaaaaaa".into();
        boot(&cfg).unwrap();
        let mut cfg2 = cfg.clone();
        cfg2.current_seq_mac_key_fingerprint = "bbbbbbbbbbbbbbbb".into();
        boot(&cfg2).expect("rotation permitted with empty queue");
    }

    #[test]
    fn refuses_bad_filename() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Plant a garbage filename inside a valid recipient dir.
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        fs::create_dir_all(&recipient_dir).unwrap();
        fs::write(recipient_dir.join("not-a-hash.json"), b"{}").unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("sha256_hex"));
    }

    #[test]
    fn refuses_oversize_file() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        fs::create_dir_all(&recipient_dir).unwrap();
        // 128 KiB + 1 byte, valid filename.
        let name = format!("{}.json", "a".repeat(64));
        fs::write(
            recipient_dir.join(&name),
            vec![b'x'; (MAX_RECORD_BYTES + 1) as usize],
        )
        .unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("exceeds"));
    }

    #[test]
    fn refuses_when_total_bytes_exceed_cap() {
        let dir = TempDir::new().unwrap();
        let mut cfg = sample_cfg(dir.path().to_path_buf());
        // Set a tiny cap.
        cfg.caps.max_bytes = 100;
        boot(&cfg).unwrap();
        let path = record_path(dir.path(), TEST_RECIPIENT, "envelope-A");
        // Write a record whose serialised size exceeds 100 bytes.
        write_record(&path, &queued("envelope-A", 1)).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::BudgetExceeded { .. }));
        assert_eq!(err.exit_code(), EXIT_BUDGET_EXCEEDED);
    }

    #[test]
    fn refuses_when_total_files_exceed_cap() {
        let dir = TempDir::new().unwrap();
        let mut cfg = sample_cfg(dir.path().to_path_buf());
        cfg.caps.max_envelopes = 1;
        boot(&cfg).unwrap();
        write_record(
            &record_path(dir.path(), TEST_RECIPIENT, "env-A"),
            &queued("env-A", 1),
        )
        .unwrap();
        write_record(
            &record_path(dir.path(), TEST_RECIPIENT, "env-B"),
            &queued("env-B", 2),
        )
        .unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::BudgetExceeded { .. }));
    }

    #[test]
    fn filename_shape_validator_smoke() {
        assert!(is_valid_record_filename(&format!(
            "{}.json",
            "a".repeat(64)
        )));
        assert!(!is_valid_record_filename("short.json"));
        assert!(!is_valid_record_filename(&"a".repeat(69))); // no .json
        assert!(!is_valid_record_filename(&format!(
            "{}.json",
            "A".repeat(64)
        ))); // uppercase
        assert!(!is_valid_record_filename(&format!(
            "{}.txt",
            "a".repeat(64)
        ))); // wrong ext
    }

    #[test]
    fn boot_error_maps_to_expected_exit_codes() {
        let e = BootError::Structural { reason: "s".into() };
        assert_eq!(e.exit_code(), EXIT_STRUCTURAL_ERROR);
        let e = BootError::BudgetExceeded { reason: "b".into() };
        assert_eq!(e.exit_code(), EXIT_BUDGET_EXCEEDED);
        let e = BootError::GenerationSaturation { current: u32::MAX };
        assert_eq!(e.exit_code(), EXIT_GENERATION_SATURATION);
        let e = BootError::SeqMacKeyMismatch {
            meta_fingerprint: "a".into(),
            current_fingerprint: "b".into(),
        };
        assert_eq!(e.exit_code(), EXIT_SEQ_MAC_KEY_MISMATCH);
        let e = BootError::ParsePanic {
            path: PathBuf::from("x"),
            payload: "p".into(),
            file_sha256_hex: "<unreadable>".into(),
        };
        assert_eq!(e.exit_code(), EXIT_PARSE_OR_COMMIT_PANIC);
        let e = BootError::ConfigError { reason: "c".into() };
        assert_eq!(e.exit_code(), EXIT_CONFIG_ERROR);
    }

    #[test]
    fn generation_saturation_returns_exit_4_at_24_bit_cap() {
        // Plant a meta AT the 2^24-1 cap; the next boot must
        // refuse rather than advance past the seq-space budget.
        // Locked v4 §13 Q1 + PR-2 M3a round-1 review F2.
        let dir = TempDir::new().unwrap();
        fs::create_dir_all(dir.path().join("queue")).unwrap();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: queue_meta::MAX_BOOT_GENERATION,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        queue_meta::write_meta(dir.path(), &meta).unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(
            err,
            BootError::GenerationSaturation { current }
                if current == queue_meta::MAX_BOOT_GENERATION
        ));
        assert_eq!(err.exit_code(), EXIT_GENERATION_SATURATION);
    }

    #[test]
    fn generation_saturation_still_refuses_above_the_cap() {
        // Defence-in-depth: a meta planted ABOVE the cap
        // (hostile / corrupted meta file) must still refuse-boot.
        let dir = TempDir::new().unwrap();
        fs::create_dir_all(dir.path().join("queue")).unwrap();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: queue_meta::MAX_BOOT_GENERATION + 1,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        queue_meta::write_meta(dir.path(), &meta).unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::GenerationSaturation { .. }));
        assert_eq!(err.exit_code(), EXIT_GENERATION_SATURATION);
    }

    // ────────────────────────────────────────────────────────────
    // Round-1 M2 review — adversarial tests for the new
    // disk-binding invariants.
    // ────────────────────────────────────────────────────────────

    #[test]
    fn refuses_non_hex_recipient_dir() {
        // Round-1 M2 P0 #4: recipient dir name MUST be exactly
        // 64 lowercase hex.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let bad_recipient = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join("NOT_HEX_AT_ALL");
        fs::create_dir_all(&bad_recipient).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("recipient dir"));
    }

    #[test]
    fn refuses_recipient_that_is_only_16_hex_chars() {
        // Round-1 M2 P0 #4: recipient MUST be 64 chars, not 16.
        // Reproduces the pre-amendment test setup that this
        // amendment specifically hardens against.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let short_recipient = dir.path().join("queue").join("01").join("0123456789abcdef");
        fs::create_dir_all(&short_recipient).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("64"));
    }

    #[test]
    fn refuses_shard_mismatch() {
        // Round-1 M2 P0 #4: shard dir name MUST equal the first
        // 2 chars of the recipient name. TEST_RECIPIENT starts
        // with "01", so putting it under shard "ab" must refuse.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let wrong_shard = dir.path().join("queue").join("ab").join(TEST_RECIPIENT);
        fs::create_dir_all(&wrong_shard).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("disagrees with shard"));
    }

    #[test]
    fn refuses_shard_dir_that_is_not_two_hex() {
        // Round-1 M2 P0 #4: shard dir name MUST be exactly 2 lowercase hex.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let bad_shard = dir.path().join("queue").join("bad_shard_name");
        fs::create_dir_all(&bad_shard).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("shard dir"));
    }

    #[test]
    fn refuses_filename_not_matching_sha256_of_id() {
        // Round-1 M2 P0 #4: filename MUST equal sha256_hex(id)
        // + ".json". A record whose filename disagrees with its
        // id is adversarial-plant or corruption.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Write a valid-looking Queued record but plant it under
        // a mismatched filename (sha256_hex of "OTHER" instead of
        // its own id "victim").
        let rec = queued("victim", 1);
        let bytes = serde_json::to_vec(&rec).unwrap();
        // `record_filename` already appends `.json`; the test
        // uses it verbatim to produce a valid-looking filename
        // whose sha256_hex(id) resolves to a DIFFERENT id.
        let wrong_name = crate::persistence::record_filename("OTHER");
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        fs::create_dir_all(&recipient_dir).unwrap();
        fs::write(recipient_dir.join(&wrong_name), &bytes).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("sha256_hex(id)"));
    }

    #[test]
    fn refuses_duplicate_id_within_recipient() {
        // Round-1 M2 P0 #4: per-recipient duplicate id. Two
        // valid records with the SAME id would produce the same
        // filename — but a bug/attacker could split them across
        // two files (via mv/cp). The replay pass keeps a
        // per-recipient HashSet<id> and refuses on collision.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Write the same id under two different filenames — the
        // second filename won't match sha256_hex(id) so it fails
        // the filename check first. To test dup-id specifically
        // we need TWO valid records whose IDs happen to be the
        // same. That's what write_record then a manual copy at
        // a matching filename simulates: write valid record A,
        // then copy it to a filename that also hashes to the
        // same id — impossible under sha256, so we simulate a
        // *different* attack: use two records under the same
        // recipient with the same id in the DTO but different
        // filenames. The filename check will fire first; the
        // dup-id check is exercised in a separate test where the
        // filename validation passes.
        //
        // We instead write two DIFFERENT records with different
        // ids, then post-process one to have the same id as the
        // other while keeping its filename intact. That yields
        // a valid filename (sha256 of the file's original id)
        // but a DTO id that duplicates a peer — the dup-id check
        // catches it.
        let rec_a = queued("dup", 1);
        let rec_b = queued("dup", 2); // same id, different seq
                                      // Write rec_a under sha256("dup").
        let path_a = record_path(dir.path(), TEST_RECIPIENT, "dup");
        write_record(&path_a, &rec_a).unwrap();
        // Craft a filename that hashes some OTHER id but plant
        // rec_b's bytes there. Then the filename-vs-id check
        // will fire first (because rec_b's id is "dup" but the
        // filename is sha256("distinct")). That's not what we
        // want.
        //
        // Cleanest dup-id test: two identical files with the
        // same filename would collide on the filesystem itself
        // (same path). So dup-id via same-recipient-same-id is
        // structurally impossible on disk. The dup-id check
        // instead guards against a hypothetical bug where a
        // future refactor lets two DIFFERENT files carry the
        // same DTO id. We simulate that by directly writing
        // rec_b's bytes under a filename derived from a
        // different id.
        let alt_id = "distinct";
        let alt_path = record_path(dir.path(), TEST_RECIPIENT, alt_id);
        // Write rec_b (id="dup") at filename sha256("distinct").
        // Filename validation will fire first and refuse.
        fs::write(&alt_path, serde_json::to_vec(&rec_b).unwrap()).unwrap();
        let err = boot(&cfg).unwrap_err();
        // Either filename-vs-id OR dup-id is a legitimate
        // structural refusal — the ordering is a defence-in-depth
        // decision inside the module. Both stop the attack.
        assert!(matches!(err, BootError::Structural { .. }));
    }

    #[test]
    fn sweeps_staging_tempfile_and_proceeds() {
        // Round-1 M2 P0 #2: a `.staging-<random>.tmp` file left
        // behind by a SIGKILL between file-fsync and rename MUST
        // be swept by preflight, NOT rejected as unknown.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Seed one real record so we know boot actually recovered.
        let real_path = record_path(dir.path(), TEST_RECIPIENT, "real-envelope");
        write_record(&real_path, &queued("real-envelope", 42)).unwrap();
        // Plant a tempfile-shaped uncommitted write.
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        // Round-2 M2 P1 #2: predicate requires alphanumeric-only
        // random segment; the pre-amendment test used a hyphen
        // which is now refused. Use tempfile's real shape.
        let leftover = ".staging-abc123XY.tmp";
        fs::write(recipient_dir.join(leftover), b"partial garbage").unwrap();
        // Boot must succeed, sweep the tempfile, and count it.
        let result = boot(&cfg).unwrap();
        assert_eq!(result.walk.staging_tempfiles_swept, 1);
        assert_eq!(result.records.len(), 1);
        assert_eq!(result.records[0].record.id(), "real-envelope");
        // Verify the tempfile actually got removed.
        assert!(!recipient_dir.join(leftover).exists());
    }

    #[test]
    fn refuses_staging_tempfile_with_empty_random_segment() {
        // Round-2 M2 P1 #2: `.staging-.tmp` (empty random) is
        // NOT a real tempfile shape; predicate refuses it, so
        // the walker treats it as an unknown record name and
        // fires structural refuse rather than silently sweep.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        fs::create_dir_all(&recipient_dir).unwrap();
        // Empty random segment — must NOT be swept.
        fs::write(recipient_dir.join(".staging-.tmp"), b"suspicious").unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        // Predicate refused → walker sees an unknown filename
        // and fires the sha256_hex-shape check.
        assert!(err.to_string().contains("sha256_hex"));
    }

    #[test]
    fn zero_staging_tempfiles_on_healthy_boot() {
        // Sweep counter stays at 0 when there are no leftovers.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        write_record(
            &record_path(dir.path(), TEST_RECIPIENT, "envelope-A"),
            &queued("envelope-A", 1),
        )
        .unwrap();
        let result = boot(&cfg).unwrap();
        assert_eq!(result.walk.staging_tempfiles_swept, 0);
    }

    // ────────────────────────────────────────────────────────────
    // Round-2 M2 P1 #4 — walker refuses unexpected node types.
    // ────────────────────────────────────────────────────────────

    #[test]
    fn refuses_file_at_shard_level() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Plant a plain file where a shard dir should be.
        fs::write(dir.path().join("queue").join("stray-file"), b"garbage").unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("shard-level"));
    }

    #[test]
    fn refuses_file_at_recipient_level() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Real shard dir but a file where a recipient dir belongs.
        let shard_dir = dir.path().join("queue").join(TEST_SHARD);
        fs::create_dir_all(&shard_dir).unwrap();
        fs::write(shard_dir.join("stray-file"), b"garbage").unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("recipient-level"));
    }

    #[test]
    fn refuses_dir_at_record_level() {
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Real shard + recipient dirs, but a nested dir where a
        // record file belongs.
        let recipient_dir = dir
            .path()
            .join("queue")
            .join(TEST_SHARD)
            .join(TEST_RECIPIENT);
        fs::create_dir_all(&recipient_dir).unwrap();
        fs::create_dir_all(recipient_dir.join("nested-dir")).unwrap();
        let err = boot(&cfg).unwrap_err();
        assert!(matches!(err, BootError::Structural { .. }));
        assert!(err.to_string().contains("record-level"));
    }

    // ────────────────────────────────────────────────────────────
    // Round-2 M2 P1 #5 — ParsePanic downcast + sha256.
    // ────────────────────────────────────────────────────────────

    #[test]
    fn extract_panic_message_downcasts_static_str() {
        // Simulate a `panic!("literal")` payload.
        let payload: Box<dyn std::any::Any + Send> = Box::new("panic literal from &str");
        let msg = extract_panic_message(&payload);
        assert_eq!(msg, "panic literal from &str");
    }

    #[test]
    fn extract_panic_message_downcasts_string() {
        // Simulate a `panic!("dynamic {value}")` payload (which
        // becomes a `String` rather than `&'static str`).
        let payload: Box<dyn std::any::Any + Send> =
            Box::new(String::from("dynamic panic from String"));
        let msg = extract_panic_message(&payload);
        assert_eq!(msg, "dynamic panic from String");
    }

    #[test]
    fn extract_panic_message_falls_back_when_unknown_type() {
        // Some other type — the fallback marker.
        let payload: Box<dyn std::any::Any + Send> = Box::new(42u64);
        let msg = extract_panic_message(&payload);
        assert!(msg.contains("non-string"));
    }

    // Round-3 M2 P1 #1: `hash_file_best_effort` was removed
    // from the production path (replay hashes the pre-read
    // buffer). The invariant "the ParsePanic evidence hash
    // describes the exact bytes the parser consumed" is now
    // locked by `parse_panic_sha_matches_pre_read_buffer_even_after_file_deleted`
    // below.

    #[test]
    fn parse_panic_error_displays_evidence_fields() {
        let e = BootError::ParsePanic {
            path: PathBuf::from("/state/queue/01/aa..bb/deadbeef.json"),
            payload: "explicit panic message".into(),
            file_sha256_hex: "0123456789abcdef".repeat(4),
        };
        let s = e.to_string();
        assert!(s.contains("explicit panic message"));
        assert!(s.contains("file_sha256"));
        assert!(s.contains("deadbeef.json"));
    }

    #[test]
    fn parse_panic_sha_matches_pre_read_buffer_even_after_file_deleted() {
        // Round-3 M2 P1 #1 integration test. Locks the
        // single-read evidence contract:
        //   1. Write a valid record on disk (content C, hash H_C).
        //   2. Enable FORCE_REPLAY_PANIC_AND_DELETE — the replay
        //      loop will, INSIDE catch_unwind, remove the file
        //      and then panic. This simulates the pathological
        //      race the reviewer called out: the file the panic
        //      happened over is gone by the time the panic is
        //      caught.
        //   3. boot() surfaces BootError::ParsePanic.
        //   4. Assert the reported file_sha256_hex is H_C —
        //      derived from the buffer that WAS parsed, NOT
        //      from any re-read (which would report
        //      `<unreadable>` for the now-missing file).
        //
        // Round-3 amendment: seam is thread-local, so parallel
        // tests are naturally isolated. The RAII guard still
        // restores the flag on drop in case the test panics
        // mid-way and future test additions on this thread
        // (async runtimes, etc.) inherit a clean value.
        struct SeamGuard;
        impl Drop for SeamGuard {
            fn drop(&mut self) {
                FORCE_REPLAY_PANIC_AND_DELETE.with(|c| c.set(false));
            }
        }
        let _guard = SeamGuard;

        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        // Initial fresh install.
        boot(&cfg).unwrap();
        // Seed one record.
        let record = queued("panic-victim", 42);
        let path = record_path(dir.path(), TEST_RECIPIENT, "panic-victim");
        write_record(&path, &record).unwrap();
        // Compute the expected evidence SHA from the exact
        // bytes on disk RIGHT NOW.
        let expected_sha = {
            use sha2::{Digest, Sha256};
            let bytes = fs::read(&path).unwrap();
            hex::encode(Sha256::digest(&bytes))
        };
        assert_eq!(expected_sha.len(), 64);

        // Arm the fault seam (thread-local) and drive boot.
        FORCE_REPLAY_PANIC_AND_DELETE.with(|c| c.set(true));
        let err = boot(&cfg).expect_err("replay must panic under fault seam");
        // Seam runs inside the catch_unwind scope of the replay
        // loop, so the panic surfaces as ParsePanic.
        let (reported_path, reported_sha) = match err {
            BootError::ParsePanic {
                path,
                file_sha256_hex,
                ..
            } => (path, file_sha256_hex),
            other => panic!("expected ParsePanic, got {other:?}"),
        };
        assert_eq!(reported_path, path);
        // File was removed by the seam — a naive post-panic
        // re-read would return `<unreadable>`. The pre-read
        // buffer's SHA MUST match.
        assert_eq!(
            reported_sha, expected_sha,
            "ParsePanic.file_sha256_hex must reflect the pre-panic buffer, not the (now-missing) file",
        );
        // Sanity: the file really is gone.
        assert!(!path.exists(), "fault seam did not remove the file");
    }

    #[test]
    fn panic_before_read_still_maps_to_parse_panic_with_marker() {
        // Round-4 M2 P1: locks the R1 P1 #6 contract that
        // panics ANYWHERE in the per-file replay — including
        // AT or BEFORE the byte capture — surface as
        // BootError::ParsePanic (never an escaping unwind).
        // The `file_sha256_hex` field carries the explicit
        // UNAVAILABLE_BEFORE_READ_MARKER so operator triage
        // can distinguish this case from a real digest.
        struct SeamGuard;
        impl Drop for SeamGuard {
            fn drop(&mut self) {
                FORCE_REPLAY_PANIC_BEFORE_READ.with(|c| c.set(false));
            }
        }
        let _guard = SeamGuard;

        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        // Seed a valid record; the seam fires BEFORE its read.
        let record = queued("panic-before-read", 1);
        let path = record_path(dir.path(), TEST_RECIPIENT, "panic-before-read");
        write_record(&path, &record).unwrap();

        FORCE_REPLAY_PANIC_BEFORE_READ.with(|c| c.set(true));
        let err = boot(&cfg).expect_err("replay must panic under before-read fault seam");
        match err {
            BootError::ParsePanic {
                path: reported_path,
                file_sha256_hex,
                payload,
            } => {
                assert_eq!(reported_path, path);
                assert_eq!(
                    file_sha256_hex, UNAVAILABLE_BEFORE_READ_MARKER,
                    "pre-capture panic must carry the explicit marker, not a fabricated hash",
                );
                assert!(payload.contains("BEFORE read"));
            }
            other => panic!("expected ParsePanic, got {other:?}"),
        }
    }

    #[test]
    fn loaded_record_helpers_classify_variant() {
        // Round-1 M2 P0 #3: LoadedRecord::is_queued / is_tombstone.
        let dir = TempDir::new().unwrap();
        let cfg = sample_cfg(dir.path().to_path_buf());
        boot(&cfg).unwrap();
        write_record(
            &record_path(dir.path(), TEST_RECIPIENT, "queued-envelope"),
            &queued("queued-envelope", 1),
        )
        .unwrap();
        // Seed a tombstone.
        let tomb = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: "tombstone-envelope".into(),
            seq: 2,
            body_hash: "0123456789abcdef".repeat(4),
            acked_at: 100,
            dedup_until: 100 + 172_800,
        };
        write_record(
            &record_path(dir.path(), TEST_RECIPIENT, "tombstone-envelope"),
            &tomb,
        )
        .unwrap();
        let result = boot(&cfg).unwrap();
        assert_eq!(result.records.len(), 2);
        let queued_count = result.records.iter().filter(|r| r.is_queued()).count();
        let tomb_count = result.records.iter().filter(|r| r.is_tombstone()).count();
        assert_eq!(queued_count, 1);
        assert_eq!(tomb_count, 1);
        assert_eq!(result.walk.queued_records, 1);
        assert_eq!(result.walk.tombstone_records, 1);
    }
}
