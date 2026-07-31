// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M1 — failpoint macro skeleton.
//!
//! Locked design: `mini-lock-draft-v4.md` §12 (SIGKILL tests via
//! deterministic failpoint) + `v4.1-amendments.md` §3 V-M6
//! (stderr-marker synchronisation between failpoint and test
//! harness parent process).
//!
//! At M1 this module ships only the type surface + macros. The
//! real failpoint-blocking + SIGKILL tests land in M5. M1's job
//! is to establish the compile-time invariant that the failpoint
//! macros compile to *nothing* in release builds — so no
//! production overhead sneaks in later.

/// Registry of active failpoints (populated at M5 by the test
/// harness). At M1 the type exists only so the `debug_assertions`
/// branch of [`failpoint!`] compiles.
#[cfg(debug_assertions)]
#[derive(Default)]
pub struct FailpointRegistry {
    // M5 will replace this placeholder with a
    // `std::sync::RwLock<HashMap<&'static str, FailpointAction>>`.
    _reserved: (),
}

#[cfg(debug_assertions)]
impl FailpointRegistry {
    pub const fn new() -> Self {
        Self { _reserved: () }
    }

    /// Placeholder for the M5 blocking check. At M1 this is a
    /// zero-cost no-op even in debug — the failpoint infrastructure
    /// only becomes observable once M5 wires the actual barriers.
    #[inline]
    pub fn check(&self, _name: &'static str) {
        // M5 will inspect an internal table and block/proceed here.
    }
}

/// Global process-wide registry (debug only).
#[cfg(debug_assertions)]
pub static FAILPOINT_REGISTRY: std::sync::OnceLock<FailpointRegistry> = std::sync::OnceLock::new();

/// Insert a named failpoint barrier into a code path.
///
/// In release builds this compiles to nothing — the entire
/// expression is elided by the `#[cfg]` gate, so zero runtime
/// overhead lands in production. In debug builds M5 will replace
/// the current no-op with a real blocking check that the test
/// harness can trigger via env-var config.
///
/// Locked design v4 §12 rationale for the debug-only shape: SIGKILL
/// tests need deterministic write-path barriers (e.g. "send crashed
/// exactly after fsync-file, before fsync-parent-dir"), but any
/// runtime overhead in production is unacceptable. `#[cfg]`
/// elision guarantees the compiler cannot even see the check in
/// release builds.
#[cfg(debug_assertions)]
#[macro_export]
macro_rules! failpoint {
    ($name:literal) => {{
        // Fetch-or-init on first use. Cheap after warmup.
        let reg = $crate::failpoints::FAILPOINT_REGISTRY
            .get_or_init($crate::failpoints::FailpointRegistry::new);
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

#[cfg(test)]
mod tests {
    /// The macro must compile as an expression in a `let` binding
    /// so callers can chain it into any statement position.
    #[test]
    fn failpoint_macro_is_expression() {
        let _: () = {
            crate::failpoint!("m1.compile_smoke");
        };
    }

    /// Two consecutive invocations of the same name are legal —
    /// the check is idempotent by design (a test may set a
    /// barrier, exercise it repeatedly, then release).
    #[test]
    fn failpoint_macro_idempotent_between_calls() {
        crate::failpoint!("m1.first");
        crate::failpoint!("m1.first");
    }
}
