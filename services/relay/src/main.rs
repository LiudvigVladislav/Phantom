// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

// All implementation lives in lib.rs so that integration tests in
// `tests/` and any future tooling can drive the same router this binary
// serves. main.rs is just the runtime entry point.
use phantom_relay::{
    boot_loader, capacity_ledger, config, health_listener, m4_adapters, rest_workers, routes,
    shutdown, state, sweep_scheduler, tombstone_config,
};

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "phantom_relay=info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // PR-0 A-5: from_env() now returns Result<RelayConfig, ConfigError>
    // so a misconfigured RELAY_STATE_DIR (relative path, `..` component,
    // empty string) fails FATAL at boot instead of silently landing
    // state files at an unpredictable location under the read_only
    // rootfs. Exit code 12 reserved for config-time errors — distinct
    // from PR-2's exit 10 (replay quarantine) and 11 (tombstone
    // config error) so an operator can distinguish causes.
    let cfg = match config::RelayConfig::from_env() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("FATAL: relay boot config invalid: {e}");
            std::process::exit(12);
        }
    };

    // RC-RELAY-STATE-DIR-REPAIR PR-1b — Order B boot preflight:
    //   validate cfg (done above)
    //   → ensure state_dir exists
    //   → open state_dir/.lock and try_lock_exclusive
    //   → preflight sentinel write+fsync+unlink under the held lock
    //   → return the locked File; main binds it to `_state_dir_lock`
    //     so the singleton lock is held for the ENTIRE process lifetime.
    //
    // Contention → std::process::exit(2). Preflight failure → panic-loud.
    // Both are distinct from the config exit=12 above so an operator can
    // distinguish "config invalid" from "another relay is running" from
    // "state_dir not writable".
    let _state_dir_lock = state::state_dir_preflight(&cfg);

    // ── PR-2 M4-2b atomic activation: boot → runtime → fatal subscriber ──
    //
    // Order is fixed:
    //   1. Assemble `BootConfig` from `RelayConfig` + state_dir preflight.
    //   2. `boot_loader::boot(&boot_cfg)` — reads/compacts the durable
    //      queue and returns a `BootLoaderResult` (with the survivor set,
    //      seq counters, tombstone dedup table).
    //   3. `WorkerRuntimeSpec::from_boot(...)` — seeds the capacity
    //      ledger from disk truth.
    //   4. `spawn_worker_runtime(spec)` — spawns the 64-worker pool.
    //   5. Fatal broadcast subscriber installed BEFORE the listener
    //      binds, so any fatal that fires during handler cold-start is
    //      caught.
    //   6. `AppState::new(cfg, runtime)` — mandatory constructor.
    //      `store` / `rest_store` are the runtime's own `Arc` handles
    //      (round-2 F1 pre-widening designed for this).
    // M4-2b round-1 REDLINE P0-1: preflight caps + ownership
    // read from env. Fail-closed on invalid config. Defaults are
    // chosen deliberately below the compose-file 512 MiB memory
    // ceiling so the ledger refuses admission before Docker OOMs
    // the container; production tuning happens via env override.
    //
    // Round-2 REDLINE P0-1: assemble runtime caps FIRST so the
    // subsequent `check_preflight_covers_runtime` invariant
    // fires at startup rather than lurking until the next boot
    // hits `BudgetExceeded`.
    let preflight_caps = preflight_caps_from_env();
    let runtime_caps = capacity_caps_from_env();
    let violations = check_preflight_covers_runtime(&preflight_caps, &runtime_caps);
    if !violations.is_empty() {
        eprintln!(
            "FATAL: env caps out of order (preflight must cover runtime):\n  - {}",
            violations.join("\n  - ")
        );
        std::process::exit(11);
    }
    let boot_cfg = boot_loader::BootConfig {
        state_dir: cfg.state_dir.clone(),
        caps: preflight_caps,
        tombstone: tombstone_config::TombstoneConfig::from_env().unwrap_or_else(|e| {
            eprintln!("FATAL: tombstone config invalid: {e}");
            std::process::exit(11);
        }),
        current_seq_mac_key_fingerprint: cfg.seq_mac_key.fingerprint(),
        ownership: ownership_from_env(),
    };
    let boot_result = match boot_loader::boot(&boot_cfg) {
        Ok(r) => r,
        Err(e) => {
            // M4-2b round-1 REDLINE P1-3: typed BootError → its
            // reserved exit code (3/4/5/8/10/11) via
            // `BootError::exit_code()`. Prior shape collapsed
            // every variant to exit 3.
            eprintln!("FATAL: boot failed: {e:?}");
            std::process::exit(e.exit_code());
        }
    };
    let (fatal_tx, fatal_rx) = tokio::sync::broadcast::channel::<rest_workers::FatalReason>(64);
    let spec = match rest_workers::WorkerRuntimeSpec::from_boot(
        boot_result,
        cfg.max_envelopes_per_recipient,
        Arc::clone(&cfg.seq_mac_key),
        runtime_caps,
        fatal_tx,
    ) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("FATAL: WorkerRuntimeSpec::from_boot: {e:?}");
            std::process::exit(3);
        }
    };
    let runtime = Arc::new(match rest_workers::spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("FATAL: spawn_worker_runtime: {e:?}");
            std::process::exit(3);
        }
    });

    // ── Fatal subscriber (Gate #6): installed BEFORE the listener ──
    //
    // Fail-closed on Lagged and on Closed-while-Running. Every
    // path ends in `FATAL: worker-runtime ...` + `process::exit(1)`.
    {
        let runtime_probe = Arc::clone(&runtime);
        let mut rx = fatal_rx;
        tokio::spawn(async move {
            loop {
                let event = match rx.recv().await {
                    Ok(reason) => {
                        eprintln!("{}", m4_adapters::render_fatal_line(&reason));
                        m4_adapters::FatalSubscriberEvent::FatalReceived
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        eprintln!(
                            "FATAL: worker-runtime fatal subscriber lagged, missed_count={n} \
                             (Lagged is fatal per M4 Gate #6)"
                        );
                        m4_adapters::FatalSubscriberEvent::Lagged
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        m4_adapters::FatalSubscriberEvent::Closed
                    }
                };
                let is_running = matches!(
                    runtime_probe.state_kind(),
                    phantom_relay::worker_pool::PoolStateKind::Running,
                );
                match m4_adapters::classify_fatal_subscriber_event(&event, is_running) {
                    m4_adapters::FatalSubscriberAction::EmitFatalAndExit => {
                        if matches!(event, m4_adapters::FatalSubscriberEvent::Closed) {
                            eprintln!(
                                "FATAL: worker-runtime fatal channel closed while pool was Running"
                            );
                        }
                        std::process::exit(1);
                    }
                    m4_adapters::FatalSubscriberAction::CleanExit => return,
                }
            }
        });
    }

    // ── PR-2 M4-4: shutdown coordination (round-1 REDLINE amendments) ───
    //
    // Single source of truth for "the process is draining." Producers:
    //   * The signal handler task (below) — flips true on ctrl_c or,
    //     on Unix, SIGTERM.
    // Consumers (all `watch::Receiver::clone()` cheap):
    //   * Both axum listeners, via `.with_graceful_shutdown(...)` —
    //     stop accepting new requests and drain in-flight ones.
    //   * The sweep scheduler loop — refuses to start a new tick,
    //     exits cleanly; aborts mid-tick if the signal fires during
    //     `run_sweep_tick`.
    //   * The `/ready` handler — returns 503 as soon as this flips true.
    //   * The `/status` handler — reports the flag in its JSON body.
    //
    // `watch::channel(false)` gives us `wait_for(|&v| v)` which returns
    // immediately if the value is already true (race-free vs `Notify`
    // where `notified()` before `notify_waiters()` would miss the wake).
    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
    // Round-1 REDLINE P1-1: capture the WALL-CLOCK moment the signal
    // fires so the whole shutdown sequence can be bounded by ONE
    // absolute deadline measured from that instant — not from
    // "whenever the listener drain happens to complete."
    let signal_at: Arc<std::sync::OnceLock<tokio::time::Instant>> =
        Arc::new(std::sync::OnceLock::new());

    // Round-1 REDLINE P1-4: register SIGTERM BEFORE spawning the
    // signal-handler task so a registration failure surfaces as an
    // immediate `FATAL:` + exit(1). Pre-round-1 the `signal(...)`
    // call lived inside a detached `tokio::spawn` where an `expect()`
    // panic would tear down only that task — the relay would then
    // stay up without a SIGTERM handler, exactly the "silent no
    // shutdown" defect the reviewer flagged.
    #[cfg(unix)]
    let sigterm_stream = match tokio::signal::unix::signal(
        tokio::signal::unix::SignalKind::terminate(),
    ) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("FATAL: SIGTERM handler registration failed: {e}");
            std::process::exit(1);
        }
    };
    {
        let shutdown_tx = shutdown_tx.clone();
        let signal_at = Arc::clone(&signal_at);
        #[cfg(unix)]
        let mut sigterm = sigterm_stream;
        tokio::spawn(async move {
            #[cfg(unix)]
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = sigterm.recv() => {}
            }
            #[cfg(not(unix))]
            let _ = tokio::signal::ctrl_c().await;
            // Store the signal instant FIRST so any consumer that
            // observes `draining == true` can safely read a valid
            // `signal_at`; only then flip the watch.
            let _ = signal_at.set(tokio::time::Instant::now());
            tracing::info!(
                event = "shutdown_signal_received",
                "graceful shutdown initiated"
            );
            let _ = shutdown_tx.send_replace(true);
        });
    }

    let app_state = Arc::new(state::AppState::new(cfg.clone(), runtime));
    // F11 + F26: rebuild the WS-auth signing-key bindings from the
    // disk-replayed prekey store before serving traffic so a relay restart
    // keeps every previously-published identity's binding stable.
    app_state.rebuild_signing_keys_from_prekeys().await;

    let app = routes::router(Arc::clone(&app_state));

    let addr = format!("{}:{}", cfg.host, cfg.port);
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();

    // PR-H1c (2026-05-13): aggressive TCP keepalive on the listener so each
    // accepted client connection picks up SO_KEEPALIVE + TCP_KEEPIDLE/INTVL/
    // CNT inherited from the listening socket. On Linux these options ARE
    // inherited at accept() time (per socket(7)).
    //
    // Test #35 motivation: server-side conn_id=5 sat in a half-open state
    // for 178 s after the client side noticed the socket was dead and
    // forceReconnect()'d. Default Linux tcp_keepalive_time is 7200 s — far
    // too long for a mobile messenger. With these values the relay surfaces
    // half-open sockets in ~30 s (15 s idle + 3 × 5 s probes), matching
    // the client's PR-H1c proactive-reconnect window. The two sides become
    // symmetric: neither holds a zombie session for minutes.
    //
    // `#[cfg(unix)]` because socket2's `TcpKeepalive::with_retries(...)`
    // (which sets TCP_KEEPCNT) is unavailable on Windows. The relay
    // ships in a Linux container in production (deploy/docker-compose.yml);
    // Windows builds are local dev only and run without the tuned
    // keepalive — the kernel's default applies, which is acceptable for
    // dev where mobile NAT eviction is not in scope.
    #[cfg(unix)]
    {
        use socket2::{SockRef, TcpKeepalive};
        use std::time::Duration;
        let sock_ref = SockRef::from(&listener);
        let keepalive = TcpKeepalive::new()
            .with_time(Duration::from_secs(15))
            .with_interval(Duration::from_secs(5))
            .with_retries(3);
        if let Err(e) = sock_ref.set_tcp_keepalive(&keepalive) {
            tracing::warn!(
                error = %e,
                "TCP keepalive setup failed on listener — accepted sockets will use kernel defaults",
            );
        } else {
            tracing::info!(
                idle_s = 15, interval_s = 5, retries = 3,
                "TCP keepalive configured on listener (PR-H1c)",
            );
        }
    }

    // Startup banner — key operational parameters logged once at boot.
    // Trust boundary: only operational metadata, never secrets.
    tracing::info!(
        host = %cfg.host,
        port = cfg.port,
        max_payload_kb = cfg.max_payload_bytes / 1024,
        ttl_days = cfg.envelope_ttl_secs / 86400,
        rate_limit = cfg.rate_limit_per_window,
        admin_token_set = cfg.secret_token.is_some(),
        ws_auth = "signed-challenge (Ed25519)",
        state_dir = %cfg.state_dir.display(),
        "phantom-relay starting"
    );

    // Arm D heartbeat echo flag state — logged at startup so an operator
    // running the RC-DIRECT-STABILITY1 Arm D field test can confirm at the
    // banner whether the env var was picked up before the first Android
    // connection arrives. When the flag is `false` (production default),
    // this is just a one-line audit trail; when `true`, it is a load-bearing
    // signal that the diagnostic handler is live.
    tracing::info!(
        heartbeat_echo_enabled = cfg.heartbeat_echo_enabled,
        slow_post_diag_enabled = cfg.slow_post_diag_enabled,
        t2_diag_enabled = cfg.t2_diag_enabled,
        poll_chunked_flush = cfg.poll_chunked_flush,
        "relay feature flags"
    );

    // Background task: purge expired non-queue state every 5 minutes.
    //
    // **PR-2 M4-2b atomic activation**: the pre-M4 shape ALSO ran
    // `retain(|e| !e.is_expired())` on `cleanup_state.store` and
    // `cleanup_state.rest_store` (the two envelope queues). Both
    // blocks are deleted here — those maps are now owned by the
    // `WorkerRuntime` and mutation-side handlers route through
    // `runtime.try_send(RestOp::Send | Ack | Sweep)`. TTL expiry of
    // recipient queues moves to `RestOp::Sweep`, dispatched
    // per-recipient from M4-3's scheduler. This task keeps the
    // other state clean: prekeys, auth challenges, REST session
    // caches, notifiers.
    let cleanup_state = Arc::clone(&app_state);
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
        loop {
            interval.tick().await;
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            cleanup_state.prekeys.purge_expired_previous_spks(now_ms).await;
            cleanup_state.auth_challenges.purge_expired(now_ms).await;
            // PR-D0r: purge expired REST fallback state.
            cleanup_state.rest_tokens.purge_expired().await;
            cleanup_state.rest_session_cache.purge_expired().await;
            cleanup_state.rest_idempotency.purge_expired().await;
            // Trek 2 Stage 1 Q6 — evict idle `notifiers` entries to bound
            // map size. Drop ONLY entries whose `Arc::strong_count == 1`
            // — that means the only owner is the map itself (no in-flight
            // poll waiter holds a clone, no concurrent `notify_recipient`
            // send call is mid-execution with a clone). This avoids the
            // drop-while-register race where eviction would strand a
            // waiter without a notifier the send path can reach
            // (security-reviewer Finding 3.A).
            //
            // Trade-off in the race window: if cleanup drops the entry
            // just before `/relay/send` does `notify_recipient`, the
            // send simply finds no entry and is a no-op — the envelope
            // is still in `rest_store` for the next poll cycle, so
            // Guardrail A "delivery never lost" holds.
            {
                let mut notifiers = cleanup_state.notifiers.write().await;
                let before = notifiers.len();
                notifiers.retain(|_, arc| std::sync::Arc::strong_count(arc) > 1);
                let dropped = before.saturating_sub(notifiers.len());
                if dropped > 0 {
                    tracing::debug!(
                        event   = "trek2_notifiers_cleanup",
                        dropped = dropped,
                        kept    = notifiers.len(),
                    );
                }
            }
            tracing::debug!(
                "Cleanup: purged expired envelopes + previous SPKs + auth challenges + REST state"
            );
        }
    });

    // Background task: sweep expired media chunks every hour (PR-M1r).
    // TTL is per-media-entry: if the earliest chunk is older than media_ttl_secs,
    // the entire entry is removed. Relay never touches content; only metadata
    // (media_id prefix, chunk count, age) is logged.
    let media_sweep_state = Arc::clone(&app_state);
    tokio::spawn(async move {
        let ttl_secs = media_sweep_state.config.media_ttl_secs;
        let ttl_ms = ttl_secs * 1_000;
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(3_600));
        loop {
            interval.tick().await;
            let swept = media_sweep_state.media_store.sweep_expired(ttl_ms).await;
            if swept > 0 {
                tracing::info!(swept = swept, "media sweeper removed expired entries");
            } else {
                tracing::debug!("media sweeper: no expired entries");
            }
        }
    });

    // ── PR-2 M4-3 (round-1 REDLINE): durable queue sweep scheduler ─────
    //
    // 300 s cadence (aligned with the pre-M4 legacy `Vec::retain`
    // block that M4-2b deleted). Every tick calls the pure
    // `sweep_scheduler::run_sweep_tick(...)` function, which:
    //
    //   1. Buckets recipients by owning shard via
    //      `runtime.recipient_snapshot_by_shard()` — raw indices
    //      never leave the runtime.
    //   2. Refuses the whole tick when the runtime is not
    //      `Running` (drops through, does not warn per remaining
    //      recipient).
    //   3. Runs different shards CONCURRENTLY under a bounded
    //      semaphore (default 16 shards in flight). Within a
    //      shard, dispatch stays sequential so `do_sweep`'s
    //      per-shard actor contract is preserved.
    //   4. Re-checks `state_kind()` before every per-recipient
    //      dispatch — a `Running → Closing` transition mid-tick
    //      aborts cleanly.
    //   5. Bounds the whole pass with `DEFAULT_TICK_DEADLINE`
    //      (240 s, comfortably below the 300 s cadence). A
    //      single stalled shard cannot hold up the tick.
    //
    // `MissedTickBehavior::Skip` — if a tick runs long, the next
    // tick fires at the NEXT 300 s boundary rather than
    // immediately, preventing overlap.
    // Round-1 REDLINE P1-2: hold the sweep scheduler's `JoinHandle`
    // so the shutdown sequence can await its clean exit BEFORE
    // `runtime.close()`. Pre-round-1 the handle was silently dropped
    // and `main` closed the pool without confirming the scheduler had
    // stopped — an in-flight `run_sweep_tick` could still be dispatching
    // `RestOp::Sweep` at up to `DEFAULT_TICK_DEADLINE` (240 s) after
    // signal, well past the drain contract.
    let sweep_state = Arc::clone(&app_state);
    let mut sweep_shutdown_rx = shutdown_rx.clone();
    let sweep_handle: tokio::task::JoinHandle<()> = tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(300));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            // Race an interval tick against the shutdown flag.
            // `wait_for(|&v| v)` returns immediately if the flag is
            // already true, so if the signal fired between two ticks
            // we exit at the next `select!` poll rather than firing
            // one more tick that would race the pool drain.
            tokio::select! {
                _ = interval.tick() => {}
                _ = sweep_shutdown_rx.wait_for(|&v| v) => {
                    tracing::info!(
                        event = "sweep_scheduler_shutdown",
                        "sweep scheduler exiting on shutdown signal (between ticks)"
                    );
                    return;
                }
            }
            if *sweep_shutdown_rx.borrow() {
                tracing::info!(
                    event = "sweep_scheduler_shutdown",
                    "sweep scheduler observed draining flag mid-loop, exiting"
                );
                return;
            }
            let runtime = sweep_state.runtime();
            // Round-2 REDLINE P1-1: cooperative cancellation. The
            // round-1 shape wrapped `run_sweep_tick` in an outer
            // `tokio::select!` and DROPPED the tick future on
            // shutdown — but `Drop for JoinSet` only requests abort;
            // it does not await the abort's completion. So the
            // scheduler handle could report clean exit while shard
            // tasks were still momentarily alive between a
            // state-check and a `runtime.try_send`. Round-2 pushes
            // the cancellation observer INSIDE the tick via
            // `run_sweep_tick_cancellable`, which invokes
            // `JoinSet::shutdown().await` on cancel and waits for
            // every shard task's Drop before returning.
            let cancel_fut = {
                let mut rx = sweep_shutdown_rx.clone();
                async move {
                    let _ = rx.wait_for(|&v| v).await;
                }
            };
            let report = sweep_scheduler::run_sweep_tick_cancellable(
                runtime,
                sweep_scheduler::DEFAULT_PER_RECIPIENT_DEADLINE,
                sweep_scheduler::DEFAULT_TICK_DEADLINE,
                sweep_scheduler::DEFAULT_SHARD_CONCURRENCY,
                cancel_fut,
            )
            .await;
            // Post-tick shutdown check — if the tick returned due to
            // cancellation, the flag is already set; exit cleanly
            // without a spurious `sweep_tick_complete` log line.
            if *sweep_shutdown_rx.borrow() {
                tracing::info!(
                    event = "sweep_scheduler_shutdown",
                    "sweep scheduler exiting after cooperative tick cancellation"
                );
                return;
            }
            if report.aborted_by_state {
                tracing::debug!(
                    event = "sweep_scheduler_skipped",
                    state = ?runtime.state_kind(),
                    recipients_scanned = report.recipients_scanned,
                    "runtime not Running mid-tick — sweep tick aborted cleanly"
                );
                continue;
            }
            tracing::info!(
                event = "sweep_tick_complete",
                recipients_scanned = report.recipients_scanned,
                recipients_dispatched = report.recipients_dispatched,
                queued_swept = report.queued_swept,
                tombstones_swept = report.tombstones_swept,
                disk_reclaimed_bytes = report.disk_reclaimed_bytes,
                failures = report.failures,
                aborted_by_deadline = report.aborted_by_deadline,
            );
        }
    });

    // ── PR-2 M4-4: loopback health surface ───────────────────────────────
    //
    // Round-1 REDLINE P1-3: fail-closed bind. Pre-round-1 shape used
    // `.unwrap()` (panic on port conflict) and accepted `RELAY_HEALTH_PORT
    // = 0` (kernel picks an ephemeral port while the log still claimed
    // `127.0.0.1:0`). Both defects fixed here: `port == 0` is refused at
    // parse time, bind failure emits `FATAL:` + `exit(1)`, and the
    // startup log line uses `local_addr()` so an operator sees the
    // ACTUAL address the kernel bound.
    //
    // Loopback-only by construction — a misconfiguration that would
    // expose internal counters to the outside network is impossible
    // without changing the code below. The public `/health` route stays
    // on the main listener, byte-identical, so external liveness probes
    // keep working unchanged.
    let health_port = parse_health_port_env("RELAY_HEALTH_PORT", 8081);
    let health_addr = format!("127.0.0.1:{health_port}");
    let health_listener_bind = match tokio::net::TcpListener::bind(&health_addr).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("FATAL: health listener bind {health_addr} failed: {e}");
            std::process::exit(1);
        }
    };
    let bound_health_addr = match health_listener_bind.local_addr() {
        Ok(a) => a.to_string(),
        Err(e) => {
            eprintln!(
                "FATAL: health listener bound but local_addr() failed: {e} (bind target was {health_addr})"
            );
            std::process::exit(1);
        }
    };
    let health_router = health_listener::router(health_listener::HealthListenerState {
        runtime: Arc::clone(app_state.runtime()),
        shutdown_rx: shutdown_rx.clone(),
    });
    tracing::info!(
        event = "health_listener_bound",
        addr = %bound_health_addr,
        "internal loopback health surface listening"
    );

    let public_shutdown_rx = shutdown_rx.clone();
    let public_serve = axum::serve(listener, app).with_graceful_shutdown(async move {
        let mut rx = public_shutdown_rx;
        let _ = rx.wait_for(|&v| v).await;
    });

    // Keep the health listener alive while the public listener drains.
    // `/ready` observes the process-wide shutdown watch and returns 503,
    // while this separate watch controls when the health TCP listener
    // itself stops accepting requests.
    let (health_stop_tx, health_stop_rx) = tokio::sync::watch::channel(false);
    let health_serve =
        axum::serve(health_listener_bind, health_router).with_graceful_shutdown(async move {
            let mut rx = health_stop_rx;
            let _ = rx.wait_for(|&v| v).await;
        });

    // ── PR-2 M4-4 round-1 REDLINE P1-1: absolute-deadline shutdown ──────
    //
    // Pre-round-1 shape: `tokio::join!(public_serve, health_serve)` was
    // UNBOUNDED; the 45 s deadline only started AFTER both listeners
    // returned. A long-lived in-flight request could hold the join
    // forever, and the "45 s shutdown contract" never actually applied
    // to the listener drain. Round-1 wraps every shutdown phase against
    // ONE absolute deadline measured from `signal_at`.
    //
    // Phases and budget:
    //   1. Listener drain — public first while health exposes draining,
    //      then health; both bounded by the ABSOLUTE deadline.
    //   2. Sweep scheduler exit — awaits `sweep_handle` with whatever
    //      budget remains. Closes P1-2 (scheduler must be joined
    //      before `runtime.close()`).
    //   3. `runtime.close()` + `drain_handles(remaining)` — the
    //      pool-drain contract inherits the remaining budget too, so
    //      a slow phase 1/2 shortens phase 3, but the total never
    //      overruns.
    //
    // ANY phase running out of budget → `FATAL: shutdown: ...` +
    // `std::process::exit(1)` (per locked v4.2.1 §1: no new exit code
    // for shutdown deadline).
    let deadline_secs = parse_shutdown_deadline_secs_env("RELAY_SHUTDOWN_DEADLINE_SECS", 45);
    let total = std::time::Duration::from_secs(deadline_secs);

    // Phase 1: serve normally until shutdown, then bound listener drain.
    //
    // E2E REDLINE 2026-07-29: the round-2 shape started
    // `timeout(total, listeners_joined)` immediately at process boot.
    // `elapsed_since_signal` returned zero while `signal_at` was unset, so
    // a healthy relay exited after 45 seconds without any signal. Keep both
    // listener futures actively polled from boot, but arm the timeout only
    // after the shutdown watch flips true.
    let public_serve = std::future::IntoFuture::into_future(public_serve);
    let health_serve = std::future::IntoFuture::into_future(health_serve);
    let (public_res, health_res) = match serve_listeners_until_shutdown(
        public_serve,
        health_serve,
        shutdown_rx.clone(),
        health_stop_tx,
        &signal_at,
        total,
    )
    .await
    {
        Ok(pair) => pair,
        Err(verdict) => emit_fatal_and_exit(verdict),
    };
    if let Err(e) = public_res {
        tracing::error!(error = ?e, "public listener error at shutdown");
    }
    if let Err(e) = health_res {
        tracing::error!(error = ?e, "health listener error at shutdown");
    }

    // Phase 2: sweep scheduler join
    match await_sweep_handle_with_deadline(sweep_handle, &signal_at, total).await {
        Ok(()) => tracing::info!(
            event = "sweep_scheduler_joined",
            "sweep scheduler exited cleanly before pool close"
        ),
        Err(verdict) => emit_fatal_and_exit(verdict),
    }

    // Phase 3: runtime.close() + drain_handles(remaining)
    let elapsed = elapsed_since_signal(&signal_at);
    let remaining = shutdown::remaining_budget(elapsed, total);
    if remaining.is_zero() {
        emit_fatal_and_exit(shutdown::phase_deadline_fatal(
            shutdown::ShutdownPhase::WorkerDrain,
            total,
        ));
    }
    let runtime_for_shutdown = app_state.runtime();
    tracing::info!(
        event = "shutdown_close_initiated",
        deadline_secs,
        remaining_ms = remaining.as_millis() as u64,
        "runtime.close() + drain_handles pending"
    );
    runtime_for_shutdown.close();
    let drain_result = runtime_for_shutdown.drain_handles(remaining).await;
    match shutdown::classify_drain_result(drain_result) {
        shutdown::ShutdownVerdict::Clean { clean_count } => {
            tracing::info!(
                event = "shutdown_complete",
                clean_count,
                "graceful shutdown complete; process exiting"
            );
        }
        shutdown::ShutdownVerdict::Fatal { reason, exit_code } => {
            eprintln!("FATAL: shutdown: {reason}");
            std::process::exit(exit_code);
        }
    }
}

/// **PR-2 M4-4 round-1 REDLINE P1-1**: seconds since the shutdown
/// signal fired. Returns `Duration::ZERO` if the signal has not yet
/// been observed — treated as "the whole budget is still available."
/// The signal handler stores the wall-clock BEFORE flipping the watch,
/// so any code path reaching this after `wait_for(true)` sees a set
/// value.
fn elapsed_since_signal(
    signal_at: &Arc<std::sync::OnceLock<tokio::time::Instant>>,
) -> std::time::Duration {
    match signal_at.get() {
        Some(t) => tokio::time::Instant::now().saturating_duration_since(*t),
        None => std::time::Duration::ZERO,
    }
}

/// E2E REDLINE 2026-07-29: poll both listeners for the full process
/// lifetime, but start the absolute shutdown deadline only after the
/// shutdown watch becomes true. During shutdown, drain public traffic
/// first while health remains available with `/ready=503`; only then
/// stop the health listener.
///
/// A listener that exits before shutdown is fail-closed: serving only one
/// of the public or internal surfaces is not a healthy degraded mode. The
/// `biased` select gives an already-observed shutdown flag priority over a
/// listener completing as part of that same shutdown transition.
async fn serve_listeners_until_shutdown<P, H>(
    public_serve: P,
    health_serve: H,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
    health_stop_tx: tokio::sync::watch::Sender<bool>,
    signal_at: &Arc<std::sync::OnceLock<tokio::time::Instant>>,
    total: std::time::Duration,
) -> Result<(std::io::Result<()>, std::io::Result<()>), shutdown::ShutdownVerdict>
where
    P: std::future::Future<Output = std::io::Result<()>>,
    H: std::future::Future<Output = std::io::Result<()>>,
{
    tokio::pin!(public_serve);
    tokio::pin!(health_serve);

    tokio::select! {
        biased;
        shutdown_result = shutdown_rx.wait_for(|&value| value) => {
            if shutdown_result.is_err() {
                return Err(shutdown::ShutdownVerdict::Fatal {
                    reason: "shutdown watch closed before a shutdown signal was observed".into(),
                    exit_code: shutdown::SHUTDOWN_FATAL_EXIT_CODE,
                });
            }
        }
        result = public_serve.as_mut() => {
            return Err(listener_exited_before_shutdown("public", result));
        }
        result = health_serve.as_mut() => {
            return Err(listener_exited_before_shutdown("health", result));
        }
    }

    let elapsed = elapsed_since_signal(signal_at);
    let remaining = shutdown::remaining_budget(elapsed, total);
    if remaining.is_zero() {
        return Err(shutdown::phase_deadline_fatal(
            shutdown::ShutdownPhase::ListenerDrain,
            total,
        ));
    }

    // Drain the public listener first while the loopback health listener
    // remains available. Its router sees shutdown_rx=true and therefore
    // exposes `/ready` as 503 throughout this interval.
    let public_drain = async {
        tokio::select! {
            biased;
            result = public_serve.as_mut() => Ok(result),
            result = health_serve.as_mut() => {
                Err(listener_exited_during_shutdown("health", result))
            }
        }
    };
    let public_res = match tokio::time::timeout(remaining, public_drain).await {
        Ok(Ok(result)) => result,
        Ok(Err(verdict)) => return Err(verdict),
        Err(_) => {
            return Err(shutdown::phase_deadline_fatal(
                shutdown::ShutdownPhase::ListenerDrain,
                total,
            ))
        }
    };

    // Public traffic is drained. Stop the health listener and charge its
    // own graceful drain against the same absolute shutdown budget.
    health_stop_tx.send_replace(true);
    let elapsed = elapsed_since_signal(signal_at);
    let remaining = shutdown::remaining_budget(elapsed, total);
    if remaining.is_zero() {
        return Err(shutdown::phase_deadline_fatal(
            shutdown::ShutdownPhase::ListenerDrain,
            total,
        ));
    }
    let health_res = match tokio::time::timeout(remaining, health_serve.as_mut()).await {
        Ok(result) => result,
        Err(_) => {
            return Err(shutdown::phase_deadline_fatal(
                shutdown::ShutdownPhase::ListenerDrain,
                total,
            ))
        }
    };

    Ok((public_res, health_res))
}

fn listener_exited_during_shutdown(
    listener: &'static str,
    result: std::io::Result<()>,
) -> shutdown::ShutdownVerdict {
    let detail = match result {
        Ok(()) => "returned before its ordered shutdown phase".to_string(),
        Err(error) => format!("failed during shutdown: {error}"),
    };
    shutdown::ShutdownVerdict::Fatal {
        reason: format!("{listener} listener {detail}"),
        exit_code: shutdown::SHUTDOWN_FATAL_EXIT_CODE,
    }
}

fn listener_exited_before_shutdown(
    listener: &'static str,
    result: std::io::Result<()>,
) -> shutdown::ShutdownVerdict {
    let detail = match result {
        Ok(()) => "returned cleanly without a shutdown signal".to_string(),
        Err(error) => format!("failed before shutdown: {error}"),
    };
    shutdown::ShutdownVerdict::Fatal {
        reason: format!("{listener} listener {detail}"),
        exit_code: shutdown::SHUTDOWN_FATAL_EXIT_CODE,
    }
}

/// **PR-2 M4-4 round-1 REDLINE P1-2 + P1-1**: wait for the sweep
/// scheduler task to exit under whatever budget remains from the
/// absolute deadline. Panic inside the scheduler task is logged but
/// does NOT block shutdown — the pool drain still runs so no worker
/// is stranded.
async fn await_sweep_handle_with_deadline(
    handle: tokio::task::JoinHandle<()>,
    signal_at: &Arc<std::sync::OnceLock<tokio::time::Instant>>,
    total: std::time::Duration,
) -> Result<(), shutdown::ShutdownVerdict> {
    let elapsed = elapsed_since_signal(signal_at);
    let remaining = shutdown::remaining_budget(elapsed, total);
    if remaining.is_zero() {
        return Err(shutdown::phase_deadline_fatal(
            shutdown::ShutdownPhase::SweepJoin,
            total,
        ));
    }
    match tokio::time::timeout(remaining, handle).await {
        Ok(Ok(())) => Ok(()),
        Ok(Err(join_err)) => {
            tracing::error!(
                error = ?join_err,
                "sweep scheduler task join error at shutdown — proceeding to pool drain"
            );
            Ok(())
        }
        Err(_) => Err(shutdown::phase_deadline_fatal(
            shutdown::ShutdownPhase::SweepJoin,
            total,
        )),
    }
}

/// **PR-2 M4-4 round-1 REDLINE P1-1**: uniform emit-and-exit for every
/// phase-fatal shutdown outcome. Never returns.
fn emit_fatal_and_exit(verdict: shutdown::ShutdownVerdict) -> ! {
    match verdict {
        shutdown::ShutdownVerdict::Fatal { reason, exit_code } => {
            eprintln!("FATAL: shutdown: {reason}");
            std::process::exit(exit_code);
        }
        shutdown::ShutdownVerdict::Clean { .. } => {
            // Unreachable — only Fatal is fed here — but keep the
            // exhaustive-match discipline explicit.
            eprintln!("FATAL: shutdown: Clean verdict fed to fatal path (invariant break)");
            std::process::exit(1);
        }
    }
}

/// **PR-2 M4-4 round-2 REDLINE P1-2**: parse the shutdown deadline
/// env with a hard cap. Values `1..=SHUTDOWN_DEADLINE_MAX_SECS` are
/// accepted; `0` and anything above are refused via
/// `shutdown::validate_shutdown_deadline_secs` with FATAL + exit(11).
/// The cap is structurally coupled to the Compose service's
/// `stop_grace_period` (60 s): any accepted value MUST leave
/// SIGKILL comfortably outside the drain window.
fn parse_shutdown_deadline_secs_env(key: &'static str, default: u64) -> u64 {
    let raw = parse_u64_env(key, default);
    match shutdown::validate_shutdown_deadline_secs(raw) {
        Ok(v) => v,
        Err(shutdown::ShutdownDeadlineError::Zero) => {
            eprintln!(
                "FATAL: env {key}=0 refused (a zero deadline gives the pool no time to drain \
                 in-flight work)"
            );
            std::process::exit(11);
        }
        Err(shutdown::ShutdownDeadlineError::AboveCap { got, cap }) => {
            eprintln!(
                "FATAL: env {key}={got} exceeds {cap}s cap (compose stop_grace_period is 60s; \
                 a deadline at or above 60s races SIGKILL and reintroduces the round-1 \
                 SIGKILL-before-contract defect — raise stop_grace_period FIRST if you \
                 genuinely need a larger budget)"
            );
            std::process::exit(11);
        }
    }
}

/// **PR-2 M4-4 round-1 REDLINE P1-3**: parse a health-listener port
/// from env. Same fail-closed shape as `parse_u16_env` PLUS refuses
/// `0` at parse time — pre-round-1 accepted zero which would bind an
/// ephemeral port while the startup log still claimed `127.0.0.1:0`,
/// hiding the misconfig.
fn parse_health_port_env(key: &'static str, default: u16) -> u16 {
    let port = parse_u16_env(key, default);
    if port == 0 {
        eprintln!(
            "FATAL: env {key}=0 refused (port 0 would bind an ephemeral kernel-picked port, \
             hiding the misconfig from ops)"
        );
        std::process::exit(11);
    }
    port
}

/// **PR-2 M4-4**: fail-closed `u16` env parser (health listener port).
/// Same shape as `parse_u64_env` above — `NotPresent` → default, every
/// other outcome → FATAL + `process::exit(11)`.
fn parse_u16_env(key: &'static str, default: u16) -> u16 {
    match classify_u64_env(std::env::var(key)) {
        EnvClassification::Absent => default,
        EnvClassification::Parsed(n) => match u16::try_from(n) {
            Ok(v) => v,
            Err(_) => {
                eprintln!("FATAL: env {key}={n} exceeds u16 range");
                std::process::exit(11);
            }
        },
        EnvClassification::NonUtf8 => {
            eprintln!("FATAL: env {key} is not valid UTF-8");
            std::process::exit(11);
        }
        EnvClassification::Malformed { raw, detail } => {
            eprintln!("FATAL: env {key}={raw} not a valid u16: {detail}");
            std::process::exit(11);
        }
    }
}

// ─── PR-2 M4-2b round-2 REDLINE: config hardening ──────────────────────

/// **M4-2b round-2 REDLINE P1-3**: typed classification of a
/// `std::env::var` result. Fully fail-closed: only
/// `VarError::NotPresent` yields "use default". Every other
/// outcome — non-UTF-8, malformed value — is a hard error the
/// caller MUST surface as `FATAL:` + `process::exit(11)`.
///
/// Extracted as a pure enum so tests can drive every branch
/// without invoking `process::exit`.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum EnvClassification {
    /// Env unset — caller should apply its default.
    Absent,
    /// Env set to a well-parsed value.
    Parsed(u64),
    /// Env set but its bytes are not valid UTF-8. Fail-closed.
    NonUtf8,
    /// Env set to a UTF-8 string that failed the target-type
    /// parse. Fail-closed. The raw string is preserved for the
    /// fatal-line diagnostic.
    Malformed { raw: String, detail: String },
}

/// **M4-2b round-2 P1-3**: classify a `u64` env var. Round-1's
/// `.ok()` collapsed `NotUnicode` into `None` (silent
/// default) — the classifier below distinguishes so
/// `NotUnicode` becomes `NonUtf8` (hard error).
pub(crate) fn classify_u64_env(
    raw: Result<String, std::env::VarError>,
) -> EnvClassification {
    match raw {
        Err(std::env::VarError::NotPresent) => EnvClassification::Absent,
        Err(std::env::VarError::NotUnicode(_)) => EnvClassification::NonUtf8,
        Ok(v) => match v.parse::<u64>() {
            Ok(n) => EnvClassification::Parsed(n),
            Err(e) => EnvClassification::Malformed {
                raw: v,
                detail: format!("{e}"),
            },
        },
    }
}

/// **M4-2b round-2 P1-3**: classify an octal mode env var
/// (e.g., `"0o027"` or `"027"`). Same fail-closed shape as
/// [`classify_u64_env`].
pub(crate) fn classify_octal_env(
    raw: Result<String, std::env::VarError>,
) -> EnvClassification {
    match raw {
        Err(std::env::VarError::NotPresent) => EnvClassification::Absent,
        Err(std::env::VarError::NotUnicode(_)) => EnvClassification::NonUtf8,
        Ok(v) => match u32::from_str_radix(v.trim_start_matches("0o"), 8) {
            Ok(n) => EnvClassification::Parsed(n as u64),
            Err(e) => EnvClassification::Malformed {
                raw: v,
                detail: format!("{e}"),
            },
        },
    }
}

/// **M4-2b round-2 P0-1**: fail-closed check that preflight
/// caps cover the runtime admission caps. Extracted as a pure
/// fn so tests can drive the mismatch branches without
/// spawning a runtime. Returns human-readable violation
/// strings; empty vec means all three dimensions are covered.
///
/// The invariant: `preflight >= runtime` for every dimension.
/// A runtime that admits more than the boot loader is willing
/// to restore leaves durable state the next boot rejects with
/// `BootError::BudgetExceeded`, and the operator would only
/// discover the mis-config after a restart. Fail at startup
/// instead.
pub(crate) fn check_preflight_covers_runtime(
    preflight: &boot_loader::PreflightCaps,
    runtime: &capacity_ledger::CapacityCaps,
) -> Vec<String> {
    let mut violations = Vec::new();
    if runtime.max_envelopes > preflight.max_envelopes {
        violations.push(format!(
            "runtime.max_envelopes ({}) > preflight.max_envelopes ({}) — next boot would refuse to restore",
            runtime.max_envelopes, preflight.max_envelopes
        ));
    }
    if runtime.max_bytes > preflight.max_bytes {
        violations.push(format!(
            "runtime.max_bytes ({}) > preflight.max_bytes ({}) — next boot would refuse to restore",
            runtime.max_bytes, preflight.max_bytes
        ));
    }
    if runtime.ram_budget > preflight.ram_budget {
        violations.push(format!(
            "runtime.ram_budget ({}) > preflight.ram_budget ({}) — next boot would refuse to restore",
            runtime.ram_budget, preflight.ram_budget
        ));
    }
    violations
}

/// **PR-2 M6-1**: single source of truth for both the preflight
/// and the runtime RAM-budget defaults. Value calibrated by
/// PR-2 M5b Mac/Docker benchmark against `--memory 512m`
/// (worst-case scenario `small-broad`, observed ratio 4.4908×,
/// cgroup peak was limiting probe): a 128 MiB safety margin
/// yields a candidate budget of 84.37 MiB, floored to a
/// conservative 16 MiB step. See
/// `docs/adr/ADR-027-relay-queue-durability-and-ram-budget.md`
/// (M6-3) for the calibration record.
const DEFAULT_RELAY_RAM_BUDGET_BYTES: u64 = 80 * 1024 * 1024;

/// **M4-2b round-2 P0-1**: preflight cap defaults. Chosen at
/// the same values as the runtime cap defaults below so the
/// `preflight >= runtime` invariant holds when the operator
/// sets no envs.
///
/// Envs (fail-closed on malformed / non-UTF-8):
///   * `RELAY_PREFLIGHT_MAX_ENVELOPES`  (default 100_000)
///   * `RELAY_PREFLIGHT_MAX_BYTES`      (default 384 MiB)
///   * `RELAY_PREFLIGHT_RAM_BUDGET`     (default 80 MiB —
///     `DEFAULT_RELAY_RAM_BUDGET_BYTES`, PR-2 M6-1)
fn preflight_caps_from_env() -> boot_loader::PreflightCaps {
    boot_loader::PreflightCaps {
        max_envelopes: parse_u64_env("RELAY_PREFLIGHT_MAX_ENVELOPES", 100_000),
        max_bytes: parse_u64_env("RELAY_PREFLIGHT_MAX_BYTES", 384 * 1024 * 1024),
        ram_budget: parse_u64_env("RELAY_PREFLIGHT_RAM_BUDGET", DEFAULT_RELAY_RAM_BUDGET_BYTES),
    }
}

/// **M4-2b round-2 P0-2**: ownership attestation, env-driven,
/// hardened. Round-1 defaults were permissive (`None` uid +
/// gid + `0o022` allowed 0755); the locked witness pinned
/// `10001:10001:0750`.
///
/// Envs (fail-closed on malformed / non-UTF-8):
///   * `RELAY_EXPECTED_UID`   (u32; default 10001)
///   * `RELAY_EXPECTED_GID`   (u32; default 10001)
///   * `RELAY_MODE_FORBIDDEN` (octal; default 0o027 —
///                             group-write, group-exec-check,
///                             any world bit refused)
fn ownership_from_env() -> boot_loader::OwnershipExpectation {
    let expected_uid = parse_uid_env("RELAY_EXPECTED_UID", Some(10001));
    let expected_gid = parse_uid_env("RELAY_EXPECTED_GID", Some(10001));
    let mode_forbidden = match classify_octal_env(std::env::var("RELAY_MODE_FORBIDDEN")) {
        EnvClassification::Absent => 0o027,
        EnvClassification::Parsed(n) => n as u32,
        EnvClassification::NonUtf8 => {
            eprintln!("FATAL: RELAY_MODE_FORBIDDEN is not valid UTF-8");
            std::process::exit(11);
        }
        EnvClassification::Malformed { raw, detail } => {
            eprintln!("FATAL: RELAY_MODE_FORBIDDEN={raw} not a valid octal: {detail}");
            std::process::exit(11);
        }
    };
    boot_loader::OwnershipExpectation {
        expected_uid,
        expected_gid,
        mode_forbidden,
    }
}

/// **M4-2b round-2 P0-1**: capacity-ledger caps. Defaults now
/// equal the preflight defaults so the `preflight >= runtime`
/// invariant holds without operator config; a startup check
/// (`check_preflight_covers_runtime`) trips FATAL if an
/// operator override breaks it in either direction.
///
/// Envs (fail-closed on malformed / non-UTF-8):
///   * `RELAY_QUEUE_MAX_ENVELOPES`     (default 100_000)
///   * `RELAY_QUEUE_MAX_BYTES`         (default 384 MiB)
///   * `RELAY_QUEUE_RAM_BUDGET_BYTES`  (default 80 MiB —
///     `DEFAULT_RELAY_RAM_BUDGET_BYTES`, PR-2 M6-1; well
///     under the 512 MiB compose ceiling)
fn capacity_caps_from_env() -> capacity_ledger::CapacityCaps {
    capacity_ledger::CapacityCaps {
        max_envelopes: parse_u64_env("RELAY_QUEUE_MAX_ENVELOPES", 100_000),
        max_bytes: parse_u64_env("RELAY_QUEUE_MAX_BYTES", 384 * 1024 * 1024),
        ram_budget: parse_u64_env(
            "RELAY_QUEUE_RAM_BUDGET_BYTES",
            DEFAULT_RELAY_RAM_BUDGET_BYTES,
        ),
    }
}

/// **M4-2b round-2 P1-3**: parse a `u64` env var, fail-closed
/// on non-UTF-8 or malformed input. `NotPresent` uses the
/// caller's default; NON-`NotPresent` errors are FATAL.
fn parse_u64_env(key: &'static str, default: u64) -> u64 {
    match classify_u64_env(std::env::var(key)) {
        EnvClassification::Absent => default,
        EnvClassification::Parsed(n) => n,
        EnvClassification::NonUtf8 => {
            eprintln!("FATAL: env {key} is not valid UTF-8");
            std::process::exit(11);
        }
        EnvClassification::Malformed { raw, detail } => {
            eprintln!("FATAL: env {key}={raw} not a valid u64: {detail}");
            std::process::exit(11);
        }
    }
}

/// **M4-2b round-2 P0-2**: parse a `u32` UID/GID env var with
/// a mandatory production default (10001). `None` default is
/// only meaningful in tests via `permissive_for_tests`.
fn parse_uid_env(key: &'static str, default: Option<u32>) -> Option<u32> {
    match classify_u64_env(std::env::var(key)) {
        EnvClassification::Absent => default,
        EnvClassification::Parsed(n) => {
            if n > u32::MAX as u64 {
                eprintln!("FATAL: env {key}={n} exceeds u32::MAX");
                std::process::exit(11);
            }
            Some(n as u32)
        }
        EnvClassification::NonUtf8 => {
            eprintln!("FATAL: env {key} is not valid UTF-8");
            std::process::exit(11);
        }
        EnvClassification::Malformed { raw, detail } => {
            eprintln!("FATAL: env {key}={raw} not a valid u32: {detail}");
            std::process::exit(11);
        }
    }
}

// ─── PR-2 M4-2b round-1 REDLINE P1-7 coverage ─────────────────────────
//
// `main.rs` is compiled + linked as a separate test binary
// (`cargo test --bin phantom-relay`) so its unit tests run
// alongside the library suite. The env parsers are the
// production-hardening surface flagged by architect P0-1/P0-2;
// pinning defaults + failure modes here is the M4-2b round-1
// gate.
//
// The architect's second half of P1-7 ("suite не исполняет
// новый production main.rs") is addressed by the fact that
// EVERY integration test in `services/relay/tests/*.rs` now
// goes through `state::build_test_app_state`, which runs the
// SAME `boot() → from_boot() → spawn_worker_runtime()`
// sequence `main.rs` runs — just with per-test hermetic
// TempDir instead of an operator's state_dir. The only main-
// only surface is the env parsing (below) + the signal /
// listener glue (deferred to M4-4).
#[cfg(test)]
mod tests {
    use super::*;
    use serial_test::serial;

    // The env-parser tests mutate process env; `serial_test`
    // (already in dev-deps) serialises them so parallel test
    // workers don't race.

    #[test]
    #[serial]
    fn parse_u64_env_returns_default_when_unset() {
        std::env::remove_var("RELAY_PARSE_U64_DEFAULT_KEY");
        assert_eq!(parse_u64_env("RELAY_PARSE_U64_DEFAULT_KEY", 42), 42);
    }

    #[test]
    #[serial]
    fn parse_u64_env_reads_valid_value() {
        std::env::set_var("RELAY_PARSE_U64_VALID_KEY", "123456");
        assert_eq!(parse_u64_env("RELAY_PARSE_U64_VALID_KEY", 0), 123456);
        std::env::remove_var("RELAY_PARSE_U64_VALID_KEY");
    }

    #[test]
    #[serial]
    fn preflight_caps_defaults_are_below_compose_ceiling() {
        // Fail-closed defaults chosen deliberately below the
        // compose-file 512 MiB memory ceiling. PR-2 M6-1
        // recalibrated `ram_budget` to
        // `DEFAULT_RELAY_RAM_BUDGET_BYTES` (80 MiB) — pinning it
        // in this test guards against a silent revert to the
        // pre-M6 384 MiB default the measurement rejected.
        std::env::remove_var("RELAY_PREFLIGHT_MAX_ENVELOPES");
        std::env::remove_var("RELAY_PREFLIGHT_MAX_BYTES");
        std::env::remove_var("RELAY_PREFLIGHT_RAM_BUDGET");
        let caps = preflight_caps_from_env();
        assert_eq!(caps.max_envelopes, 100_000);
        assert_eq!(caps.max_bytes, 384 * 1024 * 1024);
        assert_eq!(caps.ram_budget, DEFAULT_RELAY_RAM_BUDGET_BYTES);
        assert_eq!(caps.ram_budget, 83_886_080);
        assert!(
            caps.ram_budget < 512 * 1024 * 1024,
            "ram_budget must stay below 512 MiB compose ceiling"
        );
    }

    #[test]
    #[serial]
    fn preflight_caps_reads_env_overrides() {
        std::env::set_var("RELAY_PREFLIGHT_MAX_ENVELOPES", "999");
        std::env::set_var("RELAY_PREFLIGHT_MAX_BYTES", "1024");
        std::env::set_var("RELAY_PREFLIGHT_RAM_BUDGET", "2048");
        let caps = preflight_caps_from_env();
        assert_eq!(caps.max_envelopes, 999);
        assert_eq!(caps.max_bytes, 1024);
        assert_eq!(caps.ram_budget, 2048);
        std::env::remove_var("RELAY_PREFLIGHT_MAX_ENVELOPES");
        std::env::remove_var("RELAY_PREFLIGHT_MAX_BYTES");
        std::env::remove_var("RELAY_PREFLIGHT_RAM_BUDGET");
    }

    #[test]
    #[serial]
    fn capacity_caps_defaults_are_below_compose_ceiling() {
        // PR-2 M6-1: runtime `ram_budget` now shares the M5b-
        // calibrated `DEFAULT_RELAY_RAM_BUDGET_BYTES` with the
        // preflight parser. The literal byte-count is pinned in
        // parallel with the constant so a future accidental
        // change to the constant surfaces in this test.
        std::env::remove_var("RELAY_QUEUE_MAX_ENVELOPES");
        std::env::remove_var("RELAY_QUEUE_MAX_BYTES");
        std::env::remove_var("RELAY_QUEUE_RAM_BUDGET_BYTES");
        let caps = capacity_caps_from_env();
        assert_eq!(caps.max_envelopes, 100_000);
        assert_eq!(caps.max_bytes, 384 * 1024 * 1024);
        assert_eq!(caps.ram_budget, DEFAULT_RELAY_RAM_BUDGET_BYTES);
        assert_eq!(caps.ram_budget, 83_886_080);
        assert!(
            caps.ram_budget < 512 * 1024 * 1024,
            "ram_budget must stay below 512 MiB compose ceiling"
        );
    }

    // ─── M4-2b round-2 P0-2: ownership hardening ─────────────

    #[test]
    #[serial]
    fn ownership_defaults_are_hardened_10001_10001_0o027() {
        std::env::remove_var("RELAY_EXPECTED_UID");
        std::env::remove_var("RELAY_EXPECTED_GID");
        std::env::remove_var("RELAY_MODE_FORBIDDEN");
        let own = ownership_from_env();
        assert_eq!(
            own.expected_uid,
            Some(10001),
            "production UID must default to 10001 (locked witness value)"
        );
        assert_eq!(
            own.expected_gid,
            Some(10001),
            "production GID must default to 10001 (locked witness value)"
        );
        assert_eq!(
            own.mode_forbidden, 0o027,
            "default mask must refuse group-write, group-exec-check, any world bit"
        );
    }

    #[test]
    #[serial]
    fn ownership_reads_uid_and_gid_and_octal_mode() {
        std::env::set_var("RELAY_EXPECTED_UID", "10001");
        std::env::set_var("RELAY_EXPECTED_GID", "10002");
        std::env::set_var("RELAY_MODE_FORBIDDEN", "0o077");
        let own = ownership_from_env();
        assert_eq!(own.expected_uid, Some(10001));
        assert_eq!(own.expected_gid, Some(10002));
        assert_eq!(own.mode_forbidden, 0o077);
        std::env::remove_var("RELAY_EXPECTED_UID");
        std::env::remove_var("RELAY_EXPECTED_GID");
        std::env::remove_var("RELAY_MODE_FORBIDDEN");
    }

    // ─── M4-2b round-2 P1-3: fail-closed env classification ──

    #[test]
    fn classify_u64_env_absent_returns_absent() {
        let out = classify_u64_env(Err(std::env::VarError::NotPresent));
        assert_eq!(out, EnvClassification::Absent);
    }

    #[test]
    fn classify_u64_env_valid_returns_parsed() {
        let out = classify_u64_env(Ok("42".into()));
        assert_eq!(out, EnvClassification::Parsed(42));
    }

    #[test]
    fn classify_u64_env_malformed_returns_malformed_never_absent() {
        // Round-1's `.ok()` collapsed this to `None` → silent
        // default. Post-fix it MUST surface a distinct variant.
        let out = classify_u64_env(Ok("not-a-number".into()));
        match out {
            EnvClassification::Malformed { raw, .. } => assert_eq!(raw, "not-a-number"),
            other => panic!("expected Malformed, got: {other:?}"),
        }
    }

    #[test]
    fn classify_u64_env_non_utf8_returns_non_utf8_never_absent() {
        use std::ffi::OsString;
        #[cfg(unix)]
        let bad_bytes: OsString = std::os::unix::ffi::OsStringExt::from_vec(vec![0xFFu8]);
        #[cfg(windows)]
        let bad_bytes: OsString =
            std::os::windows::ffi::OsStringExt::from_wide(&[0xD800u16]);
        let out = classify_u64_env(Err(std::env::VarError::NotUnicode(bad_bytes)));
        assert_eq!(out, EnvClassification::NonUtf8);
    }

    #[test]
    fn classify_octal_env_accepts_leading_0o_prefix_and_bare_octal() {
        assert_eq!(
            classify_octal_env(Ok("0o027".into())),
            EnvClassification::Parsed(0o027)
        );
        assert_eq!(
            classify_octal_env(Ok("027".into())),
            EnvClassification::Parsed(0o027)
        );
    }

    #[test]
    fn classify_octal_env_malformed_returns_malformed() {
        let out = classify_octal_env(Ok("8".into())); // 8 is not an octal digit
        assert!(
            matches!(out, EnvClassification::Malformed { .. }),
            "expected Malformed for non-octal digit"
        );
    }

    // ─── M4-2b round-2 P0-1: preflight >= runtime invariant ──

    #[test]
    fn preflight_covers_runtime_matches_are_ok() {
        let pf = boot_loader::PreflightCaps {
            max_envelopes: 100,
            max_bytes: 1_000,
            ram_budget: 2_000,
        };
        let rt = capacity_ledger::CapacityCaps {
            max_envelopes: 100,
            max_bytes: 1_000,
            ram_budget: 2_000,
        };
        assert!(check_preflight_covers_runtime(&pf, &rt).is_empty());
    }

    #[test]
    fn preflight_covers_runtime_underscoped_ram_is_a_violation() {
        let pf = boot_loader::PreflightCaps {
            max_envelopes: 100,
            max_bytes: 1_000,
            ram_budget: 100, // < runtime
        };
        let rt = capacity_ledger::CapacityCaps {
            max_envelopes: 100,
            max_bytes: 1_000,
            ram_budget: 2_000,
        };
        let violations = check_preflight_covers_runtime(&pf, &rt);
        assert_eq!(violations.len(), 1);
        assert!(violations[0].contains("ram_budget"));
    }

    #[test]
    fn preflight_covers_runtime_all_three_can_be_violated() {
        let pf = boot_loader::PreflightCaps {
            max_envelopes: 1,
            max_bytes: 2,
            ram_budget: 3,
        };
        let rt = capacity_ledger::CapacityCaps {
            max_envelopes: 10,
            max_bytes: 20,
            ram_budget: 30,
        };
        let violations = check_preflight_covers_runtime(&pf, &rt);
        assert_eq!(violations.len(), 3);
        assert!(violations.iter().any(|v| v.contains("max_envelopes")));
        assert!(violations.iter().any(|v| v.contains("max_bytes")));
        assert!(violations.iter().any(|v| v.contains("ram_budget")));
    }

    #[test]
    #[serial]
    fn env_defaults_yield_covering_preflight_no_violations() {
        // Guarantee: with no env overrides, the production
        // defaults produce a `preflight >= runtime` set. Any
        // future default drift that breaks this reads as a
        // test failure BEFORE it ships.
        std::env::remove_var("RELAY_PREFLIGHT_MAX_ENVELOPES");
        std::env::remove_var("RELAY_PREFLIGHT_MAX_BYTES");
        std::env::remove_var("RELAY_PREFLIGHT_RAM_BUDGET");
        std::env::remove_var("RELAY_QUEUE_MAX_ENVELOPES");
        std::env::remove_var("RELAY_QUEUE_MAX_BYTES");
        std::env::remove_var("RELAY_QUEUE_RAM_BUDGET_BYTES");
        let pf = preflight_caps_from_env();
        let rt = capacity_caps_from_env();
        let violations = check_preflight_covers_runtime(&pf, &rt);
        assert!(
            violations.is_empty(),
            "default cap set MUST satisfy preflight >= runtime: {violations:?}"
        );
    }

    // ─── M4-4 E2E REDLINE: listener deadline anchoring ──────

    #[tokio::test(start_paused = true)]
    async fn listeners_survive_past_shutdown_budget_without_signal_then_timeout_from_signal() {
        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let (health_stop_tx, _health_stop_rx) = tokio::sync::watch::channel(false);
        let signal_at = Arc::new(std::sync::OnceLock::new());
        let signal_for_task = Arc::clone(&signal_at);
        let handle = tokio::spawn(async move {
            serve_listeners_until_shutdown(
                std::future::pending::<std::io::Result<()>>(),
                std::future::pending::<std::io::Result<()>>(),
                shutdown_rx,
                health_stop_tx,
                &signal_for_task,
                std::time::Duration::from_secs(5),
            )
            .await
        });

        tokio::task::yield_now().await;
        tokio::time::advance(std::time::Duration::from_secs(7)).await;
        tokio::task::yield_now().await;
        assert!(
            !handle.is_finished(),
            "listener phase must not treat shutdown budget as process uptime"
        );

        signal_at
            .set(tokio::time::Instant::now())
            .expect("test signal instant set once");
        shutdown_tx.send_replace(true);
        tokio::task::yield_now().await;

        tokio::time::advance(std::time::Duration::from_secs(4)).await;
        tokio::task::yield_now().await;
        assert!(
            !handle.is_finished(),
            "listener deadline must not fire before five seconds after signal"
        );

        tokio::time::advance(std::time::Duration::from_secs(1)).await;
        tokio::task::yield_now().await;
        let verdict = handle
            .await
            .expect("listener phase task joined")
            .expect_err("pending listeners must hit post-signal deadline");
        match verdict {
            shutdown::ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, shutdown::SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("listener drain"));
                assert!(reason.contains("5s absolute shutdown deadline"));
            }
            other => panic!("expected listener deadline fatal, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn listeners_complete_cleanly_after_shutdown_signal() {
        fn listener(
            mut rx: tokio::sync::watch::Receiver<bool>,
        ) -> impl std::future::Future<Output = std::io::Result<()>> {
            async move {
                rx.wait_for(|&value| value).await.map_err(|error| {
                    std::io::Error::other(format!("shutdown watch closed: {error}"))
                })?;
                Ok(())
            }
        }

        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let (health_stop_tx, health_stop_rx) = tokio::sync::watch::channel(false);
        let signal_at = Arc::new(std::sync::OnceLock::new());
        let signal_for_task = Arc::clone(&signal_at);
        let public = listener(shutdown_rx.clone());
        let health = listener(health_stop_rx);
        let handle = tokio::spawn(async move {
            serve_listeners_until_shutdown(
                public,
                health,
                shutdown_rx,
                health_stop_tx,
                &signal_for_task,
                std::time::Duration::from_secs(5),
            )
            .await
        });

        tokio::task::yield_now().await;
        signal_at
            .set(tokio::time::Instant::now())
            .expect("test signal instant set once");
        shutdown_tx.send_replace(true);

        let (public_result, health_result) = handle
            .await
            .expect("listener phase task joined")
            .expect("both listeners drain cleanly");
        assert!(public_result.is_ok());
        assert!(health_result.is_ok());
    }

    #[tokio::test]
    async fn listener_exit_before_signal_is_fail_closed() {
        let (_shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let (health_stop_tx, _health_stop_rx) = tokio::sync::watch::channel(false);
        let signal_at = Arc::new(std::sync::OnceLock::new());
        let verdict = serve_listeners_until_shutdown(
            std::future::ready(Ok(())),
            std::future::pending::<std::io::Result<()>>(),
            shutdown_rx,
            health_stop_tx,
            &signal_at,
            std::time::Duration::from_secs(5),
        )
        .await
        .expect_err("public listener exited before shutdown");

        match verdict {
            shutdown::ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, shutdown::SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("public listener"));
                assert!(reason.contains("without a shutdown signal"));
            }
            other => panic!("expected early-listener fatal, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn health_listener_stays_live_until_public_listener_finishes_draining() {
        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let (health_stop_tx, health_stop_rx) = tokio::sync::watch::channel(false);
        let health_stop_observer = health_stop_rx.clone();
        let (public_done_tx, public_done_rx) = tokio::sync::oneshot::channel::<()>();
        let public = async move {
            public_done_rx.await.map_err(|error| {
                std::io::Error::other(format!("public completion sender dropped: {error}"))
            })?;
            Ok(())
        };
        let health = async move {
            let mut rx = health_stop_rx;
            rx.wait_for(|&value| value).await.map_err(|error| {
                std::io::Error::other(format!("health stop watch closed: {error}"))
            })?;
            Ok(())
        };
        let signal_at = Arc::new(std::sync::OnceLock::new());
        let signal_for_task = Arc::clone(&signal_at);
        let handle = tokio::spawn(async move {
            serve_listeners_until_shutdown(
                public,
                health,
                shutdown_rx,
                health_stop_tx,
                &signal_for_task,
                std::time::Duration::from_secs(5),
            )
            .await
        });

        tokio::task::yield_now().await;
        signal_at
            .set(tokio::time::Instant::now())
            .expect("test signal instant set once");
        shutdown_tx.send_replace(true);
        tokio::task::yield_now().await;

        assert!(
            !*health_stop_observer.borrow(),
            "health listener must remain live while public requests drain"
        );
        assert!(!handle.is_finished());

        public_done_tx
            .send(())
            .expect("public listener completion delivered");
        let (public_result, health_result) = handle
            .await
            .expect("listener phase task joined")
            .expect("ordered listener drain clean");
        assert!(public_result.is_ok());
        assert!(health_result.is_ok());
        assert!(
            *health_stop_observer.borrow(),
            "health listener stops after public drain"
        );
    }
}
