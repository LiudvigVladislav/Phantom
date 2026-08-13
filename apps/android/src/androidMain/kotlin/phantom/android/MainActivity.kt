// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import phantom.android.notifications.PhantomNotificationManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.service.PhantomMessagingService
import phantom.android.screens.splash.PhantomSplashScreen
import androidx.compose.runtime.saveable.rememberSaveable
import phantom.android.navigation.Screen
import phantom.android.navigation.ScreenSaver
import phantom.android.navigation.resolveScreenAfterStartup
import phantom.android.qr.QrScanScreen
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import phantom.android.screens.calls.ActiveCallScreen
import phantom.android.screens.calls.CallsScreen
import phantom.android.screens.calls.IncomingCallScreen
import phantom.android.screens.chat.ChatScreen
import phantom.android.screens.chatlist.ChatListScreen
import phantom.android.screens.contact.ContactProfileScreen
import phantom.android.screens.lock.AppLockScreen
import phantom.android.screens.migration.MigrationScreen
import phantom.android.screens.onboarding.OnboardingScreen
import phantom.android.screens.profile.ProfileScreen
import phantom.android.screens.requests.MessageRequestsScreen
import phantom.android.screens.saved.SavedMessagesScreen
import phantom.android.screens.archive.ArchiveScreen
import phantom.android.screens.channel.CreateChannelScreen
import phantom.android.screens.group.CreateGroupScreen
import phantom.android.screens.group.GroupChatScreen
import phantom.android.screens.settings.SettingsScreen
import phantom.android.ui.theme.*

class MainActivity : ComponentActivity() {

    /**
     * Parses a `phantom://invite/{base64url(username:pubkeyHex)}` URI from an incoming Intent.
     * Returns the decoded payload as-is (`"username:pubkeyHex"`) — the same format the QR
     * scanner produces — so it can be fed directly into [scannedQrValue] / [AddContactDialog].
     */
    private fun parseInviteIntent(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "phantom" || uri.host != "invite") return null
        val encoded = uri.lastPathSegment ?: return null
        return try {
            val decoded = String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8)
            // Require at least one colon separating username from pubkey
            if (decoded.indexOf(':') < 0) null else decoded
        } catch (e: Exception) {
            Log.w("PHANTOM", "parseInviteIntent: malformed payload — ${e.message}")
            null
        }
    }

    // Mutable state hoisted to Activity level so onNewIntent can update Compose state.
    private val pendingInviteQr = androidx.compose.runtime.mutableStateOf<String?>(null)

    // App Lock — hoisted to Activity so onResume can trigger re-lock after background timeout.
    // Initialised to false; set to true in onCreate when the pref is enabled.
    private val isLockedState = androidx.compose.runtime.mutableStateOf(false)
    private var backgroundedAt: Long = 0

    override fun onPause() {
        super.onPause()
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // PR-RECV-DIAG1 v1.1 — re-request the foreground service on every
        // onResume. On aggressive OEM kills (HiOS / MIUI / OneUI) the
        // service can be reaped between onCreate and the user returning
        // to the app, so a defensive `startForegroundService` here is
        // idempotent (Android merges concurrent intents) and ensures we
        // recover. Service-side `RECV_DIAG service_onStartCommand` will
        // show whether the call actually woke the service.
        Log.i(
            "PhantomMessaging",
            "RECV_DIAG mainActivity_onResume service_start_request source=onResume",
        )
        try {
            startForegroundService(Intent(this, PhantomMessagingService::class.java))
            Log.i(
                "PhantomMessaging",
                "RECV_DIAG mainActivity_service_start_ok source=onResume",
            )
        } catch (t: Throwable) {
            Log.e(
                "PhantomMessaging",
                "RECV_DIAG mainActivity_service_start_fail source=onResume " +
                    "err=${t::class.simpleName} msg=${t.message}",
            )
        }
        val prefs = getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("app_lock_enabled", false)) {
            // User-configurable threshold — see Settings → App Lock → Auto-lock.
            // 0L means "lock immediately whenever the app is backgrounded";
            // any positive value is a grace window in milliseconds.
            val timeoutMs = prefs.getLong("app_lock_timeout_ms", 60_000L)
            val elapsed = System.currentTimeMillis() - backgroundedAt
            if (backgroundedAt > 0 && (timeoutMs == 0L || elapsed > timeoutMs)) {
                isLockedState.value = true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseInviteIntent(intent)?.let { payload ->
            pendingInviteQr.value = payload
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("PHANTOM_INIT", "MainActivity onCreate")
        // PR-RECV-DIAG1 v1.1 — mirror MainActivity lifecycle into the
        // PhantomMessaging tag so Test #84 can prove (a) MainActivity
        // is actually running on Tecno (where we suspect the OS is
        // killing the service silently) and (b) startForegroundService
        // returned without throwing. The corresponding service_onCreate
        // log lives in PhantomMessagingService.
        Log.i(
            "PhantomMessaging",
            "RECV_DIAG mainActivity_onCreate pid=${android.os.Process.myPid()}",
        )
        // Block screenshots, screen recording, and the recents-thumbnail preview.
        // FLAG_SECURE on the only Activity in the app is sufficient — there are
        // no other Activity classes (one ComponentActivity, all screens are
        // Compose). Windows that don't belong to MainActivity (system dialogs,
        // BiometricPrompt, IME, OS notifications) are governed by the OS, not us.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Start the foreground service that owns the WebSocket connection lifetime.
        // The service awaits app.ready internally, so it is safe to launch before init completes.
        Log.i(
            "PhantomMessaging",
            "RECV_DIAG mainActivity_service_start_request source=onCreate",
        )
        try {
            startForegroundService(Intent(this, PhantomMessagingService::class.java))
            Log.i(
                "PhantomMessaging",
                "RECV_DIAG mainActivity_service_start_ok source=onCreate",
            )
        } catch (t: Throwable) {
            Log.e(
                "PhantomMessaging",
                "RECV_DIAG mainActivity_service_start_fail source=onCreate " +
                    "err=${t::class.simpleName} msg=${t.message}",
            )
        }
        val app = application as PhantomApplication
        // Read notification extras once — intent is immutable after activity creation.
        val notifConversationId = intent.getStringExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID)
        val notifSenderName     = intent.getStringExtra(PhantomNotificationManager.EXTRA_THEIR_USERNAME)
        // Parse invite deep link from cold-start intent (warm-start handled by onNewIntent).
        parseInviteIntent(intent)?.let { payload ->
            pendingInviteQr.value = payload
        }
        // Edge-to-edge configuration.
        //
        // API 35+: the system enforces edge-to-edge automatically; calling
        // enableEdgeToEdge() ourselves there corrupts the EGL surface setup
        // (observed: GFXSTREAM / Unknown dataspace 0).
        //
        // API 26–34: we have to opt in. We previously tried just
        // `WindowCompat.setDecorFitsSystemWindows(window, false)` — that drew
        // under the bars but left IME inset reporting unreliable on some OEM
        // builds (Tecno HiOS / Android 12 in QA-v9: `Modifier.imePadding()`
        // resolved to 0 with the keyboard open, so the chat input slid under
        // the keyboard). enableEdgeToEdge() is the AndroidX-managed wrapper
        // that combines decor-fits-system-windows + the OnApplyWindowInsets
        // listener Compose needs for `WindowInsets.ime` and
        // `WindowInsets.navigationBars` to be populated.
        if (Build.VERSION.SDK_INT < 35) {
            enableEdgeToEdge()
        }

        // Initialise lock state from prefs before Compose renders its first frame.
        val prefs = getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("app_lock_enabled", false)) {
            isLockedState.value = true
        }

        setContent {
            PhantomTheme {
                // Observe the activity-level lock flag inside Compose.
                val isLocked by isLockedState

                if (isLocked) {
                    AppLockScreen(onUnlocked = { isLockedState.value = false })
                } else {
                    var container by remember { mutableStateOf<AppContainer?>(null) }
                    var initError by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        Log.d("PHANTOM_INIT", "MainActivity: awaiting ready…")
                        runCatching { app.ready.await() }
                            .onSuccess {
                                Log.d("PHANTOM_INIT", "MainActivity: ready — rendering app")
                                container = app.container
                            }
                            .onFailure { t ->
                                Log.e("PHANTOM_INIT", "MainActivity: init failed: ${t.message}", t)
                                initError = when (t) {
                                    is SecurityException ->
                                        "Encryption keys could not be unlocked.\nTry restarting the app."
                                    is android.database.sqlite.SQLiteException ->
                                        "Database error. Please reinstall the app."
                                    else ->
                                        "Startup failed. Please restart."
                                }
                            }
                    }

                    val c = container
                    when {
                        c != null -> PhantomApp(
                            container              = c,
                            notifConversationId    = notifConversationId,
                            notifSenderName        = notifSenderName,
                            pendingInviteQr        = pendingInviteQr,
                        )

                        initError != null -> Box(
                            modifier = Modifier.fillMaxSize().background(BgDeep).padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Startup error:\n\n$initError",
                                color = Danger,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                            )
                        }

                        else -> PhantomSplashScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun PhantomApp(
    container: AppContainer,
    notifConversationId: String? = null,
    notifSenderName: String? = null,
    pendingInviteQr: androidx.compose.runtime.MutableState<String?> = androidx.compose.runtime.mutableStateOf(null),
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // C6-a round-8 REDLINE §P0/§P1 pin: separate presentation
    // slots. Repair (proven corruption) still uses in-memory
    // override to force MissingKeyRepairRequired even when the
    // marker write failed. Transient (retryable operational
    // failure) uses its OWN screen — Screen.StartupError — so it
    // NEVER touches the Terms gate / finalize holder / durable
    // marker.
    var quarantineForcedInMemory by remember { mutableStateOf(false) }
    // Round-8 §P0 pin: single-flight retry counter. LaunchedEffect
    // keys on this value; each Retry tap increments it and
    // triggers a fresh startup run. A prior in-flight run is
    // cancelled by LaunchedEffect's structured concurrency —
    // CancellationException propagates up (guaranteed by all four
    // decideStartupRoute suspend catches) and the old coroutine
    // does not mutate presentation.
    var retryTick by remember { mutableStateOf(0) }
    // Round-8 §P0 pin: single-flight guard read by the Retry
    // callback. Prevents double-tap from launching a second
    // concurrent startup run. Toggled true when a run begins,
    // false when it completes.
    var startupInFlight by remember { mutableStateOf(false) }
    val startupContext = androidx.compose.ui.platform.LocalContext.current

    // currentScreen starts as null so the first frame after readiness
    // shows the splash, NOT a brief flash of Onboarding (the prior
    // default that surfaced as "ToS shown on every restart" in 2026-04-30
    // testing — bug F). The when-block below treats null → splash.
    //
    // Onboarding-stabilization block 2026-08-11: `rememberSaveable` with
    // a custom Screen saver keeps the current route across Activity
    // recreation (rotation, split-screen, dark-mode toggle). Plain
    // `remember` reset it to `null` on recreation → the startup
    // LaunchedEffect below re-derived `Screen.ChatList` from disk and
    // dropped the user out of Profile / Settings / any detail screen
    // back to Chats on every rotation.
    //
    // Declared BEFORE the startup LaunchedEffect so the coroutine can
    // read it at completion time — that read is the load-bearing
    // defect fix for the Final Stabilization Mini-Block 2026-08-11
    // (P1). See [resolveScreenAfterStartup] KDoc for the precedence
    // rules; the resolver's contract tests live at
    // `phantom.android.navigation.StartupRouteResolverTest`.
    var currentScreen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen?>(null)
    }
    // scannedQrValue carries both QR-scanner results and decoded invite deep links —
    // both resolve to the same "username:pubkeyHex" format consumed by AddContactDialog.
    var scannedQrValue by remember { mutableStateOf<String?>(null) }

    // Drain any invite deep link that arrived before Compose was ready (cold-start)
    // or while the app was running (onNewIntent forwards to pendingInviteQr).
    LaunchedEffect(pendingInviteQr.value) {
        pendingInviteQr.value?.let { payload ->
            scannedQrValue = payload
            pendingInviteQr.value = null
        }
    }

    LaunchedEffect(retryTick) {
        // Round-8 REDLINE §P0/§P1 pin: startup effect follows
        // architect's scope lock:
        //   1. decideStartupRoute — pure, cancellation-safe on
        //      ALL FOUR suspend calls (markerRead + loadIdentity
        //      + initMessaging + needsMigration).
        //   2. applyStartupDecision — testable orchestrator
        //      returns StartupPresentation; marker side-effect
        //      is injected + runs off-Main (Dispatchers.IO); a
        //      failed .commit() falls back to in-memory repair
        //      for THIS session.
        //   3. resolveScreenAfterStartup — the Final Stabilization
        //      Mini-Block 2026-08-11 (P1) resolver reads the LIVE
        //      currentScreen at completion time; a healthy ChatList
        //      decision preserves whatever route the user was
        //      already on (restored by ScreenSaver OR navigated to
        //      before the coroutine finished). The four
        //      security-critical decisions (RepairQuarantine,
        //      TransientStartupFailure, FreshOnboarding, Migration)
        //      still force their routes — see the resolver KDoc.
        //   4. Single-flight: `startupInFlight = true` at start,
        //      = false in `finally` so Retry callbacks see the
        //      right state.
        startupInFlight = true
        try {
            val decision = phantom.android.screens.onboarding.v2.decideStartupRoute(
                markerRead = {
                    phantom.android.screens.onboarding.v2.IdentityRepairMarker
                        .isRepairRequired(startupContext)
                },
                loadIdentity = { container.identityRepo.loadIdentity() },
                initMessaging = {
                    // Round-8 pin: return true on success, false
                    // on non-cancellation failure. Cancellation
                    // rethrown so decideStartupRoute propagates
                    // it up (structured concurrency).
                    try {
                        container.initMessagingFromStorage()
                        true
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "MainActivity",
                            "container.initMessagingFromStorage threw", t,
                        )
                        false
                    }
                },
                needsMigration = {
                    // Round-8 mini-round §P1 pin: absence of
                    // migrationManager is an OPERATIONAL failure
                    // (initMessaging didn't bootstrap it), NOT
                    // proven identity corruption. `checkNotNull`
                    // throws → decideStartupRoute's catch on this
                    // suspend call maps it to
                    // TransientStartupFailure(NeedsMigrationThrew),
                    // which never writes the marker.
                    val mgr = checkNotNull(container.migrationManager) {
                        "migrationManager unavailable after initMessaging"
                    }
                    mgr.needsMigration()
                },
            )
            val presentation = phantom.android.screens.onboarding.v2.applyStartupDecision(
                decision = decision,
                markerWriter = {
                    phantom.android.screens.onboarding.v2
                        .productionMarkerWriter(startupContext)
                },
            )
            // Reset then re-apply presentation-specific flag so a
            // Retry after Repair→Transient doesn't leak the prior
            // quarantine bit.
            quarantineForcedInMemory =
                presentation is phantom.android.screens.onboarding.v2
                    .StartupPresentation.OnboardingRepair

            // Snapshot the LIVE currentScreen at completion — this is
            // the load-bearing defect fix. rememberSaveable restored
            // the pre-recreation route (e.g. Screen.Profile) before
            // this coroutine got scheduled; the resolver keeps that
            // route on a healthy ChatList decision.
            val restoredOrCurrent = currentScreen
            val resolved = resolveScreenAfterStartup(decision, restoredOrCurrent)

            // Defence-in-depth: if we're landing on ChatList and
            // migrationManager wasn't bootstrapped by the primary
            // initMessaging path, try once more. Never blocks the
            // navigation write.
            if (resolved is Screen.ChatList && container.migrationManager == null) {
                runCatching { container.initMessagingFromStorage() }
            }

            // Notification tap: only apply when we're actually landing
            // on a fresh ChatList (no restored route, decision healthy)
            // — never hijack a restored Profile/Settings/etc.
            val destination = if (
                resolved is Screen.ChatList &&
                restoredOrCurrent == null &&
                notifConversationId != null &&
                notifSenderName != null
            ) {
                Log.d("PHANTOM", "Notification tap → Chat($notifConversationId)")
                Screen.Chat(notifConversationId, notifSenderName)
            } else {
                resolved
            }
            currentScreen = destination
        } finally {
            startupInFlight = false
        }
    }

    // Transport connect and startReceiving are now owned by PhantomMessagingService (foreground
    // service). Calling them here would create a second competing connection loop. The service
    // is started from MainActivity.onCreate() and runs independently of Activity lifecycle.

    val context = androidx.compose.ui.platform.LocalContext.current

    // Holds the ActiveCall screen to navigate to once mic permission is granted on answer.
    var pendingAnswerScreen by remember { mutableStateOf<Screen.ActiveCall?>(null) }

    val answerMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val dest = pendingAnswerScreen ?: return@rememberLauncherForActivityResult
        pendingAnswerScreen = null
        if (granted) {
            scope.launch { container.callManager?.answerCall() }
            currentScreen = dest
        } else {
            // Permission denied — cannot proceed; treat as reject so the call clears.
            scope.launch { container.callManager?.rejectCall() }
            currentScreen = Screen.ChatList
        }
    }

    // System-back / predictive-back gesture handler — the per-screen
    // onBack lambdas only fire when the user taps the in-UI back arrow.
    // Without this, a hardware back press / left-edge swipe drops the
    // user out of the app instead of navigating up the screen tree.
    // The parent-of map mirrors the literal `onBack` wiring in the
    // when block below; top-level destinations (ChatList / Calls /
    // Nearby / Settings) return null so Android's default behaviour
    // (move task to back / exit) kicks in.
    val parent = parentScreenOf(currentScreen)
    BackHandler(enabled = parent != null) {
        currentScreen = parent
    }

    when (val screen = currentScreen) {
        null -> {
            // Initial frame before LaunchedEffect resolves the start
            // screen. Show splash, NOT Onboarding — the prior default
            // briefly flashed the welcome screen on every relaunch
            // even when identity already existed (bug F, 2026-04-30).
            PhantomSplashScreen()
        }
        is Screen.StartupError -> {
            // C6-a round-8 §P0 pin — retryable transient startup
            // failure. Renders a stable-copy screen with a Retry
            // action; single-flight guarded — if a run is already
            // in flight the tap is a no-op.
            phantom.android.screens.onboarding.v2.OnboardingStartupErrorScreen(
                reason = runCatching {
                    enumValueOf<phantom.android.screens.onboarding.v2.TransientReason>(
                        screen.reasonName,
                    )
                }.getOrElse {
                    // Defensive: if the reason string was corrupted
                    // (shouldn't happen — Screen.StartupError is set
                    // only by MainActivity from the enum's own name),
                    // fall back to a generic reason so the screen
                    // still renders.
                    phantom.android.screens.onboarding.v2
                        .TransientReason.LoadIdentityThrew
                },
                // Mini-round §P2 pin — single-flight guard also
                // reflected in the button's `enabled` state, so
                // the user cannot tap Retry while a startup run
                // is already in flight. The `enabled` value
                // recomposes with `startupInFlight`.
                enabled = !startupInFlight,
                onRetry = {
                    // Mini-round §P2 pin — synchronous
                    // `startupInFlight = true` BEFORE
                    // `retryTick += 1`. Compose recomposition
                    // observes the flip immediately, so a
                    // second tap on the same frame sees the
                    // button disabled AND the guard is already
                    // true. Prior shape only guarded on the
                    // flag; LaunchedEffect(retryTick) flipped
                    // it later, leaving a 1-frame race.
                    if (!startupInFlight) {
                        startupInFlight = true
                        retryTick += 1
                    }
                },
            )
        }
        is Screen.Onboarding -> phantom.android.screens.onboarding.v2.OnboardingScreenV2(
            // Commit 5 round-1 REDLINE §P0 fix: switch to the
            // `OnboardingScreenV2` WRAPPER (not `OnboardingFlowV2`
            // directly). The wrapper gates Terms-of-Service
            // acceptance BEFORE the flow — bypassing it would let
            // a first-run user see Welcome without accepting the
            // legal terms, which the legacy `OnboardingScreen`
            // did NOT allow. Signature is identical to the legacy
            // entry point (both take `container` + `onComplete`).
            //
            // The `onComplete` shape is identical (the V2 finalize
            // state machine calls it once via
            // `FinaleConfirmationStepV2.onContinueClick` after
            // `initMessaging` succeeded), so the post-onboarding
            // flow — foreground-service restart + jump to
            // Screen.ChatList — is unchanged.
            //
            // Legacy `OnboardingScreen.kt` is INTENTIONALLY kept in
            // the source tree for now; the follow-up cleanup PR
            // (out of Commit 5 scope) will delete it after this
            // switch soaks. Rollback plan if a P0 emerges post-
            // merge: revert Commit 5's MainActivity edit only —
            // the OnboardingFlowV2 code stays but the entry point
            // reverts to legacy in one line.
            container = container,
            onComplete = {
                // Identity is now persisted. Restart the foreground service so it
                // picks up the new identity, calls startReceiving(), and opens the
                // WebSocket — the earlier onStartCommand bailed out via stopSelf()
                // because no identity existed yet. Without this kick the user has
                // to fully restart the app before messages can flow.
                context.startForegroundService(Intent(context, PhantomMessagingService::class.java))
                currentScreen = Screen.ChatList
            },
            // C6-a round-6 §P1 pin: forward in-memory quarantine
            // flag. When the durable marker write failed in the
            // startup routing branch, `quarantineForcedInMemory`
            // is true and this override forces the flow's holder
            // to seed at MissingKeyRepairRequired regardless of
            // what the disk marker reads back.
            explicitInitialFinalizeState = if (quarantineForcedInMemory)
                phantom.android.screens.onboarding.v2.OnboardingFinalizeState.MissingKeyRepairRequired
            else
                null,
        )
        is Screen.Migration -> {
            // PR C-followup-2: Alpha 1 → Alpha 2 migration UI. The
            // manager was wired by initMessagingFromStorage in the
            // launch LaunchedEffect, so it's non-null here. On a
            // success the runMigration call has already updated the
            // local state; we kick the foreground service so the new
            // bundle gets published over a fresh WS connection
            // (background ticker would otherwise wait 24h) and route
            // the user onto ChatList.
            val mgr = container.migrationManager
            if (mgr != null) {
                MigrationScreen(
                    migrationManager = mgr,
                    onMigrationComplete = {
                        // Trigger initial bundle publish via lifecycle
                        // service immediately — without it the user
                        // can't receive first messages until the 24-h
                        // ticker fires.
                        scope.launch {
                            runCatching {
                                container.preKeyLifecycle?.bootstrapForNewIdentity()
                            }
                        }
                        context.startForegroundService(
                            Intent(context, PhantomMessagingService::class.java),
                        )
                        currentScreen = Screen.ChatList
                    },
                    onQuit = {
                        // Activity finish() drops the user back to launcher.
                        // The next launch will see needsMigration() == true
                        // again — there is no "skip migration" path.
                        (context as? android.app.Activity)?.finishAndRemoveTask()
                    },
                )
            } else {
                // Edge case: container.migrationManager is null because
                // initMessaging never ran (e.g. some race). Fall back
                // to ChatList; the user will hit a hard send error
                // until they restart the app.
                Log.w(
                    "PHANTOM_MIGRATION",
                    "Screen.Migration with null migrationManager; falling back to ChatList",
                )
                currentScreen = Screen.ChatList
            }
        }
        is Screen.ChatList -> ChatListScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
            onScanQr = { currentScreen = Screen.QrScan },
            scannedQr = scannedQrValue,
            onScannedQrConsumed = { scannedQrValue = null },
        )
        is Screen.Calls -> CallsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.Nearby -> phantom.android.screens.nearby.NearbyScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.Premium -> phantom.android.screens.premium.PremiumScreen(
            onBack = { currentScreen = Screen.Settings },
        )
        is Screen.AddContact -> phantom.android.screens.addcontact.AddContactScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Settings -> SettingsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.PrivacyModeDetail -> phantom.android.screens.settings.PrivacyModeDetailScreen(
            container = container,
            onBack = { currentScreen = Screen.Settings },
        )
        is Screen.Profile -> ProfileScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onLogout = { currentScreen = Screen.Onboarding },
        )
        is Screen.MessageRequests -> MessageRequestsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.QrScan -> QrScanScreen(
            onScanned = { raw ->
                currentScreen = Screen.ChatList
                scannedQrValue = raw
            },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Chat -> ChatScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onContactProfile = { currentScreen = Screen.ContactProfile(screen.conversationId, screen.theirUsername) },
            onStartVoiceCall = {
                currentScreen = Screen.ActiveCall(screen.conversationId, screen.theirUsername)
            },
        )
        is Screen.ContactProfile -> ContactProfileScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.Chat(screen.conversationId, screen.theirUsername) },
            onDeleteConversation = { currentScreen = Screen.ChatList },
            onVerify = {
                currentScreen = Screen.Verify(screen.conversationId, screen.theirUsername)
            },
        )
        is Screen.Verify -> phantom.android.screens.verify.VerifyScreen(
            container = container,
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            onBack = {
                currentScreen = Screen.ContactProfile(screen.conversationId, screen.theirUsername)
            },
        )
        is Screen.SavedMessages -> SavedMessagesScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Archive -> ArchiveScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onNavigateToChat = { chatScreen -> currentScreen = chatScreen },
        )
        is Screen.GroupChat -> GroupChatScreen(
            groupId   = screen.groupId,
            groupName = screen.groupName,
            isChannel = screen.isChannel,
            container = container,
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.CreateGroup -> CreateGroupScreen(
            container = container,
            onCreated = { groupId, groupName -> currentScreen = Screen.GroupChat(groupId, groupName, false) },
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.CreateChannel -> CreateChannelScreen(
            container = container,
            onCreated = { groupId, groupName -> currentScreen = Screen.GroupChat(groupId, groupName, true) },
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.ActiveCall -> {
            val cm = container.callManager
            val noCallFlow = remember { MutableStateFlow<ActiveCall?>(null) }
            val callState by (cm?.activeCall ?: noCallFlow).collectAsState()
            val call = callState
            // After hangup / remote-hangup return to the chat we came from, NOT
            // back to ChatList. The user expects context continuity: end the
            // call, see the conversation thread.
            val backToChat = Screen.Chat(screen.conversationId, screen.username)
            LaunchedEffect(call) {
                if (call == null) {
                    currentScreen = backToChat
                }
            }
            if (call != null && cm != null) {
                ActiveCallScreen(
                    call = call,
                    onHangup = { scope.launch { cm.hangup() }; currentScreen = backToChat },
                    onToggleMute = { cm.toggleMute() },
                    onToggleSpeaker = { cm.toggleSpeaker() },
                    onBack = { currentScreen = backToChat },
                )
            }
        }
        is Screen.IncomingCall -> IncomingCallScreen(
            username = screen.username,
            onAnswer = {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    scope.launch { container.callManager?.answerCall() }
                    currentScreen = Screen.ActiveCall(screen.conversationId, screen.username)
                } else {
                    pendingAnswerScreen = Screen.ActiveCall(screen.conversationId, screen.username)
                    answerMicLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
            onReject = {
                scope.launch { container.callManager?.rejectCall() }
                currentScreen = Screen.ChatList
            },
        )
    }

    // Global observer — navigate to IncomingCall when a RINGING call arrives from any screen
    LaunchedEffect(container.callManager) {
        container.callManager?.activeCall?.collect { call ->
            if (call != null &&
                call.state == CallState.RINGING &&
                currentScreen !is Screen.IncomingCall
            ) {
                currentScreen = Screen.IncomingCall(call.remotePubKeyHex, call.remoteUsername)
            }
        }
    }
}

/**
 * Maps each [Screen] to the destination its in-UI back arrow / system
 * back gesture should pop to. Returns `null` for screens where Android's
 * default behaviour (move task to back / exit) is desired:
 *
 *  - Top-level destinations (ChatList / Calls / Nearby / Settings) —
 *    pressing back exits the app, matching mainstream Android apps.
 *  - Onboarding / Migration / Splash — modal flows that own their own
 *    back semantics; system back during onboarding shouldn't tear it
 *    down mid-key-generation.
 *  - IncomingCall / ActiveCall — call screens have explicit hangup /
 *    accept actions and shouldn't be dismissed by accidental gesture.
 *
 * The mapping mirrors the literal `onBack` lambdas wired in the main
 * `when` block so swipe-back behaviour matches the in-UI back button.
 */
private fun parentScreenOf(screen: Screen?): Screen? = when (screen) {
    null,
    Screen.Onboarding,
    Screen.Migration,
    Screen.ChatList,
    Screen.Calls,
    Screen.Nearby,
    Screen.Settings -> null

    // C6-a round-8: transient startup error is a top-level
    // screen with its own Retry action; no parent for Back.
    is Screen.StartupError -> null

    Screen.Premium -> Screen.Settings
    Screen.PrivacyModeDetail -> Screen.Settings
    Screen.AddContact -> Screen.ChatList
    Screen.Profile -> Screen.ChatList
    Screen.MessageRequests -> Screen.ChatList
    Screen.QrScan -> Screen.ChatList
    Screen.SavedMessages -> Screen.ChatList
    Screen.Archive -> Screen.ChatList
    Screen.CreateGroup -> Screen.ChatList
    Screen.CreateChannel -> Screen.ChatList

    is Screen.Chat -> Screen.ChatList
    is Screen.GroupChat -> Screen.ChatList
    is Screen.ContactProfile -> Screen.Chat(screen.conversationId, screen.theirUsername)
    is Screen.Verify -> Screen.ContactProfile(screen.conversationId, screen.theirUsername)

    // Calls screens own their lifecycle — declining via the on-screen
    // controls is the supported path; back gesture is intentionally
    // a no-op so a stray swipe doesn't drop the user out of the call.
    is Screen.ActiveCall,
    is Screen.IncomingCall -> null
}
