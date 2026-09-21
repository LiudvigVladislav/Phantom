# Roadmap 2.0 -> Execution Map

This file translates the public roadmap into the current engineering order. The
original Alpha-0 startup sequence is complete and is preserved in git history;
it is no longer an active checklist.

## Current baseline - 2026-09-21

The following foundations are on `master`:

- Android/KMP application, Rust relay, CI, threat model, doctrine, and ADR set;
- encrypted 1:1 text and voice notes;
- Direct WSS, REALITY, Tor text fallback, and REST delivery fallback;
- encrypted local state, prekey bootstrap, Double Ratchet, and Sealed Sender;
- residual N1 recovery, including deterministic queued replay and a common
  settle-before-encrypt barrier;
- API 36 release configuration, fail-closed signing, and 16 KiB native-library
  alignment.

See [`STATUS_2026_09_21.md`](STATUS_2026_09_21.md) for the measured snapshot.

## Active delivery sequence

1. Finish Android design parity in its isolated worktree.
2. Rotate production relay to the durable-queue implementation already merged
   on `master`; remove the completed heartbeat-echo diagnostic flag.
3. Run the expanded two-device capability smoke: text and receipts, voice,
   Standard/Private/Ghost, network and process recovery, and supported call
   behavior.
4. Integrate design, run CI, build a signed APK/AAB, perform a clean install,
   and rehearse the exact presentation path.
5. Publish a new Alpha candidate only after those gates pass.

## Work that does not block the presentation candidate

- first-contact bootstrap latency reduction;
- production-stable encrypted groups;
- photo/file attachments through the media pipeline;
- calls leaving experimental status;
- desktop/iOS clients and linked-device identity;
- independent cryptographic audit and the offline-verification tooling that
  supports it.

These remain product work. They are not prerequisites for an honestly scoped
demo of 1:1 text, receipts, voice notes, and privacy modes.
