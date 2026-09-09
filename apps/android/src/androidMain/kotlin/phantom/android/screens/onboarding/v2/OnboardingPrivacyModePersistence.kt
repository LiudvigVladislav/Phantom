// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.content.Context
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TransportPreferences

/**
 * First-run privacy-mode persistence — writes the user's chosen mode
 * to BOTH storage surfaces `AppContainer.setPrivacyMode(mode)` mirrors,
 * WITHOUT the socket teardown / hint clearing that `setPrivacyMode`
 * also does. Onboarding runs before any transport has connected, so
 * nothing needs tearing down.
 *
 * Round-2 REDLINE on Commit 4 §P1-1: the pre-REDLINE shape wrote
 * only `transportPreferences.privacyMode = mode`. The legacy mirror was
 * historically read directly by `ChatScreen`; R-N1.17 replaced that with
 * a capability from `PrivacyModeCoordinator`, and this function is now
 * reached only through the coordinator's persistence adapter.
 * read-receipt gate reads a separate `phantom_prefs.privacy_mode`
 * legacy SharedPreferences key. Skipping the mirror meant a user
 * selecting Private got Private transport routing but kept sending
 * read receipts as Standard.
 *
 * Round-3 REDLINE on Commit 4 §P1-1 durable-boundary: the round-2
 * amend wrote the two keys separately — canonical via the
 * `transportPreferences` setter (which internally calls
 * `SharedPreferences.Editor.apply()` — async, no error surface) and
 * legacy via a separate `apply()`. If the process crashed between the
 * two writes, or after `apply()` was called but before the disk
 * flush, the persisted state could have identity present + mode
 * partial/missing. Fix: BOTH keys land in the same `phantom_prefs`
 * file, so we batch them into ONE `Editor.commit()` — atomic (single
 * transaction) AND synchronous (commit returns Boolean; a failure
 * throws immediately rather than fire-and-forget).
 *
 * `commit()` runs on the calling thread and does disk I/O.
 *
 * Round-4 REDLINE on Commit 4 §P1-2 correction: the pre-round-4 KDoc
 * incorrectly claimed the finalize path already ran off Main.
 * `OnboardingFlowV2` builds the controller inside
 * `rememberCoroutineScope()` which is Main-dispatched, and the
 * controller's `withContext(NonCancellable)` only changes the Job,
 * NOT the dispatcher. Callers of THIS helper MUST hop to
 * `Dispatchers.IO` themselves — `AppContainer.applyPrivacyModeFromOnboarding`
 * (the flow's actual wiring) does exactly that. This function
 * itself is a plain synchronous write so tests can call it directly
 * from a Robolectric main-thread context without extra ceremony.
 *
 * If the commit fails, we throw `PrivacyModePersistenceException`
 * so the finalize state machine flips to `Idle` with a stable
 * user-facing error message, and no identity is persisted
 * downstream.
 */
const val LEGACY_PHANTOM_PREFS_NAME: String = "phantom_prefs"

/** Legacy key read by [phantom.android.screens.chat] read-receipt gate. */
const val LEGACY_PRIVACY_MODE_KEY: String = "privacy_mode"

/**
 * Canonical key `TransportPreferencesAndroid` reads on every
 * `privacyMode` getter access. Duplicated here so the atomic write
 * below can update both keys in one editor.
 */
const val CANONICAL_TRANSPORT_PRIVACY_MODE_KEY: String = "transport.privacy_mode"

/**
 * Thrown when the underlying `SharedPreferences.commit()` returns
 * false — the disk write did not land. Fail-loud so the finalize
 * state machine can revert to Idle without any half-persisted
 * identity downstream.
 */
class PrivacyModePersistenceException(mode: PrivacyMode) :
    RuntimeException("Failed to persist onboarding privacy mode: $mode")

fun applyPrivacyModeToFirstRunStores(
    context: Context,
    transportPreferences: TransportPreferences,
    mode: PrivacyMode,
) {
    // Both keys live in the SAME `phantom_prefs` file
    // (`TransportPreferencesAndroid` documents this via its
    // `transport.*` key prefix). We batch them into ONE editor so
    // the two writes commit atomically — no window where identity
    // could persist with only one of them landed.
    val prefs = context.applicationContext
        .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
    val ok = prefs.edit()
        .putString(CANONICAL_TRANSPORT_PRIVACY_MODE_KEY, mode.name)
        .putString(LEGACY_PRIVACY_MODE_KEY, mode.name)
        .commit()
    if (!ok) {
        throw PrivacyModePersistenceException(mode)
    }
    // TransportPreferencesAndroid reads from the same prefs file
    // on every getter access, so the commit above is already
    // visible through the interface. The parameter here is kept
    // for two reasons:
    //   1. Fake `InMemoryTransportPreferences` in tests doesn't
    //      share the SharedPreferences file — this line keeps the
    //      in-memory fake in sync so tests reading through the
    //      interface see the write.
    //   2. If a future refactor moves the canonical store OFF
    //      SharedPreferences, this call is the type-safe hook.
    transportPreferences.privacyMode = mode
}
