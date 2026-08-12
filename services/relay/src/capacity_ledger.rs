// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M1 — three-counter global capacity gate.
//!
//! Locked design: `v4.2-amendments.md` §4 B-4 (three counters +
//! transition table) + `v4.1-amendments.md` §1 V-P0-6 (`parking_lot::
//! Mutex`, synchronous — allows `Drop`-time rollback that
//! `tokio::sync::Mutex` would force through `.await`).
//!
//! At M1 the gate ships with the type surface + reserve-for-send +
//! Drop-rollback + explicit commit/release; the on-disk persistence
//! ledger reconciliation (v4.2 §4 boot-time ledger recompute from
//! disk truth) lands in M2 once the persistence layer exists.

use parking_lot::Mutex;

/// Kind of record a capacity slot corresponds to.
///
/// Locked design v4.2 §4 transition table: a `Queued` record
/// consumes one `active_envelope` + `active_bytes`; an
/// `AckedTombstone` record consumes one `tombstone_record` +
/// `tombstone_bytes`; both consume RAM.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordKind {
    Queued,
    AckedTombstone,
}

/// Backing counters guarded by [`GlobalCapacityGate::inner`].
///
/// Every counter is an unsigned `u64` bytes-count (or record-count).
/// The design demands `i64` deltas for transitions because
/// `Queued → AckedTombstone` decreases `active_*` while increasing
/// `tombstone_*` — see [`GlobalCapacityGate::transition`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlobalCapacityInner {
    pub active_envelopes: u64,
    pub active_bytes: u64,
    pub tombstone_records: u64,
    pub tombstone_bytes: u64,
    pub ram_bytes: u64,
}

impl Default for GlobalCapacityInner {
    fn default() -> Self {
        Self {
            active_envelopes: 0,
            active_bytes: 0,
            tombstone_records: 0,
            tombstone_bytes: 0,
            ram_bytes: 0,
        }
    }
}

/// Configuration caps (all `u64` bytes / record counts).
///
/// Locked design v4 §8 + v4.2.1 §6: `max_bytes` bounds
/// `active_bytes + tombstone_bytes`; `max_envelopes` bounds
/// `active_envelopes` only (tombstones do NOT count against the
/// sender-facing 429 threshold); `ram_budget` bounds `ram_bytes`.
#[derive(Debug, Clone, Copy)]
pub struct CapacityCaps {
    pub max_envelopes: u64,
    pub max_bytes: u64,
    pub ram_budget: u64,
}

/// Error kinds returned by [`GlobalCapacityGate::reserve_send`] and
/// [`GlobalCapacityGate::transition`].
///
/// Each variant carries the observed value and the cap it would
/// have exceeded, so callers can log a precise refusal reason and
/// operators can distinguish `TOO_MANY_REQUESTS` cases at a glance.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapacityError {
    EnvelopeCapExceeded {
        observed: u64,
        cap: u64,
    },
    DiskCapExceeded {
        observed: u64,
        cap: u64,
    },
    RamCapExceeded {
        observed: u64,
        cap: u64,
    },
    /// A counter would have overflowed `u64` — realistically
    /// impossible at production scale but the check costs
    /// nothing and covers a hypothetical accounting bug.
    ArithmeticOverflow,
    /// A counter would have gone below zero. Round-1 review P1 #2:
    /// this MUST be fail-loud; the pre-amendment `saturating_sub`
    /// silently hid double-releases and accounting drift in release
    /// builds. Every underflow now surfaces as an explicit error
    /// AND leaves the ledger unchanged (see
    /// [`GlobalCapacityGate::transition`] atomicity contract).
    ArithmeticUnderflow,
}

impl std::fmt::Display for CapacityError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CapacityError::EnvelopeCapExceeded { observed, cap } => {
                write!(f, "envelope cap exceeded: {observed} > {cap}")
            }
            CapacityError::DiskCapExceeded { observed, cap } => {
                write!(f, "disk cap exceeded: {observed} > {cap}")
            }
            CapacityError::RamCapExceeded { observed, cap } => {
                write!(f, "ram cap exceeded: {observed} > {cap}")
            }
            CapacityError::ArithmeticOverflow => write!(f, "capacity accounting overflow"),
            CapacityError::ArithmeticUnderflow => write!(f, "capacity accounting underflow"),
        }
    }
}

/// Full footprint of a single `PersistedRecord` for accounting.
///
/// Each transition ships two independent footprints — the outgoing
/// record's actual size (from the on-disk `PersistedRecord::Queued`
/// serialized bytes at the time of write) and the incoming record's
/// size. That decouples the two: a `Queued`(200 B envelope) → `AckedTombstone`(32 B)
/// transition must release 200 B from `active_bytes` and add 32 B to
/// `tombstone_bytes`. The pre-amendment API used a single `disk_bytes`
/// argument for both, which the round-1 P0 review correctly flagged
/// as unsound.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordFootprint {
    pub kind: RecordKind,
    pub disk_bytes: u64,
    pub ram_bytes: u64,
}

impl std::error::Error for CapacityError {}

/// Global capacity gate — one instance per relay process.
///
/// Locked design v4.2 §4: guards the three-counter transitions
/// under a `parking_lot::Mutex` (synchronous, ~10µs critical
/// section, no `.await`). At 10K QPS shared across the fleet the
/// mutex-hold-time × QPS product is negligible; if contention ever
/// becomes visible a future PR shards the gate across 4 stripes.
pub struct GlobalCapacityGate {
    inner: Mutex<GlobalCapacityInner>,
    caps: CapacityCaps,
}

impl GlobalCapacityGate {
    /// Construct a gate with the given caps and zeroed counters.
    ///
    /// The boot loader (M2) will overwrite the counters via
    /// `transition` calls once it has walked the on-disk state;
    /// M1 ships this constructor for unit tests and for future
    /// M4 wiring.
    pub fn new(caps: CapacityCaps) -> Self {
        Self {
            inner: Mutex::new(GlobalCapacityInner::default()),
            caps,
        }
    }

    /// Reserve capacity for a single `Queued` record about to be
    /// persisted (locked design v4.2 §4 first row of transition
    /// table).
    ///
    /// Returns a RAII [`CapacityReservation`] that rolls the
    /// reservation back on `Drop` unless the caller called
    /// [`CapacityReservation::commit`] first. The commit-or-rollback
    /// contract lets the worker acquire capacity BEFORE the
    /// potentially-failing tempfile+fsync write and release it
    /// automatically on any failure path.
    pub fn reserve_send(
        &self,
        disk_bytes: u64,
        ram_bytes: u64,
    ) -> Result<CapacityReservation<'_>, CapacityError> {
        let mut inner = self.inner.lock();
        let new_envelopes = inner
            .active_envelopes
            .checked_add(1)
            .ok_or(CapacityError::ArithmeticOverflow)?;
        let new_active_bytes = inner
            .active_bytes
            .checked_add(disk_bytes)
            .ok_or(CapacityError::ArithmeticOverflow)?;
        let combined_bytes = new_active_bytes
            .checked_add(inner.tombstone_bytes)
            .ok_or(CapacityError::ArithmeticOverflow)?;
        let new_ram = inner
            .ram_bytes
            .checked_add(ram_bytes)
            .ok_or(CapacityError::ArithmeticOverflow)?;

        if new_envelopes > self.caps.max_envelopes {
            return Err(CapacityError::EnvelopeCapExceeded {
                observed: new_envelopes,
                cap: self.caps.max_envelopes,
            });
        }
        if combined_bytes > self.caps.max_bytes {
            return Err(CapacityError::DiskCapExceeded {
                observed: combined_bytes,
                cap: self.caps.max_bytes,
            });
        }
        if new_ram > self.caps.ram_budget {
            return Err(CapacityError::RamCapExceeded {
                observed: new_ram,
                cap: self.caps.ram_budget,
            });
        }

        inner.active_envelopes = new_envelopes;
        inner.active_bytes = new_active_bytes;
        inner.ram_bytes = new_ram;

        Ok(CapacityReservation {
            gate: Some(self),
            active_bytes_delta: disk_bytes,
            ram_bytes_delta: ram_bytes,
            committed: false,
        })
    }

    /// Snapshot of the current counters. Nanosecond critical section.
    pub fn snapshot(&self) -> GlobalCapacityInner {
        *self.inner.lock()
    }

    /// Snapshot of the caps this gate was constructed with.
    pub fn caps(&self) -> CapacityCaps {
        self.caps
    }

    /// Apply a `RecordKind` transition (locked design v4.2 §4
    /// transition table).
    ///
    /// - `(None, Some(footprint))` — new record persisted (only used
    ///   by boot-time reconciliation or by callers that own their
    ///   own cap gating). New sends go through
    ///   [`GlobalCapacityGate::reserve_send`], which additionally
    ///   enforces caps.
    /// - `(Some(from), Some(to))` — Queued↔AckedTombstone shift,
    ///   or a same-kind footprint edit.
    /// - `(Some(footprint), None)` — TTL sweep releases.
    ///
    /// **Atomicity (round-1 P1 #2)**: this method computes the full
    /// post-transition counter set OUTSIDE any mutation, then
    /// assigns the ledger in a single write. Any underflow surfaces
    /// as [`CapacityError::ArithmeticUnderflow`] and leaves the
    /// ledger unchanged. Any overflow surfaces as
    /// [`CapacityError::ArithmeticOverflow`] with the same
    /// guarantee. `saturating_sub` is deliberately not used —
    /// underflow indicates a real accounting invariant break and
    /// MUST be observable in release builds.
    ///
    /// **Footprint independence (round-1 P0 #1)**: `from` and `to`
    /// carry separate `disk_bytes` + `ram_bytes` because the
    /// outgoing and incoming records almost never share a size
    /// (e.g. Queued 200 B envelope → AckedTombstone 32 B tombstone).
    /// Pre-amendment API took a single `disk_bytes` and silently
    /// mis-accounted.
    ///
    /// **RAM concurrency (round-1 P0 #1 tail)**: `ram_bytes` is
    /// applied as a delta (`- from.ram_bytes + to.ram_bytes`), not
    /// as an absolute assignment. Absolute assignment would race
    /// concurrent transitions and let a slow ack overwrite a fast
    /// send's ram footprint.
    ///
    /// Caps are NOT checked here — post-send state transitions are
    /// on operations already committed to disk. Only
    /// [`GlobalCapacityGate::reserve_send`] rejects on caps.
    pub fn transition(
        &self,
        from: Option<RecordFootprint>,
        to: Option<RecordFootprint>,
    ) -> Result<(), CapacityError> {
        let mut inner = self.inner.lock();
        let next = compute_next_counters(*inner, from, to)?;
        *inner = next;
        Ok(())
    }
}

/// Pure function that computes the post-transition counter set
/// without touching the mutex. Split out for unit-testability of
/// the underflow/overflow logic + as a hard atomicity guarantee:
/// [`GlobalCapacityGate::transition`] cannot partially mutate the
/// ledger because this function either produces a complete `next`
/// or an error, and the caller assigns `next` in one write.
fn compute_next_counters(
    current: GlobalCapacityInner,
    from: Option<RecordFootprint>,
    to: Option<RecordFootprint>,
) -> Result<GlobalCapacityInner, CapacityError> {
    let mut next = current;

    // Release `from` first — fail-loud on underflow.
    if let Some(f) = from {
        next.ram_bytes = next
            .ram_bytes
            .checked_sub(f.ram_bytes)
            .ok_or(CapacityError::ArithmeticUnderflow)?;
        match f.kind {
            RecordKind::Queued => {
                next.active_envelopes = next
                    .active_envelopes
                    .checked_sub(1)
                    .ok_or(CapacityError::ArithmeticUnderflow)?;
                next.active_bytes = next
                    .active_bytes
                    .checked_sub(f.disk_bytes)
                    .ok_or(CapacityError::ArithmeticUnderflow)?;
            }
            RecordKind::AckedTombstone => {
                next.tombstone_records = next
                    .tombstone_records
                    .checked_sub(1)
                    .ok_or(CapacityError::ArithmeticUnderflow)?;
                next.tombstone_bytes = next
                    .tombstone_bytes
                    .checked_sub(f.disk_bytes)
                    .ok_or(CapacityError::ArithmeticUnderflow)?;
            }
        }
    }

    // Add `to` second — fail-loud on overflow.
    if let Some(f) = to {
        next.ram_bytes = next
            .ram_bytes
            .checked_add(f.ram_bytes)
            .ok_or(CapacityError::ArithmeticOverflow)?;
        match f.kind {
            RecordKind::Queued => {
                next.active_envelopes = next
                    .active_envelopes
                    .checked_add(1)
                    .ok_or(CapacityError::ArithmeticOverflow)?;
                next.active_bytes = next
                    .active_bytes
                    .checked_add(f.disk_bytes)
                    .ok_or(CapacityError::ArithmeticOverflow)?;
            }
            RecordKind::AckedTombstone => {
                next.tombstone_records = next
                    .tombstone_records
                    .checked_add(1)
                    .ok_or(CapacityError::ArithmeticOverflow)?;
                next.tombstone_bytes = next
                    .tombstone_bytes
                    .checked_add(f.disk_bytes)
                    .ok_or(CapacityError::ArithmeticOverflow)?;
            }
        }
    }

    Ok(next)
}

/// RAII slot returned by [`GlobalCapacityGate::reserve_send`].
///
/// If the caller succeeds, they invoke [`CapacityReservation::commit`]
/// to lock the reservation in. If they drop this value without
/// committing (any early return, `?` propagation, panic), the
/// counters are rolled back automatically. See v4.1 §1 V-P0-6.
pub struct CapacityReservation<'a> {
    gate: Option<&'a GlobalCapacityGate>,
    active_bytes_delta: u64,
    ram_bytes_delta: u64,
    committed: bool,
}

impl std::fmt::Debug for CapacityReservation<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual Debug impl — `GlobalCapacityGate` cannot derive
        // Debug because `parking_lot::Mutex` does not; render the
        // presence bit + numeric fields instead so unit-test
        // `expect_err` messages stay readable.
        f.debug_struct("CapacityReservation")
            .field("gate_present", &self.gate.is_some())
            .field("active_bytes_delta", &self.active_bytes_delta)
            .field("ram_bytes_delta", &self.ram_bytes_delta)
            .field("committed", &self.committed)
            .finish()
    }
}

impl<'a> CapacityReservation<'a> {
    /// Consume the reservation as a successful commit. No rollback
    /// runs on subsequent `Drop`.
    pub fn commit(mut self) {
        self.committed = true;
    }
}

impl Drop for CapacityReservation<'_> {
    fn drop(&mut self) {
        if self.committed {
            return;
        }
        let Some(gate) = self.gate else { return };
        // Round-1 P1 #2 fail-loud rollback: same atomicity contract
        // as `transition`. Compute the full post-rollback snapshot
        // outside the mutation; any underflow panics with a
        // descriptive message rather than silently masking a
        // double-release with `saturating_sub`. If an underflow ever
        // fires here it indicates a serious accounting invariant
        // break (reservation committed twice, gate mutated out of
        // band, etc.) — the panic is the right signal.
        let mut inner = gate.inner.lock();
        let next_active_envelopes = inner
            .active_envelopes
            .checked_sub(1)
            .expect("CapacityReservation::drop: active_envelopes underflow");
        let next_active_bytes = inner
            .active_bytes
            .checked_sub(self.active_bytes_delta)
            .expect("CapacityReservation::drop: active_bytes underflow");
        let next_ram_bytes = inner
            .ram_bytes
            .checked_sub(self.ram_bytes_delta)
            .expect("CapacityReservation::drop: ram_bytes underflow");
        inner.active_envelopes = next_active_envelopes;
        inner.active_bytes = next_active_bytes;
        inner.ram_bytes = next_ram_bytes;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn caps() -> CapacityCaps {
        CapacityCaps {
            max_envelopes: 1_000,
            max_bytes: 1_000_000,
            ram_budget: 1_000_000,
        }
    }

    #[test]
    fn fresh_gate_snapshot_is_zeroed() {
        let gate = GlobalCapacityGate::new(caps());
        let snap = gate.snapshot();
        assert_eq!(snap.active_envelopes, 0);
        assert_eq!(snap.active_bytes, 0);
        assert_eq!(snap.tombstone_records, 0);
        assert_eq!(snap.tombstone_bytes, 0);
        assert_eq!(snap.ram_bytes, 0);
    }

    #[test]
    fn reserve_commit_increments_counters() {
        let gate = GlobalCapacityGate::new(caps());
        let reservation = gate.reserve_send(1024, 2048).expect("reserve OK");
        reservation.commit();
        let snap = gate.snapshot();
        assert_eq!(snap.active_envelopes, 1);
        assert_eq!(snap.active_bytes, 1024);
        assert_eq!(snap.ram_bytes, 2048);
    }

    #[test]
    fn reserve_dropped_without_commit_rolls_back() {
        let gate = GlobalCapacityGate::new(caps());
        {
            let _reservation = gate.reserve_send(512, 1024).expect("reserve OK");
            let mid = gate.snapshot();
            assert_eq!(mid.active_envelopes, 1);
            // Drop at end of scope without commit — should roll back.
        }
        let after = gate.snapshot();
        assert_eq!(after.active_envelopes, 0);
        assert_eq!(after.active_bytes, 0);
        assert_eq!(after.ram_bytes, 0);
    }

    #[test]
    fn reserve_refuses_when_envelope_cap_exceeded() {
        let gate = GlobalCapacityGate::new(CapacityCaps {
            max_envelopes: 2,
            max_bytes: 1_000_000,
            ram_budget: 1_000_000,
        });
        gate.reserve_send(1, 1).expect("first OK").commit();
        gate.reserve_send(1, 1).expect("second OK").commit();
        let third = gate.reserve_send(1, 1).expect_err("third rejected");
        assert!(matches!(
            third,
            CapacityError::EnvelopeCapExceeded {
                observed: 3,
                cap: 2
            }
        ));
    }

    #[test]
    fn reserve_refuses_when_disk_cap_exceeded() {
        let gate = GlobalCapacityGate::new(CapacityCaps {
            max_envelopes: 100,
            max_bytes: 1024,
            ram_budget: 1_000_000,
        });
        gate.reserve_send(1024, 1).expect("first OK").commit();
        let second = gate.reserve_send(1, 1).expect_err("second rejected");
        assert!(matches!(
            second,
            CapacityError::DiskCapExceeded {
                observed: 1025,
                cap: 1024
            }
        ));
    }

    #[test]
    fn reserve_refuses_when_ram_cap_exceeded() {
        let gate = GlobalCapacityGate::new(CapacityCaps {
            max_envelopes: 100,
            max_bytes: 1_000_000,
            ram_budget: 100,
        });
        gate.reserve_send(1, 100).expect("first OK").commit();
        let second = gate.reserve_send(1, 1).expect_err("second rejected");
        assert!(matches!(
            second,
            CapacityError::RamCapExceeded {
                observed: 101,
                cap: 100
            }
        ));
    }

    #[test]
    fn transition_queued_to_tombstone_shifts_counters_with_correct_footprints() {
        // Round-1 P0 #1: the pre-amendment API took ONE disk_bytes for
        // both old-release and new-add, which mis-accounted the
        // Queued(200 B) → AckedTombstone(32 B) shift. The correct
        // semantics (verified here) release the ORIGINAL 200 B from
        // active_bytes and add the NEW 32 B to tombstone_bytes.
        let gate = GlobalCapacityGate::new(caps());
        gate.reserve_send(200, 300).expect("reserve OK").commit();
        gate.transition(
            Some(RecordFootprint {
                kind: RecordKind::Queued,
                disk_bytes: 200,
                ram_bytes: 300,
            }),
            Some(RecordFootprint {
                kind: RecordKind::AckedTombstone,
                disk_bytes: 32,
                ram_bytes: 50,
            }),
        )
        .expect("transition OK");
        let snap = gate.snapshot();
        assert_eq!(snap.active_envelopes, 0);
        // Full 200 B released — not 200 − 32 = 168 as the broken
        // pre-amendment API produced.
        assert_eq!(snap.active_bytes, 0);
        assert_eq!(snap.tombstone_records, 1);
        assert_eq!(snap.tombstone_bytes, 32);
        // RAM applied as delta: 300 (reserve) − 300 (release) + 50 (add) = 50.
        assert_eq!(snap.ram_bytes, 50);
    }

    #[test]
    fn transition_tombstone_to_none_releases_tombstone_bytes() {
        let gate = GlobalCapacityGate::new(caps());
        // Seed a tombstone directly.
        gate.transition(
            None,
            Some(RecordFootprint {
                kind: RecordKind::AckedTombstone,
                disk_bytes: 64,
                ram_bytes: 40,
            }),
        )
        .expect("seed OK");
        assert_eq!(gate.snapshot().tombstone_records, 1);
        // Sweep it.
        gate.transition(
            Some(RecordFootprint {
                kind: RecordKind::AckedTombstone,
                disk_bytes: 64,
                ram_bytes: 40,
            }),
            None,
        )
        .expect("sweep OK");
        let snap = gate.snapshot();
        assert_eq!(snap.tombstone_records, 0);
        assert_eq!(snap.tombstone_bytes, 0);
        assert_eq!(snap.ram_bytes, 0);
    }

    #[test]
    fn transition_underflow_returns_error_and_leaves_ledger_untouched() {
        // Round-1 P1 #2: try to release something not there and
        // confirm (a) an error surfaces, (b) the ledger is
        // completely unchanged (atomic-or-nothing).
        let gate = GlobalCapacityGate::new(caps());
        gate.reserve_send(100, 50).expect("seed OK").commit();
        let before = gate.snapshot();
        // Try to release 1000 B from a 100 B active pool.
        let err = gate
            .transition(
                Some(RecordFootprint {
                    kind: RecordKind::Queued,
                    disk_bytes: 1000,
                    ram_bytes: 0,
                }),
                None,
            )
            .expect_err("underflow must surface");
        assert_eq!(err, CapacityError::ArithmeticUnderflow);
        // Atomicity: counters unchanged.
        let after = gate.snapshot();
        assert_eq!(after.active_envelopes, before.active_envelopes);
        assert_eq!(after.active_bytes, before.active_bytes);
        assert_eq!(after.tombstone_records, before.tombstone_records);
        assert_eq!(after.tombstone_bytes, before.tombstone_bytes);
        assert_eq!(after.ram_bytes, before.ram_bytes);
    }

    #[test]
    fn transition_ram_underflow_returns_error_and_leaves_ledger_untouched() {
        let gate = GlobalCapacityGate::new(caps());
        gate.reserve_send(10, 20).expect("seed OK").commit();
        let before = gate.snapshot();
        let err = gate
            .transition(
                Some(RecordFootprint {
                    kind: RecordKind::Queued,
                    disk_bytes: 10,
                    ram_bytes: 999, // > current ram_bytes=20
                }),
                None,
            )
            .expect_err("ram underflow must surface");
        assert_eq!(err, CapacityError::ArithmeticUnderflow);
        let after = gate.snapshot();
        assert_eq!(after, before);
    }

    #[test]
    fn transition_release_and_add_are_delta_based_for_ram_concurrency() {
        // Round-1 P0 #1 tail: ensure ram_bytes is delta-based, not
        // absolute. Seed two records; ack one; expect the other's
        // ram footprint intact.
        let gate = GlobalCapacityGate::new(caps());
        gate.reserve_send(50, 100).expect("send-A").commit();
        gate.reserve_send(50, 200).expect("send-B").commit();
        // Ack A only.
        gate.transition(
            Some(RecordFootprint {
                kind: RecordKind::Queued,
                disk_bytes: 50,
                ram_bytes: 100,
            }),
            Some(RecordFootprint {
                kind: RecordKind::AckedTombstone,
                disk_bytes: 8,
                ram_bytes: 12,
            }),
        )
        .expect("ack-A OK");
        let snap = gate.snapshot();
        // A: released 100 RAM + 50 disk; added 12 RAM tombstone + 8 disk tombstone.
        // B still holds 200 RAM + 50 disk untouched.
        // Total RAM: 100+200 − 100 + 12 = 212. Absolute-assignment
        // (broken pre-amendment) would have overwritten to 12.
        assert_eq!(snap.ram_bytes, 212);
        assert_eq!(snap.active_envelopes, 1);
        assert_eq!(snap.active_bytes, 50);
        assert_eq!(snap.tombstone_records, 1);
        assert_eq!(snap.tombstone_bytes, 8);
    }

    #[test]
    fn transition_none_none_is_noop() {
        let gate = GlobalCapacityGate::new(caps());
        gate.reserve_send(30, 40).expect("seed OK").commit();
        let before = gate.snapshot();
        gate.transition(None, None).expect("no-op OK");
        assert_eq!(gate.snapshot(), before);
    }

    #[test]
    #[should_panic(expected = "CapacityReservation::drop")]
    fn dropping_reservation_after_external_underflow_panics() {
        // Round-1 P1 #2: fail-loud rollback. Force the gate into a
        // state where a rollback would underflow (external mutation
        // draining counters) and confirm the Drop panics — no
        // silent saturation.
        let gate = GlobalCapacityGate::new(caps());
        let reservation = gate.reserve_send(100, 50).expect("reserve OK");
        // External mutation: zero the counters out of band, then
        // drop the uncommitted reservation. The rollback tries to
        // subtract 100 from active_bytes=0 → panic.
        {
            let mut inner = gate.inner.lock();
            inner.active_envelopes = 0;
            inner.active_bytes = 0;
            inner.ram_bytes = 0;
        }
        drop(reservation); // panics here
    }

    #[test]
    fn caps_accessor_returns_configured_values() {
        let gate = GlobalCapacityGate::new(CapacityCaps {
            max_envelopes: 42,
            max_bytes: 42_000,
            ram_budget: 42_000_000,
        });
        let caps = gate.caps();
        assert_eq!(caps.max_envelopes, 42);
        assert_eq!(caps.max_bytes, 42_000);
        assert_eq!(caps.ram_budget, 42_000_000);
    }
}
