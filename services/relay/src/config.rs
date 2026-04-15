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
        }
    }
}
