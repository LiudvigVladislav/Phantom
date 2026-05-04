// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use crate::{
    envelope::*,
    error::RelayError,
    prekeys::{
        DeleteError, OneTimePreKeyPublicBundle, PreKeyBundle, PreKeyStatus, PublishError,
        SignedPreKeyPublicBundle,
    },
    push::wake_offline_recipient,
    state::{
        append_block_to_disk, append_push_token_to_disk, append_report_to_disk,
        AbuseReport, AppState, PushTokenRecord, RateEntry,
    },
};
use subtle::ConstantTimeEq;
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path, Query, State,
    },
    http::StatusCode,
    response::IntoResponse,
    routing::{delete, get, post},
    Json, Router,
};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tower_http::{
    limit::RequestBodyLimitLayer,
    timeout::TimeoutLayer,
    trace::TraceLayer,
};
use axum::http::Request;
use std::sync::atomic::Ordering;

pub fn router(state: Arc<AppState>) -> Router {
    let max_body = state.config.max_payload_bytes + 1024;

    // TimeoutLayer must NOT apply to /ws — WebSocket connections are long-lived
    // and a 30-second timeout would kill idle-but-healthy sessions. Scope it
    // to the HTTP sub-router only; /ws is mounted separately with no timeout.
    let http_routes = Router::new()
        .route("/health",             get(health))
        .route("/send",               post(send_envelope))
        .route("/fetch/{recipient}",  get(fetch_envelopes))
        .route("/ack/{id}",           delete(ack_envelope))
        .route("/report",             post(submit_report))
        .route("/admin/reports",      get(admin_list_reports))
        .route("/admin/block",        post(admin_block_key))
        .route("/admin/blocklist",    get(admin_list_blocklist))
        // ── X3DH prekey bundle endpoints (ADR-009) ─────────────────────
        // No admin token: prekey bundles are public material by design,
        // but per-identity rate limits inside each handler defeat OPK
        // drain attacks and abusive publish loops.
        .route("/prekeys/publish",                post(publish_prekeys))
        .route("/prekeys/bundle/{identity}",      get(fetch_bundle))
        .route("/prekeys/status/{identity}",      get(prekey_status))
        .route("/prekeys/{identity}/opk/{key_id}", delete(delete_opk))
        // ── UnifiedPush wake-up registration (ADR-016) ─────────────────
        // Client publishes its ntfy topic URL here so the relay can POST
        // a one-byte wake-up when an envelope arrives while the client
        // is offline. The relay never inspects or retains push payloads;
        // the topic URL is the only thing stored.
        .route("/push/register", post(register_push_token))
        .layer(TimeoutLayer::with_status_code(
            axum::http::StatusCode::REQUEST_TIMEOUT,
            Duration::from_secs(30),
        ));

    Router::new()
        .route("/ws", get(ws_handler))
        .merge(http_routes)
        // Log only method + path — never query string, to avoid leaking ?token= secrets.
        .layer(TraceLayer::new_for_http().make_span_with(|req: &Request<_>| {
            tracing::info_span!(
                "request",
                method = %req.method(),
                path   = %req.uri().path(),
            )
        }))
        .layer(RequestBodyLimitLayer::new(max_body))
        .with_state(state)
}

// ── WebSocket ─────────────────────────────────────────────────────────────────

async fn ws_handler(
    ws: WebSocketUpgrade,
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    let id = params.get("id").cloned().unwrap_or_default();

    // Token check: if RELAY_SECRET_TOKEN is set, require a matching ?token=
    // query parameter.  The relay never logs the token value — only compares it.
    // Trust boundary: this is Alpha-0 shared-secret protection for a private
    // demo relay; it is not a replacement for per-user authentication.
    if let Some(expected) = &state.config.secret_token {
        let provided = params.get("token").map(|s| s.as_str()).unwrap_or("");
        let token_ok: bool = provided.as_bytes().ct_eq(expected.as_bytes()).into();
        if !token_ok {
            tracing::warn!(id = %&id[..id.len().min(16)], "ws rejected: bad or missing token");
            return (StatusCode::UNAUTHORIZED, "Unauthorized").into_response();
        }
    }

    ws.on_upgrade(move |socket| handle_socket(socket, id, state))
}

async fn handle_socket(mut socket: WebSocket, identity: String, state: Arc<AppState>) {
    use futures_util::StreamExt;

    // Single-task select! loop instead of split() + two spawned tasks.
    //
    // Why: after `socket.split()`, the read half (SplitStream) and write half
    // (SplitSink) share the underlying tungstenite connection through a BiLock.
    // tungstenite enqueues an auto-PONG when a PING is received, but that PONG
    // is only flushed to the wire on the *next write attempt* through the Sink
    // half. If the relay has nothing to forward (idle period — no live delivery,
    // no ack to send), the auto-PONG sits in the queue forever and the client's
    // OkHttp pingInterval(15s) trips a SocketTimeoutException after 15 seconds
    // of "sent ping but didn't receive pong" — visible in QA-v5 as a fixed
    // ~45-60-second reconnect cycle on every client (after_3-4_successful_ping_pongs)
    // regardless of network path (cellular vs. WiFi vs. emulator-on-host).
    //
    // The single-task select! reads frames AND writes frames from the same
    // future, so an explicit `socket.send(Message::Pong(payload))` on every
    // received PING fires immediately — no auto-PONG queue, no idle starvation.

    // Channel: relay → this client
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();

    // Register client — mint a unique connection ID so the cleanup path can
    // distinguish this session from a later reconnect that races with our exit.
    let conn_id = state.conn_counter.fetch_add(1, Ordering::Relaxed);
    if !identity.is_empty() {
        state.clients.write().await.insert(identity.clone(), (conn_id, tx.clone()));
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        tracing::info!(
            event  = "connect",
            key    = %&identity[..identity.len().min(16)],
            ts_ms  = ts,
            "metadata"
        );

        // Re-flush every envelope the store still holds for this recipient.
        // We deliberately do NOT drain the queue here — envelopes stay in the
        // store until the client confirms each one with {"type":"ack-deliver"}.
        // This makes reconnect-redelivery durable across:
        //   • mpsc-channel-pushed-but-WS-write-failed gaps,
        //   • client-process-killed-after-receive-but-before-decrypt gaps,
        //   • client-decrypted-but-DB-insert-failed gaps.
        // The client deduplicates on the messages.id PRIMARY KEY (INSERT OR
        // IGNORE), so a recipient that successfully processed an envelope on
        // a prior session simply ignores the duplicate on reconnect.
        let queued: Vec<Envelope> = {
            let mut store = state.store.write().await;
            let queue = store.entry(identity.clone()).or_default();
            queue.retain(|e| !e.is_expired());
            queue.clone()
        };
        if !queued.is_empty() {
            tracing::info!(id = %&identity[..identity.len().min(16)], count = queued.len(), "flushing queued envelopes (retained until ack-deliver)");
        }
        for env in queued {
            let deliver = serde_json::json!({
                "type":         "deliver",
                // For sealed messages `from` is empty and the recipient recovers
                // sender identity by decrypting `sealedSender` client-side.
                "from":         if env.sealed_sender.is_empty() { &env.from } else { "" },
                "sealedSender": env.sealed_sender,
                "payload":      env.payload,
                "messageId":    env.id,
            });
            let _ = tx.send(deliver.to_string());
        }
    }

    // Single-task read/write loop.
    loop {
        tokio::select! {
            // Outbound: forward whatever the relay queued for this client.
            outbound = rx.recv() => {
                let Some(text) = outbound else { break };
                if socket.send(Message::Text(text.into())).await.is_err() {
                    break;
                }
            }
            // Inbound: process frames from the client.
            inbound = socket.next() => {
                match inbound {
                    Some(Ok(Message::Text(text))) => {
                        handle_message(text.as_str(), &identity, &state).await;
                    }
                    Some(Ok(Message::Ping(payload))) => {
                        // Explicit, immediate PONG — required because we no
                        // longer rely on tungstenite's queued auto-PONG (see
                        // top-of-fn comment).
                        if socket.send(Message::Pong(payload)).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) => break,
                    Some(Ok(_)) => {}             // Pong/Binary: ignored.
                    Some(Err(_)) | None => break, // Read error or stream end.
                }
            }
        }
    }

    // Unregister client — only remove if the stored conn_id still matches ours.
    // If the client reconnected before this cleanup ran, the new session has
    // already inserted a fresh (conn_id, tx) entry; removing it blindly would
    // cause the new session to go silent (no live delivery) until its next
    // reconnect. Comparing conn_id is the minimal fix for this race.
    if !identity.is_empty() {
        let mut clients = state.clients.write().await;
        if clients.get(&identity).map(|(id, _)| *id) == Some(conn_id) {
            clients.remove(&identity);
        }
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        tracing::info!(
            event = "disconnect",
            key   = %&identity[..identity.len().min(16)],
            ts_ms = ts,
            "metadata"
        );
    }
}

async fn handle_message(text: &str, from_identity: &str, state: &Arc<AppState>) {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        return;
    };

    match value.get("type").and_then(|t| t.as_str()) {
        Some("send") => {
            let to      = value["to"].as_str().unwrap_or("").to_string();
            // `from_identity` is the authenticated WS connection identity.
            // It is used ONLY for rate-limiting and blocklist checks — it is
            // never stored in the envelope or emitted in logs for sealed messages.
            // Client-supplied "from" fields are intentionally ignored; spoofing
            // prevention is enforced at the connection layer, not the frame layer.
            let sealed_sender = value["sealedSender"].as_str().unwrap_or("").to_string();
            let payload = value["payload"].as_str().unwrap_or("").to_string();
            let msg_id  = value["messageId"].as_str().unwrap_or("").to_string();

            if to.is_empty() || payload.is_empty() || msg_id.is_empty() {
                tracing::warn!(
                    to = %&to[..to.len().min(16)],
                    sealed = !sealed_sender.is_empty(),
                    "send dropped: empty field"
                );
                return;
            }
            let ts = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis();
            // Log only routing metadata — never from/to key prefixes for sealed
            // messages, so the relay holds zero knowledge of who sent to whom.
            tracing::info!(
                event  = "message",
                msg_id = %msg_id,
                size_b = payload.len(),
                sealed = !sealed_sender.is_empty(),
                ts_ms  = ts,
                "metadata"
            );

            // Rate-limit check — sliding window per sender identity.
            // On window expiry the counter resets; within the window it increments.
            // Drops silently: informing the spammer about the limit is undesirable.
            // Rate-limit and blocklist checks are keyed on `from_identity`
            // (the authenticated WS connection), NOT on any client-supplied field.
            // This remains true for sealed messages: the relay can throttle abuse
            // without learning message content or envelope metadata.
            let rate_ok = {
                let mut limiter = state.rate_limiter.write().await;
                let entry = limiter.entry(from_identity.to_string()).or_insert(RateEntry {
                    count: 0,
                    window_start: std::time::Instant::now(),
                });

                if entry.window_start.elapsed().as_secs() >= state.config.rate_limit_window_secs {
                    // New window — reset counter.
                    entry.count = 1;
                    entry.window_start = std::time::Instant::now();
                    true
                } else if entry.count < state.config.rate_limit_per_window {
                    entry.count += 1;
                    true
                } else {
                    false
                }
            };

            if !rate_ok {
                tracing::warn!("rate limit exceeded");
                return;
            }

            // Blocklist check — silent drop for both sender and recipient.
            // Sender is identified by the authenticated WS identity, not by any
            // client-supplied field, so spoofed "from" values have no effect.
            {
                let bl = state.blocklist.read().await;
                if bl.contains(from_identity) || bl.contains(&to) {
                    tracing::warn!(to = %&to[..to.len().min(16)], "blocked key — message dropped");
                    return;
                }
            }

            // Build the delivery frame. For sealed messages `from` is empty so
            // the relay never reveals the sender identity to anyone, including
            // itself — the recipient decrypts `sealedSender` client-side.
            let envelope_from = if sealed_sender.is_empty() {
                from_identity.to_string()
            } else {
                String::new()
            };
            let deliver = serde_json::json!({
                "type":         "deliver",
                "from":         envelope_from,
                "sealedSender": sealed_sender,
                "payload":      payload,
                "messageId":    msg_id,
            })
            .to_string();

            // Persist FIRST. Live delivery via the in-memory mpsc channel is a
            // best-effort optimisation; a recipient WS that silently dies
            // between mpsc.send() and the actual ws_tx.send() must not lose the
            // envelope. The store is the source of truth — the client removes
            // an envelope only by sending {"type":"ack-deliver", ...} after
            // successful decrypt + DB insert. On reconnect the client gets
            // every envelope still in the store, deduped at the message_id
            // level on the client side (INSERT OR IGNORE on the messages
            // table). This keeps the QA-observed loss-after-idle-disconnect
            // bug from happening again.
            let envelope = Envelope::new(
                msg_id.clone(),
                to.clone(),
                envelope_from,
                sealed_sender.clone(),
                payload.clone(),
                state.config.envelope_ttl_secs,
            );
            {
                let mut store = state.store.write().await;
                let queue = store.entry(to.clone()).or_default();
                queue.retain(|e| !e.is_expired() && e.id != msg_id);
                if queue.len() < state.config.max_envelopes_per_recipient {
                    queue.push(envelope);
                } else {
                    tracing::warn!(
                        msg_id = %msg_id,
                        cap    = state.config.max_envelopes_per_recipient,
                        "store at capacity — envelope dropped"
                    );
                }
            }

            // Attempt live delivery — best-effort.
            let delivered = {
                let clients = state.clients.read().await;
                if let Some((_, recipient_tx)) = clients.get(&to) {
                    recipient_tx.send(deliver.clone()).is_ok()
                } else {
                    false
                }
            };

            if delivered {
                tracing::info!(msg_id = %msg_id, "live delivery dispatched (envelope retained until client ack-deliver)");
            } else {
                let online_count = state.clients.read().await.len();
                tracing::info!(
                    msg_id       = %msg_id,
                    online_count,
                    "recipient offline — queued for next reconnect"
                );

                // ADR-016 UnifiedPush wake-up. Self-hosted ntfy distributor
                // at `state.config.ntfy_url`; one-byte payload; fire-and-
                // forget. The envelope is already durably queued above;
                // this is a hint to the recipient device that a message
                // is waiting, not a delivery primitive. See push.rs for
                // privacy boundary and rationale.
                wake_offline_recipient(Arc::clone(&state), to.clone());
            }

            // Ack back to sender
            if let Some((_, sender_tx)) = state.clients.read().await.get(from_identity) {
                let ack = serde_json::json!({
                    "type": "ack",
                    "messageId": msg_id,
                    "status": if delivered { "delivered" } else { "relayed" },
                })
                .to_string();
                let _ = sender_tx.send(ack);
            }
        }
        Some("ping") => {
            if let Some((_, tx)) = state.clients.read().await.get(from_identity) {
                let _ = tx.send(r#"{"type":"pong"}"#.to_string());
            }
        }
        // ── Client → Relay delivery acknowledgement ────────────────────────────
        // The recipient client sends this after it has fully processed an
        // inbound envelope (Sealed-Sender unseal → decrypt → DB insert). The
        // relay then removes the envelope from the per-recipient store so that
        // the next reconnect does not redeliver it. Without this handler the
        // store grows unboundedly and the client is forced to handle the same
        // ciphertext on every reconnect.
        //
        // Identity check: a client may only ack-deliver envelopes addressed to
        // its own connection identity. Otherwise an attacker could erase
        // somebody else's pending mail.
        Some("ack-deliver") => {
            let msg_id = value["messageId"].as_str().unwrap_or("").to_string();
            if msg_id.is_empty() {
                return;
            }
            let removed = {
                let mut store = state.store.write().await;
                if let Some(queue) = store.get_mut(from_identity) {
                    let before = queue.len();
                    queue.retain(|e| e.id != msg_id);
                    before != queue.len()
                } else {
                    false
                }
            };
            if removed {
                tracing::debug!(msg_id = %msg_id, "client ack-deliver — envelope removed from store");
            }
        }
        Some("typing") => {
            // Ephemeral event — forward live to recipient if online, drop silently if not.
            // Never stored, never queued: typing indicators have no value after the fact.
            // The "from" field is always the authenticated WS identity to prevent spoofing.
            // Rate-limit typing events using the same sliding-window limiter as sends.
            let rate_ok = {
                let mut limiter = state.rate_limiter.write().await;
                let entry = limiter.entry(from_identity.to_string()).or_insert(RateEntry {
                    count: 0,
                    window_start: std::time::Instant::now(),
                });
                if entry.window_start.elapsed().as_secs() >= state.config.rate_limit_window_secs {
                    entry.count = 1;
                    entry.window_start = std::time::Instant::now();
                    true
                } else if entry.count < state.config.rate_limit_per_window {
                    entry.count += 1;
                    true
                } else {
                    false
                }
            };
            if !rate_ok { return; }

            let to = value["to"].as_str().unwrap_or("").to_string();
            if !to.is_empty() {
                let clients = state.clients.read().await;
                if let Some((_, tx)) = clients.get(&to) {
                    let typing_msg = serde_json::json!({
                        "type": "typing",
                        "from": from_identity,
                    });
                    let _ = tx.send(typing_msg.to_string());
                }
                // If recipient is offline: drop silently — no warn, no queue.
            }
        }
        _ => {}
    }
}

// ── REST endpoints (kept for tooling/testing) ─────────────────────────────────

async fn health() -> impl IntoResponse {
    (StatusCode::OK, Json(serde_json::json!({ "status": "ok" })))
}

async fn send_envelope(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
    Json(req): Json<SendRequest>,
) -> Result<impl IntoResponse, RelayError> {
    if !check_admin_token(&params, &state) {
        return Err(RelayError::BadRequest("unauthorized".into()));
    }
    // `from` is optional for sealed-sender messages; one of `from` or
    // `sealed_sender` must be present so the envelope is attributable for
    // abuse-response purposes (the relay stores neither for sealed messages
    // — the sealed blob is opaque — but empty envelopes are useless).
    let is_sealed = !req.sealed_sender.is_empty();
    if req.id.is_empty() || req.to.is_empty() || (!is_sealed && req.from.is_empty()) {
        return Err(RelayError::BadRequest(
            "id and to are required; either from or sealedSender must be present".into(),
        ));
    }
    if req.payload.len() > state.config.max_payload_bytes {
        return Err(RelayError::PayloadTooLarge);
    }

    let envelope_from = if is_sealed {
        String::new()
    } else {
        req.from
    };
    let envelope = Envelope::new(
        req.id,
        req.to.clone(),
        envelope_from,
        req.sealed_sender,
        req.payload,
        state.config.envelope_ttl_secs,
    );

    let mut store = state.store.write().await;
    let queue = store.entry(req.to).or_default();
    queue.retain(|e| !e.is_expired());

    if queue.len() >= state.config.max_envelopes_per_recipient {
        return Err(RelayError::QuotaExceeded);
    }

    let id = envelope.id.clone();
    queue.push(envelope);
    tracing::debug!(message_id = %id, "envelope stored via REST");

    Ok((StatusCode::ACCEPTED, Json(serde_json::json!({ "id": id }))))
}

async fn fetch_envelopes(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
    Path(recipient): Path<String>,
) -> impl IntoResponse {
    if !check_admin_token(&params, &state) {
        return Json(FetchResponse { envelopes: vec![] }).into_response();
    }
    let mut store = state.store.write().await;
    let queue = store.entry(recipient).or_default();
    queue.retain(|e| !e.is_expired());
    let envelopes: Vec<Envelope> = queue.clone();
    Json(FetchResponse { envelopes }).into_response()
}

async fn ack_envelope(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<impl IntoResponse, RelayError> {
    if !check_admin_token(&params, &state) {
        return Err(RelayError::BadRequest("unauthorized".into()));
    }
    let recipient = params
        .get("recipient")
        .ok_or_else(|| RelayError::BadRequest("recipient query param required".into()))?
        .clone();

    let mut store = state.store.write().await;
    let queue = store.get_mut(&recipient).ok_or(RelayError::NotFound)?;
    let before = queue.len();
    queue.retain(|e| e.id != id);

    if queue.len() == before {
        return Err(RelayError::NotFound);
    }

    tracing::debug!(message_id = %id, "envelope acknowledged");
    Ok((StatusCode::OK, Json(AckResponse { acknowledged: id })))
}

// ── Abuse report ──────────────────────────────────────────────────────────────

#[derive(serde::Deserialize)]
struct ReportRequest {
    reporter_key: String,
    reported_key: String,
    category: String,
}

async fn submit_report(
    State(state): State<Arc<AppState>>,
    Json(req): Json<ReportRequest>,
) -> impl IntoResponse {
    if req.reporter_key.is_empty() || req.reported_key.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "missing fields" })));
    }

    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;

    let report = AbuseReport {
        reporter_key: req.reporter_key[..req.reporter_key.len().min(64)].to_string(),
        reported_key: req.reported_key[..req.reported_key.len().min(64)].to_string(),
        category: req.category[..req.category.len().min(64)].to_string(),
        timestamp_ms: ts,
    };

    tracing::warn!(
        event        = "abuse_report",
        reporter_key = %&report.reporter_key[..report.reporter_key.len().min(16)],
        reported_key = %&report.reported_key[..report.reported_key.len().min(16)],
        category     = %report.category,
        ts_ms        = ts,
        "report received"
    );

    append_report_to_disk(&report);
    state.reports.write().await.push(report);

    (StatusCode::OK, Json(serde_json::json!({ "status": "received" })))
}

// ── Admin endpoints (require ?token=ADMIN_SECRET) ─────────────────────────────

fn check_admin_token(params: &HashMap<String, String>, state: &Arc<AppState>) -> bool {
    match &state.config.secret_token {
        Some(expected) => params.get("token").map_or(false, |provided| {
            let ok: bool = provided.as_bytes().ct_eq(expected.as_bytes()).into();
            ok
        }),
        None => false,
    }
}

async fn admin_list_reports(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    if !check_admin_token(&params, &state) {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({ "error": "unauthorized" }))).into_response();
    }
    let reports = state.reports.read().await.clone();
    (StatusCode::OK, Json(serde_json::json!({ "count": reports.len(), "reports": reports }))).into_response()
}

#[derive(serde::Deserialize)]
struct BlockRequest {
    key: String,
}

async fn admin_block_key(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
    Json(req): Json<BlockRequest>,
) -> impl IntoResponse {
    if !check_admin_token(&params, &state) {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({ "error": "unauthorized" })));
    }
    if req.key.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "key required" })));
    }
    tracing::warn!(event = "admin_block", key = %&req.key[..req.key.len().min(16)], "key blocked by admin");
    append_block_to_disk(&req.key);
    state.blocklist.write().await.insert(req.key.clone());
    (StatusCode::OK, Json(serde_json::json!({ "blocked": req.key })))
}

async fn admin_list_blocklist(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    if !check_admin_token(&params, &state) {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({ "error": "unauthorized" }))).into_response();
    }
    let list: Vec<String> = state.blocklist.read().await.iter().cloned().collect();
    (StatusCode::OK, Json(serde_json::json!({ "count": list.len(), "keys": list }))).into_response()
}

// ── UnifiedPush registration (ADR-016) ───────────────────────────────────────

/// Body of POST /push/register.
///
/// `identity` is the hex public key of the registering client (the same
/// value used as `?id=` on the WebSocket route). `topic_url` is the full
/// HTTP(S) URL of the ntfy topic the relay will POST a wake-up to when
/// an envelope arrives for this identity while the client is offline.
///
/// The relay does not validate the topic URL beyond basic shape — the
/// distributor is part of the same trust domain as the relay (both run
/// under PHANTOM operator control on the same Hetzner VPS), and a
/// malformed URL only breaks wake-up for the calling client. Topic URL
/// is treated as opaque metadata; the relay never inspects it for any
/// purpose other than POSTing the one-byte payload.
#[derive(serde::Deserialize)]
struct PushRegisterRequest {
    identity: String,
    topic_url: String,
}

async fn register_push_token(
    State(state): State<Arc<AppState>>,
    Json(req): Json<PushRegisterRequest>,
) -> impl IntoResponse {
    // Basic input shape.
    if req.identity.is_empty() || req.topic_url.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "identity and topic_url required" })),
        )
            .into_response();
    }
    // Sanity check identity prefix length so a buggy client doesn't blow
    // out our state map with junk keys. Real identity keys are 64 hex
    // chars (32 bytes) but we accept anything from 16 to 128 to remain
    // forward-compatible.
    if req.identity.len() < 16 || req.identity.len() > 128 {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "identity length out of range" })),
        )
            .into_response();
    }
    // Loose URL shape check — not exhaustive.
    if !req.topic_url.starts_with("http://") && !req.topic_url.starts_with("https://") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "topic_url must be http(s)://" })),
        )
            .into_response();
    }
    if req.topic_url.len() > 512 {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "topic_url too long" })),
        )
            .into_response();
    }

    let rec = PushTokenRecord {
        identity: req.identity.clone(),
        topic_url: req.topic_url.clone(),
    };
    // Update in-memory map (last write wins per identity) and append to
    // jsonl so a relay restart replays the latest registration.
    {
        let mut tokens = state.push_tokens.write().await;
        tokens.insert(rec.identity.clone(), rec.topic_url.clone());
    }
    append_push_token_to_disk(&rec);

    tracing::info!(
        identity_prefix = %&rec.identity[..rec.identity.len().min(8)],
        "UnifiedPush topic registered"
    );
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

// ── X3DH prekey endpoints (ADR-009) ──────────────────────────────────────────

/// Per-endpoint rate limit windows.
///
/// publish: 10 calls per hour per identity. Tight because publish rotates
/// SPK + replaces the OPK pool, both relatively heavy storage operations.
/// A normal client publishes once per pool-replenish (≈ once per ~50 first
/// contacts), so 10/hour leaves headroom for retries but caps abuse.
const PUBLISH_RATE_LIMIT: u32 = 10;
const PUBLISH_RATE_WINDOW_SECS: u64 = 3600;

/// bundle: 60 calls per minute per requester key. Looking up someone's
/// bundle is the read path that drains OPKs; an attacker spamming the
/// endpoint can exhaust a victim's pool. The limit is bucketed on the
/// `?requester=<hex>` query param, falling back to the path identity
/// when no requester is supplied (best-effort — the relay does not yet
/// authenticate bundle fetchers; ADR-019 will add a session token).
const BUNDLE_RATE_LIMIT: u32 = 60;
const BUNDLE_RATE_WINDOW_SECS: u64 = 60;

const STATUS_RATE_LIMIT: u32 = 60;
const STATUS_RATE_WINDOW_SECS: u64 = 60;

const DELETE_RATE_LIMIT: u32 = 30;
const DELETE_RATE_WINDOW_SECS: u64 = 60;

#[derive(serde::Deserialize)]
struct PublishRequest {
    /// X25519 — primary routing identity. Unchanged from Alpha 1; matches
    /// the publicKeyHex visible in QR codes / contacts list / send paths.
    identity_pubkey_hex: String,
    /// Ed25519 — signing identity used to sign `signed_pre_key`. Per
    /// ADR-009 (revised 2026-04-30) this is a SEPARATE keypair from the
    /// X25519 identity, stored alongside on `IdentityRecord`. The relay
    /// enforces a 1:1 binding: once an X25519 identity has registered an
    /// Ed25519 signing key, subsequent publishes must use the same one
    /// or the request is rejected with `SigningKeyMismatch`.
    signing_pubkey_hex: String,
    signed_pre_key: SignedPreKeyPublicBundle,
    /// Up to MAX_OPKS_PER_PUBLISH (100). The bundle replaces the previous
    /// OPK pool wholesale on each publish.
    one_time_pre_keys: Vec<OneTimePreKeyPublicBundle>,
}

async fn publish_prekeys(
    State(state): State<Arc<AppState>>,
    Json(req): Json<PublishRequest>,
) -> impl IntoResponse {
    // Rate limit BEFORE signature verify so that a spammer hammering with
    // garbage payloads still gets bucketed and isn't burning CPU on Ed25519
    // verifies for free.
    if !state
        .prekeys
        .allow_call(
            &format!("publish:{}", req.identity_pubkey_hex),
            PUBLISH_RATE_LIMIT,
            PUBLISH_RATE_WINDOW_SECS,
        )
        .await
    {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "publish rate limit exceeded" })),
        )
            .into_response();
    }
    let now_ms = now_millis();
    match state
        .prekeys
        .publish(
            &req.identity_pubkey_hex,
            &req.signing_pubkey_hex,
            req.signed_pre_key,
            req.one_time_pre_keys,
            now_ms,
        )
        .await
    {
        Ok(stored_count) => {
            tracing::info!(
                event = "prekey_publish",
                identity = %&req.identity_pubkey_hex[..req.identity_pubkey_hex.len().min(16)],
                opk_count = stored_count,
                "metadata"
            );
            (
                StatusCode::CREATED,
                Json(serde_json::json!({ "stored_opks": stored_count })),
            )
                .into_response()
        }
        Err(e) => publish_error_response(e),
    }
}

fn publish_error_response(e: PublishError) -> axum::response::Response {
    let (status, msg) = match e {
        PublishError::BadIdentity(m) => (StatusCode::BAD_REQUEST, m.to_string()),
        PublishError::BadSigningKey(m) => (StatusCode::BAD_REQUEST, m.to_string()),
        PublishError::BadSignature(m) => (StatusCode::BAD_REQUEST, m.to_string()),
        PublishError::BadOpk(m) => (StatusCode::BAD_REQUEST, m.to_string()),
        PublishError::TooManyOpks(n) => (
            StatusCode::PAYLOAD_TOO_LARGE,
            format!("too many OPKs: {} (max 100)", n),
        ),
        // 409 Conflict: a different signing key was previously registered
        // for this X25519 identity. Client should treat as a hard failure
        // (not a retryable transport error).
        PublishError::SigningKeyMismatch => (
            StatusCode::CONFLICT,
            "signing_pubkey_hex does not match the one registered for this identity_pubkey_hex".to_string(),
        ),
    };
    (status, Json(serde_json::json!({ "error": msg }))).into_response()
}

async fn fetch_bundle(
    Path(identity): Path<String>,
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    // Rate-limit on requester key when supplied (Alpha-2 best-effort), else
    // on the target identity. The latter is intentionally permissive: a
    // single attacker behind a varying requester header would still be
    // throttled by `target:<identity>` once OPK drain becomes detectable
    // through pool depletion. Hardening to a session token: ADR-019.
    let bucket = match params.get("requester") {
        Some(r) if !r.is_empty() => format!("bundle-req:{}", r),
        _ => format!("bundle-tgt:{}", identity),
    };
    if !state
        .prekeys
        .allow_call(&bucket, BUNDLE_RATE_LIMIT, BUNDLE_RATE_WINDOW_SECS)
        .await
    {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "bundle rate limit exceeded" })),
        )
            .into_response();
    }
    match state.prekeys.consume_bundle(&identity).await {
        Some(bundle) => {
            tracing::info!(
                event = "prekey_consume",
                identity = %&identity[..identity.len().min(16)],
                had_opk = bundle.one_time_pre_key.is_some(),
                "metadata"
            );
            (StatusCode::OK, Json::<PreKeyBundle>(bundle)).into_response()
        }
        None => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "no published prekeys for this identity" })),
        )
            .into_response(),
    }
}

async fn prekey_status(
    Path(identity): Path<String>,
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    let bucket = match params.get("requester") {
        Some(r) if !r.is_empty() => format!("status-req:{}", r),
        _ => format!("status-tgt:{}", identity),
    };
    if !state
        .prekeys
        .allow_call(&bucket, STATUS_RATE_LIMIT, STATUS_RATE_WINDOW_SECS)
        .await
    {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "status rate limit exceeded" })),
        )
            .into_response();
    }
    let now_ms = now_millis();
    let s: PreKeyStatus = state.prekeys.status(&identity, now_ms).await;
    (StatusCode::OK, Json(s)).into_response()
}

#[derive(serde::Deserialize)]
struct DeleteOpkRequest {
    /// Wall-clock millisecond timestamp the client signed. Verified inside
    /// a 5-minute window to blunt off-the-wire replay; see prekeys.rs.
    timestamp_ms: i64,
    /// 64-byte Ed25519 detached signature, hex-encoded.
    signature_hex: String,
}

async fn delete_opk(
    Path((identity, key_id)): Path<(String, String)>,
    State(state): State<Arc<AppState>>,
    Json(req): Json<DeleteOpkRequest>,
) -> impl IntoResponse {
    if !state
        .prekeys
        .allow_call(
            &format!("delete:{}", identity),
            DELETE_RATE_LIMIT,
            DELETE_RATE_WINDOW_SECS,
        )
        .await
    {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "delete rate limit exceeded" })),
        )
            .into_response();
    }
    let now_ms = now_millis();
    match state
        .prekeys
        .delete_opk(&identity, &key_id, req.timestamp_ms, &req.signature_hex, now_ms)
        .await
    {
        Ok(()) => {
            tracing::info!(
                event = "prekey_delete_opk",
                identity = %&identity[..identity.len().min(16)],
                "metadata"
            );
            (StatusCode::NO_CONTENT, ()).into_response()
        }
        Err(e) => match e {
            DeleteError::BadIdentity(m) | DeleteError::BadSignature(m) => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": m })),
            )
                .into_response(),
            DeleteError::TimestampOutOfWindow => (
                StatusCode::BAD_REQUEST,
                Json(
                    serde_json::json!({ "error": "timestamp outside 5-minute tolerance window" }),
                ),
            )
                .into_response(),
            DeleteError::NotFound => (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "opk not found" })),
            )
                .into_response(),
        },
    }
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
