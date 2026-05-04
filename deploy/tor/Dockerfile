# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Tor daemon container for the PHANTOM relay onion service (ADR-016).
#
# Built from Alpine instead of a pre-rolled Tor image so we control the
# exact provenance and don't pull in third-party container layers we
# don't need. Alpine's tor package tracks the official Tor Project
# stable release and ships Vanguards-Lite (Proposal 333) by default for
# every onion service we run. See no-go-checks/02-vanguards-deployability.md
# for the rationale.
#
# Version pinning policy: Alpine major version is pinned (3.19), the tor
# package version is intentionally NOT pinned. Tor security advisories
# arrive frequently and Alpine 3.19 receives backports for ~2 years; an
# unpinned package keeps every rebuild on the latest patched release on
# the 3.19 line. Pinning a specific tor=x.y.z-rN also breaks reproducibly
# whenever Alpine prunes older patch versions from the index (which is
# what happened on 2026-05-04 with 0.4.8.16-r0 → 0.4.8.21-r0).

FROM alpine:3.19

RUN apk add --no-cache tor \
    && mkdir -p /var/lib/tor \
    && chown -R tor:nogroup /var/lib/tor \
    && chmod 700 /var/lib/tor

# Run as the unprivileged tor user. The image's tor user has UID 100;
# the named volume mounted at /var/lib/tor must be writable by that UID.
# docker-compose handles the chown via the volume's first-write semantics
# when the volume is created empty.
USER tor

# torrc is mounted read-only via docker-compose; this is just the
# default fallback path tor expects.
CMD ["tor", "-f", "/etc/tor/torrc"]
