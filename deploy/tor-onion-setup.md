# Tor onion service operational notes

For the PHANTOM relay onion service deployed as part of ADR-016
(Tor + UnifiedPush hybrid transport).

## Architecture quick recap

The `tor` Docker service runs an Alpine-based tor 0.4.8.16 daemon
configured as a hidden service host only. It does not act as a SOCKS
proxy and does not contribute bandwidth to the Tor network. Its sole
job is to publish a v3 onion service descriptor for the PHANTOM relay
and proxy incoming circuits to the relay's internal port 8080.

Network path:
```
   Tor client (kmp-tor on Android)
        │
        │  via Tor circuit (3-hop)
        ▼
   Tor rendezvous point
        │
        │  via Tor circuit (3-hop, on the service side)
        ▼
   tor daemon container (PHANTOM operator-controlled)
        │
        │  plaintext HTTP/WS over the docker bridge network
        ▼
   relay container :8080
```

TLS is intentionally not used between the Tor circuit and the relay
container — Tor's circuit already provides confidentiality, integrity,
and onion-address authentication, and adding TLS would only break the
authentication property without adding security.

## First-time deployment

1. Build and start the stack:
   ```
   docker compose -f deploy/docker-compose.yml up -d --build
   ```

2. Wait ~30 seconds for the tor daemon to bootstrap. Watch the logs:
   ```
   docker logs phantom-tor --follow
   ```
   Expected sequence:
   ```
   Tor 0.4.8.16 opening new log file.
   Bootstrapped 100% (done): Done
   ```

3. Retrieve the generated onion address:
   ```
   docker exec phantom-tor cat /var/lib/tor/hidden_service/hostname
   ```
   Output is the v3 onion address, e.g.
   `abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz23.onion`.
   This is the value PHANTOM clients will use as the relay endpoint
   when in Tor mode.

4. Commit the onion address to the appropriate place:
   - For Alpha bring-up: hardcode in
     `apps/android/src/androidMain/.../BuildConfig.RELAY_ONION_URL`.
   - For Beta: bake into bundled assets and rotate via app updates.

## Backing up the onion key

The onion address is derived from `hs_ed25519_secret_key` inside the
named volume `phantom-tor-data`. Losing this file rotates the onion
address — clients with the old address pinned will be unable to
connect until they receive the new one.

Back up procedure (run from the host shell, NOT from inside the
container):
```
docker run --rm \
    -v phantom-tor-data:/data:ro \
    -v $(pwd):/backup \
    alpine:3.19 \
    tar czf /backup/tor-onion-backup-$(date +%Y%m%d).tar.gz -C /data hidden_service
```

Restore procedure (DESTRUCTIVE — overwrites current onion identity):
```
docker compose -f deploy/docker-compose.yml stop tor
docker run --rm \
    -v phantom-tor-data:/data \
    -v $(pwd):/backup \
    alpine:3.19 \
    sh -c 'cd /data && tar xzf /backup/tor-onion-backup-YYYYMMDD.tar.gz'
docker compose -f deploy/docker-compose.yml start tor
```

Store backups encrypted, off-host, in at least two locations. The
secret key file IS the onion identity — anyone who possesses it can
impersonate the relay's onion address.

## Rotating the onion address (operator decision)

Reasons you might rotate:
- Suspected key compromise.
- Migrating to a new operator.
- Major architectural change.

Procedure:
```
docker compose -f deploy/docker-compose.yml stop tor
docker volume rm phantom-tor-data
docker compose -f deploy/docker-compose.yml up -d tor
docker exec phantom-tor cat /var/lib/tor/hidden_service/hostname
```

The new onion address must be distributed to clients via an app
update before the rotation; otherwise existing clients in Tor mode
lose connectivity until they update.

## Health check

```
docker exec phantom-tor cat /var/lib/tor/hidden_service/hostname
```
should print the address. Then from a Tor-enabled host:
```
torify curl -v http://<onion-address>/health
```
should return HTTP 200 with the same body as
`curl https://relay.phntm.pro/health`.

## Logs

`docker logs phantom-tor` for daemon-level events. Bootstrap progress,
connection counts, hidden service descriptor publication. The relay
itself logs nothing distinguishing for onion-arrived requests
(intentional — same code path as direct WSS).

## Known operational quirks

- **First bootstrap can be slow on a fresh VPS** (60-120 s to reach
  100%). Subsequent restarts are faster (5-15 s) because guards are
  cached in the volume.
- **Outbound bandwidth on the host:** Tor circuits use ~50-150 KB/s
  steady-state for an idle hidden service. Active conversations add
  message-size + circuit overhead. Hetzner CPX22 unmetered tier
  comfortably absorbs this.
- **Vanguards-Lite** (Proposal 333) is enabled by default in tor 0.4.7+
  and applies to every onion service we host. No torrc setting needed;
  no operational tunable. See no-go-checks/02-vanguards-deployability.md.
- **Restart behaviour:** stopping and starting the tor container does
  not change the onion address (key persists in the volume). Restart
  is safe; rebuild is safe; only `docker volume rm` rotates the address.

## Next stages

This stage (Stage 1 server infrastructure) only sets up the onion
service to receive connections. The PHANTOM Android client cannot yet
connect over Tor — that requires kmp-tor integration in Stage 2 of
ADR-016. After Stage 1 deploy, only direct WSS clients reach the
relay; onion service is silently waiting for clients that route there.

You can manually verify the onion endpoint works using `torify` or Tor
Browser before Stage 2 is shipped — connect to `http://<onion>/health`
and you should get the same response as the public WSS endpoint.
