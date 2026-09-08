# PHANTOM Roadmap

**Status checkpoint:** 2026-09-08. This ordering index describes product
direction, not dates, implementation acceptance or authorization to deploy.
The [development status](docs/project/STATUS_2026_09_08.md) distinguishes
published code from local candidates and named physical checks. The
[README](README.md) describes the public Alpha surface, not a reliability guarantee.

## Product priority

PHANTOM should work where other messengers begin to fail, while remaining
pleasant to use. Delivery, recovery, privacy, identity continuity and safety
take precedence over adding features.

## Execution order

1. **N1 / WSS-3: closed at the 4/8 boundary.** On 2026-09-08 the owner closed
   this measurement track with the four accepted host-VPN-on carrier/phone-VPN
   profiles. The four unrun host-VPN-off profiles will not be conducted and are
   excluded from the remaining plan. They are neither passes nor failures;
   an all-eight comparison cannot be claimed. This is scope closure, not new
   physical evidence or broad transport qualification.
2. **N1 / Direct product reliability.** Audit and agree the Direct/REST
   split-state, active transport selection, recovery and queued delivery
   contract. Reconcile the local delivery corrections with that contract;
   passing selected tests does not close the whole milestone.
3. **INF0 / Infrastructure.** Inventory the Floki outage, current service roles,
   dependencies, privacy boundaries and migration options before choosing
   recovery or deployment work. Proposals are not deployment authority.
4. **N1 remainder.** Qualify lifecycle/idle and queued-delivery soak, Wi-Fi,
   and network/VPN handoffs. Mobile-carrier tests do not qualify Wi-Fi, and
   USB-powered tests do not qualify unplugged background operation.
5. **N2 / Private and Ghost.** Run representative mode qualification after
   the required transport and infrastructure prerequisites are available.
6. **D1-A / Application-wide design.** Inventory and complete the existing
   application using the accepted design models and templates. The merged
   onboarding baseline is not completion of the whole app design.
7. **I1 + D1-B / Backup and restore.** Agree the cryptographic ADR and UX
   contract before implementation. Existing-account recovery remains on hold
   pending those decisions; recovery foundations are not paywalled.
8. **OBS1 / Privacy-safe observability.** Define a canonical account registry
   and bounded registration/activity metrics without message content, contact
   graphs, raw keys, usernames or persistent IP history.
9. **S1 + D1-B / Safety.** Local block/report and bounded operator enforcement.
10. **U1 + D1-B / Handles.** Globally unique PHANTOM handles and reservation policy.
11. **D1 integration.** Unify release-foundation surfaces in one design system.
12. **SEC1 / Security.** Audit user flows, client, protocol, backend,
    infrastructure and operations, with remediation gates before release.
13. **R1 / Closed Android alpha.** Qualify recovery, safety, observability,
    security and support readiness. This is a future release gate, not the
    historical Alpha tag or a declaration that current candidates have landed.
14. **M1 + IOS1.** Media/voice-message quality and iOS parity foundations.
15. **C1.** Voice and video calls.
16. **G1.** Small private groups.
17. **Gate C.** Public beta.
18. **P1.** Subscriptions, after privacy-safe retention evidence.
19. **G2.** Large groups and channels.
20. **Innovation.** At most one measured experiment at a time after the core
    product promise is dependable.

## Scope discipline

Critical security, message-loss, identity-loss, abuse-safety or store-compliance
findings may interrupt this order. Other findings enter the backlog first.
Only one implementation track runs at a time. Changing order requires an
explicit owner decision, dependencies and a named acceptance gate.

Chat-opening/rendering latency was deferred on 2026-09-08. Correct notification
routing was observed on the local candidate, but the remaining delay is not
declared fixed. It does not open a parallel optimization track.

## Historical plans and research

Older phase dates, monetization assumptions and feature horizons in the
[2026 plan](docs/project/PHANTOM_ROADMAP_2026.md) and
[early execution map](docs/project/Roadmap_2.0_to_Execution_Map.md) are historical,
not the current order. Prior desktop, linked-device, self-hosting, mesh, DHT,
federation and post-quantum ideas remain inputs requiring their own decisions;
this update assigns no new release deadline or implementation slot to them.

## Explicit non-goals

- A super-app with wallets, shopping or mini-apps.
- A cryptocurrency platform.
- A moderation service that reads personal messages.
- A data broker or advertising network.

These boundaries follow the [Product Doctrine](docs/doctrine/Product_Doctrine.md).
Product proposals can be discussed through GitHub issues; suspected
vulnerabilities must use the private process in [SECURITY.md](SECURITY.md).
