/// Relay configuration loaded from environment variables.
#[derive(Clone, Debug)]
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
