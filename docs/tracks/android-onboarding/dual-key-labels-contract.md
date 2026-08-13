> **SUPERSEDED** — 2026-08-14 — this contract described an intermediate shape that was replaced by the L4 stabilization block (Finale simplification + Profile Advanced-collapsible). Retained for provenance; not authoritative for current UI. See `c6-onboarding-baseline-landing-contract.md` §7 documentation allowlist.

# Dual-key labels track — contract sheet (round 0)

**Status**: awaits architect GREEN. No production code touched.
**Base**: local HEAD after logo-flash implementation (not yet
committed at time of writing — one consolidated commit with
logo-flash + dual-key + naming-hygiene lands after this
contract passes).
**Author**: builder.
**Date**: 2026-08-10.

Fixes the P1 architect flagged on device 2026-08-10 after C6-b:
Finale shows Ed25519 while Profile shows X25519, and Profile
mislabels X25519 as `ED25519`. Users see two different strings
for "my identity" without understanding they are two different
public keys for two different purposes.

**Direction (architect)**: **Option C** now (both keys shown,
clearly labelled). **Option D** (versioned identity bundle,
Ed25519 signs binding to X25519, single canonical fingerprint)
deferred to a separate protocol track.

---

## 0. Scope

- **In scope**: UI wiring in `FinaleConfirmationStepV2.kt` and
  `ProfileScreen.kt` to display BOTH `Ed25519` (signing) and
  `X25519` (messaging) keys with exact labels; correct the
  mislabelled `ED25519` header in Profile; relabel the QR from
  "identity fingerprint" to `Connection QR`; explicitly mark
  `Key ID` as a short X25519 identifier.
- **In scope (state model impact — architect explicit)**:
  extend `FinalizeOutcome.Completed` and
  `OnboardingFinalizeState.Completed` to carry BOTH validated
  64-char public-key hex values; extend the Saver; extend the
  repair/quarantine policy to cover a missing / malformed
  X25519 hex on the identity record.
- **Out of scope**:
  - Any change to `IdentityRecord` storage schema — the
    `publicKeyHex` (X25519) and `signingPublicKeyHex`
    (Ed25519) fields already exist per ADR-009.
  - Any change to the underlying key generation.
  - Any change to the QR payload wire format — architect pin:
    QR remains exactly `username:X25519`.
  - Option D (canonical binding, versioned bundle) — deferred.
  - Backend / relay / storage / protocol touched — none.

---

## 1. Reference: the current mismatch

Source-level evidence (already documented in the earlier P0
investigation):

| Surface | Field | Type | Current label |
|---------|-------|------|--------------|
| Finale ([FinaleConfirmationStepV2.kt L91](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/FinaleConfirmationStepV2.kt#L91)) | `signingPublicKeyHex` | Ed25519 (64 hex) | "Your Ed25519 key" + "IDENTITY CREATED" |
| Profile QrKeyCard header ([ProfileScreen.kt L791](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt#L791)) | (label only, no field) | — | **"ED25519"** — MISLABELLED — the actual value under this header is X25519 |
| Profile QrKeyCard body ([ProfileScreen.kt L820](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt#L820)) | `publicKeyHex` (first 32 chars) | X25519 (32 hex prefix of 64) | "Your public identity fingerprint" |
| Profile ConnectionRow ([ProfileScreen.kt L1035](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt#L1035)) | `publicKeyHex.take(12)` | X25519 (short prefix) | "Key ID" |
| Profile QR content ([ProfileScreen.kt L196, L229, L283, L806](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt#L196)) | `"${username}:${publicKeyHex}"` | X25519 | (encoded in QR — no visible label) |

Ed25519 and X25519 are two independent 32-byte keys generated
together (per ADR-009). Their byte contents are unrelated;
first-and-last-8-chars can never match.

---

## 2. Target UI shape

### 2.1 Finale (FinaleConfirmationStepV2)

Both keys visible, each with its own labelled card. The
existing key card already renders `signingPublicKeyHex` — this
track adds a second sibling card for `publicKeyHex`.

Card 1 (existing, unchanged label wording):
- Header: `Identity signing key · Ed25519` (was: "Your Ed25519 key").
- Body: full 64-char `signingPublicKeyHex`, chunked as 8 × 8
  groups via `formatFingerprintForDisplay` (existing).
- Green status dot, "CREATED" status text (existing).
- Copy / Save backup / Regen affordances — deferred, out of C6-b
  scope; retained as-is (currently: Copy on card body).

Card 2 (NEW):
- Header: `Messaging encryption key · X25519`.
- Body: full 64-char `publicKeyHex`, chunked identically to card 1.
- Green status dot, "CREATED" status text.
- Copy button that copies the FULL 64-char X25519 hex.

Both cards live inside the existing scrollable body; CTA
"Continue" stays in the fixed-bottom band and is unchanged.

Short-form fingerprint chip below the cards is retained but
explicitly labelled `Ed25519 signing key · short form` (was:
"fingerprint · short form" — architect pin: no ambiguous
"fingerprint" language).

### 2.2 Profile (QrKeyCard + ConnectionRow)

QrKeyCard body reshape:
- Card outer header: `Connection QR` (was: "IDENTITY KEY" +
  mislabelled "ED25519").
- QR image (unchanged content payload — see §2.3).
- Below QR:
  - Row 1: `Identity signing key · Ed25519` label + full
    64-char `signingPublicKeyHex`, chunked identically to the
    Finale card.
  - Row 2: `Messaging encryption key · X25519` label + full
    64-char `publicKeyHex`, chunked identically.
- Explanatory footer copy: "Ed25519 signs your identity.
  X25519 secures the connection." (or architect-approved
  variant).
- Share button copy: `Share connection payload` (was:
  "Share my key") — matches the QR-labelled surface.

ConnectionRow (L1035):
- Label: `X25519 short ID` (was: "Key ID").
- Value: `publicKeyHex.take(12) + "…"` (unchanged mechanics).
- Adjacent tooltip / helper copy explicitly notes "short
  X25519 identifier" if the design token supports helper text
  under connection-row entries. If not, the label alone
  carries the meaning.

### 2.3 QR payload immutability (architect pin)

QR content stays exactly `"${username}:${publicKeyHex}"` — the
X25519 encryption key on which peer connection setup depends.
No Ed25519 in the QR content; no wire-format change; peers on
older Phantom builds continue to scan compatibly.

Test locks this: any refactor that changes the QR content
string fails a byte-exact assertion.

---

## 3. State model impact (architect explicit — before code)

### 3.1 FinalizeOutcome.Completed

Current:
```kotlin
sealed interface FinalizeOutcome {
    data class Completed(val signingPublicKeyHex: String) : FinalizeOutcome { init { … } }
    …
}
```

Target:
```kotlin
sealed interface FinalizeOutcome {
    data class Completed(
        val signingPublicKeyHex: String,   // Ed25519 (64 hex)
        val publicKeyHex: String,          // X25519 (64 hex)
    ) : FinalizeOutcome {
        init {
            require(isValidEd25519PublicKeyHex(signingPublicKeyHex)) { … }
            require(isValidX25519PublicKeyHex(publicKeyHex)) { … }
        }
    }
    …
}
```

Two `require` calls. Missing / malformed EITHER hex is a
`MissingKeyMaterial` outcome upstream (see §3.3).

### 3.2 OnboardingFinalizeState.Completed

Current:
```kotlin
data class Completed(val signingPublicKeyHex: String) : OnboardingFinalizeState { init { … } }
```

Target:
```kotlin
data class Completed(
    val signingPublicKeyHex: String,
    val publicKeyHex: String,
) : OnboardingFinalizeState {
    init {
        require(isValidEd25519PublicKeyHex(signingPublicKeyHex)) { … }
        require(isValidX25519PublicKeyHex(publicKeyHex)) { … }
    }
}
```

`applyFinalizeOutcome` unchanged in shape — just forwards both
fields from `FinalizeOutcome.Completed` to
`OnboardingFinalizeState.Completed`.

### 3.3 runFinalize — extract + validate BOTH hexes

Current runFinalize:
```kotlin
val hex = end.record.signingPublicKeyHex
if (hex != null && isValidEd25519PublicKeyHex(hex)) {
    FinalizeOutcome.Completed(hex)
} else {
    FinalizeOutcome.MissingKeyMaterial
}
```

Target:
```kotlin
val signHex = end.record.signingPublicKeyHex
val encHex = end.record.publicKeyHex     // NEW — always non-null per IdentityRecord
val signValid = signHex != null && isValidEd25519PublicKeyHex(signHex)
val encValid = isValidX25519PublicKeyHex(encHex)
if (signValid && encValid) {
    FinalizeOutcome.Completed(signingPublicKeyHex = signHex!!, publicKeyHex = encHex)
} else {
    FinalizeOutcome.MissingKeyMaterial
}
```

- `publicKeyHex` on `IdentityRecord` is `val publicKeyHex: String` (non-null).
  If it is empty / wrong length / non-hex → MissingKeyMaterial
  (same repair-required path). This is defensive: production
  key-gen never produces such records, but the storage /
  transport layer could theoretically deliver corrupted data.
- The pair (signValid && encValid) is atomic — either BOTH
  valid → Completed; else MissingKeyMaterial. There is no
  intermediate "signing key valid but encryption key not" state.

### 3.4 isValidX25519PublicKeyHex

New helper alongside `isValidEd25519PublicKeyHex`:
```kotlin
internal fun isValidX25519PublicKeyHex(hex: String): Boolean {
    if (hex.length != 64) return false
    return hex.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
}
```

Same implementation shape as the Ed25519 variant (both keys
are 32 bytes → 64 hex chars). Kept as a separate function
name for grep clarity + so a future divergence (canonical form
constraints etc.) can be pinned in one place.

### 3.5 Saver round-trip — both values

`OnboardingFinalizeStateSaver` currently:
```kotlin
is OnboardingFinalizeState.Completed -> listOf("Completed", state.signingPublicKeyHex)
```

Target:
```kotlin
is OnboardingFinalizeState.Completed -> listOf(
    "Completed",
    state.signingPublicKeyHex,
    state.publicKeyHex,
)
```

On restore:
```kotlin
"Completed" -> {
    val signHex = it[1] as String
    val encHex = it[2] as String
    if (!isValidEd25519PublicKeyHex(signHex)) error("… corrupt signing hex …")
    if (!isValidX25519PublicKeyHex(encHex)) error("… corrupt encryption hex …")
    OnboardingFinalizeState.Completed(signHex, encHex)
}
```

Restore rejects EITHER invalid hex with a localised failure —
same invariant as construction. Corrupted saved state produces
a clearly-diagnosable stack, not a downstream NPE.

Legacy saved states with only the 2-element `[Completed, hex]`
list from before this track ARE rejected on restore (list
index 2 throws IndexOutOfBoundsException). Migration policy:
force-clear the saved-state block on the next process launch
via a schema-version bump.

Architect asks (§3.6 below) — I need decision.

### 3.6 Saver backward compatibility — awaiting architect

**Question**: how to handle a saved state written by a
pre-dual-key build (list of 2 elements: `[Completed, sign_hex]`)
after the app updates and the user reopens onboarding?

Options:
- **A**: Reject with a clean failure (throw on restore) →
  Compose falls back to the composable's default (`NotStarted`),
  user re-does finalize. Simple; no data preserved.
- **B**: Accept legacy 2-element form, hydrate `publicKeyHex`
  by reading `IdentityRecord.publicKeyHex` from the container
  at restore time. Complex — requires container access from
  the Saver, which the Saver API does not natively support.
- **C**: Bump the state-tag from `"Completed"` to
  `"CompletedV2"`, treat any `"Completed"` legacy tag as
  restore-failure but silently (log-only) → same effect as A
  but grep-explicit.

Awaiting architect call. Recommendation: **A** — onboarding
is a one-shot flow; users mid-onboarding across an app
update is a very narrow edge case, and the "re-do finalize"
recovery is quick.

### 3.7 Signature drift in the Compose observer

`OnboardingFinalizeStateHolder.signingPublicKeyHex` getter
projection (currently returns the Ed25519 hex or null based on
whether state is `Completed(hex)`) stays as-is; a new sibling
getter `publicKeyHex: String?` returns the X25519 hex or null
via the same projection pattern. The Finale composable then
reads both:
```kotlin
FinaleConfirmationStepV2(
    signingPublicKeyHex = holder.signingPublicKeyHex,
    publicKeyHex        = holder.publicKeyHex,
    onContinueClick     = onComplete,
)
```

`FinaleConfirmationStepV2` gains a required `publicKeyHex:
String?` parameter, symmetric to the existing
`signingPublicKeyHex`. Null-branch (defensive) renders the
existing "Something went wrong" fallback.

---

## 4. Files touched (planned)

Prod (M):
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt`
  — `FinalizeOutcome.Completed` +1 field + validation;
  `runFinalize` extract + validate BOTH hexes; new
  `isValidX25519PublicKeyHex`.
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFinalizeStateHolder.kt`
  — `OnboardingFinalizeState.Completed` +1 field + validation;
  Saver +1 slot + validation on restore; new `publicKeyHex`
  getter projection.
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt`
  (Finale call-site) — pass both hexes into
  `FinaleConfirmationStepV2`.
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/FinaleConfirmationStepV2.kt`
  — accept new `publicKeyHex: String?` parameter; render
  second key card + updated labels; fingerprint chip label
  updated.
- `apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt`
  — QrKeyCard header + body reshape (BOTH keys with new
  labels); ConnectionRow label update.

Tests (M + A):
- `OnboardingV2FinalizeOutcomeContractTest` (M) — add
  Completed(both-fields) contract tests; add MissingKeyMaterial
  routing for missing / malformed X25519; existing tests for
  Ed25519 malformed keep firing.
- `OnboardingV2StateTest` (M) — `isValidX25519PublicKeyHex`
  pure tests.
- **NEW** `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/FinaleDualKeyLabelsTest.kt`
  — behavioural Compose UI tests pinning both cards render
  with exact labels + full 64-char hex + Copy button per card.
- **NEW** `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/profile/ProfileQrKeyCardDualKeyLabelsTest.kt`
  — behavioural Compose UI tests pinning:
    1. Card header reads `Connection QR` (NOT `ED25519`).
    2. Both key rows present with exact labels.
    3. Ed25519 row displays `signingPublicKeyHex`.
    4. X25519 row displays `publicKeyHex`.
    5. QR content payload equals exactly
       `"${username}:${publicKeyHex}"` (byte-exact).
    6. Share dialog / copy actions use the QR payload
       string, NOT some other synthesized string.
    7. ConnectionRow labelled `X25519 short ID`.

Docs (M):
- This file (`dual-key-labels-contract.md`).
- `logo-flash-fix-contract.md` § "next track" pointer updated.

Deliberately UNCHANGED:
- `IdentityRecord` (`shared/core/identity/src/commonMain/kotlin/phantom/core/identity/IdentityKey.kt`)
  — both fields already exist; no schema change.
- Storage adapters, protocol serializers, relay code — none.
- Alpha-1 identity migration path (still filters on Ed25519
  `signingPublicKeyHex == null` → migration).
- Any QR scanning code / peer-side parsing — payload unchanged.

---

## 5. Structural guarantees

1. **QR payload byte-exact stability**. Pinned by the
   `ProfileQrKeyCardDualKeyLabelsTest` behavioural test
   fetching the composable's `identityString` capture (via a
   test-only sink) and asserting exact `"${username}:${X25519}"`
   equality. A regression that mixes Ed25519 into the QR
   content fails-red.
2. **Label-to-field correspondence**. Both new tests assert
   the exact label string sits above the exact field content
   (`Ed25519` label → `signingPublicKeyHex` value; `X25519`
   label → `publicKeyHex` value). No label swap possible
   without failing.
3. **Missing / malformed X25519 → repair path**. Pure JVM
   contract test walks `runFinalize` with a record whose
   `publicKeyHex` is empty / short / non-hex → asserts outcome
   is `MissingKeyMaterial` → holder lands on
   `MissingKeyRepairRequired`. Same repair screen as the
   Ed25519 missing path.
4. **Saver rejects malformed on restore**. Pure JVM test walks
   the Saver's restore lambda with corrupt payloads (short hex,
   non-hex, missing element) → asserts exception.
5. **No backend / storage / protocol touched**. Enforced by
   scope (no touch to `shared/core/*` in this track).

---

## 6. Test matrix (planned)

| # | Contract requirement | Test file | Method | Type |
|---|----------------------|-----------|--------|------|
| 6.1 | isValidX25519PublicKeyHex — 64-char [0-9a-fA-F] accepts | `OnboardingV2StateTest` | `isValidX25519PublicKeyHex_accepts_64_char_lower_and_upper_hex` | Pure JVM |
| 6.2 | isValidX25519PublicKeyHex — rejects empty / wrong length / non-hex | ″ | `isValidX25519PublicKeyHex_rejects_empty_and_wrong_length_and_non_hex` | Pure JVM |
| 6.3 | Completed carries BOTH hex fields; init rejects malformed either side | `OnboardingV2FinalizeOutcomeContractTest` | `finalize_outcome_completed_carries_both_signing_and_encryption_hexes` + `_rejects_malformed_encryption_hex` | Pure JVM |
| 6.4 | runFinalize with valid IdentityRecord (both keys) → Completed(sign, enc) | ″ | `runFinalize_valid_record_produces_completed_with_both_hexes` | Pure JVM |
| 6.5 | runFinalize with missing / malformed X25519 → MissingKeyMaterial | ″ | `runFinalize_missing_or_malformed_encryption_hex_routes_to_MissingKeyMaterial` | Pure JVM |
| 6.6 | Saver round-trip preserves both hexes | ″ | `finalize_state_saver_round_trip_preserves_both_signing_and_encryption_hexes` | Pure JVM |
| 6.7 | Saver rejects legacy 2-element / corrupt payloads | ″ | `finalize_state_saver_restore_rejects_legacy_and_corrupt_payloads` | Pure JVM |
| 6.8 | Finale renders both cards with exact labels + full hexes + per-card Copy | `FinaleDualKeyLabelsTest` | `finale_renders_both_key_cards_with_exact_labels_and_full_hexes` | Compose UI |
| 6.9 | Finale Copy on Ed25519 card copies signing hex; Copy on X25519 card copies encryption hex | ″ | `finale_copy_actions_copy_full_hex_of_correct_key` | Compose UI |
| 6.10 | Finale fingerprint chip labelled `Ed25519 signing key · short form` | ″ | `finale_short_form_chip_labelled_ed25519_signing_key` | Compose UI |
| 6.11 | Profile QrKeyCard header reads `Connection QR` and NOT `ED25519` | `ProfileQrKeyCardDualKeyLabelsTest` | `profile_qr_card_header_reads_connection_qr_not_ed25519` | Compose UI |
| 6.12 | Profile shows both key rows with exact labels + full hexes | ″ | `profile_qr_card_shows_both_key_rows_with_exact_labels_and_full_hexes` | Compose UI |
| 6.13 | QR payload equals exactly `"${username}:${publicKeyHex}"` (byte-exact) | ″ | `profile_qr_payload_equals_username_colon_x25519_exactly` | Compose UI (payload capture) |
| 6.14 | Profile ConnectionRow label reads `X25519 short ID` | ″ | `profile_connection_row_labelled_x25519_short_id` | Compose UI |

Total: **14 focused tests** (7 pure JVM + 7 Compose UI). No
Paparazzi (visual change is a re-layout, not a motion — a
Paparazzi golden would just re-record the two label strings).

If architect wants a Paparazzi golden of the new Finale layout
+ new Profile QrKeyCard layout, that's 2 additional goldens
recorded as part of the consolidated device-pass block.

---

## 7. Repair / quarantine policy for missing X25519 (§3.3 elaboration)

Production reality: `IdentityRecord.publicKeyHex` is a
non-null `String` field. Legit production key-gen (Ed25519
+ X25519 keypair generation via `IdentityManager.createOrLoad`)
ALWAYS populates a valid 64-char hex. The only paths that
could yield a corrupt X25519 hex on the record are:

- A backing-store corruption (SQLite row rewrite, file
  tampering).
- A future Alpha-N migration path that partially updates the
  record.

For both, the fail-safe treatment matches the existing Ed25519
"missing / malformed → MissingKeyMaterial → repair screen"
path. No new UI, no new state — the existing repair-required
screen has copy general enough to cover "either key is missing".

If architect wants the repair screen copy updated to
distinguish the two failure modes (e.g. "signing key
missing" vs "encryption key missing"), that's an
additional bullet — flag now.

---

## 8. Scope-lock reminders (from architect)

- Option C now / Option D later.
- No backend / storage / protocol changes.
- QR payload immutable (byte-exact).
- Restore/backup track (C6-existing-account) depends on this
  landing first, because it needs to know the canonical pair
  shape.
- Contract sheet BEFORE code (this file — awaits GREEN).
- Focused tests only during dev. Paparazzi + APK + device pass
  combined with the consolidated block after all three tracks
  (logo-flash + C6-b cleanup + dual-key) land together.

---

## 9. Awaiting from architect

1. GREEN on the state-model expansion in §3 (both hex fields
   carried through outcome + state + Saver).
2. GREEN on §3.6 Saver backward-compatibility policy —
   builder recommends option A (reject, force re-finalize).
3. GREEN on the UI shape in §2 (labels, layouts, QR relabel).
4. GREEN on the 14-test matrix in §6, or an extension /
   trim / restructure.
5. (Optional) Decision on §7: distinguish signing-vs-encryption
   missing-key copy in the repair screen — YES / NO / already
   fine.

On GREEN: implement the state-model expansion first (Pure JVM
tests come with it), then Finale UI, then Profile UI. Each
sub-batch compiles + focused-tests passes before the next.
No handoff bundle until the consolidated device-pass block.
