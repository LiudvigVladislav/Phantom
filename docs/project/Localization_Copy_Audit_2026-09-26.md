# Localization copy audit: product claims

Status: evidence and decisions needed before Russian release. This is a
source-level audit of five disputed surfaces, not a complete inventory of
every Android string or a legal approval. No product behavior or published
policy was changed in this pass.

## Reachability and findings

| Surface | Reachability | Measured behavior and copy conflict | Translation disposition |
| --- | --- | --- | --- |
| Pre-flow Terms | `MainActivity` enters `OnboardingScreenV2`, whose pre-flow gate shows `TermsScreenV2` before first-run onboarding. | All 25 visible English strings except the PHANTOM wordmark are resources. The in-app summary says the service has no access to contacts or activity and that IPs are not stored long-term. The linked published Terms and Privacy Policy are reachable, but the screen, published policy, and current infrastructure have not been reconciled or legally approved in this audit. Existing `legal/TERMS_OF_SERVICE_RU.md` and `legal/PRIVACY_POLICY_RU.md` are translations of the longer documents, not approval of the app summary. | Keep English. Require owner/legal/privacy review of each claim, then translate an approved version and check the link targets in both locales. |
| Onboarding pricing and Settings Premium | The Ghost segment in onboarding opens `OnboardingPricingSheetV2`; Settings opens `PremiumScreen`. | Onboarding lists Plus at `$3.99` in `OnboardingPricingSheetTierData.kt`; Premium lists Plus at `$4.99` in `PremiumScreen.kt`. Both list Pro at `$9.99`. Onboarding CTA closes the sheet with a `coming soon` toast; Premium CTA shows a `Coming soon` snackbar. Premium nevertheless says `Cancel any time`, and onboarding presents Ghost as an upgrade while Settings can select Ghost without a subscription check. These are presentation paths, not a working purchase flow. | Do not translate the price, subscription, feature-entitlement, or cancellation claims as settled facts. Decide whether to remove/hide offers until billing exists or publish one approved preview with consistent price and explicit unavailability. |
| Nearby | Normal bottom-navigation destination. | `NearbyScreen` reads and persists `nearby_discoverable` (default `true`), but has no scan or beacon operation; `deviceCount` is fixed at zero. The animated radar, `Discoverable by others` toggle, and beacon helper text imply live discovery. Its empty-state paragraph says mesh discovery is future work. | Block translation as a functioning discovery flow. Decide whether to remove the inert toggle/radar and show a truthful unavailable state, or implement and verify discovery first. |
| Create Channel | `MainActivity` can render `Screen.CreateChannel` and `ScreenSaver` can restore it, but a source search found no normal UI navigation to that route. Existing channel rows can still open `GroupChat`. | `CreateChannelScreen` calls `createGroup(..., members = emptyList(), isChannel = true)`. The screen promises subscribers can read and react, but it has no subscriber invitation flow and `GroupChatScreen` exposes no reaction action. Route presence is not feature completion. | Classify as not reachable through ordinary new-channel UI, but retain in audit because saved routes and existing channels can surface. Do not publish/translate the subscriber or reaction promise until those paths are verified or copy is narrowed. |
| Alpha 1 to Alpha 2 migration | `MainActivity` routes to `MigrationScreen` when `MigrationManager.needsMigration()` is true for an older identity. | UI text still promises usernames `planned July 2026`. Failure text includes exception messages and `NoIdentity` advises reinstalling. `docs/project/Alpha2_Migration.md` and the ADR-009 supplement explicitly lock this copy, so localization alone cannot silently rewrite it. | Obtain an ADR/copy decision and review recovery behavior before translating or changing the screen. No raw exception detail should be carried into a new user-facing translation. |

## Route map

`navigation/Screen.kt` defines 23 route variants. The categories below
account for all 23 once; they describe reachability, not string coverage.

| Category | Routes | Localization state |
| --- | --- | --- |
| Ordinary UI or runtime route (19) | `Onboarding`, `ChatList`, `Calls`, `Nearby`, `Premium`, `Settings`, `PrivacyModeDetail`, `MessageRequests`, `Profile`, `AddContact`, `QrScan`, `SavedMessages`, `Archive`, `Chat`, `ContactProfile`, `Verify`, `GroupChat`, `ActiveCall`, `IncomingCall` | English extraction is recorded per screen in `Localization_Progress_2026-09-25.md`. Onboarding Terms has been extracted, but onboarding Pricing, Nearby, and Premium remain blocked by the findings above. `GroupChat` depends on an existing group/channel record; `IncomingCall` depends on call state. |
| Conditional startup route (2) | `Migration`, `StartupError` | `StartupError` is resource-backed. `Migration` is copy-locked and needs an ADR/recovery review. |
| No ordinary new-route entry found (2) | `CreateGroup`, `CreateChannel` | Both are renderable by `MainActivity` and serializable by `ScreenSaver`, but no normal UI navigation to either was found in Android source. Create Group's English strings were extracted earlier; Create Channel's feature promises remain blocked. Do not infer that a route is newly reachable from its renderer or saver alone. |

This route map is not the acceptance contract's required per-occurrence
classification. It does not cover dialogs, notifications, services, embedded
states, dynamic exceptions, or every literal in the 23 renderers.

## Evidence boundaries

- Android reachability: `apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt`, `navigation/ScreenSaver.kt`, `screens/chatlist/ChatListScreen.kt`, and `screens/settings/SettingsScreen.kt`.
- UI and behavior: `screens/onboarding/v2/TermsScreenV2.kt`, `OnboardingFlowV2.kt`, `OnboardingPricingSheetTierData.kt`, `screens/premium/PremiumScreen.kt`, `screens/settings/PrivacyModeDetailScreen.kt`, `screens/nearby/NearbyScreen.kt`, `screens/channel/CreateChannelScreen.kt`, `screens/group/GroupChatScreen.kt`, and `screens/migration/MigrationScreen.kt`.
- Source copy contract: `docs/project/Alpha2_Migration.md`, `docs/adr/ADR-009-identity-prekey-separation.md`, and the four English/Russian files under `legal/`.
- Published pages checked on 2026-09-25 UTC: <https://phntm.pro/terms> and <https://phntm.pro/privacy>. Their availability and text do not prove infrastructure behavior or legal sufficiency.

## Next release gates

1. Resolve the five copy/product decisions above with named approved wording or an explicit decision to keep a surface inaccessible. The Terms and migration decisions must honor their legal/ADR ownership.
2. Continue the complete-flow Android string inventory. A screen route, a resource key, or a source-literal count alone does not prove a flow is translated.
3. Only after approved English copy is stable, create the full Russian resource set and verify key/format parity, active-flow geometry, accessibility, notifications, and system/in-app language switching on Android 12 and 13+.

The current branch still has no `values-ru` directory or language picker and is
not ready to expose Russian.
