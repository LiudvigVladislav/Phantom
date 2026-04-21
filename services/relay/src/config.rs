/// Relay configuration loaded from environment variables.
#[derive(Clone)]
pub struct RelayConfig {
    pub host: String,
    pub port: u16,
    /// Maximum envelope payload size in bytes (default 64 KB).
    pub max_payload_bytes: usize,
    /// How long an envelope is kept before being purged (seconds).
    pub envelope_ttl_secs: u64,
    /// Maximum envelopes stored per recipient public key.
    pub max_envelopes_per_recipient: usize,
    /// Optional shared secret for Alpha-0 access control.
    /// When set, every WebSocket connection must supply a matching `token` query
    /// parameter.  When absent the relay operates in open dev mode.
    /// Relay stores this only to compare — it never logs or forwards the value.
    pub secret_token: Option<String>,
    /// Max messages a single sender identity may send per rate-limit window.
    pub rate_limit_per_window: u32,
    /// Duration of the rate-limit sliding window in seconds.
    pub rate_limit_window_secs: u64,
    /// Optional FCM server key (Legacy HTTP API) for sending silent push notifications
    /// to offline recipients so their device wakes and drains via WebSocket.
    /// When absent the relay skips FCM delivery entirely — no crash, no error.
    /// Set via environment variable: RELAY_FCM_SERVER_KEY=<key>
    /// TODO: migrate to FCM v1 API (OAuth2) before production — Legacy key is deprecated.
    pub fcm_server_key: Option<String>,
}

impl RelayConfig {
    pub fn from_env() -> Self {
        Self {
            host: std::env::var("RELAY_HOST").unwrap_or_else(|_| "0.0.0.0".into()),
            port: std::env::var("RELAY_PORT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(8080),
            max_payload_bytes: std::env::var("RELAY_MAX_PAYLOAD_BYTES")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(65_536),
            envelope_ttl_secs: std::env::var("RELAY_ENVELOPE_TTL_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(7 * 24 * 3600), // 7 days
            max_envelopes_per_recipient: std::env::var("RELAY_MAX_ENVELOPES_PER_RECIPIENT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(500),
            secret_token: std::env::var("RELAY_SECRET_TOKEN").ok(),
            fcm_server_key: std::env::var("RELAY_FCM_SERVER_KEY").ok(),
            rate_limit_per_window: std::env::var("RELAY_RATE_LIMIT_PER_WINDOW")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            rate_limit_window_secs: std::env::var("RELAY_RATE_LIMIT_WINDOW_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
        }
    }
}

impl std::fmt::Debug for RelayConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayConfig")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("max_payload_bytes", &self.max_payload_bytes)
            .field("envelope_ttl_secs", &self.envelope_ttl_secs)
            .field("max_envelopes_per_recipient", &self.max_envelopes_per_recipient)
            .field("secret_token", &self.secret_token.as_ref().map(|_| "[REDACTED]"))
            .field("rate_limit_per_window", &self.rate_limit_per_window)
            .field("rate_limit_window_secs", &self.rate_limit_window_secs)
            .field("fcm_server_key", &self.fcm_server_key.as_ref().map(|_| "[REDACTED]"))
            .finish()
    }
}
