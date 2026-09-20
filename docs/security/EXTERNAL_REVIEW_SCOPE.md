# External security review: scope package

Last updated: 2026-09-17
Status: **not engaged.** No vendor has been selected, no engagement letter has
been signed, and no review has been scheduled. This document exists so that a
prospective reviewer can quote the work without a discovery call.

PHANTOM has never had an independent cryptographic or application-security
review. Every security claim the project makes today rests on the founder's own
reading of the code.

## 1. What we are asking to be reviewed

In priority order. A reviewer who can only take the first two should say so.

1. **The custom protocol composition.** PHANTOM composes X3DH-style key
   agreement, a Double Ratchet, and sealed-sender-inspired envelopes over
   libsodium. It is not the Signal Protocol and carries none of that protocol's
   review history. We want the composition itself examined, not just the
   primitives it calls.
2. **Key lifecycle.** Identity key generation on device, one-time prekey
   publication and consumption, signed prekey rotation, the two-phase deferred
   OPK consume path, and the pending/active session state machine.
3. **Relay trust boundary.** What the relay can learn, what it can withhold,
   what it can replay, and what an operator with database access can do. The
   relay is designed to be untrusted; we want that assumption attacked.
4. **Transport selection and failure modes.** Whether a blocked or degraded
   route can cause a downgrade that weakens confidentiality, and whether Ghost
   mode's fail-visible promise holds.
5. **Local storage and device compromise boundary.** Encrypted local storage,
   platform keystore usage, and what survives on a locked device.

## 2. Architecture and threat model inputs

- Architecture: [`ARCHITECTURE.md`](../../ARCHITECTURE.md) and
  [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)
- Threat model: [`docs/threat-model/Threat_Model_v0.md`](../threat-model/Threat_Model_v0.md)
- Crypto notes: [`docs/CRYPTO.md`](../CRYPTO.md)
- Privacy-mode behaviour: [`docs/PRIVACY_MODE_BEHAVIOR.md`](../PRIVACY_MODE_BEHAVIOR.md)
- Known issues: [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md)
- Prior internal audits (self-performed, not independent):
  [`docs/audit/`](../audit/)
- Security policy and disclosure route: [`SECURITY.md`](../../SECURITY.md)
- Field-test evidence: [`docs/field-tests/TEST_MATRIX.md`](../field-tests/TEST_MATRIX.md)

Data flow in one line: Android client → encrypted envelope → selected transport
(Direct WSS, VLESS+REALITY, Tor v3, or REST fallback) → untrusted Rust
store-and-forward relay → recipient.

## 3. Custom cryptographic code paths

These are the paths where PHANTOM wrote its own logic rather than calling a
vetted implementation, and they are where review effort is worth most.

- Double Ratchet implementation over libsodium primitives.
- Sealed-sender-style envelope construction and unwrapping.
- X25519 key derivation and the session bootstrap artifacts blob.
- One-time prekey reservation, deferred consume, and the LRU-bounded pending
  ratchet state.
- Safety Number derivation and the QR contact-exchange encoding.
- Relay authentication: per-user Ed25519 signed challenge.
- Relay-side storage format and delivery bookkeeping.

## 4. Out of scope for a first engagement

Groups, calls, and offline mesh are not production-ready and should not consume
review budget. Web and iOS clients do not exist.

## 5. Test and build instructions

The reviewer should be able to build and run without contacting us.

- Build from source: see [`README.md`](../../README.md), "Building from source".
- The Gradle wrapper is checked in; no network-fetched tooling is required
  beyond declared dependencies.
- The relay is in [`services/`](../../services/) and builds with Cargo.
- Test suites run through the repository's own Gradle and Cargo tasks.
- A reviewer who needs a running relay to test against should ask; we will
  provide a dedicated instance rather than access to the live one.

## 6. Deliverables we want

1. A findings report with severity, reproduction, and affected code path.
2. A short written opinion on whether the protocol composition is sound, or on
   what would have to change before it could be called sound.
3. A remediation review after we fix, covering the fixes only.
4. A public summary we may publish verbatim, agreed in advance.

We would rather receive a report that says the composition is not yet sound
than one that is hedged into uselessness.

## 7. Budget and vendor selection

The round allocates **$50,000** to independent security review and remediation.
That figure is an internal allocation, not a quote. No indicative quotes have
been requested yet; requesting them is the first action once the round's use of
funds is confirmed.

Vendor selection criteria, in the order we will apply them:

- Published work on messaging or transport-layer cryptography.
- Willingness to review a custom composition rather than only primitives.
- Ability to publish a summary we can show publicly.
- No requirement that we weaken the AGPL licence or withhold the threat model.

## 8. Language we may use, and when

- Before an engagement letter is signed: "independent security review" as a
  funded intention.
- Only after a signed engagement letter: "audit scheduled".
- Only after delivery and publication: "audited", with the summary linked.

Until the first of those becomes the second, every public surface must continue
to say that no independent audit has taken place.
