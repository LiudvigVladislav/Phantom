use crate::{envelope::*, error::RelayError, state::{AppState, AbuseReport, RateEntry, append_report_to_disk, append_block_to_disk}};
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

pub fn router(state: Arc<AppState>) -> Router {
    let max_body = state.config.max_payload_bytes + 1024;
    Router::new()
        .route("/health",             get(health))
        .route("/ws",                 get(ws_handler))
        .route("/send",               post(send_envelope))
        .route("/fetch/{recipient}",  get(fetch_envelopes))
        .route("/ack/{id}",           delete(ack_envelope))
        .route("/report",             post(submit_report))
        .route("/admin/reports",      get(admin_list_reports))
        .route("/admin/block",        post(admin_block_key))
        .route("/admin/blocklist",    get(admin_list_blocklist))
        // Log only method + path — never query string, to avoid leaking ?token= secrets.
        .layer(TraceLayer::new_for_http().make_span_with(|req: &Request<_>| {
            tracing::info_span!(
                "request",
                method = %req.method(),
                path   = %req.uri().path(),
            )
        }))
        .layer(TimeoutLayer::with_status_code(
            axum::http::StatusCode::REQUEST_TIMEOUT,
            Duration::from_secs(30),
        ))
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

async fn handle_socket(socket: WebSocket, identity: String, state: Arc<AppState>) {
    use futures_util::{SinkExt, StreamExt};

    let (mut ws_tx, mut ws_rx) = socket.split();

    // Channel: relay → this client
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();

    // Register client
    if !identity.is_empty() {
        state.clients.write().await.insert(identity.clone(), tx.clone());
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

        // Flush queued messages
        let queued: Vec<Envelope> = {
            let mut store = state.store.write().await;
            let queue = store.entry(identity.clone()).or_default();
            queue.retain(|e| !e.is_expired());
            std::mem::take(queue)
        };
        if !queued.is_empty() {
            tracing::info!(id = %&identity[..identity.len().min(16)], count = queued.len(), "flushing queued envelopes");
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

    // Task: forward outbound channel → WebSocket
    let mut send_task = tokio::spawn(async move {
        while let Some(text) = rx.recv().await {
            if ws_tx.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    // Task: receive frames from WebSocket
    let state_rx = Arc::clone(&state);
    let identity_rx = identity.clone();
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = ws_rx.next().await {
            match msg {
                Message::Text(text) => {
                    handle_message(text.as_str(), &identity_rx, &state_rx).await;
                }
                Message::Close(_) => break,
                _ => {}
            }
        }
    });

    // Wait for either task to finish
    tokio::select! {
        _ = &mut send_task => recv_task.abort(),
        _ = &mut recv_task => send_task.abort(),
    }

    // Unregister client
    if !identity.is_empty() {
        state.clients.write().await.remove(&identity);
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

            // Try live delivery first
            let delivered = {
                let clients = state.clients.read().await;
                if let Some(recipient_tx) = clients.get(&to) {
                    recipient_tx.send(deliver.clone()).is_ok()
                } else {
                    false
                }
            };

            if delivered {
                tracing::info!(msg_id = %msg_id, "live delivery OK");
            } else {
                // Store for later
                let envelope = Envelope::new(
                    msg_id.clone(),
                    to.clone(),
                    envelope_from,
                    sealed_sender.clone(),
                    payload,
                    state.config.envelope_ttl_secs,
                );
                {
                    // Log only online_count, never key prefixes — presence metadata leak.
                    let online_count = state.clients.read().await.len();
                    tracing::info!(
                        msg_id       = %msg_id,
                        online_count,
                        "recipient offline — queuing"
                    );
                }
                let mut store = state.store.write().await;
                let queue = store.entry(to).or_default();
                queue.retain(|e| !e.is_expired());
                if queue.len() < state.config.max_envelopes_per_recipient {
                    queue.push(envelope);
                }

                // TODO: Send FCM silent push so the offline device wakes and drains via WebSocket.
                // Gated on RELAY_FCM_SERVER_KEY being set (state.config.fcm_server_key.is_some()).
                // Requires: reqwest = { version = "0.11", features = ["json"] } added to
                //           services/relay/Cargo.toml (and the workspace Cargo.toml if needed).
                // Once reqwest is present, use the Legacy HTTP API:
                //   POST https://fcm.googleapis.com/fcm/send
                //   Authorization: key=<fcm_server_key>
                //   Body: { "to": "/topics/user_<recipient_prefix>", "priority": "high",
                //           "data": { "type": "new_message" } }
                // Migration note: Legacy FCM key is deprecated — switch to FCM v1 (OAuth2)
                // before production. ADR required before implementing.
            }

            // Ack back to sender
            if let Some(sender_tx) = state.clients.read().await.get(from_identity) {
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
            if let Some(tx) = state.clients.read().await.get(from_identity) {
                let _ = tx.send(r#"{"type":"pong"}"#.to_string());
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
                if let Some(tx) = clients.get(&to) {
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
