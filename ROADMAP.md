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
  local storage;
- ordered outbound recovery across process and network interruptions, including
  fail-closed settlement before every production encryption path;
- a durable production relay queue that survives relay process and container
  restarts, with the completed heartbeat-echo diagnostic disabled;
- authenticated, short-lived TURN fallback for one-to-one audio calls when
  direct ICE cannot connect the peers;
- Android API 36 release builds with verified 16 KiB native-library alignment.

The boundaries matter: the cryptographic protocol code is custom and has not
received an independent audit; groups and calls are not production-ready; the
TURN field proof used one physical phone and one emulator, not two physical
phones; Tor is text-only; and field validation covers specific devices,
carriers, routes, and dates rather than every network or future DPI policy.

## Next

- Finish the current Android design-parity pass and cut a new signed Alpha
  candidate from the resulting integrated tree.
- Complete English/Russian localization: follow the device language by default,
  offer an in-app System/English/Russian selector, and verify whole user flows
  rather than shipping a partially translated UI. See
  [`Localization_RU_Contract.md`](docs/project/Localization_RU_Contract.md).
- Audit every Settings control against its actual authority. Remove or label
  no-op switches, verify notifications and privacy on devices, and describe
  screenshot protection as local-device protection only. See
  [`Settings_Audit_2026-09-25.md`](docs/project/Settings_Audit_2026-09-25.md).
- Broaden device and network validation of the Direct/REST recovery behavior
  already present on `master`.
- Exercise provider and endpoint replacement so a single blocked IP range is
  not the only production path. Measure recovery without claiming universal
  censorship resistance.
- Repeat authenticated TURN calls between two physical phones on independent
  networks, investigate the intermittent phone-side crackle, and validate audio
  routing without emulator or acoustic-feedback ambiguity.
- Reduce first-contact bootstrap latency and failure modes.
- Make encrypted groups stable enough for public Alpha use.
- Ship encrypted one-to-one photos and files through the media pipeline only
  after size, quota, expiry, retry, and recipient-state behavior pass the
  [`Attachments_Contract.md`](docs/project/Attachments_Contract.md) matrix.

## Readiness gates

**Demo candidate:** a signed build from one integrated tree, with the actual
text, voice, and network-recovery flows shown on devices. The presentation
must label calls, groups, and attachments according to their measured status,
not portray planned features as available.

**Closed pilot:** core flows work for consenting external testers on their own
devices; onboarding and error states are understandable in supported
languages; crashes, delivery failures, and route failures have a minimal,
privacy-preserving aggregate measurement plan. A pilot is not a security
endorsement or broad production launch.

**Public launch:** independent security and cryptographic review, stable
recovery across provider failures, documented retention and support behavior,
and production-ready claims supported by multi-device field evidence. These
gates are intentionally separate from design-parity completion.

## Beta horizon

- Harden one-to-one calls across direct and TURN-assisted network paths.
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
