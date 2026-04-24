# Multi-stage Dockerfile for phantom-relay.
#
# Stage 1: build the release binary against the full Rust toolchain.
# Stage 2: copy the binary into a minimal debian-slim runtime image.
# The final image runs as a non-root user and ships no build tools.

# -----------------------------------------------------------------------------
# Stage 1 — builder
# -----------------------------------------------------------------------------
FROM rust:1.83-slim-bookworm AS builder

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
# (avoids needing other workspace crates in the build context).
RUN sed -i 's|members = \[.*\]|members = ["relay"]|' Cargo.toml

RUN cargo build --release --package phantom-relay \
    && strip target/release/phantom-relay

# -----------------------------------------------------------------------------
# Stage 2 — runtime
# -----------------------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

# ca-certificates needed if the relay ever makes outbound HTTPS (e.g. FCM).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --shell /usr/sbin/nologin phantom

COPY --from=builder /build/target/release/phantom-relay /usr/local/bin/phantom-relay

USER phantom
EXPOSE 8080

# Healthcheck hits the /health endpoint over plain HTTP from inside the container.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/phantom-relay"]
