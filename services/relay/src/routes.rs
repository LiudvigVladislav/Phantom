use crate::{envelope::*, error::RelayError, state::{AppState, RateEntry}};
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

pub fn router(state: Arc<AppState>) -> Router {
    let max_body = state.config.max_payload_bytes + 1024;
    Router::new()
        .route("/health",             get(health))
        .route("/ws",                 get(ws_handler))
        .route("/send",               post(send_envelope))
        .route("/fetch/{recipient}",  get(fetch_envelopes))
        .route("/ack/{id}",           delete(ack_envelope))
        .layer(TraceLayer::new_for_http())
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
        if provided != expected.as_str() {
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
        tracing::info!(id = %&identity[..identity.len().min(16)], "client connected");

        // Flush queued messages
        let queued: Vec<Envelope> = {
            let mut store = state.store.write().await;
            let queue = store.entry(identity.clone()).or_default();
            queue.retain(|e| !e.is_expired());
            std::mem::take(queue)
        };
        for env in queued {
            let deliver = serde_json::json!({
                "type": "deliver",
                "from": env.from,
                "payload": env.payload,
                "messageId": env.id,
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
        tracing::info!(id = %&identity[..identity.len().min(16)], "client disconnected");
    }
}

async fn handle_message(text: &str, from_identity: &str, state: &Arc<AppState>) {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        return;
    };

    match value.get("type").and_then(|t| t.as_str()) {
        Some("send") => {
            let to      = value["to"].as_str().unwrap_or("").to_string();
            let from    = value["from"].as_str().unwrap_or(from_identity).to_string();
            let payload = value["payload"].as_str().unwrap_or("").to_string();
            let msg_id  = value["messageId"].as_str().unwrap_or("").to_string();

            if to.is_empty() || payload.is_empty() || msg_id.is_empty() {
                return;
            }

            // Rate-limit check — sliding window per sender identity.
            // On window expiry the counter resets; within the window it increments.
            // Drops silently: informing the spammer about the limit is undesirable.
            let rate_ok = {
                let mut limiter = state.rate_limiter.write().await;
                let entry = limiter.entry(from.clone()).or_insert(RateEntry {
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
                tracing::warn!(from = %&from[..from.len().min(16)], "rate limit exceeded");
                return; // silent drop — do not inform the spammer
            }

            let deliver = serde_json::json!({
                "type": "deliver",
                "from": from,
                "payload": payload,
                "messageId": msg_id,
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
                tracing::debug!(msg_id = %msg_id, to = %&to[..to.len().min(16)], "live delivery");
            } else {
                // Store for later
                let envelope = Envelope::new(
                    msg_id.clone(),
                    to.clone(),
                    from,
                    payload,
                    state.config.envelope_ttl_secs,
                );
                let mut store = state.store.write().await;
                let queue = store.entry(to).or_default();
                queue.retain(|e| !e.is_expired());
                if queue.len() < state.config.max_envelopes_per_recipient {
                    queue.push(envelope);
                    tracing::debug!(msg_id = %msg_id, "envelope queued");
                }
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
        _ => {}
    }
}

// ── REST endpoints (kept for tooling/testing) ─────────────────────────────────

async fn health() -> impl IntoResponse {
    (StatusCode::OK, Json(serde_json::json!({ "status": "ok" })))
}

async fn send_envelope(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SendRequest>,
) -> Result<impl IntoResponse, RelayError> {
    if req.id.is_empty() || req.to.is_empty() || req.from.is_empty() {
        return Err(RelayError::BadRequest("id, to, from are required".into()));
    }
    if req.payload.len() > state.config.max_payload_bytes {
        return Err(RelayError::PayloadTooLarge);
    }

    let envelope = Envelope::new(
        req.id,
        req.to.clone(),
        req.from,
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
    State(state): State<Arc<AppState>>,
    Path(recipient): Path<String>,
) -> impl IntoResponse {
    let mut store = state.store.write().await;
    let queue = store.entry(recipient).or_default();
    queue.retain(|e| !e.is_expired());
    let envelopes: Vec<Envelope> = queue.clone();
    Json(FetchResponse { envelopes })
}

async fn ack_envelope(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<impl IntoResponse, RelayError> {
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
