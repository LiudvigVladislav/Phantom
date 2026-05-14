# ADR-021: Relay PreKey storage SQL migration

**Status:** Reserved — content pending
**Date reserved:** 2026-05-14
**Owner:** Relay maintainer (currently solo)
**Layer:** services/relay (Rust)

## Context

Relay PreKey storage currently uses `RwLock<HashMap>` + JSONL append, matching existing patterns for envelopes, reports, blocklist. This suffices at current Alpha-2 scale (single-VPS deployment, hundreds of identities, JSONL files <50 MB).

A migration to a SQL backend is anticipated but not yet planned in detail. This ADR reserves the slot and lists trigger conditions documented in `KNOWN_ISSUES.md` § ISSUE-012; the actual decision — storage engine choice (SQLite vs Postgres vs embedded KV), migration strategy (live vs maintenance-window), index design, JSONL backward-compat — will be made when one of the trigger conditions fires.

## Trigger conditions (when to write the rest of this ADR)

1. **Single-relay identity count crosses ~10 000.** `HashMap` + JSONL becomes memory-pressure-relevant on the 4 GB VPS that currently hosts the relay.
2. **JSONL replay-on-restart becomes user-visible during operator-driven redeploys.** Current baseline is not measured; revisit when the first complaint arrives or the first measured restart crosses ~5 seconds.
3. **Multi-relay federation lands on the roadmap.** `HashMap` cannot be shared across relay instances; any multi-instance deployment needs a shared storage layer.

Until one of these fires, the existing JSONL backend is the conscious choice — see ADR-006 lineage on "use boring tech where it fits".

## What this ADR is NOT

- Not a commitment to SQLite, Postgres, or any specific backend.
- Not a timeline. "When triggered" is the timeline.
- Not a substitute for SQL-migration design work — that comes later, when this ADR's `Status` flips from `Reserved` to `Proposed` and the body is filled in.

The companion `KNOWN_ISSUES.md` § ISSUE-012 forward-references this ADR slot so external readers see that the architectural question is **tracked**, not forgotten.

## References

- [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md) § ISSUE-012 — current architectural choice with the same trigger-condition narrative.
- [ADR-006](ADR-006-Crypto-Library-Decision.md) — the "use boring tech where it fits" lineage that informed the current JSONL choice.
- [`services/relay/src/prekeys.rs`](../../services/relay/src/prekeys.rs) — current implementation.
