# Multi-stage Dockerfile for phantom-relay.
#
# Stage 1: build the release binary against the full Rust toolchain.
# Stage 2: copy the binary into a minimal debian-slim runtime image.
# The final image runs as a non-root user and ships no build tools.

# -----------------------------------------------------------------------------
# Stage 1 — builder
# -----------------------------------------------------------------------------
FROM rust:1.88-slim-bookworm AS builder
# Rust 1.85+ is required because the workspace transitively pulls
# `time = 0.3.47` (and other crates) that opted into edition2024, which
# Cargo 1.83 cannot parse — the build aborts in the registry-fetch step
# with `feature `edition2024` is required`. 1.88 is the smallest version
# that comfortably covers anything pinned via Cargo.lock right now.

WORKDIR /build

# Install build dependencies. OpenSSL dev headers are required by some
# transitive crates (e.g. tokio-native-tls if pulled in later).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        pkg-config \
        libssl-dev \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy the workspace manifest and relay crate.
# Assumes docker build context is the repository root (see docker-compose.yml).
COPY services/Cargo.toml services/Cargo.lock ./
COPY services/relay ./relay

# Patch workspace members so only the relay crate is built in this stage
# (avoids needing other workspace crates in the build context). The sed
# uses -z (null-record mode) so it tolerates the multi-line `members = [
#   "relay",
#   "bootstrap",
#   ...
# ]` layout the workspace switched to once it grew past one crate.
RUN sed -i -z 's|members = \[[^]]*\]|members = ["relay"]|' Cargo.toml

RUN cargo build --release --package phantom-relay \
    && strip target/release/phantom-relay

# -----------------------------------------------------------------------------
# Stage 2 — runtime
# -----------------------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

# ca-certificates needed if the relay ever makes outbound HTTPS (e.g. FCM).
#
# `groupadd --gid 10001` is EXPLICIT (not implicit-via-useradd) so the
# gid is pinned to 10001 regardless of what the base image's
# /etc/group already contains. The pre-round-6 shape used a bare
# `useradd --system --uid 10001` which delegated group creation to
# Debian's `useradd`, which picks the next available system gid
# (`999` on `debian:bookworm-slim`) — that produced `/var/phantom`
# owned `10001:999:750` in production, tripping the RC-RELAY-STATE-
# DIR-REPAIR PR-1a §5.3 Path A witness (which expects
# `phantom:phantom:750` == `10001:10001:750`). Field-caught by the
# witness runbook 2026-07-13; see the round-6 amendment on PR #376.
#
# `--no-create-home` because the relay is a long-running daemon that
# never touches $HOME. `useradd --system` implied no home directory
# in the pre-round-6 shape; making it explicit here after dropping
# `--system` keeps the same behaviour.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 phantom \
    && useradd --uid 10001 --gid 10001 \
        --no-create-home --shell /usr/sbin/nologin phantom

# RC-RELAY-STATE-DIR-REPAIR PR-1a §5.1: seed the state-file directory the
# relay writes reports.jsonl / blocklist.txt / push_tokens.jsonl /
# prekeys.jsonl to. Owned by uid 10001 (phantom) with gid 10001 (phantom)
# and mode 750 so writes from the running container succeed against the
# volume mounted at this path by deploy/docker-compose.yml. The
# chown/chmod only take effect on the FIRST mount to an empty named
# volume — an already-populated production volume requires the operator
# sidecar in `docs/tracks/rc-relay-state-dir-repair.md` §5.3 Path A
# before deploy.
RUN mkdir -p /var/phantom \
    && chown phantom:phantom /var/phantom \
    && chmod 750 /var/phantom

COPY --from=builder /build/target/release/phantom-relay /usr/local/bin/phantom-relay

# RC-RELAY-STATE-DIR-REPAIR PR-1a §5.1: WORKDIR /var/phantom is
# defence-in-depth against a future refactor reintroducing a bare
# relative-path write. The canonical resolution path is via
# `RelayConfig.state_dir` (env `RELAY_STATE_DIR`, default `/var/phantom`)
# so a code-side change is the primary contract; WORKDIR is a belt-and-
# suspenders backstop that keeps a stray relative write inside the
# writable volume instead of landing at `/` on the read-only rootfs.
WORKDIR /var/phantom

USER phantom
EXPOSE 8080

# Healthcheck hits the /health endpoint over plain HTTP from inside the container.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/phantom-relay"]
