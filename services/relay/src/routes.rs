use crate::{envelope::*, error::RelayError, state::AppState};
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{delete, get, post},
    Json, Router,
};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tower_http::{
    cors::CorsLayer,
    limit::RequestBodyLimitLayer,
    timeout::TimeoutLayer,
    trace::TraceLayer,
};

pub fn router(state: Arc<AppState>) -> Router {
    let max_body = state.config.max_payload_bytes + 1024;
    Router::new()
        .route("/health",              get(health))
        .route("/send",                post(send_envelope))
        .route("/fetch/:recipient",    get(fetch_envelopes))
        .route("/ack/:id",             delete(ack_envelope))
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .layer(TimeoutLayer::new(Duration::from_secs(10)))
        .layer(RequestBodyLimitLayer::new(max_body))
        .with_state(state)
}

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
    tracing::debug!(message_id = %id, "envelope stored");

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

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::Body,
        http::{Method, Request},
    };
    use serde_json::{json, Value};
    use tower::ServiceExt;

    fn test_state() -> Arc<AppState> {
        Arc::new(AppState::new(crate::config::RelayConfig {
            host: "127.0.0.1".into(),
            port: 8080,
            max_payload_bytes: 65_536,
            envelope_ttl_secs: 3600,
            max_envelopes_per_recipient: 500,
        }))
    }

    fn app() -> Router {
        router(test_state())
    }

    async fn body_json(body: axum::body::Body) -> Value {
        let bytes = axum::body::to_bytes(body, usize::MAX).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn health_returns_ok() {
        let resp = app()
            .oneshot(Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn send_stores_and_fetch_returns_envelope() {
        let state = test_state();
        let app = router(Arc::clone(&state));

        let body = json!({ "id": "msg-1", "to": "bob", "from": "alice", "payload": "abc123" });
        let resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/send")
                    .header("content-type", "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::ACCEPTED);

        let resp2 = router(Arc::clone(&state))
            .oneshot(Request::builder().uri("/fetch/bob").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let json = body_json(resp2.into_body()).await;
        assert_eq!(json["envelopes"][0]["id"], "msg-1");
        assert_eq!(json["envelopes"][0]["payload"], "abc123");
    }

    #[tokio::test]
    async fn ack_removes_envelope() {
        let state = test_state();

        let body = json!({ "id": "msg-2", "to": "carol", "from": "alice", "payload": "xyz" });
        router(Arc::clone(&state))
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/send")
                    .header("content-type", "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();

        let resp = router(Arc::clone(&state))
            .oneshot(
                Request::builder()
                    .method(Method::DELETE)
                    .uri("/ack/msg-2?recipient=carol")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);

        let resp2 = router(Arc::clone(&state))
            .oneshot(Request::builder().uri("/fetch/carol").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let json = body_json(resp2.into_body()).await;
        assert_eq!(json["envelopes"].as_array().unwrap().len(), 0);
    }

    #[tokio::test]
    async fn send_rejects_oversized_payload() {
        let state = Arc::new(AppState::new(crate::config::RelayConfig {
            max_payload_bytes: 10,
            host: "127.0.0.1".into(),
            port: 8080,
            envelope_ttl_secs: 3600,
            max_envelopes_per_recipient: 500,
        }));
        let body = json!({ "id": "x", "to": "bob", "from": "alice", "payload": "a".repeat(11) });
        let resp = router(state)
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/send")
                    .header("content-type", "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn fetch_empty_returns_empty_list() {
        let resp = app()
            .oneshot(Request::builder().uri("/fetch/nobody").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let json = body_json(resp.into_body()).await;
        assert_eq!(json["envelopes"].as_array().unwrap().len(), 0);
    }
}
