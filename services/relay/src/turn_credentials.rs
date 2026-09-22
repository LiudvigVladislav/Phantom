// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Authenticated, short-lived coturn credentials.
//!
//! The long-lived shared secret is read once from an operator-owned file and
//! never leaves the relay. Clients receive only standard TURN REST credentials
//! scoped by an expiry timestamp. The opaque username tag is derived from the
//! authenticated identity, so no identity or stable account identifier is
//! exposed to the TURN server.

use std::collections::HashMap;
use std::path::Path;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::{
    extract::State,
    http::{
        header::{CACHE_CONTROL, PRAGMA},
        HeaderMap, HeaderValue, StatusCode,
    },
    response::{IntoResponse, Response},
    Json,
};
use base64::Engine as _;
use hmac::{Hmac, Mac};
use serde::Serialize;
use sha1::Sha1;
use sha2::Sha256;
use tokio::sync::Mutex;
use zeroize::Zeroizing;

use crate::{rest_fallback::extract_bearer, state::AppState};

const DEFAULT_TTL_SECS: u64 = 900;
const MIN_TTL_SECS: u64 = 300;
const MAX_TTL_SECS: u64 = 3600;
const RATE_LIMIT: u32 = 12;
const RATE_WINDOW: Duration = Duration::from_secs(300);

#[derive(Debug)]
struct RateWindow {
    count: u32,
    started: Instant,
}

pub struct TurnCredentialIssuer {
    secret: Option<Zeroizing<Vec<u8>>>,
    uris: Vec<String>,
    ttl_secs: u64,
    rate: Mutex<HashMap<String, RateWindow>>,
}

#[derive(Debug, Serialize)]
pub struct TurnCredentialResponse {
    pub username: String,
    pub credential: String,
    pub expires_at: u64,
    pub ttl_seconds: u64,
    pub uris: Vec<String>,
}

#[derive(Debug, PartialEq, Eq)]
enum IssueError {
    Disabled,
    RateLimited,
}

impl TurnCredentialIssuer {
    pub fn from_env() -> Self {
        let secret_file = std::env::var("RELAY_TURN_SECRET_FILE").ok();
        let uris = std::env::var("RELAY_TURN_URLS")
            .ok()
            .map(|raw| parse_uris(&raw))
            .unwrap_or_default();
        let ttl_secs = std::env::var("RELAY_TURN_CREDENTIAL_TTL_SECS")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(DEFAULT_TTL_SECS)
            .clamp(MIN_TTL_SECS, MAX_TTL_SECS);

        let secret = secret_file.map(|path| {
            read_secret(&path).unwrap_or_else(|error| {
                panic!("RELAY_TURN_SECRET_FILE is invalid: {error}")
            })
        });

        if secret.is_some() && uris.is_empty() {
            panic!("RELAY_TURN_URLS must contain at least one turn: or turns: URI");
        }

        Self {
            secret,
            uris,
            ttl_secs,
            rate: Mutex::new(HashMap::new()),
        }
    }

    #[cfg(test)]
    fn enabled(secret: &[u8], uris: Vec<String>, ttl_secs: u64) -> Self {
        Self {
            secret: Some(Zeroizing::new(secret.to_vec())),
            uris,
            ttl_secs,
            rate: Mutex::new(HashMap::new()),
        }
    }

    async fn issue_at(
        &self,
        identity: &str,
        now_secs: u64,
    ) -> Result<TurnCredentialResponse, IssueError> {
        let secret = self.secret.as_ref().ok_or(IssueError::Disabled)?;
        if self.uris.is_empty() {
            return Err(IssueError::Disabled);
        }

        let mut rate = self.rate.lock().await;
        rate.retain(|_, window| window.started.elapsed() < RATE_WINDOW);
        let entry = rate.entry(identity.to_owned()).or_insert_with(|| RateWindow {
            count: 0,
            started: Instant::now(),
        });
        if entry.started.elapsed() >= RATE_WINDOW {
            entry.count = 0;
            entry.started = Instant::now();
        }
        if entry.count >= RATE_LIMIT {
            return Err(IssueError::RateLimited);
        }
        entry.count += 1;
        drop(rate);

        let expires_at = now_secs.saturating_add(self.ttl_secs);
        let tag = opaque_identity_tag(secret, identity, expires_at);
        let username = format!("{expires_at}:{tag}");
        let credential = turn_password(secret, &username);
        Ok(TurnCredentialResponse {
            username,
            credential,
            expires_at,
            ttl_seconds: self.ttl_secs,
            uris: self.uris.clone(),
        })
    }
}

fn parse_uris(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(str::trim)
        .filter(|uri| uri.starts_with("turn:") || uri.starts_with("turns:"))
        .map(str::to_owned)
        .collect()
}

fn read_secret(path: &str) -> Result<Zeroizing<Vec<u8>>, String> {
    let path = Path::new(path);
    if !path.is_absolute() {
        return Err("path must be absolute".into());
    }
    let bytes = std::fs::read(path).map_err(|error| error.to_string())?;
    let secret = bytes
        .into_iter()
        .take_while(|byte| *byte != b'\n' && *byte != b'\r')
        .collect::<Vec<_>>();
    if secret.len() < 32 {
        return Err("secret must contain at least 32 bytes".into());
    }
    Ok(Zeroizing::new(secret))
}

fn opaque_identity_tag(secret: &[u8], identity: &str, expires_at: u64) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(identity.as_bytes());
    mac.update(&expires_at.to_be_bytes());
    hex::encode(&mac.finalize().into_bytes()[..12])
}

fn turn_password(secret: &[u8], username: &str) -> String {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(username.as_bytes());
    base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes())
}

fn no_store(mut response: Response) -> Response {
    response.headers_mut().insert(
        CACHE_CONTROL,
        HeaderValue::from_static("no-store, max-age=0"),
    );
    response
        .headers_mut()
        .insert(PRAGMA, HeaderValue::from_static("no-cache"));
    response
}

pub async fn issue_turn_credentials(
    State(state): State<std::sync::Arc<AppState>>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let token = match extract_bearer(&headers) {
        Some(token) => token,
        None => {
            return no_store(
                (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error":"unauthorized"})))
                    .into_response(),
            )
        }
    };
    let identity = match state.rest_tokens.validate(token).await {
        Some(identity) => identity,
        None => {
            return no_store(
                (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error":"unauthorized"})))
                    .into_response(),
            )
        }
    };
    let now_secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    match state.turn_credentials.issue_at(&identity, now_secs).await {
        Ok(response) => no_store((StatusCode::OK, Json(response)).into_response()),
        Err(IssueError::Disabled) => no_store(
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(serde_json::json!({"error":"turn_unavailable"})),
            )
                .into_response(),
        ),
        Err(IssueError::RateLimited) => no_store(
            (
                StatusCode::TOO_MANY_REQUESTS,
                Json(serde_json::json!({"error":"rate_limited"})),
            )
                .into_response(),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &[u8] = b"0123456789abcdef0123456789abcdef";

    #[tokio::test]
    async fn issues_standard_coturn_hmac_with_opaque_expiring_username() {
        let issuer = TurnCredentialIssuer::enabled(
            SECRET,
            vec!["turn:turn.phntm.pro:3478?transport=udp".into()],
            900,
        );
        let issued = issuer.issue_at("identity-secret-value", 1_000).await.unwrap();
        assert_eq!(issued.expires_at, 1_900);
        assert!(issued.username.starts_with("1900:"));
        assert!(!issued.username.contains("identity-secret-value"));
        assert_eq!(issued.credential, turn_password(SECRET, &issued.username));
        assert_eq!(issued.ttl_seconds, 900);
    }

    #[tokio::test]
    async fn limiter_is_separate_per_authenticated_identity() {
        let issuer = TurnCredentialIssuer::enabled(SECRET, vec!["turn:x:3478".into()], 900);
        for _ in 0..RATE_LIMIT {
            assert!(issuer.issue_at("a", 1_000).await.is_ok());
        }
        assert_eq!(issuer.issue_at("a", 1_000).await.unwrap_err(), IssueError::RateLimited);
        assert!(issuer.issue_at("b", 1_000).await.is_ok());
    }

    #[test]
    fn uri_parser_rejects_non_turn_schemes() {
        assert_eq!(
            parse_uris("https://bad, turn:one:3478?transport=udp,turns:two:5349"),
            vec!["turn:one:3478?transport=udp", "turns:two:5349"],
        );
    }

    #[test]
    fn credential_responses_are_never_cacheable() {
        let response = no_store(StatusCode::OK.into_response());
        assert_eq!(
            response.headers().get(CACHE_CONTROL).unwrap(),
            "no-store, max-age=0",
        );
        assert_eq!(response.headers().get(PRAGMA).unwrap(), "no-cache");
    }
}
