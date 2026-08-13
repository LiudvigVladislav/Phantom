// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

sealed class Screen {
    object Onboarding : Screen()
    /**
     * Alpha 1 → Alpha 2 cryptographic protocol upgrade. Shown on launch
     * when MigrationManager.needsMigration() returns true (i.e. the
     * IdentityRecord lacks the Ed25519 signing keypair). After the user
     * taps Continue and migration succeeds, the app navigates onto
     * [ChatList]. C-followup-2.
     */
    object Migration : Screen()
    object ChatList : Screen()
    /**
     * C6-a round-8 REDLINE §P0 pin — retryable startup error
     * shown when `decideStartupRoute` returns
     * `StartupRouteDecision.TransientStartupFailure`. Dedicated
     * screen (NOT reused Screen.Onboarding) so a transient
     * failure NEVER touches the finalize holder / Terms /
     * durable identity_repair_required marker. Carries the
     * underlying transient reason (name only) so the UI can
     * surface a stable copy without leaking a Throwable message.
     * A downstream commit may add per-reason recovery hints.
     */
    data class StartupError(val reasonName: String) : Screen()
    object Calls : Screen()
    object Nearby : Screen()
    object Premium : Screen()
    object Settings : Screen()
    /**
     * ADR-020 Phase 3 + Settings rewrite: dedicated detail screen for the
     * Privacy Mode selector. Reached from the Privacy & Security row in
     * Settings (chevron). Hosts the existing pill picker + Ghost confirm.
     */
    object PrivacyModeDetail : Screen()
    object MessageRequests : Screen()
    object Profile : Screen()
    object AddContact : Screen()
    object QrScan : Screen()
    object SavedMessages : Screen()
    object Archive : Screen()
    object CreateGroup : Screen()
    object CreateChannel : Screen()
    data class Chat(val conversationId: String, val theirUsername: String) : Screen()
    data class ContactProfile(val conversationId: String, val theirUsername: String) : Screen()
    /**
     * Full-screen identity verification flow per FULL_COMPOSE Verification.
     * Carries Compare → Verified / Mismatch states internally; exits back
     * to ContactProfile when the user taps the trailing close-arrow.
     */
    data class Verify(val conversationId: String, val theirUsername: String) : Screen()
    data class GroupChat(val groupId: String, val groupName: String, val isChannel: Boolean) : Screen()
    data class ActiveCall(val conversationId: String, val username: String) : Screen()
    data class IncomingCall(val conversationId: String, val username: String) : Screen()
}
