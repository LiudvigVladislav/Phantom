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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.ui.theme.PhantomTokens
import phantom.android.R
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
    MigrationContent(migrationManager::runMigration, onMigrationComplete, onQuit)
}

@Composable
internal fun MigrationContent(
    migrate: suspend () -> Result<Unit>,
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
                text = stringResource(R.string.migration_title),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = PhantomTokens.Colors.TextPrimary,
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.migration_explanation),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 15.sp,
                color = PhantomTokens.Colors.TextPrimary,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.migration_data_preservation),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 15.sp,
                color = PhantomTokens.Colors.TextSecondary,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.migration_keep_installed),
                modifier = Modifier.fillMaxWidth(),
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
                            text = stringResource(R.string.migration_running),
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
                            text = stringResource(s.failure.messageRes),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 13.sp,
                            color = PhantomTokens.Colors.Danger,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // Stack actions so large-font labels can wrap independently.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onQuit,
                    enabled = state !is MigrationUiState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.migration_quit),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                if ((state as? MigrationUiState.Failure)?.failure?.canRetry != false) {
                    Button(
                        onClick = {
                            if (state is MigrationUiState.Running) return@Button
                            state = MigrationUiState.Running
                            scope.launch {
                                val result = migrate()
                                state = if (result.isSuccess) {
                                    onMigrationComplete()
                                    MigrationUiState.Idle
                                } else {
                                    MigrationUiState.Failure(migrationFailure(result.exceptionOrNull()))
                                }
                            }
                        },
                        enabled = state !is MigrationUiState.Running,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhantomTokens.Colors.Cyan,
                            contentColor = PhantomTokens.Colors.SurfaceDeep,
                        ),
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            text = stringResource(
                                if (state is MigrationUiState.Failure) R.string.migration_retry
                                else R.string.migration_continue,
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
internal data class MigrationFailure(@param:StringRes val messageRes: Int, val canRetry: Boolean)

internal fun migrationFailure(e: Throwable?): MigrationFailure = when (e) {
    is MigrationException.PublishRateLimited ->
        MigrationFailure(R.string.migration_error_busy, true)
    is MigrationException.PublishUnexpected ->
        MigrationFailure(R.string.migration_error_connection, true)
    is MigrationException.PublishBadRequest ->
        MigrationFailure(R.string.migration_error_rejected, false)
    is MigrationException.SigningKeyMismatch ->
        MigrationFailure(R.string.migration_error_keys, false)
    is MigrationException.NoIdentity ->
        MigrationFailure(R.string.migration_error_identity, false)
    else -> MigrationFailure(R.string.migration_error_generic, true)
}

private sealed interface MigrationUiState {
    data object Idle : MigrationUiState
    data object Running : MigrationUiState
    data class Failure(val failure: MigrationFailure) : MigrationUiState
}
