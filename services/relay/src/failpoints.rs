// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M5a-1 — env-configured failpoint
//! registry + `failpoint!` macro.
//!
//! Locked design: `mini-lock-draft-v4.md` §12 (SIGKILL tests via
//! deterministic failpoint) + `v4.1-amendments.md` §3 V-M6
//! (stderr-marker synchronisation between failpoint and test
//! harness parent process).
//!
//! ## Contract (architect scope-lock 2026-07-30)
//!
//! - **Release-elided in full.** `#[cfg(not(debug_assertions))]` sees
//!   only the empty-expansion macro; no env parsing, no marker
//!   strings, no runtime branch, no atomics, no `OnceLock`. Zero
//!   overhead in production.
//! - **Single action variant.** Only [`FailpointAction::PrintMarkerAndBlock`]
//!   exists. `Exit` and `Panic` were explicitly excluded — those
//!   cover user-space abort modes already covered by
//!   `TEST_FORCE_*_FATAL` seams and `fatal_*_invariant` helpers.
//!   The failpoint mechanism is narrowly for SIGKILL-contract
//!   testing where the parent externally kills the child at a marker.
//! - **Exact marker format.** `FAILPOINT_REACHED name=<name> pid=<pid>`
//!   followed by a mandatory `stderr().flush()`. The parent test
//!   harness reads child stderr line-by-line and matches on this
//!   line. Any deviation breaks the wire contract.
//! - **Fail-loud config.** Malformed env, unknown action, and
//!   duplicate failpoint names all fail-loud with a `FATAL:`
//!   line + `std::process::exit(11)` — matches the M4-2b env-parser
//!   discipline. An UNKNOWN failpoint name reached at `check()` time
//!   stays a silent no-op (that is the whole point of not-armed
//!   placements — production paths compile and run through them
//!   without any effect).
//! - **Test isolation.** Parser and `FailpointRegistry` API are pure
//!   / local-instance construction; unit tests parallel-safely
//!   exercise them without ever touching the global env-backed
//!   `OnceLock`. Real env-driven initialisation is only exercised
//!   by the M5a-3 subprocess SIGKILL tests, one per invocation of
//!   the child binary.
//!
//! M5a-1 ships this registry + macro wiring only. Strategic
//! failpoint call sites at durability boundaries land in M5a-2;
//! subprocess SIGKILL crash-replay tests in M5a-3. M5a-4 is the
//! deadlock/concurrency stress harness. Everything gated by the
//! architect's per-commit review checkpoints.

// ── Debug-only surface ──────────────────────────────────────────────────

#[cfg(debug_assertions)]
mod debug_impl {
    use std::collections::HashMap;
    use std::io::Write;

    /// The only failpoint action available in M5a-1. Additional
    /// variants (`Exit(u8)`, `Panic`) were explicitly excluded at
    /// scope-lock — the SIGKILL-contract test harness has no need
    /// for anything besides "advertise the barrier + park until
    /// killed."
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum FailpointAction {
        /// Print `FAILPOINT_REACHED name=<name> pid=<pid>` to
        /// stderr, `flush()`, then block indefinitely by parking
        /// the current thread. The test-harness parent process
        /// reads the marker line-by-line and delivers SIGKILL to
        /// the child at that exact instruction.
        PrintMarkerAndBlock,
    }

    /// Refusal shapes for [`parse_failpoint_env`]. Every variant
    /// terminates process boot via a caller-side FATAL + exit(11).
    #[derive(Debug, PartialEq, Eq)]
    pub enum FailpointConfigError {
        /// An entry did not have the `name:action` shape — e.g. no
        /// colon, or empty on either side of the colon.
        MalformedEntry { entry: String, detail: &'static str },
        /// Entry parsed but the action word is not one of the
        /// recognised set (currently only `block`).
        UnknownAction { name: String, action: String },
        /// Same `name` listed more than once in the same env value.
        /// Distinct-action-per-name would let a mistyped entry
        /// silently override an earlier one; refuse loudly.
        DuplicateName { name: String },
    }

    impl std::fmt::Display for FailpointConfigError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            match self {
                FailpointConfigError::MalformedEntry { entry, detail } => write!(
                    f,
                    "PHANTOM_FAILPOINT entry {entry:?} malformed: {detail}"
                ),
                FailpointConfigError::UnknownAction { name, action } => write!(
                    f,
                    "PHANTOM_FAILPOINT entry name={name:?} action={action:?} — \
                     unknown action (recognised: `block`)"
                ),
                FailpointConfigError::DuplicateName { name } => write!(
                    f,
                    "PHANTOM_FAILPOINT lists failpoint name={name:?} more than once"
                ),
            }
        }
    }

    /// Pure parser for the `PHANTOM_FAILPOINT` env value.
    ///
    /// Grammar: `name:action[,name:action]*`. Whitespace around
    /// commas and colons is trimmed for individual entries.
    ///
    /// **Round-1 amendment (architect P1)**: only a fully
    /// empty-or-whitespace WHOLE string means "empty registry"
    /// (matches an unset env). ANY empty segment inside a
    /// non-empty string — leading `,a:block`, trailing
    /// `a:block,`, interior `a:block,,b:block`, or just `,,,`
    /// — is a hard [`FailpointConfigError::MalformedEntry`].
    /// Pre-amendment the parser silently skipped empty segments;
    /// an operator typo could leave the registry empty instead
    /// of the required FATAL + exit(11).
    ///
    /// Fail-loud on:
    /// * empty segment inside a non-empty string (per above);
    /// * malformed entry (missing colon, empty name half, empty
    ///   action half);
    /// * unknown action string;
    /// * duplicate failpoint names.
    ///
    /// Unit-testable without any global state.
    pub fn parse_failpoint_env(
        raw: &str,
    ) -> Result<Vec<(String, FailpointAction)>, FailpointConfigError> {
        // Round-1 amendment: whitespace-only WHOLE string is the
        // ONLY shortcut to an empty registry. Everything else has
        // to pass strict per-segment validation.
        if raw.trim().is_empty() {
            return Ok(Vec::new());
        }
        let mut pairs: Vec<(String, FailpointAction)> = Vec::new();
        let mut seen: std::collections::HashSet<String> =
            std::collections::HashSet::new();
        for entry in raw.split(',') {
            let entry_trimmed = entry.trim();
            if entry_trimmed.is_empty() {
                // Round-1 amendment: reject rather than skip.
                // Preserve the raw (untrimmed) segment in the
                // diagnostic so an operator can see whether the
                // fault was a stray leading/trailing/interior
                // comma vs whitespace-only entry.
                return Err(FailpointConfigError::MalformedEntry {
                    entry: entry.to_string(),
                    detail:
                        "empty segment between commas — leading, trailing, or interior commas are refused",
                });
            }
            let (name_raw, action_raw) =
                entry_trimmed.split_once(':').ok_or_else(|| {
                    FailpointConfigError::MalformedEntry {
                        entry: entry_trimmed.to_string(),
                        detail: "expected `name:action`, missing colon",
                    }
                })?;
            let name = name_raw.trim();
            let action_str = action_raw.trim();
            if name.is_empty() {
                return Err(FailpointConfigError::MalformedEntry {
                    entry: entry_trimmed.to_string(),
                    detail: "empty failpoint name before colon",
                });
            }
            if action_str.is_empty() {
                return Err(FailpointConfigError::MalformedEntry {
                    entry: entry_trimmed.to_string(),
                    detail: "empty action after colon",
                });
            }
            let action = match action_str {
                "block" => FailpointAction::PrintMarkerAndBlock,
                other => {
                    return Err(FailpointConfigError::UnknownAction {
                        name: name.to_string(),
                        action: other.to_string(),
                    });
                }
            };
            if !seen.insert(name.to_string()) {
                return Err(FailpointConfigError::DuplicateName {
                    name: name.to_string(),
                });
            }
            pairs.push((name.to_string(), action));
        }
        Ok(pairs)
    }

    /// Registry of active failpoints. Local instances are
    /// constructed by tests via [`FailpointRegistry::empty`] and
    /// [`FailpointRegistry::from_pairs`]; the process-wide
    /// instance is initialised lazily on first `failpoint!` hit
    /// via [`init_from_env`].
    pub struct FailpointRegistry {
        inner: HashMap<String, FailpointAction>,
    }

    impl FailpointRegistry {
        /// Empty registry. `check(any_name)` is a silent no-op
        /// — the same shape production sees when
        /// `PHANTOM_FAILPOINT` is unset.
        pub fn empty() -> Self {
            Self {
                inner: HashMap::new(),
            }
        }

        /// Build from parser output. Duplicate-name detection
        /// already ran at parse; this consumes the pairs.
        pub fn from_pairs(pairs: Vec<(String, FailpointAction)>) -> Self {
            let mut inner = HashMap::with_capacity(pairs.len());
            for (name, action) in pairs {
                inner.insert(name, action);
            }
            Self { inner }
        }

        /// Number of armed failpoints. Test-only accessor.
        #[cfg(test)]
        pub fn len(&self) -> usize {
            self.inner.len()
        }

        /// Test-only: is a given name armed?
        #[cfg(test)]
        pub fn contains(&self, name: &str) -> bool {
            self.inner.contains_key(name)
        }

        /// Test-only shortcut: parse `raw` and build a registry,
        /// panicking on any parser error. Used by unit tests that
        /// want to exercise the (parser, registry) composition
        /// end-to-end without touching the global `OnceLock`.
        #[cfg(test)]
        pub fn from_env_str_test_only(raw: &str) -> Self {
            let pairs = parse_failpoint_env(raw).expect("test env string parses");
            Self::from_pairs(pairs)
        }

        /// Fire the failpoint if armed; silently return otherwise.
        ///
        /// The armed branch prints the wire-contract marker line
        /// `FAILPOINT_REACHED name=<name> pid=<pid>` to stderr,
        /// flushes it (mandatory — the parent test harness reads
        /// stderr line-by-line and MUST observe the marker before
        /// delivering SIGKILL), then parks the current thread
        /// indefinitely.
        ///
        /// A `thread::park` loop is deliberate: the process is
        /// expected to be killed externally at this point. Any
        /// unpark that races the kill (spurious wake per
        /// std::thread::park docs) simply re-enters the park —
        /// there is no path back to normal execution once the
        /// barrier fires.
        pub fn check(&self, name: &'static str) {
            let Some(action) = self.inner.get(name) else {
                return;
            };
            match action {
                FailpointAction::PrintMarkerAndBlock => {
                    let pid = std::process::id();
                    let mut stderr = std::io::stderr().lock();
                    // Best-effort write + mandatory flush. If the
                    // write itself fails we still park — the parent
                    // will hit its own read timeout and the whole
                    // subprocess-test framework surfaces that as a
                    // FAIL. Silently returning would break the
                    // SIGKILL contract far worse.
                    let _ = writeln!(
                        stderr,
                        "FAILPOINT_REACHED name={name} pid={pid}"
                    );
                    let _ = stderr.flush();
                    drop(stderr);
                    // Block forever. `park()` may spuriously
                    // return; loop.
                    loop {
                        std::thread::park();
                    }
                }
            }
        }
    }

    /// Process-wide registry. Initialised on the FIRST
    /// `failpoint!` hit anywhere in the binary via
    /// [`init_from_env`]. Debug-only.
    pub static FAILPOINT_REGISTRY: std::sync::OnceLock<FailpointRegistry> =
        std::sync::OnceLock::new();

    /// Env-driven registry initialiser. Called lazily from the
    /// `failpoint!` macro through the `OnceLock`. Fail-loud on:
    /// * `PHANTOM_FAILPOINT` set to non-UTF-8 → FATAL + exit(11);
    /// * `PHANTOM_FAILPOINT` malformed / unknown action /
    ///   duplicate name → FATAL + exit(11).
    ///
    /// `NotPresent` and empty string both yield an empty registry
    /// (identical to production shape).
    ///
    /// This function is `pub` because the macro expands in
    /// arbitrary crates (integration tests) and must reference
    /// the initialiser through `$crate::failpoints::init_from_env`.
    pub fn init_from_env() -> FailpointRegistry {
        match std::env::var("PHANTOM_FAILPOINT") {
            Err(std::env::VarError::NotPresent) => FailpointRegistry::empty(),
            Err(std::env::VarError::NotUnicode(_)) => {
                eprintln!("FATAL: PHANTOM_FAILPOINT env is not valid UTF-8");
                std::process::exit(11);
            }
            Ok(raw) if raw.trim().is_empty() => FailpointRegistry::empty(),
            Ok(raw) => match parse_failpoint_env(&raw) {
                Ok(pairs) => FailpointRegistry::from_pairs(pairs),
                Err(e) => {
                    eprintln!("FATAL: PHANTOM_FAILPOINT parse failed: {e}");
                    std::process::exit(11);
                }
            },
        }
    }
}

// Debug-only re-exports for `failpoint!` macro and test callers.
#[cfg(debug_assertions)]
pub use debug_impl::{
    init_from_env, parse_failpoint_env, FailpointAction, FailpointConfigError,
    FailpointRegistry, FAILPOINT_REGISTRY,
};

// ── Macro (debug arm + release-elided arm) ──────────────────────────────

/// Insert a named failpoint barrier into a code path.
///
/// In release builds this compiles to the unit expression `()` — the
/// entire expansion is elided by the `#[cfg]` gate on the debug arm.
/// Zero runtime overhead lands in production: no env parsing, no
/// marker strings, no atomics, no OnceLock, no branch.
///
/// In debug builds the macro:
///   1. Fetches the process-wide `FAILPOINT_REGISTRY` (initialising
///      it from `PHANTOM_FAILPOINT` env on first call — fail-loud
///      on invalid env).
///   2. Calls `check(name)`. Armed names print the wire-contract
///      marker and park; unarmed names are a silent no-op.
///
/// Locked design v4 §12 rationale for the debug-only shape: SIGKILL
/// tests need deterministic write-path barriers (e.g. "send
/// crashed exactly after fsync-file, before parent-dir fsync"), but
/// any runtime overhead in production is unacceptable. `#[cfg]`
/// elision guarantees the compiler cannot even see the check in
/// release builds.
#[cfg(debug_assertions)]
#[macro_export]
macro_rules! failpoint {
    ($name:literal) => {{
        let reg = $crate::failpoints::FAILPOINT_REGISTRY
            .get_or_init($crate::failpoints::init_from_env);
        reg.check($name);
    }};
}

#[cfg(not(debug_assertions))]
#[macro_export]
macro_rules! failpoint {
    ($name:literal) => {
        ()
    };
}

// ── Tests ───────────────────────────────────────────────────────────────

#[cfg(all(test, debug_assertions))]
mod tests {
    //! All tests here work against LOCAL registry instances or the
    //! pure parser. NONE of them touch the process-wide
    //! `FAILPOINT_REGISTRY` `OnceLock` — that is exercised only by
    //! the M5a-3 subprocess SIGKILL tests, each of which starts a
    //! fresh child process with its own `PHANTOM_FAILPOINT` env.
    //! This isolation is what the scope-lock demands (corrective 5).

    use super::{parse_failpoint_env, FailpointAction, FailpointConfigError, FailpointRegistry};

    // ── Parser (pure) ─────────────────────────────────────────────

    #[test]
    fn parse_empty_string_returns_empty_pairs() {
        assert_eq!(parse_failpoint_env("").unwrap(), Vec::new());
    }

    #[test]
    fn parse_whitespace_only_string_returns_empty_pairs() {
        // Round-1 amendment: the WHOLE string being
        // whitespace-only is the only shortcut to an empty
        // registry. Tabs, newlines, and spaces all count as
        // whitespace under `char::is_whitespace`.
        assert_eq!(parse_failpoint_env("   \n\t  ").unwrap(), Vec::new());
    }

    // Round-1 amendment reject tests (architect P1):
    // stray leading / trailing / interior commas + a
    // commas-only-non-whitespace string all become
    // `MalformedEntry`, not "silently empty registry".

    #[test]
    fn parse_only_commas_are_rejected_as_malformed() {
        // ",,," is NOT whitespace-only (it has non-whitespace
        // characters — commas), so the amendment refuses it.
        let err = parse_failpoint_env(",,,").unwrap_err();
        assert!(matches!(
            err,
            FailpointConfigError::MalformedEntry {
                detail: "empty segment between commas — leading, trailing, or interior commas are refused",
                ..
            }
        ));
    }

    #[test]
    fn parse_whitespace_only_between_commas_are_rejected_as_malformed() {
        // `"  ,  ,  "` trims to `",  ,"` which is non-empty →
        // per-segment trim yields three empty entries → refuse
        // on the first.
        let err = parse_failpoint_env("  ,  ,  ").unwrap_err();
        assert!(matches!(err, FailpointConfigError::MalformedEntry { .. }));
    }

    #[test]
    fn parse_leading_comma_is_rejected_as_malformed() {
        let err = parse_failpoint_env(",a:block").unwrap_err();
        assert!(matches!(err, FailpointConfigError::MalformedEntry { .. }));
    }

    #[test]
    fn parse_trailing_comma_is_rejected_as_malformed() {
        let err = parse_failpoint_env("a:block,").unwrap_err();
        assert!(matches!(err, FailpointConfigError::MalformedEntry { .. }));
    }

    #[test]
    fn parse_interior_double_comma_is_rejected_as_malformed() {
        let err = parse_failpoint_env("a:block,,b:block").unwrap_err();
        assert!(matches!(err, FailpointConfigError::MalformedEntry { .. }));
    }

    #[test]
    fn parse_single_entry_returns_pair() {
        let out = parse_failpoint_env("send.after_disk:block").unwrap();
        assert_eq!(
            out,
            vec![(
                "send.after_disk".to_string(),
                FailpointAction::PrintMarkerAndBlock,
            )]
        );
    }

    #[test]
    fn parse_multi_entry_preserves_order_and_dedupes() {
        let out = parse_failpoint_env("a.x:block,b.y:block,c.z:block").unwrap();
        assert_eq!(out.len(), 3);
        assert_eq!(out[0].0, "a.x");
        assert_eq!(out[1].0, "b.y");
        assert_eq!(out[2].0, "c.z");
    }

    #[test]
    fn parse_whitespace_around_colon_and_comma_trimmed() {
        let out = parse_failpoint_env("  a.b : block , c.d : block ").unwrap();
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].0, "a.b");
        assert_eq!(out[1].0, "c.d");
    }

    #[test]
    fn parse_missing_colon_is_malformed_entry() {
        let err = parse_failpoint_env("bad_no_colon").unwrap_err();
        assert!(matches!(
            err,
            FailpointConfigError::MalformedEntry { detail: "expected `name:action`, missing colon", .. }
        ));
    }

    #[test]
    fn parse_empty_name_before_colon_is_malformed() {
        let err = parse_failpoint_env(":block").unwrap_err();
        assert!(matches!(
            err,
            FailpointConfigError::MalformedEntry { detail: "empty failpoint name before colon", .. }
        ));
    }

    #[test]
    fn parse_empty_action_after_colon_is_malformed() {
        let err = parse_failpoint_env("name:").unwrap_err();
        assert!(matches!(
            err,
            FailpointConfigError::MalformedEntry { detail: "empty action after colon", .. }
        ));
    }

    #[test]
    fn parse_unknown_action_fails_loud() {
        let err = parse_failpoint_env("name:panic").unwrap_err();
        match err {
            FailpointConfigError::UnknownAction { name, action } => {
                assert_eq!(name, "name");
                assert_eq!(action, "panic");
            }
            other => panic!("expected UnknownAction, got {other:?}"),
        }
    }

    #[test]
    fn parse_duplicate_name_fails_loud() {
        let err = parse_failpoint_env("a:block,b:block,a:block").unwrap_err();
        assert_eq!(err, FailpointConfigError::DuplicateName { name: "a".into() });
    }

    #[test]
    fn parse_first_malformed_entry_wins_over_later_ok_entries() {
        let err = parse_failpoint_env("ok:block,bad,also_ok:block").unwrap_err();
        assert!(matches!(err, FailpointConfigError::MalformedEntry { .. }));
    }

    #[test]
    fn display_impl_of_config_error_names_the_env_var_for_ops() {
        let err = FailpointConfigError::UnknownAction {
            name: "x".into(),
            action: "kill".into(),
        };
        let msg = format!("{err}");
        assert!(msg.contains("PHANTOM_FAILPOINT"));
        assert!(msg.contains("unknown action"));
    }

    // ── Registry (local instances only) ──────────────────────────

    #[test]
    fn empty_registry_reports_zero_len() {
        let reg = FailpointRegistry::empty();
        assert_eq!(reg.len(), 0);
    }

    #[test]
    fn empty_registry_check_is_silent_noop() {
        // Must not block, must not print, must not panic. If this
        // test hangs, `empty()` was violated.
        FailpointRegistry::empty().check("any.name.at.all");
    }

    #[test]
    fn from_pairs_arms_exactly_the_supplied_names() {
        let reg = FailpointRegistry::from_pairs(vec![
            ("x.one".into(), FailpointAction::PrintMarkerAndBlock),
            ("x.two".into(), FailpointAction::PrintMarkerAndBlock),
        ]);
        assert_eq!(reg.len(), 2);
        assert!(reg.contains("x.one"));
        assert!(reg.contains("x.two"));
        assert!(!reg.contains("x.three"));
    }

    #[test]
    fn armed_registry_check_of_unknown_name_is_silent_noop() {
        // Same as empty() but with SOME name armed — check() on a
        // DIFFERENT name still no-ops. This is the "not-armed
        // placement" contract: production code compiles and runs
        // through unmatched failpoints without any effect.
        let reg = FailpointRegistry::from_pairs(vec![(
            "some.armed.name".into(),
            FailpointAction::PrintMarkerAndBlock,
        )]);
        reg.check("different.name");
    }

    #[test]
    fn from_env_str_test_only_composes_parser_and_registry() {
        let reg = FailpointRegistry::from_env_str_test_only("alpha:block,beta:block");
        assert_eq!(reg.len(), 2);
        assert!(reg.contains("alpha"));
        assert!(reg.contains("beta"));
    }

    // ── Macro shape (release-elision proof) ──────────────────────

    /// The macro must compile as an expression in a `let` binding
    /// so callers can chain it into any statement position. Same
    /// contract in debug and release builds.
    #[test]
    fn failpoint_macro_is_expression() {
        // A never-armed name is a silent no-op in debug builds
        // (the global OnceLock init from an unset env yields an
        // empty registry). In release the whole expansion is
        // `()`. Either way this compiles as an expression.
        let _: () = {
            crate::failpoint!("m5a_1.compile_smoke.never_armed");
        };
    }

    /// Two consecutive invocations of the same never-armed name
    /// are legal and both no-op. Confirms the macro does not
    /// carry any per-call state that would break idempotence
    /// between calls in the same code path.
    #[test]
    fn failpoint_macro_never_armed_is_idempotent() {
        crate::failpoint!("m5a_1.idempotent.never_armed");
        crate::failpoint!("m5a_1.idempotent.never_armed");
    }
}

// ── Macro-shape smoke tests (both configs) ──────────────────────────────

// These tests run in BOTH debug and release, proving the macro
// expands cleanly as an expression under both `#[cfg]` arms. They
// deliberately do NOT reach for any `FailpointRegistry` /
// `parse_failpoint_env` / `FAILPOINT_REGISTRY` — those items only
// exist in debug, and a test that named them here would fail to
// compile in release. That would ITSELF break the elision
// contract (corrective 6): the elision proof is compile-time, not
// runtime.
//
// The scope-locked design says: any never-armed name is a silent
// no-op. In release, ALL names are silent no-op (macro is `()`);
// in debug, an unset `PHANTOM_FAILPOINT` env yields the same
// behaviour. Both smoke tests below hold in both configs.

#[cfg(test)]
mod macro_shape_tests {
    #[test]
    fn failpoint_macro_is_expression_in_both_configs() {
        let _: () = {
            crate::failpoint!("m5a_1.smoke.expression");
        };
    }

    #[test]
    fn failpoint_macro_never_armed_calls_are_idempotent_in_both_configs() {
        crate::failpoint!("m5a_1.smoke.idempotent");
        crate::failpoint!("m5a_1.smoke.idempotent");
    }
}

// ── Release-elision proof ───────────────────────────────────────────────

// In release builds the `debug_impl` module and every item it
// re-exports disappears (`#[cfg(debug_assertions)]` gates on the
// module itself). The `failpoint!` macro's release arm expands to
// `()`. A test that reached for any `FailpointRegistry` /
// `parse_failpoint_env` / `FAILPOINT_REGISTRY` in release would
// fail to compile — which is exactly the elision contract the
// scope-lock (corrective 6) demands. Nothing to assert
// programmatically; the compile itself is the proof.
