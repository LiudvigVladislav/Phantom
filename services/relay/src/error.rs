// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use axum::{http::StatusCode, response::{IntoResponse, Response}, Json};
use serde_json::json;

#[derive(Debug)]
pub enum RelayError {
    PayloadTooLarge,
    QuotaExceeded,
    NotFound,
    BadRequest(String),
}

impl IntoResponse for RelayError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            RelayError::PayloadTooLarge => (StatusCode::PAYLOAD_TOO_LARGE, "payload too large".into()),
            RelayError::QuotaExceeded  => (StatusCode::TOO_MANY_REQUESTS,  "recipient queue full".into()),
            RelayError::NotFound       => (StatusCode::NOT_FOUND,           "envelope not found".into()),
            RelayError::BadRequest(m)  => (StatusCode::BAD_REQUEST,         m),
        };
        (status, Json(json!({ "error": message }))).into_response()
    }
}
