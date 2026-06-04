# SPDX-License-Identifier: AGPL-3.0-or-later
#
# RC-DIRECT-STABILITY1 Arm A.2 — minimal stunnel container built from
# upstream Alpine package. Used by the `stunnel-arm-a2` service in
# `deploy/docker-compose.armA2.yml`.
#
# Replaces the previous `image: dweomer/stunnel@sha256:c46e11...` choice
# from PR-8a (#285) and its port fixup (#286). dweomer/stunnel's
# entrypoint script generates `stunnel.conf` from `STUNNEL_SERVICE` /
# `STUNNEL_ACCEPT` / `STUNNEL_CONNECT` env vars instead of using a
# bind-mounted `/etc/stunnel/stunnel.conf` — incompatible with the
# §4 Arm A.2 Security mini-lock TLS hardening + cipher list + clients
# cap that we ship in `stunnel.armA2.conf`. Discovered at PR-8a deploy
# attempt 2026-06-05: container exited 1 with
#     one or more STUNNEL_SERVICE* values missing
# without applying our config.
#
# This Dockerfile builds locally on first
# `docker compose up -d stunnel-arm-a2` (one-time ~10-20 s). Alpine
# pinned by digest for reproducibility across deploys. `apk` pulls
# stunnel from Alpine's package index at build time — version locked
# to the 3.20 branch.
#
# To refresh the Alpine pin when a security-relevant Alpine / stunnel /
# OpenSSL update lands, see the "Alpine-pin refresh handling note" in
# `docs/tracks/rc-direct-stability1.md` §4 Arm A.2 PR-8a implementation
# record subsection.

FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc

# stunnel package + minimal apk cache cleanup. No additional packages
# beyond stunnel itself — Alpine's apk install pulls stunnel + its
# direct runtime dependencies (libcrypto, libssl) and nothing else.
RUN apk add --no-cache stunnel && rm -rf /var/cache/apk/*

# Entrypoint goes straight to the stunnel binary with our bind-mounted
# config — no init script in between. Foreground mode (`foreground = yes`)
# and stdout logging (`output = /dev/stdout`) are set inside the config.
# stunnel writes NO pid file (`pid =` empty in config) so the
# `read_only` rootfs in `docker-compose.armA2.yml` security posture does
# not collide with `/var/run/stunnel.pid` write attempts at startup.
ENTRYPOINT ["stunnel", "/etc/stunnel/stunnel.conf"]
