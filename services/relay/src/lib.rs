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
pub mod diag_shape;
pub mod envelope;
pub mod error;
pub mod media;
pub mod prekeys;
pub mod push;
pub mod rest_fallback;
pub mod routes;
pub mod seq_mac;
pub mod state;
