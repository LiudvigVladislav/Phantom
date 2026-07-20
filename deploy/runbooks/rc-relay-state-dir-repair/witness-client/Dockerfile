# PR-1b staging witness — witness-client image.
#
# Two-stage build. Stage 1 compiles the Rust binary; stage 2 wraps it in
# a `distroless/cc` runtime that carries only the binary and the ld-
# resolvable glibc it links against — no shell, no coreutils, no
# package manager. The runtime is intentionally minimal so an operator
# who runs `docker run --rm --network container:... pr1b-witness-client
# ...` on the staging VPS cannot accidentally shell into the ephemeral
# witness container.
#
# Build (from this directory):
#   docker build -t pr1b-witness-client:local .
#
# The tag is deliberately unversioned + `local` so `docker pull` never
# accidentally overwrites this image with a random registry hit. The
# bash driver in `../pr1b-staging-witness.sh` (etc.) hard-codes this
# tag and refuses to run if the image is missing.

FROM rust:1.88-bookworm AS builder
WORKDIR /build
# Copy manifests first so a dependency-only rebuild is cached.
COPY Cargo.toml ./
COPY Cargo.lock ./
# The `src/` copy is the last layer because it changes the most often.
COPY src ./src
RUN --mount=type=cache,target=/build/target \
    --mount=type=cache,target=/usr/local/cargo/registry \
    cargo build --release --locked \
    && cp target/release/pr1b-witness-client /pr1b-witness-client

FROM gcr.io/distroless/cc-debian12:nonroot AS runtime
COPY --from=builder /pr1b-witness-client /usr/local/bin/pr1b-witness-client
ENTRYPOINT ["/usr/local/bin/pr1b-witness-client"]
