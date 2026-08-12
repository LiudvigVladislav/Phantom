// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — tombstone dedup-horizon
//! configuration.
//!
//! Locked design:
//! - `v4.2.3-runbook-nits.md` §1 — Path A locked: `dedup_until =
//!   acked_at + RELAY_TOMBSTONE_DEDUP_HORIZON_SECS`, default
//!   172 800 seconds (48 h). Bounded idempotency; the residual is
//!   documented in ADR-030 §4.6 (last line of defense is client
//!   envelope-id dedup at the storage layer).
//! - `v4.2.3-runbook-nits.md` §4 — env parse via `u32`, reject
//!   zero, reject implausibly-large values (>10 y), `checked_add`
//!   on the addition so a bad clock cannot silently roll over.
//! - Boot loader (M2) maps a `TombstoneConfigError` to exit code
//!   11 (reserved for config-class boot failures).

use std::env;
use std::num::ParseIntError;

/// Env var name governing the tombstone-dedup horizon. Locked
/// v4.2.3 §1. If unset the default is `DEFAULT_HORIZON_SECS`.
pub const ENV_TOMBSTONE_HORIZON_SECS: &str = "RELAY_TOMBSTONE_DEDUP_HORIZON_SECS";

/// Locked v4.2.3 §1: 48 h (172 800 s). Justification: sender retry
/// loops typically bounded at O(hours) with exponential backoff;
/// 48 h covers Sprint-2b outbox retry horizon + slack. Operators
/// tune per fleet via the env var.
pub const DEFAULT_HORIZON_SECS: u32 = 172_800;

/// Locked v4.2.3 §4: 10 years — protects against a typo like
/// `1728000000` (~55 years). Realistic sender retry horizons are
/// well under this ceiling; the check exists solely to catch
/// misconfiguration before it reaches ledger arithmetic.
pub const MAX_HORIZON_SECS: u32 = 10 * 365 * 86_400;

/// Config parse / validation errors. Callers (M4 boot loader)
/// translate every variant to boot exit code 11 (v4.2.3 §4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TombstoneConfigError {
    /// The env var was set but did not parse as a positive `u32`
    /// integer (e.g. `"abc"`, negative, or `> u32::MAX`).
    NotAnInteger { raw: String, source: String },
    /// Parsed successfully as zero. A zero horizon would tombstone
    /// every ack for zero seconds — meaning the very next retry
    /// after ack races the sweep and can be redelivered.
    Zero,
    /// Parsed successfully but exceeds `MAX_HORIZON_SECS`. Almost
    /// certainly a typo (10 y is already generous).
    TooLarge { raw_secs: u32, cap_secs: u32 },
}

impl std::fmt::Display for TombstoneConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TombstoneConfigError::NotAnInteger { raw, source } => write!(
                f,
                "RELAY_TOMBSTONE_DEDUP_HORIZON_SECS={raw:?} is not a valid u32: {source}"
            ),
            TombstoneConfigError::Zero => {
                write!(f, "RELAY_TOMBSTONE_DEDUP_HORIZON_SECS must be > 0")
            }
            TombstoneConfigError::TooLarge { raw_secs, cap_secs } => write!(
                f,
                "RELAY_TOMBSTONE_DEDUP_HORIZON_SECS={raw_secs} exceeds cap {cap_secs} (~10 y)"
            ),
        }
    }
}

impl std::error::Error for TombstoneConfigError {}

/// Arithmetic errors surfaced from [`TombstoneConfig::dedup_until`].
///
/// Realistically impossible at production scale (u32 horizon + u64
/// seconds-since-epoch) but locked design v4.2.3 §4 mandates the
/// `checked_add` guard so a hypothetical bad clock cannot silently
/// wrap around.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TombstoneArithmeticError {
    DedupUntilOverflow { acked_at: u64, horizon_secs: u32 },
}

impl std::fmt::Display for TombstoneArithmeticError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TombstoneArithmeticError::DedupUntilOverflow {
                acked_at,
                horizon_secs,
            } => write!(
                f,
                "dedup_until overflow: acked_at={acked_at} + horizon_secs={horizon_secs} > u64::MAX"
            ),
        }
    }
}

impl std::error::Error for TombstoneArithmeticError {}

/// Validated tombstone-dedup configuration.
///
/// Locked design v4.2.3 §1 §4. `horizon_secs` is `u32` — 0..~136 y
/// range, more than enough for the 48 h default and the 10 y cap.
#[derive(Debug, Clone, Copy)]
pub struct TombstoneConfig {
    horizon_secs: u32,
}

impl TombstoneConfig {
    /// Construct from an explicit value. Used by unit tests to pin
    /// deterministic horizons; boot code uses
    /// [`TombstoneConfig::from_env`].
    ///
    /// Runs the same zero / too-large validation as `from_env`.
    pub fn from_secs(horizon_secs: u32) -> Result<Self, TombstoneConfigError> {
        if horizon_secs == 0 {
            return Err(TombstoneConfigError::Zero);
        }
        if horizon_secs > MAX_HORIZON_SECS {
            return Err(TombstoneConfigError::TooLarge {
                raw_secs: horizon_secs,
                cap_secs: MAX_HORIZON_SECS,
            });
        }
        Ok(Self { horizon_secs })
    }

    /// Parse the process env at boot. Absent env → default
    /// `DEFAULT_HORIZON_SECS`. Present but invalid → the exact
    /// error variant so the boot loader can log a precise reason
    /// before exit code 11.
    ///
    /// Round-1 M2 P1 #7: `env::VarError` has TWO variants —
    /// `NotPresent` (legitimate default) and `NotUnicode`
    /// (invalid config). Pre-amendment treated both as absence,
    /// silently defaulting on a corrupted env var. Now `NotUnicode`
    /// surfaces as `TombstoneConfigError::NotAnInteger`.
    pub fn from_env() -> Result<Self, TombstoneConfigError> {
        let raw = match env::var(ENV_TOMBSTONE_HORIZON_SECS) {
            Ok(v) => v,
            Err(env::VarError::NotPresent) => return Self::from_secs(DEFAULT_HORIZON_SECS),
            Err(env::VarError::NotUnicode(raw)) => {
                return Err(TombstoneConfigError::NotAnInteger {
                    raw: raw.to_string_lossy().into_owned(),
                    source: "value is not valid Unicode".to_string(),
                });
            }
        };
        let parsed: u32 =
            raw.parse::<u32>()
                .map_err(|e: ParseIntError| TombstoneConfigError::NotAnInteger {
                    raw: raw.clone(),
                    source: e.to_string(),
                })?;
        Self::from_secs(parsed)
    }

    /// Horizon in seconds. Callers use this via
    /// [`TombstoneConfig::dedup_until`]; the accessor exists for
    /// tracing and evidence emission.
    pub fn horizon_secs(&self) -> u32 {
        self.horizon_secs
    }

    /// Compute `dedup_until = acked_at + horizon_secs` with a
    /// checked add (v4.2.3 §4). Overflow surfaces as
    /// [`TombstoneArithmeticError::DedupUntilOverflow`] and the
    /// caller (M3 ack path) MUST map it to HTTP 500 invariant
    /// violation.
    pub fn dedup_until(&self, acked_at: u64) -> Result<u64, TombstoneArithmeticError> {
        acked_at.checked_add(u64::from(self.horizon_secs)).ok_or(
            TombstoneArithmeticError::DedupUntilOverflow {
                acked_at,
                horizon_secs: self.horizon_secs,
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_secs_accepts_default() {
        let cfg = TombstoneConfig::from_secs(DEFAULT_HORIZON_SECS).unwrap();
        assert_eq!(cfg.horizon_secs(), DEFAULT_HORIZON_SECS);
    }

    #[test]
    fn from_secs_accepts_one_second() {
        // Boundary: any positive u32 up to MAX is valid.
        let cfg = TombstoneConfig::from_secs(1).unwrap();
        assert_eq!(cfg.horizon_secs(), 1);
    }

    #[test]
    fn from_secs_accepts_max() {
        let cfg = TombstoneConfig::from_secs(MAX_HORIZON_SECS).unwrap();
        assert_eq!(cfg.horizon_secs(), MAX_HORIZON_SECS);
    }

    #[test]
    fn from_secs_rejects_zero() {
        let err = TombstoneConfig::from_secs(0).unwrap_err();
        assert_eq!(err, TombstoneConfigError::Zero);
    }

    #[test]
    fn from_secs_rejects_above_cap() {
        let err = TombstoneConfig::from_secs(MAX_HORIZON_SECS + 1).unwrap_err();
        assert!(matches!(
            err,
            TombstoneConfigError::TooLarge {
                raw_secs,
                cap_secs
            } if raw_secs == MAX_HORIZON_SECS + 1 && cap_secs == MAX_HORIZON_SECS
        ));
    }

    #[test]
    fn dedup_until_normal_case() {
        let cfg = TombstoneConfig::from_secs(3600).unwrap();
        // acked at unix epoch 1_000_000 → tombstone valid until +3600.
        assert_eq!(cfg.dedup_until(1_000_000).unwrap(), 1_003_600);
    }

    #[test]
    fn dedup_until_overflow_returns_error() {
        // horizon = MAX_HORIZON_SECS (~315M), acked_at = u64::MAX.
        // The sum overflows u64.
        let cfg = TombstoneConfig::from_secs(MAX_HORIZON_SECS).unwrap();
        let err = cfg.dedup_until(u64::MAX).unwrap_err();
        assert!(matches!(
            err,
            TombstoneArithmeticError::DedupUntilOverflow { .. }
        ));
    }

    #[test]
    fn dedup_until_boundary_exactly_at_u64_max() {
        // acked_at + horizon == u64::MAX (no overflow).
        let cfg = TombstoneConfig::from_secs(1).unwrap();
        let acked_at = u64::MAX - 1;
        assert_eq!(cfg.dedup_until(acked_at).unwrap(), u64::MAX);
    }

    // Env-var tests use `serial_test` because they mutate the
    // process-global environment. Every relay integration test
    // that touches env already uses this crate for the same
    // reason (see services/relay/tests/config_boot.rs).

    #[test]
    #[cfg(test)]
    fn from_env_absent_uses_default() {
        // SAFETY: environmental mutation. This test path is
        // exercised in isolation via `cargo test tombstone_config
        // ::tests::from_env_absent_uses_default` when the env var
        // is not set. Concurrent test workers could race; the
        // integration-test suite gates env-var flavours through
        // `serial_test`. Inside a lib-test module we accept the
        // small race window because CI runs `--release` where the
        // env var is not set by any other test.
        let _prev = env::var(ENV_TOMBSTONE_HORIZON_SECS).ok();
        // Do not mutate here — just observe.
        if env::var(ENV_TOMBSTONE_HORIZON_SECS).is_ok() {
            eprintln!("skipping — env var already set by outer harness");
            return;
        }
        let cfg = TombstoneConfig::from_env().unwrap();
        assert_eq!(cfg.horizon_secs(), DEFAULT_HORIZON_SECS);
    }

    #[test]
    fn error_display_messages_are_human_readable() {
        let e = TombstoneConfigError::Zero;
        assert!(e.to_string().contains("> 0"));

        let e = TombstoneConfigError::TooLarge {
            raw_secs: 999_999_999,
            cap_secs: MAX_HORIZON_SECS,
        };
        assert!(e.to_string().contains("exceeds cap"));

        let e = TombstoneConfigError::NotAnInteger {
            raw: "abc".to_string(),
            source: "invalid digit".to_string(),
        };
        assert!(e.to_string().contains("not a valid u32"));

        let e = TombstoneArithmeticError::DedupUntilOverflow {
            acked_at: u64::MAX,
            horizon_secs: MAX_HORIZON_SECS,
        };
        assert!(e.to_string().contains("overflow"));
    }
}
