# Localization copy audit: product claims

Status: source-level audit with follow-up outcomes for five disputed surfaces,
not a complete inventory of every Android string or a legal approval. Pricing
and Nearby copy now reflect the approved preview/unavailable boundaries;
Terms and channel work still need review. Migration recovery and copy have a
narrow correction documented in `Migration_Recovery_2026-09-26.md`; device
validation and Russian translation are not implied. Published policy was not
changed by these Android passes.

## Reachability and findings

| Surface | Reachability | Measured behavior and copy conflict | Translation disposition |
| --- | --- | --- | --- |
| Pre-flow Terms | `MainActivity` enters `OnboardingScreenV2`, whose pre-flow gate shows `TermsScreenV2` before first-run onboarding. | All 25 visible English strings except the PHANTOM wordmark are resources. The in-app summary says the service has no access to contacts or activity and that IPs are not stored long-term. The linked published Terms and Privacy Policy are reachable, but the screen, published policy, and current infrastructure have not been reconciled or legally approved in this audit. Existing `legal/TERMS_OF_SERVICE_RU.md` and `legal/PRIVACY_POLICY_RU.md` are translations of the longer documents, not approval of the app summary. | Keep English. Require owner/legal/privacy review of each claim, then translate an approved version and check the link targets in both locales. |
| Onboarding pricing and Settings Premium | The Ghost segment in onboarding opens `OnboardingPricingSheetV2`; Settings opens `PremiumScreen`. | At audit time, onboarding listed Plus at `$3.99` and Premium at `$4.99`. The owner set Plus at `$4.99` and chose a preliminary-plan presentation. Both screens label prices and features as planned and explicitly say subscriptions are unavailable; paid CTAs say coming soon. The cancellation promise was removed. The remaining Premium claim of complete invisibility/receive-only behavior is now replaced with the same resource-backed Ghost routing explanation used in onboarding. All pricing text and action labels are resource-backed; tier selection still uses stable enum values, not localized names. No purchase or verified entitlement source exists. Ghost remains Pro-only and cannot be newly selected; a saved Ghost choice is not silently downgraded and cannot initialize messaging until the user chooses Standard or Private. A saved onboarding draft with Ghost cannot advance and explains why. | English extraction complete for these pricing surfaces. Preview copy is approved for this stage, not as a live purchase or a guarantee that all listed features ship with billing. Full billing, verified entitlement integration, Russian translation, and localized-layout verification remain separate work. |
| Nearby | Normal bottom-navigation destination. | The former radar, `Discoverable by others` toggle, beacon copy, and stored `nearby_discoverable` switch were visual only. The screen now shows an unavailable state and explicitly says this device is not scanning or broadcasting. The old preference can remain in existing installations but is no longer read or written here. | Translate the honest unavailable state during the full localization pass. Do not present discovery as functional until BLE / Wi-Fi Direct is implemented and tested. Do not interpret the old preference as consent to broadcast. |
| Create Channel | `MainActivity` can render `Screen.CreateChannel` and `ScreenSaver` can restore it, but a source search found no normal UI navigation to that route. Existing channel rows can still open `GroupChat`. | `CreateChannelScreen` calls `createGroup(..., members = emptyList(), isChannel = true)`. The screen promises subscribers can read and react, but it has no subscriber invitation flow and `GroupChatScreen` exposes no reaction action. Route presence is not feature completion. | Classify as not reachable through ordinary new-channel UI, but retain in audit because saved routes and existing channels can surface. Do not publish/translate the subscriber or reaction promise until those paths are verified or copy is narrowed. |
| Alpha 1 to Alpha 2 migration | Conditional startup route for a legacy identity or identity-bound IN_PROGRESS marker. | Recovery review reproduced loss of the trigger after signing-key backfill. The owner approved durable progress and the accompanying narrow ADR/copy amendment. Initialization now excludes normal messaging until completion. Current English resources omit raw exceptions, reinstall advice, the expired username promise and unverified QR re-add/read-only claims. | English extraction and recovery correction are documented separately with test boundaries. No automatic repair of ambiguous pre-fix partial installs, device rollout or Russian localization is claimed. |

## Route map

`navigation/Screen.kt` defines 23 route variants. The categories below
account for all 23 once; they describe reachability, not string coverage.

| Category | Routes | Localization state |
| --- | --- | --- |
| Ordinary UI or runtime route (19) | `Onboarding`, `ChatList`, `Calls`, `Nearby`, `Premium`, `Settings`, `PrivacyModeDetail`, `MessageRequests`, `Profile`, `AddContact`, `QrScan`, `SavedMessages`, `Archive`, `Chat`, `ContactProfile`, `Verify`, `GroupChat`, `ActiveCall`, `IncomingCall` | English extraction is recorded per screen in `Localization_Progress_2026-09-25.md`. Onboarding Terms is extracted but awaits claim review. Pricing/Premium preview and Nearby unavailable copy are resource-backed; none is a completed paid-subscription or discovery feature. `GroupChat` depends on an existing group/channel record; `IncomingCall` depends on call state. |
| Conditional startup route (2) | `Migration`, `StartupError` | Both are resource-backed. Migration has a narrowly amended ADR/recovery contract, not a completed localized/device-validated flow. |
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

1. Resolve the outstanding Terms and Create Channel findings with named approved wording or an explicit decision to keep a surface inaccessible. Pricing preview and Nearby unavailable copy are resolved for this stage, not certified as completed features. Migration has a narrow approved recovery/copy amendment with separate verification boundaries; the Terms decision must honor legal ownership.
2. Continue the complete-flow Android string inventory. A screen route, a resource key, or a source-literal count alone does not prove a flow is translated.
3. Only after approved English copy is stable, create the full Russian resource set and verify key/format parity, active-flow geometry, accessibility, notifications, and system/in-app language switching on Android 12 and 13+.

The current branch still has no `values-ru` directory or language picker and is
not ready to expose Russian.
