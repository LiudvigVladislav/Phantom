# GitHub Issues — Drafts for Alpha 2 Backlog

Each section below is a self-contained issue body. Open
<https://github.com/WladislaWLE/Phantom/issues/new>, paste the title
into the title field, paste the body verbatim, set the suggested
labels, and click "Submit new issue".

These four issues form the Alpha 2 backlog the QA-v9 session uncovered
plus the design-brief follow-ups. They are the "post-Alpha-1 polish"
roadmap NLnet reviewers should see in the public tracker.

---

## Issue 1 — ToS review for the onboarding screen

**Title:** `Review and tighten Terms of Use shown during onboarding`

**Labels:** `ux`, `docs`, `priority:medium`, `alpha-2`

**Body:**

```
The current Terms of Use shown on the first onboarding screen
(`apps/android/.../OnboardingScreen.TermsScreen`) was a placeholder
written during the Alpha 1 sprint and never reviewed against the
project's actual policies. It mentions abuse reporting but does not
mention the email-routing addresses that now exist
(`security@`, `support@`, `legal@`, `abuse@`, `privacy@`, `press@phntm.pro`),
and it does not reflect the GDPR / data-handling posture documented
in `docs/threat-model/Threat_Model_v0.md`.

### Acceptance criteria
- ToS text reviewed against:
  - `docs/doctrine/Product_Doctrine.md` (zero-metadata invariants)
  - `docs/threat-model/Threat_Model_v0.md`
  - `docs/adr/ADR-004-Relay-Trust-Model.md`
- Each routing email referenced where appropriate (privacy/legal in the
  data-handling section, abuse in the misuse section, support in the
  general help line).
- Length stays at four short sections so the "scroll to read" UX from
  `OnboardingScreen.TermsScreen` (60 % scroll gate before the
  Continue button enables) keeps working.
- Final wording approved by founder before merge.

### Out of scope
- A full legal Privacy Policy page (separate issue once domain landing
  is built).
- Translation to languages other than English (deferred to Alpha 3).
```

---

## Issue 2 — Per-message status indicators (Sending / Sent / Delivered / Read)

**Title:** `Chat: render Sending → Sent → Delivered → Read indicators per Design Brief §13`

**Labels:** `ux`, `protocol`, `priority:high`, `alpha-2`

**Body:**

```
PHANTOM_Design_Brief_v2.pdf §13 ("Screen 06 — Active Chat") specifies
four distinct delivery states on outgoing bubbles:

| State     | Glyph       | Meaning                                           |
|-----------|-------------|---------------------------------------------------|
| Sending   | clock or ⌛ | Composed; not yet handed off to the relay         |
| Sent      | ✓ (1)       | Relay has stored the envelope                     |
| Delivered | ✓✓ (2)      | Recipient device acknowledged                     |
| Read      | ✓✓ cyan     | Recipient opened the conversation                 |

QA-v9 (2026-04-27) found the current rendering collapses the first
three states into a single check, and the second check only appears
when the recipient sends a reply. The wire-level signals already
exist on the relay path (`status=delivered` ack, `ack-deliver` from
recipient, `ReadReceipt` frame), so this is purely a client-side UI
+ status-projection bug.

### Acceptance criteria
- Outgoing bubbles render the correct glyph for each transition
  using the existing `MessageStatus` enum in
  `shared/core/messaging/.../MessagePayload.kt`.
- Glyphs use Lucide-style stroke matching `PhIconCheck` and
  `PhIconDoubleCheck` already present in
  `apps/android/.../ui/PhantomIcons.kt`.
- Read state uses Cyan accent `#00D4FF` (per design tokens), Sent /
  Delivered use `#A4ACBA` (Text Secondary).
- Transitions are observable in the UI within 1 s of the underlying
  state change.
- Reduced motion: glyph swap is instantaneous (no fade) so users with
  motion-sensitivity preferences are not penalised.

### Test plan
- Manual: send a message while recipient is offline → see Sending →
  Sent within 1 s of network ack.
- Manual: recipient comes online → see Delivered.
- Manual: recipient opens chat → see Read.
- Logcat tag filter `PhantomMessaging:V` should show the corresponding
  state writes.
```

---

## Issue 3 — Encrypted profile sync (Alpha 2 protocol slice)

**Title:** `Implement encrypted profile sync so contacts see first/last name and bio`

**Labels:** `protocol`, `crypto`, `feature`, `priority:medium`, `alpha-2`

**Body:**

```
PHANTOM_Design_Brief_v2.pdf §12 ("Screen 05 — Profile") and the
Settings → Profile flow present editable identity fields (first name,
last name, date of birth, city, country) plus the QR public key. The
design intent is that **contacts see the chosen subset of these fields
on the contact profile screen** — but Alpha 1 never wired the sync
path. QA-v9 confirmed the fields stay strictly local: editing on
device A produces no visible change on device B.

This is intentionally an Alpha 2-scoped feature, not an Alpha 1 bug,
because it requires:
1. A new envelope payload type (`ProfileUpdate`).
2. Rules for which fields are sealed-sender vs plain (most likely
   sealed-sender so the relay never sees them).
3. UI affordances for "what does my contact see?" — privacy-first
   default per Design Brief §12: date of birth shown as "Hidden"
   unless the user opts in.

### Acceptance criteria
- New `ProfileUpdate` envelope type defined in
  `shared/core/messaging/.../MessagePayload.kt`.
- Local edit on Profile screen triggers a `ProfileUpdate` envelope
  to every existing trusted-tier contact.
- Receiving side stores the payload encrypted at rest (existing
  SQLCipher database) and renders it on the contact's profile.
- Per-field visibility setting in the editing user's Profile (each
  field has Hidden / Contacts / Selected toggle, default Hidden for
  date of birth, default Contacts for first/last name).
- Date of birth rendered through the formatter introduced in #4
  (locale-aware date picker).
- Threat model entry written in `docs/threat-model/Threat_Model_v0.md`
  covering the case of a malicious contact extracting more than the
  user intended.

### Out of scope
- Avatar sync (separate issue once attachments land).
- Group profile sync — 1:1 only for the first cut.
```

---

## Issue 4 — Locale-aware date picker for "Date of birth"

**Title:** `Profile: replace plain-text date-of-birth field with a real date picker`

**Labels:** `ux`, `i18n`, `priority:medium`, `alpha-2`

**Body:**

```
QA-v9 (2026-04-27): the Profile edit dialog accepts the date of birth
as a free-form text field (current behaviour: `05091996` was typed and
stored verbatim). This is wrong on three counts:

1. **Format ambiguity.** Locales disagree on whether `05/09/1996` is
   May 9 (US) or 5 September (most of the world). The user must be
   able to enter the date in their familiar order without ambiguity.
2. **Validation.** The current text field accepts non-date strings
   (e.g. `abc`); the renderer then displays them verbatim.
3. **Privacy default.** Per `PHANTOM_Design_Brief_v2.pdf` §12 the
   field's default visibility is "Hidden" — the picker UI must reflect
   that default rather than landing on a publicly-visible value.

### Acceptance criteria
- Replace the text field at `apps/android/.../ProfileScreen.kt`
  date-of-birth row with `DatePickerDialog` (Material 3) wrapped in a
  PhantomTheme-styled container, OR a custom three-segment picker
  (year / month / day) consistent with Design Brief §06 component
  rules.
- Display formats supported simultaneously (user picks once, render
  honours `Locale`):
  - `dd.MM.yy` (e.g. 05.09.96)
  - `dd.MM.yyyy` (05.09.1996)
  - `dd MMMM` ("5 сентября" / "September 5") with current locale
  - `dd.MM` (05.09)
- Stored value is ISO 8601 (`1996-09-05`) regardless of display
  format — display is per-locale only.
- Default visibility for the field on a fresh install is "Hidden",
  per Design Brief §12.
- Empty / cleared state renders as `—` in `#6B7385` per Design Brief
  §12.

### Test plan
- Set device language to Russian → verify `05.09.1996` and
  `5 сентября` render correctly.
- Set device language to en-US → verify the picker offers MM/DD/YYYY
  while the storage stays ISO.
- Clear the field → verify it renders as "—" not as an empty string
  or a default date.
```

---

## Suggested label setup

If the labels above don't exist in the repo yet, create them once via
`https://github.com/WladislaWLE/Phantom/labels`:

| Label             | Colour    | Purpose                                       |
|-------------------|-----------|-----------------------------------------------|
| `priority:high`   | `#D93F0B` | Affects core UX, expected before Alpha 2 ship |
| `priority:medium` | `#FBCA04` | Polish that Alpha 2 should land               |
| `priority:low`    | `#0E8A16` | Nice to have                                  |
| `alpha-2`         | `#5319E7` | Scoped to the Alpha 2 milestone               |
| `ux`              | `#1D76DB` | Affects the UI surface                        |
| `protocol`        | `#5319E7` | Touches wire format / envelope types          |
| `crypto`          | `#B60205` | Touches Crypto module — needs two reviewers   |
| `i18n`            | `#0E8A16` | Locale / translation                          |
| `feature`         | `#A2EEEF` | New capability                                |
| `docs`            | `#0075CA` | Docs / policy / ToS                           |
