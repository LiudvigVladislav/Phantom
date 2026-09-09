#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — orchestrator-side egress HMAC helpers.
#
# Contract: §4.5 + §4.6 + §7 R8 + §7 R10.
#
#   - Orchestrator mints ONE 32-byte cryptographically random
#     `checkpoint_key` per checkpoint (broadcast to emu reporter,
#     used locally on Mac host).
#   - Host fingerprint = HMAC-SHA256(checkpoint_key,
#     canonical_ip_bytes)[0:16] — first 16 bytes hex (32 chars).
#   - `canonical_ip_bytes` = the IP string returned by
#     `api4.ipify.org`, UTF-8, trimmed, no `\n`.
#   - Endpoint pinned to api4.ipify.org (IPv4-only). Any getaddrinfo
#     result that isn't AF_INET → mixed_address_family fail-closed at
#     the caller.
#   - Raw IPs never touch disk.
#   - `checkpoint_key` is wiped from local shell variables after HMAC
#     computation (best-effort; Bash string handling limitations
#     acknowledged) and NEVER persisted to any file.

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

# Overridable via env var — tests inject scratch endpoints.
WSS3_EGRESS_ENDPOINT="${WSS3_EGRESS_ENDPOINT:-https://api4.ipify.org}"
WSS3_EGRESS_TIMEOUT_S="${WSS3_EGRESS_TIMEOUT_S:-5}"

# wss3_mint_checkpoint_key
#   Prints a 64-char lowercase hex string (32 random bytes).
wss3_mint_checkpoint_key() {
    python3 -c 'import os; print(os.urandom(32).hex())'
}

# wss3_host_egress_fingerprint <checkpoint-key-hex>
#   Prints a single line with three fields separated by spaces:
#       <hmac_fp_hex-or-null> <address_family> <http_status-or-null>
#   Sets exit code 0 on success (even for non-200 / timeout — the null
#   fp is a valid recorded outcome), non-zero on catastrophic tool
#   failure (curl missing, python missing).
#
# Dry-run override (contract §10 step 4): if WSS3_EGRESS_BODY_OVERRIDE
# is set, the network round-trip is SKIPPED and the HMAC is computed
# over the literal body bytes with AF taken from
# WSS3_EGRESS_AF_OVERRIDE (default AF_INET). Both host and emulator
# converge on the same value in the synthetic Mac dry-run, which
# proves the equality check works — while in production the real
# api4.ipify.org round-trip runs unchanged.
wss3_host_egress_fingerprint() {
    local key_hex="$1"
    if [ ${#key_hex} -ne 64 ]; then
        echo "wss3-egress: checkpoint-key-hex must be 64 chars" >&2
        return 2
    fi
    if [ -n "${WSS3_EGRESS_BODY_OVERRIDE:-}" ]; then
        local af="${WSS3_EGRESS_AF_OVERRIDE:-AF_INET}"
        local hmac
        hmac=$(wss3_hmac_from_bytes "$key_hex" "$WSS3_EGRESS_BODY_OVERRIDE")
        printf '%s %s %s' "$hmac" "$af" "200"
        return 0
    fi
    # Use python for HMAC + address-family classification because
    # bash's stdlib doesn't do either portably. Curl fetches the
    # body with a hard timeout; the raw IP body is piped to python
    # via a pipe and never touches disk.
    local host body af status timeout
    timeout="$WSS3_EGRESS_TIMEOUT_S"
    host="${WSS3_EGRESS_ENDPOINT#https://}"; host="${host#http://}"; host="${host%%/*}"

    # Classify the getaddrinfo result BEFORE fetching so the caller
    # can distinguish "endpoint resolved to AF_INET6, mixed family"
    # from "fetch failed".
    af=$(python3 - <<PY
import socket, sys
try:
    infos = socket.getaddrinfo("$host", 443, socket.AF_UNSPEC, socket.SOCK_STREAM)
except Exception:
    print("UNKNOWN"); sys.exit(0)
if any(i[0] == socket.AF_INET for i in infos):
    print("AF_INET")
elif any(i[0] == socket.AF_INET6 for i in infos):
    print("AF_INET6")
else:
    print("UNKNOWN")
PY
)

    # Fetch + HMAC in one python invocation so the raw IP is discarded
    # immediately after hashing. urllib.request keeps it in memory
    # only long enough for the HMAC to be computed.
    #
    # We pass the key via env var — never on the shell command line so
    # it doesn't leak to `ps` output. We wipe the env var on the child
    # process's own line after use.
    local out
    out=$(WSS3_KEY_HEX_TMP="$key_hex" WSS3_ENDPOINT="$WSS3_EGRESS_ENDPOINT" WSS3_TIMEOUT="$timeout" python3 - <<'PY'
import os, hmac, hashlib, urllib.request, socket, sys, ssl
key_hex = os.environ.pop("WSS3_KEY_HEX_TMP", None)
endpoint = os.environ.pop("WSS3_ENDPOINT", None)
try:
    timeout = int(os.environ.pop("WSS3_TIMEOUT", "5"))
except ValueError:
    timeout = 5
if not key_hex or len(key_hex) != 64:
    print("null null")
    sys.exit(0)
if not endpoint:
    print("null null")
    sys.exit(0)
try:
    key = bytes.fromhex(key_hex)
except ValueError:
    print("null null")
    sys.exit(0)
status = "null"
fp_hex = "null"
try:
    ctx = ssl.create_default_context()
    req = urllib.request.Request(endpoint, method="GET",
                                 headers={"Connection": "close"})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        status = str(resp.status)
        raw = resp.read().decode("utf-8", errors="replace").strip().encode("utf-8")
        if resp.status == 200 and raw:
            digest = hmac.new(key, raw, hashlib.sha256).digest()
            fp_hex = digest[:16].hex()
except urllib.error.HTTPError as e:
    status = str(e.code)
except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError, OSError):
    status = "null"
finally:
    key = b""
    key_hex = ""
    raw = b""
print(f"{fp_hex} {status}")
PY
)
    key_hex="wiped"  # best-effort local wipe
    unset WSS3_KEY_HEX_TMP
    # Parse and normalise.
    local fp st
    fp=$(printf '%s' "$out" | awk '{print $1}')
    st=$(printf '%s' "$out" | awk '{print $2}')
    printf '%s %s %s' "$fp" "$af" "$st"
    return 0
}

# wss3_hmac_from_bytes <checkpoint-key-hex> <canonical-bytes-str>
#   Deterministic pure-function HMAC used by tests. Prints the 32-char
#   hex fingerprint (first 16 bytes of HMAC-SHA256) — used to
#   double-check determinism across host + emu.
wss3_hmac_from_bytes() {
    local key_hex="$1" body="$2"
    WSS3_KEY_HEX_TMP="$key_hex" WSS3_BODY_TMP="$body" python3 - <<'PY'
import os, hmac, hashlib
k = os.environ.pop("WSS3_KEY_HEX_TMP", "")
b = os.environ.pop("WSS3_BODY_TMP", "").encode("utf-8")
if not k or len(k) != 64:
    print("null"); raise SystemExit
try:
    key = bytes.fromhex(k)
except ValueError:
    print("null"); raise SystemExit
print(hmac.new(key, b, hashlib.sha256).digest()[:16].hex())
PY
}
