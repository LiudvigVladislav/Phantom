#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""
RC-DIRECT-STABILITY1 §10 T2 — slow-POST Caddy streaming preflight uploader.

Purpose. Before the T2 field test runs on Tecno + Tele2 LTE, the operator
MUST verify that Caddy in front of the relay does NOT buffer the entire
POST body before forwarding to the relay handler. If Caddy buffers, the
relay `event=slow_post_chunk_received` events all fire at the END of the
upload, defeating the byte-threshold experiment.

This script does what the Android `T2SlowPostDiag` will do: send 8 chunks
of 5120 bytes each, flushing the TCP socket after each chunk, sleeping 10
seconds between chunks. The operator watches relay logs in parallel and
verifies chunk events appear progressively (every ~10 s) rather than all
at the end.

Why a script and not `curl --limit-rate`. Per Vladislav 2026-06-06 hard
gate refinement: `curl --limit-rate` smooths the throttle but does NOT
guarantee discrete chunks with explicit per-chunk flush. We need to test
the chunked streaming path explicitly, not just slow bandwidth.

Usage:

    python scripts/t2-slow-post-preflight.py \\
        --url https://relay.phntm.pro/diag/slow-post

In a parallel terminal, on the VPS, tail relay logs:

    docker logs -f phantom-relay 2>&1 | \\
        grep -E "event=slow_post_chunk_received|event=slow_post_completed|event=slow_post_aborted"

Expected output (per chunk, every ~10 s, NOT all-at-end):

    event="slow_post_chunk_received" conn_id=N total_bytes=5120  elapsed_ms=~0
    event="slow_post_chunk_received" conn_id=N total_bytes=10240 elapsed_ms=~10000
    event="slow_post_chunk_received" conn_id=N total_bytes=15360 elapsed_ms=~20000
    ...
    event="slow_post_completed"      conn_id=N total_bytes=40960 elapsed_ms=~70000

If chunks all appear at the END (after ~70 s, all clustered together),
Caddy is buffering the body before forwarding to relay. In that case,
the T2 diagnostic through Caddy is unreliable — the field test path
must either disable Caddy buffering OR bypass Caddy (e.g. via the
already-deployed stunnel overlay on :8444, IF the operator chooses to
re-deploy that for T2 — but then we are testing a different path).

Server-side dependency. Operator must flip RELAY_ENABLE_SLOW_POST_DIAG=1
on the VPS .env and recreate relay so /diag/slow-post is mounted BEFORE
running this script. Otherwise the endpoint returns 404 (defence-in-depth
per services/relay/src/routes.rs:router()).

Locked design in docs/tracks/rc-direct-stability1.md §10 T2 mini-lock.
"""

import argparse
import socket
import ssl
import sys
import time
from urllib.parse import urlparse

CHUNK_BYTES = 5120
CHUNK_COUNT = 8
TOTAL_BYTES = CHUNK_BYTES * CHUNK_COUNT  # 40 960
DELAY_S_BETWEEN_CHUNKS = 10.0
HEADER_NAME = "X-Phantom-Diag"
HEADER_VALUE = "slow-post-v1"


def slow_post(url: str) -> int:
    """Send the slow POST. Returns process exit code (0 = success, 1 = failure)."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        print(f"ERROR: unsupported scheme {parsed.scheme!r}; expected http/https",
              file=sys.stderr)
        return 1
    host = parsed.hostname or ""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    path = parsed.path or "/"
    if not host:
        print(f"ERROR: could not parse host from {url!r}", file=sys.stderr)
        return 1

    # Open raw socket so we control flush timing per-chunk. The chunked
    # transfer-encoding framing is written manually (one chunk size line
    # + bytes + CRLF per chunk, terminator "0\r\n\r\n" at end).
    print(f"[t2-preflight] connecting to {host}:{port} (scheme={parsed.scheme}) ...",
          flush=True)
    raw = socket.create_connection((host, port), timeout=10.0)
    sock: socket.socket
    if parsed.scheme == "https":
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=host)
    else:
        sock = raw

    started_ms = time_ms()
    try:
        # Send request line + headers + empty line. Transfer-Encoding:
        # chunked tells the server (and any proxy) that we will frame the
        # body ourselves and that the proxy SHOULD forward chunk-by-chunk
        # without buffering — if it respects the spec.
        request_head = (
            f"POST {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            f"Content-Type: application/octet-stream\r\n"
            f"Transfer-Encoding: chunked\r\n"
            f"{HEADER_NAME}: {HEADER_VALUE}\r\n"
            f"Connection: close\r\n"
            f"\r\n"
        ).encode("ascii")
        sock.sendall(request_head)

        # Stream 8 chunks of CHUNK_BYTES, flush + sleep between.
        zeros = b"\x00" * CHUNK_BYTES
        total_sent = 0
        for i in range(CHUNK_COUNT):
            chunk_frame = f"{CHUNK_BYTES:X}\r\n".encode("ascii") + zeros + b"\r\n"
            sock.sendall(chunk_frame)
            total_sent += CHUNK_BYTES
            elapsed = time_ms() - started_ms
            print(
                f"[t2-preflight] chunk {i + 1}/{CHUNK_COUNT} sent "
                f"(chunk_bytes={CHUNK_BYTES} total_sent={total_sent} elapsed_ms={elapsed})",
                flush=True,
            )
            if i + 1 < CHUNK_COUNT:
                time.sleep(DELAY_S_BETWEEN_CHUNKS)

        # Terminating chunk per HTTP/1.1 chunked encoding.
        sock.sendall(b"0\r\n\r\n")

        # Read response.
        resp = b""
        while True:
            try:
                buf = sock.recv(4096)
            except socket.timeout:
                break
            if not buf:
                break
            resp += buf
        elapsed = time_ms() - started_ms
        head, _, body = resp.partition(b"\r\n\r\n")
        head_str = head.decode("latin1", errors="replace").splitlines()
        status_line = head_str[0] if head_str else "<no status>"
        print(
            f"[t2-preflight] response received "
            f"(status={status_line!r} body_bytes={len(body)} total_elapsed_ms={elapsed})",
            flush=True,
        )
        print(f"[t2-preflight] body head: {body[:200]!r}", flush=True)
        return 0 if status_line.startswith("HTTP/1.1 200") else 1
    finally:
        try:
            sock.close()
        except Exception:
            pass


def time_ms() -> int:
    return int(time.monotonic() * 1000)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    p.add_argument("--url", required=True,
                   help="Full URL to relay /diag/slow-post endpoint, "
                        "e.g. https://relay.phntm.pro/diag/slow-post")
    args = p.parse_args()
    return slow_post(args.url)


if __name__ == "__main__":
    sys.exit(main())
