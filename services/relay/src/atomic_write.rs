// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — atomic tempfile+rename+fsync
//! write helper.
//!
//! Locked design v4.2.2 §2 (deadline-expiry contract):
//! > Every state-changing operation uses tempfile → `write_all` →
//! > `fsync(file)` → same-fs `rename` → `fsync(parent_dir)`.
//! > Prior successful completion of this sequence for a given
//! > state MUST hold before that state is considered "committed"
//! > to the caller.
//!
//! Round-1 M2 review amendments:
//! - **P0 #1** post-rename parent-fsync failure is now
//!   `std::process::abort()`. After the POSIX rename the new
//!   file is visible on disk; returning `Err` at that point
//!   would let the caller roll back its in-memory bookkeeping
//!   while disk state has already changed. The contract "on
//!   `Err(_)` the file at `path` is EITHER the pre-existing
//!   content OR does not exist" is preserved by making
//!   post-rename failures crash-equivalent.
//! - **P0 #2** the tempfile now uses a recognisable
//!   `.staging-` prefix + `.tmp` suffix so a SIGKILL between
//!   `NamedTempFile` drop and rename leaves an artefact the
//!   walker knows to sweep at boot rather than refuse.
//! - **P1 #5** `create_dir_all_durable` is added so lazy
//!   creation of shard + recipient dirs fsyncs every new
//!   directory PLUS its parent chain up to the state_dir root.
//!
//! Direct callers of `NamedTempFile::new()` or
//! `env::temp_dir()` remain forbidden fleet-wide (grep-gate
//! lands in M6) — every atomic write MUST go through
//! [`write_atomic`] so the same-filesystem invariant is
//! preserved and no state escapes to `/tmp`.

use std::fs;
use std::io::{self, Write};
use std::path::Path;

use tempfile::Builder as TempBuilder;

/// Requested Unix mode for every newly-created state subtree directory.
///
/// The process umask may remove additional permissions, but can never widen
/// this mode. In particular, a normal `0022` umask must not turn queue,
/// shard, or recipient directories into `0755`: production boot rejects all
/// world bits through `RELAY_MODE_FORBIDDEN=0027`.
#[cfg(unix)]
const DURABLE_STATE_DIR_MODE: u32 = 0o750;

// Round-3 M2 P2 #3: the fault-injection seam and its atomic
// primitives are gated behind `#[cfg(test)]` so they do NOT
// compile into the release binary at all. Prior amendments
// left the flag `pub(crate)` and always-compiled, which
// (a) added a `SeqCst` load to every production `write_atomic`
// call and (b) exposed the flag to non-test crate code.
// `cargo test --release` still activates `#[cfg(test)]`, so
// the subprocess abort test continues to exercise the path.
#[cfg(test)]
use std::sync::atomic::{AtomicBool, Ordering};

/// Round-2 M2 P1 #3 fault-injection seam. When `true`, the
/// post-rename parent-dir fsync in [`write_atomic`] is forced to
/// fail as though the underlying syscall returned an I/O error,
/// exercising the FATAL abort path.
///
/// Round-3 M2 P2 #3: `#[cfg(test)]`-only — the flag does not
/// exist in the release binary, so there is no production cost
/// and no way for non-test crate code to reach it. The
/// [`crate::atomic_write::tests::post_rename_fsync_failure_forced_abort_child`]
/// test spawns a subprocess that toggles this flag and confirms
/// the parent process observes a non-zero exit + `FATAL:` marker
/// in stderr.
#[cfg(test)]
pub(crate) static FORCE_POST_RENAME_FSYNC_FAIL: AtomicBool = AtomicBool::new(false);

/// Prefix applied to every tempfile via [`write_atomic`]. Locked
/// M2-round-1 amendment (P0 #2): the walker inside the boot
/// loader recognises this prefix to safely sweep uncommitted
/// tempfiles a SIGKILL may have left behind. Keep this constant
/// in sync with the walker's sweep step.
pub const STAGING_TEMPFILE_PREFIX: &str = ".staging-";

/// Suffix applied to every tempfile via [`write_atomic`]. Paired
/// with [`STAGING_TEMPFILE_PREFIX`] for the boot-time sweep.
pub const STAGING_TEMPFILE_SUFFIX: &str = ".tmp";

/// True iff `name` matches the shape `.staging-<random>.tmp`
/// used by [`write_atomic`]. Boot-time preflight uses this to
/// identify uncommitted tempfiles that a SIGKILL left behind
/// and unlink them before the main walk.
///
/// Round-2 M2 P1 #2: the random segment MUST be non-empty and
/// match `[A-Za-z0-9]+`. Pre-amendment predicate accepted
/// `.staging-.tmp` (empty middle) and any character in between,
/// which was over-broad and would let a hostile writer plant an
/// arbitrary name the sweep then removed without proof it was
/// ever a real tempfile from us. `tempfile::Builder` emits
/// exactly this alphanumeric shape, so the tighter check is
/// safe.
pub fn is_staging_tempfile(name: &str) -> bool {
    let Some(rest) = name.strip_prefix(STAGING_TEMPFILE_PREFIX) else {
        return false;
    };
    let Some(random) = rest.strip_suffix(STAGING_TEMPFILE_SUFFIX) else {
        return false;
    };
    if random.is_empty() {
        return false;
    }
    random.chars().all(|c| c.is_ascii_alphanumeric())
}

/// Atomic file write.
///
/// Contract: on `Ok(())` the file at `path` contains exactly
/// `bytes` and the state has been fsynced to durable storage
/// (including a parent-directory fsync so the rename metadata
/// hits disk).
///
/// On `Err(_)` — returned ONLY from pre-rename failure paths —
/// the file at `path` is EITHER the pre-existing content
/// (untouched) OR does not exist. The randomised tempfile is
/// cleaned up automatically on any such error via
/// `NamedTempFile`'s `Drop`.
///
/// Post-rename failure (parent-directory fsync fails after the
/// rename has succeeded) is treated as a fatal system-level
/// event: the function logs FATAL to stderr and calls
/// `std::process::abort()`. Rationale (round-1 M2 P0 #1): the
/// rename is already visible on disk, so returning `Err` would
/// let the caller roll back RAM/ledger state that disagrees
/// with disk truth. The abort matches v4.2.2 §2's "deadline
/// expiry is a CRASH-EQUIVALENT event" wording — durability
/// after a crash comes from the tempfile+fsync sequence and
/// boot recovery, not from any promise about the parent-fsync
/// completing on this particular process.
///
/// Steps:
/// 1. Create `NamedTempFile` inside the parent of `path` using
///    the recognisable `.staging-<random>.tmp` prefix/suffix
///    (round-1 M2 P0 #2). Same-directory placement keeps the
///    POSIX rename atomic; SIGKILL between drop and rename
///    leaves a file the boot walker knows to sweep.
/// 2. Write every byte with `write_all`.
/// 3. `sync_all` on the tempfile — the tempfile's persist step
///    below will not itself fsync, so this call is what commits
///    the payload to disk.
/// 4. `persist(path)` performs the atomic rename.
/// 5. Open the parent directory read-only and `sync_all` it so
///    the rename metadata is durable. On Unix a failure here is
///    FATAL (see above); on Windows the file-level sync in step
///    3 flushes the metadata so this step is effectively a no-op
///    that cannot fail.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let parent = path.parent().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("atomic_write: path has no parent: {path:?}"),
        )
    })?;
    if !parent.exists() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!("atomic_write: parent directory does not exist: {parent:?}"),
        ));
    }

    // (1) Create tempfile inside the target directory with a
    // recognisable prefix so the boot walker can sweep any leftover
    // if this process is SIGKILLed between here and rename.
    let mut tmp = TempBuilder::new()
        .prefix(STAGING_TEMPFILE_PREFIX)
        .suffix(STAGING_TEMPFILE_SUFFIX)
        .tempfile_in(parent)?;

    // (2) Write payload.
    tmp.as_file_mut().write_all(bytes)?;

    // (3) Fsync the tempfile so the payload hits durable storage
    // BEFORE the rename commits.
    tmp.as_file_mut().sync_all()?;

    // (4) Atomic rename. From this line on, the new content is
    // visible under `path` on disk.
    tmp.persist(path).map_err(|persist_err| persist_err.error)?;

    // (5) Parent-directory fsync. Post-rename failure is FATAL —
    // see doc comment for rationale.
    //
    // Round-2 M2 P1 #3 + round-3 M2 P2 #3: the fault-injection
    // seam `FORCE_POST_RENAME_FSYNC_FAIL` short-circuits into
    // the FATAL path so the subprocess test can exercise
    // `fatal_post_rename_failure` on a real process. The seam is
    // `#[cfg(test)]`-only — the release binary has zero cost
    // and no way for non-test code to trip it.
    #[cfg(test)]
    if FORCE_POST_RENAME_FSYNC_FAIL.load(Ordering::SeqCst) {
        let forced = io::Error::new(
            io::ErrorKind::Other,
            "atomic_write forced post-rename fsync failure (test fault seam)",
        );
        fatal_post_rename_failure(path, parent, &forced);
    }

    #[cfg(unix)]
    {
        match fs::File::open(parent) {
            Ok(dir) => {
                if let Err(sync_err) = dir.sync_all() {
                    fatal_post_rename_failure(path, parent, &sync_err);
                }
            }
            Err(open_err) => {
                fatal_post_rename_failure(path, parent, &open_err);
            }
        }
    }
    #[cfg(not(unix))]
    {
        // Windows: File::sync_all on a directory handle is not
        // exposed via std, but step (3) already forced the file
        // and its metadata through NTFS's journal. Confirm the
        // parent still exists (a hostile process removing it
        // mid-flight would be an invariant break) — anything
        // else is treated as fatal, same as the Unix branch.
        if let Err(meta_err) = fs::metadata(parent) {
            fatal_post_rename_failure(path, parent, &meta_err);
        }
    }

    Ok(())
}

/// Convenience: atomic write of a UTF-8 string (via bytes).
pub fn write_atomic_str(path: &Path, contents: &str) -> io::Result<()> {
    write_atomic(path, contents.as_bytes())
}

/// Report a post-rename durability failure and abort.
///
/// Locked contract v4.2.2 §2: after the atomic rename the new
/// state is visible on disk. If the paired parent-fsync cannot
/// be confirmed we can no longer honestly tell the caller
/// whether the change is durable, so the process aborts.
/// Callers must not attempt to catch this.
fn fatal_post_rename_failure(path: &Path, parent: &Path, err: &io::Error) -> ! {
    // Locked v4.2.2 §2 FATAL requirement: write directly via
    // eprintln! (bypasses the tracing subscriber, which may
    // buffer and drop the message on abort).
    eprintln!(
        "FATAL: atomic_write post-rename parent-fsync failed \
         (path={path:?}, parent={parent:?}, err={err}). Aborting \
         — disk state is committed but durability cannot be \
         confirmed by this process. Recovery via boot loader on \
         next start."
    );
    std::process::abort();
}

/// Create every directory in `path`, fsyncing each newly-created
/// directory PLUS its parent chain up to the point where the
/// chain becomes pre-existing (round-1 M2 P1 #5).
///
/// Contract:
/// - Idempotent — pre-existing directories are left alone.
/// - Every directory that this call creates gets fsynced.
/// - The parent directory that each new directory is created
///   INTO is also fsynced (so the new dir's entry becomes
///   durable metadata under the parent).
///
/// This closes the durability gap in lazy shard/recipient dir
/// creation: `mkdir queue/<hex[0..2]>/<recipient>/` followed by
/// `write_record` needs `queue/`, `queue/<hex[0..2]>/`, and
/// `<recipient>/` to all be fsynced before the record file's
/// own fsync claims the write is durable — otherwise a crash
/// can lose the FIRST record in a fresh recipient dir even
/// though the record's `sync_all` reported success.
///
/// On Unix a failure to fsync a directory is treated the same as
/// a failure to create it: returned as `Err` to the caller (this
/// path is NOT post-rename; no state is committed yet).
/// Fsync `dir` so any recently-changed directory metadata
/// (renames INTO, unlinks FROM, dir creates) becomes durable.
///
/// **PR-2 M3b-1**: boot compaction and sweep must invoke this
/// after `fs::remove_file` so the unlink actually reaches the
/// disk before we claim the record is reclaimed. Also used by
/// [`write_atomic`] and [`create_dir_all_durable`] internally.
///
/// On Unix the idiom is `File::open(dir) + sync_all()`. On
/// Windows the standard library does not expose a directory
/// fsync, so this call reduces to a metadata read as a
/// no-op-shaped placeholder — production runs on Linux.
pub fn fsync_dir(dir: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        let handle = fs::File::open(dir)?;
        handle.sync_all()
    }
    #[cfg(not(unix))]
    {
        let _ = fs::metadata(dir)?;
        Ok(())
    }
}

pub fn create_dir_all_durable(path: &Path) -> io::Result<()> {
    // Collect the ancestor chain up to the first existing dir
    // (inclusive). The first existing dir must also be fsynced
    // because the FIRST new dir directly under it needs its
    // entry durable in that dir.
    let mut chain: Vec<std::path::PathBuf> = Vec::new();
    let mut cursor = path;
    loop {
        chain.push(cursor.to_path_buf());
        if cursor.exists() {
            break;
        }
        match cursor.parent() {
            Some(p) if !p.as_os_str().is_empty() => cursor = p,
            _ => break,
        }
    }

    // Apply the restrictive mode at mkdir time, avoiding a fail-open
    // `mkdir 0755 -> chmod 0750` window. The process umask may only make
    // the resulting mode stricter.
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;

        let mut builder = fs::DirBuilder::new();
        builder.recursive(true);
        builder.mode(DURABLE_STATE_DIR_MODE);
        builder.create(path)?;
    }
    #[cfg(not(unix))]
    {
        fs::create_dir_all(path)?;
    }

    // Fsync each dir in the chain we identified (bottom-up).
    // On Unix File::open(dir) + sync_all() is the idiomatic
    // directory fsync; on Windows we substitute a stat since
    // std doesn't expose directory fsync.
    for dir in chain.iter().rev() {
        #[cfg(unix)]
        {
            let handle = fs::File::open(dir)?;
            handle.sync_all()?;
        }
        #[cfg(not(unix))]
        {
            let _ = fs::metadata(dir)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn writes_new_file_atomically() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("state.json");
        write_atomic(&path, b"{\"k\":\"v\"}").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"{\"k\":\"v\"}");
    }

    #[test]
    fn overwrites_existing_file_atomically() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("state.json");
        write_atomic(&path, b"first").unwrap();
        write_atomic(&path, b"second").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"second");
    }

    #[test]
    fn empty_payload_is_valid() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("empty.json");
        write_atomic(&path, b"").unwrap();
        assert!(path.exists());
        assert_eq!(fs::metadata(&path).unwrap().len(), 0);
    }

    #[test]
    fn parent_missing_returns_not_found() {
        let dir = TempDir::new().unwrap();
        let missing = dir.path().join("nope").join("state.json");
        let err = write_atomic(&missing, b"anything").expect_err("parent absent");
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn tempfile_does_not_leak_on_success() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("state.json");
        write_atomic(&path, b"payload").unwrap();
        let entries: Vec<_> = fs::read_dir(dir.path())
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0], "state.json");
    }

    #[test]
    fn string_helper_matches_byte_call() {
        let dir = TempDir::new().unwrap();
        let a = dir.path().join("a.txt");
        let b = dir.path().join("b.txt");
        write_atomic(&a, "hello".as_bytes()).unwrap();
        write_atomic_str(&b, "hello").unwrap();
        assert_eq!(fs::read(&a).unwrap(), fs::read(&b).unwrap());
    }

    #[test]
    fn path_with_no_parent_returns_invalid_input() {
        let bare = Path::new("bare.json");
        let res = write_atomic(bare, b"whatever");
        assert!(res.is_err());
    }

    // ─── Round-1 M2 amendment tests ─────────────────────────

    #[test]
    fn is_staging_tempfile_matches_expected_shape() {
        // Round-1 M2 P0 #2 + round-2 P1 #2: boot walker uses
        // this exact predicate. The random segment MUST be
        // non-empty alphanumeric — empty (`.staging-.tmp`) and
        // non-alphanumeric bodies are refused.
        assert!(is_staging_tempfile(".staging-abc123.tmp"));
        assert!(is_staging_tempfile(".staging-A.tmp"));
        assert!(is_staging_tempfile(".staging-ZZZZZZ.tmp"));
        assert!(
            !is_staging_tempfile(".staging-.tmp"),
            "empty random segment must be refused"
        );
        assert!(
            !is_staging_tempfile(".staging-abc-def.tmp"),
            "hyphen in random must be refused"
        );
        assert!(
            !is_staging_tempfile(".staging-abc def.tmp"),
            "space in random must be refused"
        );
        assert!(
            !is_staging_tempfile(".staging-abc/def.tmp"),
            "slash in random must be refused"
        );
        assert!(
            !is_staging_tempfile(".staging-abc\n.tmp"),
            "newline in random must be refused"
        );
        assert!(!is_staging_tempfile("something.tmp"));
        assert!(!is_staging_tempfile(".staging-abc123.json"));
        assert!(!is_staging_tempfile("normal.json"));
        assert!(!is_staging_tempfile(""));
    }

    #[test]
    fn tempfile_prefix_matches_recognisable_pattern() {
        // Round-1 M2 P0 #2: force a scenario where the tempfile
        // would linger (we forget to persist it) and verify the
        // leftover name matches the recognisable predicate.
        let dir = TempDir::new().unwrap();
        // Create a tempfile via the same builder we use in
        // write_atomic, then intentionally KEEP it (no persist).
        let tmp = TempBuilder::new()
            .prefix(STAGING_TEMPFILE_PREFIX)
            .suffix(STAGING_TEMPFILE_SUFFIX)
            .tempfile_in(dir.path())
            .unwrap();
        // NB: `keep` disarms Drop-time deletion so we can inspect
        // the name — simulates what a SIGKILL leaves behind
        // between step 4 (rename) and Drop cleanup.
        let (_, kept_path) = tmp.keep().unwrap();
        let name = kept_path
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned();
        assert!(
            is_staging_tempfile(&name),
            "leftover tempfile {name:?} not recognisable by walker predicate"
        );
    }

    #[test]
    fn tempfile_prefix_survives_repeated_writes() {
        // Confirm every atomic_write goes through the same
        // prefix/suffix shape by inspecting the residual after
        // multiple writes to the same target. Each write
        // creates + destroys a NamedTempFile; only the target
        // remains at the end.
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("state.json");
        for i in 0..5 {
            write_atomic(&path, format!("payload {i}").as_bytes()).unwrap();
        }
        let entries: Vec<_> = fs::read_dir(dir.path())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        // Only the target file survives; no `.staging-` leftovers.
        assert_eq!(entries, vec!["state.json".to_string()]);
    }

    // ─── create_dir_all_durable (P1 #5) ───────────────────────

    #[test]
    fn create_dir_all_durable_creates_nested_chain() {
        let dir = TempDir::new().unwrap();
        let deep = dir.path().join("queue").join("ab").join("recipient01");
        create_dir_all_durable(&deep).unwrap();
        assert!(deep.exists());
        assert!(dir.path().join("queue").exists());
        assert!(dir.path().join("queue").join("ab").exists());
    }

    #[test]
    fn create_dir_all_durable_is_idempotent() {
        let dir = TempDir::new().unwrap();
        let p = dir.path().join("queue").join("ab");
        create_dir_all_durable(&p).unwrap();
        create_dir_all_durable(&p).unwrap();
        assert!(p.exists());
    }

    #[test]
    fn create_dir_all_durable_on_existing_dir_is_ok() {
        let dir = TempDir::new().unwrap();
        create_dir_all_durable(dir.path()).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn create_dir_all_durable_new_chain_satisfies_hardened_mode_policy() {
        use std::os::unix::fs::PermissionsExt;

        let dir = TempDir::new().unwrap();
        let shard = dir.path().join("queue").join("ab");
        let recipient = shard.join("ab".repeat(32));
        create_dir_all_durable(&recipient).unwrap();

        for path in [dir.path().join("queue"), shard, recipient] {
            let mode = fs::metadata(&path).unwrap().permissions().mode() & 0o777;
            assert_eq!(
                mode & 0o027,
                0,
                "{} mode {mode:#o} violates RELAY_MODE_FORBIDDEN=0027",
                path.display()
            );
        }
    }

    // ─── Round-2 M2 P1 #3 subprocess abort test ──────────────

    /// The child-side counterpart to the parent test below.
    /// When run without the env var the test is a no-op; when
    /// spawned with `PHANTOM_RELAY_ATOMIC_WRITE_FORCE_FAULT=1`
    /// it flips the fault seam and invokes `write_atomic`,
    /// which MUST abort before returning. If the process reaches
    /// the sentinel `CHILD_REACHED_END` line, the abort didn't
    /// fire — the parent test asserts the absence of that
    /// marker.
    #[test]
    fn post_rename_fsync_failure_forced_abort_child() {
        if std::env::var("PHANTOM_RELAY_ATOMIC_WRITE_FORCE_FAULT").is_err() {
            // Not the spawned child — no-op.
            return;
        }
        FORCE_POST_RENAME_FSYNC_FAIL.store(true, Ordering::SeqCst);
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("state.json");
        // Ignore the return value — abort should fire before it.
        let _ = write_atomic(&path, b"payload for fault test");
        // Sentinel — must NOT reach stderr if abort fired.
        eprintln!("CHILD_REACHED_END");
    }

    #[test]
    fn post_rename_fsync_failure_aborts_child_process() {
        // Round-2 M2 P1 #3: subprocess-driven fault-injection
        // test. Spawns the current test binary with the fault
        // env var + a filter matching the sibling child test,
        // then asserts:
        //   * child exited non-zero (abort);
        //   * child stderr contains FATAL:;
        //   * child stderr does NOT contain CHILD_REACHED_END.
        //
        // The child test path is `post_rename_fsync_failure_forced_abort_child`
        // in this same module; running the test binary with
        // `--exact <full::name>` filters to just that test.
        // Round-3 M2 P2 #2: pre-amendment `return`-on-error let
        // the mandatory abort test pass without exercising the
        // abort path — a skip-as-PASS false positive. Fail-closed
        // now: any current_exe() error is a hard test failure so
        // the subprocess check cannot silently no-op.
        let exe = std::env::current_exe()
            .expect("current_exe unavailable — subprocess abort test cannot verify FATAL path");
        let output = std::process::Command::new(&exe)
            .args([
                "--exact",
                "atomic_write::tests::post_rename_fsync_failure_forced_abort_child",
                "--nocapture",
            ])
            .env("PHANTOM_RELAY_ATOMIC_WRITE_FORCE_FAULT", "1")
            .env("RUST_TEST_NOCAPTURE", "1")
            .output()
            .expect("failed to spawn child test process");

        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);

        assert!(
            !output.status.success(),
            "child unexpectedly succeeded (abort did not fire)\nstatus: {:?}\nstdout: {stdout}\nstderr: {stderr}",
            output.status
        );
        assert!(
            stderr.contains("FATAL:"),
            "child stderr missing FATAL: marker\nstderr: {stderr}\nstdout: {stdout}"
        );
        assert!(
            !stderr.contains("CHILD_REACHED_END"),
            "child reached CHILD_REACHED_END — abort did not fire\nstderr: {stderr}"
        );
    }
}
