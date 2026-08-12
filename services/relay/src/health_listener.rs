// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M4-4 — internal loopback health surface.
//!
//! Three endpoints on a separate axum `Router` intended to bind to
//! `127.0.0.1:<RELAY_HEALTH_PORT>`. Kept off the public listener so
//! (a) they never expose internal counters to the outside world, and
//! (b) the existing public `/health` route stays byte-identical for
//! backward compatibility (architect's corrected-split memo, 2026-07-28).
//!
//! Endpoints:
//!
//!   * `GET /live`   — always `200 { "status": "alive" }` while this
//!     process is running. Used by `docker` / `k8s` liveness probes to
//!     tell "process alive" from "process wedged". Never checks
//!     runtime state.
//!   * `GET /ready`  — `200 { "status": "ready" }` while the runtime
//!     is `Running` and the shutdown flag is unset; `503 { "status":
//!     "draining" }` as soon as the shutdown flag flips true OR the
//!     runtime transitions out of `Running`. Used by orchestrators
//!     to steer traffic away during graceful shutdown.
//!   * `GET /status` — JSON dump of the runtime's observable state:
//!     `state_kind`, `active_entry_count`, `tombstone_dedup_count`,
//!     `boot_seed_stats`, `capacity` snapshot. Internal-only —
//!     leaking to the public listener would let an outside caller
//!     read the ledger.
//!
//! The router is built from a dedicated [`HealthListenerState`] so
//! adding health routes did NOT need to touch `state::AppState` or
//! the public router. `main.rs` binds the router with
//! `axum::serve(loopback_listener, health_router)` and hangs a
//! `.with_graceful_shutdown` future on an ordered health-stop watch.
//! The router still observes the process-wide shutdown watch, so
//! `/ready` returns 503 while public traffic drains; the health TCP
//! listener stops only after that public drain completes.

use std::sync::Arc;

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Json},
    routing::get,
    Router,
};
use tokio::sync::watch;

use crate::rest_workers::WorkerRuntime;
use crate::worker_pool::PoolStateKind;

/// State handed to the loopback listener's route handlers. Distinct
/// from `state::AppState` so binding the health surface did not
/// require touching the main app-state or the public router.
#[derive(Clone)]
pub struct HealthListenerState {
    /// Shared handle to the runtime; used by `/ready` (state_kind) and
    /// `/status` (counters).
    pub runtime: Arc<WorkerRuntime>,
    /// Shutdown flag receiver — `/ready` returns 503 as soon as this
    /// resolves `true`. Cloneable so multiple handlers share it.
    pub shutdown_rx: watch::Receiver<bool>,
}

/// Build the internal loopback router. Consumers pass this to
/// `axum::serve(loopback_listener, router).with_graceful_shutdown(...)`.
///
/// The router is deliberately narrow — three routes, no middleware,
/// no timeout layer, no body-size limit. It is expected to bind to
/// `127.0.0.1` only; a misconfiguration that exposes it to a
/// public interface is an ops-side concern (validated in `main.rs`).
pub fn router(state: HealthListenerState) -> Router {
    Router::new()
        .route("/live", get(handle_live))
        .route("/ready", get(handle_ready))
        .route("/status", get(handle_status))
        .with_state(state)
}

async fn handle_live() -> impl IntoResponse {
    (StatusCode::OK, Json(serde_json::json!({ "status": "alive" })))
}

async fn handle_ready(State(state): State<HealthListenerState>) -> impl IntoResponse {
    let draining = *state.shutdown_rx.borrow();
    let runtime_state = state.runtime.state_kind();
    let ready = !draining && matches!(runtime_state, PoolStateKind::Running);
    if ready {
        (StatusCode::OK, Json(serde_json::json!({ "status": "ready" }))).into_response()
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({
                "status": "draining",
                "draining_flag": draining,
                "runtime_state": format!("{runtime_state:?}"),
            })),
        )
            .into_response()
    }
}

async fn handle_status(State(state): State<HealthListenerState>) -> impl IntoResponse {
    let draining = *state.shutdown_rx.borrow();
    let runtime_state = state.runtime.state_kind();
    let seed = state.runtime.boot_seed_stats();
    let capacity = state.runtime.capacity().snapshot();
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "runtime_state": format!("{runtime_state:?}"),
            "draining_flag": draining,
            "active_entry_count": state.runtime.active_entry_count(),
            "tombstone_dedup_count": state.runtime.tombstone_dedup_count(),
            "boot_seed_stats": {
                "queued_seeded": seed.queued_seeded,
                "queued_expired_swept": seed.queued_expired_swept,
                "tombstones_seeded": seed.tombstones_seeded,
                "tombstones_expired_swept": seed.tombstones_expired_swept,
                "disk_reclaimed_bytes": seed.disk_reclaimed_bytes,
            },
            "capacity": {
                "active_envelopes": capacity.active_envelopes,
                "active_bytes": capacity.active_bytes,
                "tombstone_records": capacity.tombstone_records,
                "tombstone_bytes": capacity.tombstone_bytes,
                "ram_bytes": capacity.ram_bytes,
            },
        })),
    )
        .into_response()
}

// ── Tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    //! End-to-end tests for the loopback surface. We drive requests via
    //! `tower::ServiceExt::oneshot` against the built `Router` — no TCP
    //! bind needed, so tests are deterministic under `#[tokio::test]`.
    //!
    //! The runtime under test is a real `spawn_worker_runtime` (via
    //! `WorkerRuntimeSpec::from_boot_at`) so `/status`'s counters land
    //! against the production accessors rather than a mock.

    use super::*;

    use std::sync::Arc;

    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use tempfile::TempDir;
    use tokio::sync::{broadcast, watch};
    use tower::ServiceExt;

    use crate::boot_loader::{self, BootConfig, OwnershipExpectation, PreflightCaps};
    use crate::capacity_ledger::CapacityCaps;
    use crate::queue_meta::{write_meta, Phase, QueueMeta, META_VERSION};
    use crate::rest_workers::{
        spawn_worker_runtime, FatalReason, WorkerRuntime, WorkerRuntimeSpec,
    };
    use crate::seq_mac::SeqMacRootKey;
    use crate::tombstone_config::TombstoneConfig;

    const TEST_MAC_KEY: [u8; 32] = [0x22u8; 32];
    const NOW_EPOCH: u64 = 1_720_000_000;

    fn caps() -> CapacityCaps {
        CapacityCaps {
            max_envelopes: 1_000,
            max_bytes: 10_000_000,
            ram_budget: 10_000_000,
        }
    }

    fn build_state_dir() -> TempDir {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        write_meta(dir.path(), &meta).unwrap();
        dir
    }

    fn spawn_runtime(dir: &TempDir) -> (Arc<WorkerRuntime>, broadcast::Receiver<FatalReason>) {
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let boot_cfg = BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: PreflightCaps::for_tests(),
            tombstone: TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = boot_loader::boot(&boot_cfg).expect("boot OK");
        let (fatal_tx, fatal_rx) = broadcast::channel(16);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot_result,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            NOW_EPOCH,
        )
        .expect("from_boot_at OK");
        (
            Arc::new(spawn_worker_runtime(spec).expect("spawn OK")),
            fatal_rx,
        )
    }

    async fn body_json(res: axum::response::Response) -> serde_json::Value {
        let (parts, body) = res.into_parts();
        let bytes = to_bytes(body, 8 * 1024).await.expect("body reads");
        let val: serde_json::Value = serde_json::from_slice(&bytes).unwrap_or_else(|e| {
            panic!("body not JSON: {e} raw={:?} status={}", bytes, parts.status)
        });
        val
    }

    async fn drain(runtime: &Arc<WorkerRuntime>) {
        runtime.close();
        let _ = runtime
            .drain_handles(std::time::Duration::from_secs(5))
            .await;
    }

    // ── /live ─────────────────────────────────────────────────────

    #[tokio::test]
    async fn live_returns_200_when_running() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (_tx, rx) = watch::channel(false);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .clone()
            .oneshot(Request::get("/live").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let json = body_json(res).await;
        assert_eq!(json.get("status").and_then(|v| v.as_str()), Some("alive"));
        drain(&runtime).await;
    }

    #[tokio::test]
    async fn live_returns_200_even_when_draining_flag_true() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (tx, rx) = watch::channel(false);
        tx.send_replace(true);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/live").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK, "/live must stay 200 through drain");
        drain(&runtime).await;
    }

    // ── /ready ────────────────────────────────────────────────────

    #[tokio::test]
    async fn ready_returns_200_when_running_and_not_draining() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (_tx, rx) = watch::channel(false);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/ready").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let json = body_json(res).await;
        assert_eq!(json.get("status").and_then(|v| v.as_str()), Some("ready"));
        drain(&runtime).await;
    }

    #[tokio::test]
    async fn ready_returns_503_when_draining_flag_true() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (tx, rx) = watch::channel(false);
        tx.send_replace(true);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/ready").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
        let json = body_json(res).await;
        assert_eq!(
            json.get("status").and_then(|v| v.as_str()),
            Some("draining")
        );
        assert_eq!(json.get("draining_flag").and_then(|v| v.as_bool()), Some(true));
        drain(&runtime).await;
    }

    #[tokio::test]
    async fn ready_returns_503_when_runtime_transitioned_out_of_running() {
        // Even without the draining flag flipped, a runtime that has
        // already `close()`d must report not-ready.
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        runtime.close();
        let (_tx, rx) = watch::channel(false);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/ready").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
        let json = body_json(res).await;
        assert_eq!(
            json.get("status").and_then(|v| v.as_str()),
            Some("draining")
        );
        assert_eq!(
            json.get("runtime_state").and_then(|v| v.as_str()),
            Some("Closing")
        );
        assert_eq!(json.get("draining_flag").and_then(|v| v.as_bool()), Some(false));
        let _ = runtime
            .drain_handles(std::time::Duration::from_secs(5))
            .await;
    }

    // ── /status ───────────────────────────────────────────────────

    #[tokio::test]
    async fn status_returns_expected_shape_on_empty_runtime() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (_tx, rx) = watch::channel(false);
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/status").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let json = body_json(res).await;
        assert_eq!(
            json.get("runtime_state").and_then(|v| v.as_str()),
            Some("Running")
        );
        assert_eq!(json.get("draining_flag").and_then(|v| v.as_bool()), Some(false));
        assert_eq!(
            json.get("active_entry_count").and_then(|v| v.as_u64()),
            Some(0)
        );
        assert_eq!(
            json.get("tombstone_dedup_count").and_then(|v| v.as_u64()),
            Some(0)
        );
        let seed = json.get("boot_seed_stats").expect("boot_seed_stats present");
        for k in [
            "queued_seeded",
            "queued_expired_swept",
            "tombstones_seeded",
            "tombstones_expired_swept",
            "disk_reclaimed_bytes",
        ] {
            assert!(seed.get(k).is_some(), "missing boot_seed_stats.{k}");
        }
        let cap = json.get("capacity").expect("capacity present");
        for k in [
            "active_envelopes",
            "active_bytes",
            "tombstone_records",
            "tombstone_bytes",
            "ram_bytes",
        ] {
            assert!(cap.get(k).is_some(), "missing capacity.{k}");
        }
        drain(&runtime).await;
    }

    #[tokio::test]
    async fn status_reports_draining_flag_and_state_after_close() {
        let dir = build_state_dir();
        let (runtime, _fatal_rx) = spawn_runtime(&dir);
        let (tx, rx) = watch::channel(false);
        tx.send_replace(true);
        runtime.close();
        let app = router(HealthListenerState {
            runtime: Arc::clone(&runtime),
            shutdown_rx: rx,
        });
        let res = app
            .oneshot(Request::get("/status").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let json = body_json(res).await;
        assert_eq!(json.get("draining_flag").and_then(|v| v.as_bool()), Some(true));
        assert_eq!(
            json.get("runtime_state").and_then(|v| v.as_str()),
            Some("Closing")
        );
        let _ = runtime
            .drain_handles(std::time::Duration::from_secs(5))
            .await;
    }
}
