# PHANTOM Relay

Store-and-forward relay for encrypted PHANTOM envelopes.
The relay stores ciphertext only — it has no access to message content,
sender identity beyond a public key prefix, or any plaintext.
See ADR-004 for the full trust model.

---

## Quick start

```bash
cargo build --release

RELAY_SECRET_TOKEN=changeme ./target/release/phantom-relay
```

The relay binds to `0.0.0.0:8080` by default and logs structured output to
stdout via `tracing`. Set log level with `RUST_LOG`:

```bash
RUST_LOG=phantom_relay=debug ./target/release/phantom-relay
```

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8080` | Bind port |
| `RELAY_MAX_PAYLOAD_BYTES` | `65536` (64 KB) | Maximum ciphertext payload per envelope |
| `RELAY_ENVELOPE_TTL_SECS` | `604800` (7 days) | Seconds before an undelivered envelope is purged |
| `RELAY_MAX_ENVELOPES_PER_RECIPIENT` | `500` | Queue cap per recipient public key |
| `RELAY_SECRET_TOKEN` | _(unset)_ | Shared secret required on `/ws?token=` and all `/admin/*` endpoints. **Must be set in production.** Unset = open dev mode. |
| `RELAY_RATE_LIMIT_PER_WINDOW` | `60` | Max messages a sender may send per window |
| `RELAY_RATE_LIMIT_WINDOW_SECS` | `60` | Fixed-window length in seconds (bucket + count; resets on rollover) |

The token is never logged. The startup banner emits `auth=true/false` only.

---

## API overview

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | none | Liveness check — returns `{"status":"ok"}` |
| `GET` | `/ws?id=<pubkey>&token=<secret>` | token (if set) | WebSocket connection for live delivery and send |
| `POST` | `/auth/session` | pubkey signature | Issue a bearer session token for the REST fallback endpoints below |
| `POST` | `/relay/send` | bearer | Envelope submission via the shard-worker actor path (REST fallback for middlebox environments that drop WS frames) |
| `POST` | `/relay/poll` | bearer | Long-poll for queued envelopes (Trek 2 Stage 1); hold window controlled by `RELAY_POLL_HOLD_SECS` |
| `POST` | `/relay/ack-deliver` | bearer | Acknowledge delivery of a previously polled envelope |
| `POST` | `/report` | none | Submit an abuse report |
| `GET` | `/admin/reports?token=<secret>` | token required | List all abuse reports |
| `POST` | `/admin/block?token=<secret>` | token required | Add a public key to the blocklist |
| `GET` | `/admin/blocklist?token=<secret>` | token required | List all blocked keys |

**Removed in PR-2 M6-3 round-1 (2026-07-31):** the legacy tooling / testing endpoints `POST /send`, `GET /fetch/{recipient}`, and `DELETE /ack/{id}` no longer exist. They were admin-token-guarded convenience routes predating the shard-worker actor cutover and wrote directly to the shared `state.store`, bypassing the per-recipient serialisation contract that `WorkerRuntime` owns. The primary transport paths are the WS handler above and the `/relay/*` REST fallback endpoints. Any tooling still calling `/send`, `/fetch`, or `/ack` now receives HTTP 404; migrate to `/relay/send` / `/relay/poll` / `/relay/ack-deliver` (bearer-authenticated) or use the WS path.

### WebSocket message types

Sent by the client over `/ws`:

```json
{ "type": "send", "to": "<pubkey>", "payload": "<base64-ciphertext>", "messageId": "<uuid>" }
{ "type": "ping" }
```

Received from the relay:

```json
{ "type": "deliver", "from": "<pubkey>", "payload": "<base64-ciphertext>", "messageId": "<uuid>" }
{ "type": "ack",     "messageId": "<uuid>", "status": "delivered" | "relayed" }
{ "type": "pong" }
```

The relay always uses the authenticated WebSocket identity as `from` —
client-supplied sender fields are ignored to prevent relay-layer spoofing.

---

## Admin panel

All admin endpoints require `?token=<RELAY_SECRET_TOKEN>`. Admin is disabled
entirely when `RELAY_SECRET_TOKEN` is not set.

**List abuse reports**

```bash
curl "https://relay.example.com/admin/reports?token=changeme"
```

Response:

```json
{
  "count": 1,
  "reports": [
    { "reporter_key": "...", "reported_key": "...", "category": "spam", "timestamp_ms": 1700000000000 }
  ]
}
```

**Block a public key**

```bash
curl -X POST "https://relay.example.com/admin/block?token=changeme" \
     -H "Content-Type: application/json" \
     -d '{"key":"<full-pubkey>"}'
```

Response: `{ "blocked": "<full-pubkey>" }`

**List blocklist**

```bash
curl "https://relay.example.com/admin/blocklist?token=changeme"
```

Blocked keys are silently dropped at send time — the sender receives no error.
Both reports and blocklist entries are persisted to disk so they survive restarts.

---

## Production deploy

### systemd unit

```ini
[Unit]
Description=PHANTOM Relay
After=network.target

[Service]
Type=simple
User=phantom
WorkingDirectory=/opt/phantom-relay
ExecStart=/opt/phantom-relay/phantom-relay
Restart=on-failure
RestartSec=5

Environment=RELAY_HOST=127.0.0.1
Environment=RELAY_PORT=8080
Environment=RELAY_ENVELOPE_TTL_SECS=604800
Environment=RELAY_MAX_PAYLOAD_BYTES=65536
Environment=RELAY_MAX_ENVELOPES_PER_RECIPIENT=500
Environment=RELAY_RATE_LIMIT_PER_WINDOW=60
Environment=RELAY_RATE_LIMIT_WINDOW_SECS=60
Environment=RUST_LOG=phantom_relay=info
EnvironmentFile=/etc/phantom-relay/secrets.env

[Install]
WantedBy=multi-user.target
```

`/etc/phantom-relay/secrets.env` should contain:

```
RELAY_SECRET_TOKEN=<strong-random-secret>
```

Keeping the token in `EnvironmentFile` rather than `Environment=` prevents it
from appearing in `systemctl show` output.

### nginx — HTTPS + WebSocket

```nginx
server {
    listen 443 ssl http2;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    # WebSocket upgrade
    location /ws {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host       $host;
        proxy_read_timeout 3600s;
    }

    # REST endpoints
    location / {
        proxy_pass       http://127.0.0.1:8080;
        proxy_set_header Host      $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}

server {
    listen 80;
    server_name relay.example.com;
    return 301 https://$host$request_uri;
}
```

---

## Security notes

- `RELAY_SECRET_TOKEN` **must be set in production.** Without it, any client
  can open a WebSocket and admin endpoints return 401 unconditionally.

- The same token must be compiled into the Android app as
  `BuildConfig.RELAY_TOKEN` so the client can pass `?token=<secret>` on
  WebSocket connect.

- The relay never logs token values or query strings. Full public keys never
  appear in logs — only the first 16 characters are used as correlation hints.

- Rate limiting is enforced per sender identity via a **fixed-window** counter
  (`services/relay/src/state.rs::RateEntry`): each identity gets a bucket
  bounded by `RELAY_RATE_LIMIT_PER_WINDOW` requests per
  `RELAY_RATE_LIMIT_WINDOW_SECS` seconds. On window rollover the first
  request after the boundary opens a fresh window with `count = 1`. Blocked
  keys are silently dropped — no error is returned to the sender.

- The relay stores ciphertext blobs only. It cannot read message content.
  Never pass plaintext or unencrypted metadata through the payload field.

- **Queue durability (PR-2 RC-RELAY-QUEUE-DURABILITY):** since PR-2 the
  envelope queue is disk-first, not RAM-only. Every admitted envelope is
  written via `atomic_write::write_atomic` (staging tempfile → fsync →
  rename → parent-fsync) under `RELAY_STATE_DIR/queue/<shard>/<recipient>/
  <sha256_hex(envelope_id)>.json`; the in-memory HashMap is a projection
  of what's on disk, not the source of truth. A relay restart replays
  the same directory tree at boot; nothing is lost. Envelope expiry
  compaction runs on the M4-3 background sweep scheduler inside the
  shard-worker actors (`RELAY_TOMBSTONE_DEDUP_HORIZON_SECS` controls
  the tombstone dedup window). Capacity ledger caps
  (`RELAY_QUEUE_RAM_BUDGET_BYTES`, `RELAY_QUEUE_MAX_BYTES`,
  `RELAY_QUEUE_MAX_ENVELOPES`) bound total occupancy; the RAM budget
  default is 80 MiB, calibrated by the M5b benchmark. See
  `docs/adr/ADR-027-relay-queue-durability-and-ram-budget.md` for the
  full model.
  (Abuse reports, blocklist entries, push tokens, and prekey bundles
  continue to persist as append-only JSONL side files alongside the
  queue directory; that pre-PR-2 side-store is unchanged.)
