// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.ui.theme.PhantomTokens
import phantom.core.messaging.MigrationException
import phantom.core.messaging.MigrationManager

/**
 * Alpha 1 → Alpha 2 cryptographic protocol upgrade screen.
 *
 * Shown by the launch path when [MigrationManager.needsMigration]
 * returns true on app foreground. Copy is locked in
 * `docs/project/Alpha2_Migration.md` and ADR-009 supplement; do not
 * edit text without an ADR update.
 *
 * UX states:
 *  - Idle: explainer + [Continue] + [Quit app]
 *  - Running: spinner + neutral status text, both buttons disabled
 *  - Failure: error block + [Retry] + [Quit app]
 *  - Done: [onMigrationComplete] is invoked, screen unmounts
 */
@Composable
fun MigrationScreen(
    migrationManager: MigrationManager,
    onMigrationComplete: () -> Unit,
    onQuit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MigrationUiState>(MigrationUiState.Idle) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PhantomTokens.Colors.SurfaceDeep)
            .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Security Update",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = PhantomTokens.Colors.TextPrimary,
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = "PHANTOM has upgraded its cryptographic protocol to match Signal Protocol architecture. " +
                    "Existing conversations need to be re-established with proper key exchange.",
                fontSize = 15.sp,
                color = PhantomTokens.Colors.TextPrimary,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Tap Continue to wipe local conversation state. You'll need to re-add contacts via QR code. " +
                    "Past messages will be preserved as read-only history.",
                fontSize = 15.sp,
                color = PhantomTokens.Colors.TextSecondary,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Your identity stays the same — your QR code and your existing contacts will recognize you. " +
                    "Once Phase 2 brings unique @usernames (planned July 2026), your identity will support that as well.",
                fontSize = 15.sp,
                color = PhantomTokens.Colors.TextSecondary,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(28.dp))

            // Status row — error message or progress note.
            when (val s = state) {
                is MigrationUiState.Idle -> Unit
                is MigrationUiState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = PhantomTokens.Colors.Cyan,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Generating keys and publishing to relay…",
                            fontSize = 14.sp,
                            color = PhantomTokens.Colors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                is MigrationUiState.Failure -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = PhantomTokens.Colors.Danger.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text(
                            text = s.userMessage,
                            fontSize = 13.sp,
                            color = PhantomTokens.Colors.Danger,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // Action row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onQuit,
                    enabled = state !is MigrationUiState.Running,
                ) {
                    Text("Quit app")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        state = MigrationUiState.Running
                        scope.launch {
                            val result = migrationManager.runMigration()
                            state = if (result.isSuccess) {
                                onMigrationComplete()
                                MigrationUiState.Idle
                            } else {
                                MigrationUiState.Failure(
                                    userMessage = userFacingMessage(result.exceptionOrNull()),
                                )
                            }
                        }
                    },
                    enabled = state !is MigrationUiState.Running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PhantomTokens.Colors.Cyan,
                        contentColor = PhantomTokens.Colors.SurfaceDeep,
                    ),
                ) {
                    Text(
                        text = if (state is MigrationUiState.Failure) "Retry" else "Continue",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Converts a MigrationException into a one-line user-facing message.
 * Each branch frames the failure in terms the user can act on:
 * retryable failures suggest waiting; non-retryable suggest support.
 */
private fun userFacingMessage(e: Throwable?): String = when (e) {
    is MigrationException.PublishRateLimited ->
        "The relay is busy right now. Please wait a moment and tap Retry."
    is MigrationException.PublishUnexpected ->
        "Couldn't reach the relay (server returned ${e.message?.take(80)}). Check your connection and tap Retry."
    is MigrationException.PublishBadRequest ->
        "The migration request was rejected by the relay. Please contact support — this is unexpected."
    is MigrationException.SigningKeyMismatch ->
        "This identity is already registered with different keys on the relay. " +
            "If you migrated on another device, that device's keys are still valid. " +
            "If this is unexpected, please contact support."
    is MigrationException.NoIdentity ->
        "No local identity found. Please reinstall the app to start fresh."
    null -> "Migration failed for an unknown reason. Please tap Retry."
    else -> "Migration failed: ${e.message?.take(120) ?: "unknown error"}. Please tap Retry."
}

private sealed interface MigrationUiState {
    data object Idle : MigrationUiState
    data object Running : MigrationUiState
    data class Failure(val userMessage: String) : MigrationUiState
}

