// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Library entry point for `phantom-relay`.
//!
//! Modules are exposed `pub` so integration tests in `tests/` can drive the
//! same router that `main.rs` serves over TCP. Keeping the binary thin and
//! pushing all logic behind `lib.rs` is the standard Rust pattern; nothing
//! about the runtime behaviour changes — `main.rs` is just one consumer.

pub mod auth;
pub mod config;
pub mod envelope;
pub mod error;
pub mod media;
pub mod prekeys;
pub mod push;
pub mod rest_fallback;
pub mod routes;
pub mod seq_mac;
pub mod state;
pub mod t2_diag;
pub mod diag_poll_shape;

// RC-RELAY-QUEUE-DURABILITY PR-2 M1 — shard-worker fundamentals.
// These modules are public but not yet wired into main.rs / axum
// handlers; the existing in-memory HashMap path remains
// authoritative until PR-2 M3 migrates the five mutation sites.
pub mod capacity_ledger;
pub mod failpoints;
pub mod rest_workers;
pub mod worker_pool;

// RC-RELAY-QUEUE-DURABILITY PR-2 M2 — persistence layer + boot
// loader. Same pub-but-unused principle: M4 wires them into
// main.rs / AppState. Locked design v4 §7-§8, v4.1 §5 L-N7/N11/
// N12, v4.2 §1 B-1 + §3 B-3, v4.2.2 §2, v4.2.3 §1/§4.
pub mod atomic_write;
pub mod body_hash;
pub mod boot_loader;
pub mod persistence;
pub mod queue_meta;
pub mod tombstone_config;

// RC-RELAY-QUEUE-DURABILITY PR-2 M4-1 — plumbing without runtime
// activation. Ships pure adapter functions the M4-2 atomic cutover
// will call. `main.rs` does NOT yet boot the runtime; every
// function here is unit-tested against synthetic inputs without
// spawning a WorkerRuntime.
pub mod m4_adapters;

// RC-RELAY-QUEUE-DURABILITY PR-2 M4-3 round-1 REDLINE — durable
// sweep scheduler. Extracted from `main.rs` so the tick pass is
// unit-testable end-to-end (via `run_sweep_tick`) and so the
// scheduler runs shards concurrently instead of serialising across
// the whole recipient population.
pub mod sweep_scheduler;

// RC-RELAY-QUEUE-DURABILITY PR-2 M4-4 — internal loopback health
// surface + unified shutdown classifier. Kept in two separate modules
// so the pure classifier (`shutdown`) is testable without a runtime,
// and the axum router (`health_listener`) is testable via
// `ServiceExt::oneshot` without a real TCP bind.
pub mod health_listener;
pub mod shutdown;
