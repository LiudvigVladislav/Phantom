# PHANTOM Roadmap

This is the public roadmap. The [README](README.md) is the source of truth for
what works today; this file describes direction, not commitments. Horizons do
not carry target dates unless the work has a real, externally meaningful
deadline.

For the detailed internal execution map, see
[`docs/project/Roadmap_2.0_to_Execution_Map.md`](docs/project/Roadmap_2.0_to_Execution_Map.md).

---

## Current — Alpha 2

The current public surface is deliberately narrow:

- one-to-one encrypted text messaging;
- direct WSS, embedded Xray VLESS+REALITY, Tor v3 onion for text-only
  emergency fallback, and REST polling when carrier middleboxes break
  WebSocket delivery;
- encrypted voice messages over the media pipeline;
- X3DH-style prekeys, Double Ratchet sessions, authentication, and encrypted
  local storage.

The boundaries matter: the cryptographic protocol code is custom and has not
received an independent audit; groups and calls are not production-ready; Tor
is text-only; and field validation covers specific devices, carriers, routes,
and dates rather than every network or future DPI policy.

## Next

- Improve Direct/REST stability across carrier and network changes.
- Reduce first-contact bootstrap latency and failure modes.
- Make encrypted groups stable enough for public Alpha use.
- Ship encrypted attachments through the media pipeline.

## Beta horizon

- Harden one-to-one calls over Direct and REALITY transports.
- Deliver a desktop client with practical text and media parity.
- Expand the pluggable-transport surface without weakening metadata policy.
- Add linked-device identity and explicit cross-device trust.

## v1 horizon

- Ship an iOS client.
- Add public channels with a privacy-preserving moderation model.
- Add a rate-limited username directory.
- Publish and support a self-hostable relay distribution.
- Complete an independent security and cryptographic audit.

## Post-v1 research

These are research directions, not promised features:

- BLE and Wi-Fi Direct local mesh transport.
- Kademlia-style DHT discovery.
- Federation between independently operated PHANTOM deployments.
- Post-quantum migration paths for identity and session establishment.

## Explicit non-goals

We do not plan to become any of these, even under commercial pressure:

- A super-app with wallets, shopping, or mini-apps.
- A cryptocurrency platform.
- A content-moderation service that reads personal messages.
- A data broker or ad network.

These are ruled out by the [Product Doctrine](docs/doctrine/Product_Doctrine.md),
not just by lack of time.

## How to influence the roadmap

- **Product and feature proposals:** open a GitHub issue describing the user
  problem, not only the desired implementation. Proposals that conflict with
  the doctrine may be closed with an explanation.
- **Funding or collaboration:** write to `hello@phntm.pro`.
- **Security priorities:** use the private reporting process in
  [SECURITY.md](SECURITY.md). Never disclose a suspected vulnerability in a
  public issue.
