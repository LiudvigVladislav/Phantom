# Pricing preview copy extraction

Baseline: `a9defeb000415f44a77d4379915995555daecd42`.
Scope: onboarding pricing and the Settings Premium route, including labels,
feature lists, notices, dismissal actions, and the onboarding CTA toast.

## Changes

I extracted the remaining pricing text into Android string resources. Both
surfaces reference the same keys for shared plan names, prices, features, and
the Ghost explanation. Tier selection continues to use an enum. The pricing
sheet resolves its immutable resource IDs during composition; it does not
cache localized text in global data or use it as a persistence key.

I replaced Premium's unsupported complete-invisibility/receive-only promise
with the existing onboarding explanation of Tor routing without silent WSS
or REALITY fallback. The additional controls remain explicitly future work.
This does not change routing or unlock Ghost without a verified Pro entitlement.

I replaced the duplicated onboarding toast (for example, "Plus coming soon"
followed by another "coming soon") with the same subscription-unavailable
notice used by Premium. The sheet still closes through its existing callback;
no checkout, payment, or entitlement action was added.

I added an accessible Back label in Premium and removed decorative checkmarks
from its accessibility tree. The existing three pricing-dismiss actions use
the same resource-backed label. English prices, sheet layout, and planned
feature lists remain unchanged. Display prices remain preliminary USD strings,
not localized checkout quotes or a currency conversion.

## Scoped Literal Inventory

| Source | Remaining code string literals | Classification |
| --- | --- | --- |
| `OnboardingPricingSheetTierData.kt` | None | Resource IDs for each display value, including features. |
| `OnboardingPricingSheetTierCard.kt` | None | Text and CTA accessibility use resolved resources. |
| `OnboardingPricingSheetPanel.kt` | None | Header, footer, notice, close action and CTA label use resources. |
| `OnboardingPricingSheetV2.kt` | None | Backdrop dismissal label uses a resource. |
| `OnboardingPricingSheetGrabStrip.kt` | None | Grab-strip dismissal label uses a resource. |
| `PremiumScreen.kt` | `✓` | Decorative glyph, explicitly removed from screen-reader semantics. |
| `OnboardingFlowV2.kt`, pricing callback only | None | Resource-backed unavailable notice; the rest of this large file is outside this literal inventory. |

Comments and KDoc are not UI literals. Plus, Pro, Business, and the three
USD display prices have explicitly non-translatable keys. Free, cadence,
feature prose, controls, and notices remain translation candidates. Font and
protocol names within descriptions retain their proper names when translated.

This inventory is bounded to the sources above. It is not the full app's
per-occurrence localization inventory. Nearby already uses resource-backed
unavailable copy; its source was not changed in this round.

## Verification

- The new Ghost-description and accessible-Back tests failed against the
  previous implementation (3 tests, 2 failures), then passed after the fix.
- All 22 selected regular tests passed: Premium (5), resource contracts (3),
  existing pricing-sheet interactions (12), native large-font CTA layout (1),
  and Nearby's unavailable state (1).
- The complete Paparazzi shelf passed 99/99 without changing any golden.
- The debug APK built successfully; it was not installed.
- A structured resource check confirms that all 591 existing resource entries
  are unchanged and all 51 new pricing keys are referenced. Six new keys are
  non-translatable plan names or USD prices; the other 45 are translatable.
  A bounded literal scan
  checks the six complete source files listed above; injecting an untranslated
  control into each file's in-memory source is rejected by the same checker.
- Scope checks exclude font binaries, snapshot settings/goldens, Terms,
  migration, legal documents, and ADR changes. `git diff --check` passed.

These are English host checks, not a Russian device/locale acceptance run.

## Remaining Boundaries

There is no `values-ru` directory or language picker yet. Terms still requires
claim review; migration copy remains locked by its ADR and recovery contract;
Create Channel still needs a truthful scope/membership explanation. These
decisions are not silently made by extracting pricing copy.

Billing, discovery, entitlement verification, and the listed planned features
are not implemented by this change. No APK installation, device-data clearing,
production change, or merge is part of this round.
