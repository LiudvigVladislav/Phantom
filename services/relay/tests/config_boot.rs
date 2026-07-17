// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! PR-0 A-5 boot-time validation of `RELAY_STATE_DIR`.
//!
//! `RelayConfig::from_env()` now returns `Result<RelayConfig, ConfigError>`
//! so a misconfigured state-dir env var fails FATAL at boot instead of
//! silently landing state files at an unpredictable location under the
//! `read_only` rootfs.
//!
//! Env-var mutation is serialised via `serial_test::serial` — parallel
//! test workers must not race on the process-global env.
//!
//! Fixture requirements. `RelayConfig::from_env` also loads
//! `RELAY_SEQ_MAC_KEY` via `load_seq_mac_root_key_from_env`, which
//! shortcuts to `std::process::exit(2)` when the env var is missing
//! (killing the test binary). Every case in this file therefore holds a
//! process-global mutex, temporarily sets a valid all-zero test key,
//! runs the case, and restores the prior env exactly. The exit(12)
//! contract for `InvalidStateDir` is pinned separately in the
//! subprocess test at the bottom of this file so the actual binary
//! exit code + FATAL stderr line are asserted for real.
//!
//! Downstream symlink behaviour at `RELAY_STATE_DIR` is intentionally
//! NOT exercised here — that is PR-2's boot-loader scope, not PR-0
//! config-time scope. A-5 only enforces the syntactic guards
//! `is_absolute()` + no-`ParentDir`.

use phantom_relay::config::{ConfigError, RelayConfig};
use serial_test::serial;
use std::sync::Mutex;

/// All-zero test key. Passes `SeqMacRootKey::from_hex` (64 lowercase
/// hex chars) so `load_seq_mac_root_key_from_env` inside
/// `RelayConfig::from_env` completes without exiting the process.
/// Never used to actually MAC anything — the tests here don't reach
/// the crypto surface.
const TEST_SEQ_MAC_KEY: &str = "0000000000000000000000000000000000000000000000000000000000000000";

/// Process-wide mutex around every env-var mutation in this file. Even
/// with `#[serial]` this belt-and-braces guards against future test
/// runners that ignore the attribute.
static ENV_LOCK: Mutex<()> = Mutex::new(());

/// Run `f` with `RELAY_STATE_DIR` set to `state_dir` (or unset if
/// `None`) AND `RELAY_SEQ_MAC_KEY` set to a valid all-zero test key.
/// Prior env restored exactly on return, including panics inside `f`
/// would leak env changes — but tests are `#[serial]`, so any panic
/// terminates the run before another case observes the leak.
fn with_state_dir<T, F>(state_dir: Option<&str>, f: F) -> T
where
    F: FnOnce() -> T,
{
    let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let prior_state_dir = std::env::var("RELAY_STATE_DIR").ok();
    let prior_seq_mac = std::env::var("RELAY_SEQ_MAC_KEY").ok();
    // Safety: single-threaded inside the ENV_LOCK region for this test
    // file. Other test files reading RELAY_STATE_DIR are covered by
    // `#[serial]` at the case level.
    unsafe {
        std::env::set_var("RELAY_SEQ_MAC_KEY", TEST_SEQ_MAC_KEY);
        match state_dir {
            Some(v) => std::env::set_var("RELAY_STATE_DIR", v),
            None => std::env::remove_var("RELAY_STATE_DIR"),
        }
    }
    let out = f();
    unsafe {
        match prior_state_dir {
            Some(p) => std::env::set_var("RELAY_STATE_DIR", p),
            None => std::env::remove_var("RELAY_STATE_DIR"),
        }
        match prior_seq_mac {
            Some(p) => std::env::set_var("RELAY_SEQ_MAC_KEY", p),
            None => std::env::remove_var("RELAY_SEQ_MAC_KEY"),
        }
    }
    out
}

// Absolute-path acceptance cases pin the Linux production shape
// (`/var/phantom` under Docker WORKDIR + volume mount). On Windows
// PathBuf::is_absolute() rejects `/var/phantom` because a Windows
// absolute path requires a drive letter (`C:\...`). Relay ships in a
// Linux container in production; the accept-path tests are gated to
// unix targets. The reject-path tests use inputs that fail
// is_absolute() on BOTH platforms (relative + empty + bare name) so
// they exercise the validator on either host.

#[cfg(unix)]
#[test]
#[serial]
fn state_dir_default_when_unset() {
    let cfg = with_state_dir(None, RelayConfig::from_env)
        .expect("default `/var/phantom` must pass on unix");
    assert_eq!(cfg.state_dir, std::path::PathBuf::from("/var/phantom"));
}

#[cfg(unix)]
#[test]
#[serial]
fn state_dir_absolute_unix_ok() {
    let cfg = with_state_dir(Some("/var/phantom"), RelayConfig::from_env)
        .expect("`/var/phantom` must pass on unix");
    assert_eq!(cfg.state_dir, std::path::PathBuf::from("/var/phantom"));
}

#[test]
#[serial]
fn state_dir_relative_rejected() {
    let err = with_state_dir(Some("./phantom"), RelayConfig::from_env)
        .expect_err("relative path must be rejected");
    match err {
        ConfigError::InvalidStateDir { raw, reason } => {
            assert_eq!(raw, "./phantom");
            assert!(
                reason.contains("absolute"),
                "reason must mention `absolute`, got {reason:?}",
            );
        }
    }
}

#[test]
#[serial]
fn state_dir_bare_relative_rejected() {
    let err = with_state_dir(Some("phantom"), RelayConfig::from_env)
        .expect_err("bare relative name must be rejected");
    match err {
        ConfigError::InvalidStateDir { raw, reason } => {
            assert_eq!(raw, "phantom");
            assert!(reason.contains("absolute"));
        }
    }
}

#[test]
#[serial]
fn state_dir_empty_string_rejected() {
    // Empty env value → PathBuf("") → not absolute → reject.
    let err = with_state_dir(Some(""), RelayConfig::from_env)
        .expect_err("empty env value must be rejected as non-absolute");
    match err {
        ConfigError::InvalidStateDir { raw, reason } => {
            assert_eq!(raw, "");
            assert!(reason.contains("absolute"));
        }
    }
}

// `..`-component rejection MUST fire AFTER the absolute-path check
// passes — so use a Unix absolute path with an embedded `..`. Gated to
// unix targets for the same reason as the accept-path cases above.
#[cfg(unix)]
#[test]
#[serial]
fn state_dir_parent_component_rejected() {
    let err = with_state_dir(Some("/var/../etc"), RelayConfig::from_env)
        .expect_err("`..` component must be rejected");
    match err {
        ConfigError::InvalidStateDir { raw, reason } => {
            assert_eq!(raw, "/var/../etc");
            assert!(
                reason.contains(".."),
                "reason must mention `..`, got {reason:?}",
            );
        }
    }
}

#[cfg(unix)]
#[test]
#[serial]
fn state_dir_parent_at_end_rejected() {
    // Trailing `..` variant — same rejection reason.
    let err = with_state_dir(Some("/var/phantom/.."), RelayConfig::from_env)
        .expect_err("trailing `..` component must be rejected");
    match err {
        ConfigError::InvalidStateDir { reason, .. } => {
            assert!(reason.contains(".."));
        }
    }
}

#[cfg(unix)]
#[test]
#[serial]
fn state_dir_deeply_nested_absolute_ok() {
    let cfg = with_state_dir(
        Some("/var/phantom/deep/nested/state"),
        RelayConfig::from_env,
    )
    .expect("nested absolute path must pass on unix");
    assert_eq!(
        cfg.state_dir,
        std::path::PathBuf::from("/var/phantom/deep/nested/state"),
    );
}

// ─────────────────────────────────────────────────────────────────────
// Subprocess exit-code + FATAL-stderr pin (round-1 review P1-1).
// The in-process tests above prove the Result-typed contract; this
// test spawns the actual `phantom-relay` binary and asserts the
// operator-visible exit contract PR-0 A-5 promises:
//   * exit code 12 (reserved distinct from PR-2's exit 10 = replay
//     quarantine, exit 11 = tombstone config)
//   * a `FATAL: relay boot config invalid:` line on stderr
// so an operator staring at `docker compose up` sees exactly why the
// container refused to start.
// ─────────────────────────────────────────────────────────────────────

#[cfg(unix)]
#[test]
#[serial]
fn state_dir_relative_process_exits_12_with_fatal_stderr() {
    use std::process::{Command, Stdio};

    // Path to the relay binary Cargo built for integration tests.
    let bin = env!("CARGO_BIN_EXE_phantom-relay");

    // Fresh env with the two guards flipped: valid SEQ_MAC key so we
    // reach A-5, invalid state_dir so A-5 fires. Everything else the
    // relay might read (ntfy url, admin token, etc) stays unset — the
    // exit fires before any of those matter.
    let output = Command::new(bin)
        .env_clear()
        .env("PATH", std::env::var("PATH").unwrap_or_default())
        .env("RELAY_SEQ_MAC_KEY", TEST_SEQ_MAC_KEY)
        .env("RELAY_STATE_DIR", "./relative-path-should-be-rejected")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .expect("must spawn phantom-relay binary");

    assert!(
        !output.status.success(),
        "relay must NOT start with a relative RELAY_STATE_DIR — stdout={:?} stderr={:?}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let exit_code = output.status.code().expect("must have an exit code");
    assert_eq!(
        exit_code, 12,
        "A-5 must exit with code 12 (reserved for config-time errors); \
         got {exit_code}. stderr={:?}",
        String::from_utf8_lossy(&output.stderr),
    );
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("FATAL: relay boot config invalid"),
        "expected FATAL stderr line, got: {stderr:?}",
    );
    assert!(
        stderr.contains("RELAY_STATE_DIR"),
        "FATAL line must name RELAY_STATE_DIR, got: {stderr:?}",
    );
    assert!(
        stderr.contains("absolute"),
        "FATAL line must explain the failure reason, got: {stderr:?}",
    );
}
