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

FROM alpine:3.19

RUN apk add --no-cache tor=0.4.8.16-r0 \
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
