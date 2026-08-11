// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import phantom.core.messaging.WssDiagBridge
import phantom.core.messaging.WssDiagBridgeHolder

/**
 * Direct WSS Yota-First diagnostic — zero-touch boot init for the
 * debug variant.
 *
 * Registered as a ContentProvider in the debug `AndroidManifest.xml`
 * overlay so the framework instantiates it BEFORE `Application.onCreate()`
 * runs. This installs the [WssDiagBridge] adapter that routes shared
 * `DefaultMessagingService` diagnostic events into Android's `Log.i`
 * via [WssDiag]. Release APK has no debug overlay → no provider → no
 * bridge install → shared code's `WssDiagBridgeHolder.instance` stays
 * `null` and the bridge calls are cheap null-checks.
 */
class DiagnosticBootInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Install the bridge exactly once. Idempotent — repeated
        // installs are no-ops. Debug boot init runs before
        // Application.onCreate() by design (ContentProviders start
        // first in the Android app-startup order).
        if (WssDiagBridgeHolder.instance == null) {
            WssDiagBridgeHolder.instance = AndroidWssDiagBridge
            Log.i(WssDiag.TAG, "diagnostic_boot_init bridge_installed=true")
        }
        return true
    }

    // The provider does not expose any data. All URI queries return
    // null. It exists solely for the boot-init side effect above.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Adapter from the KMP-shared [WssDiagBridge] interface to the
 * Android-side [WssDiag] structured emitter. Maps the interface's
 * neutral enums to the [WssDiag] enums 1:1.
 */
internal object AndroidWssDiagBridge : WssDiagBridge {
    override fun emit(
        event: String,
        correlationId: String,
        role: WssDiagBridge.Role,
        outcomeFlag: WssDiagBridge.OutcomeFlag,
        dedupGate: WssDiagBridge.DedupGate?,
    ) {
        WssDiag.emit(
            event = event,
            role = when (role) {
                WssDiagBridge.Role.SENDER -> WssDiag.Role.SENDER
                WssDiagBridge.Role.RECIPIENT -> WssDiag.Role.RECIPIENT
            },
            correlationId = correlationId,
            outcomeFlag = when (outcomeFlag) {
                WssDiagBridge.OutcomeFlag.SENDER_RELAY_ACK_DELIVERED ->
                    WssDiag.OutcomeFlag.SENDER_RELAY_ACK_DELIVERED
                WssDiagBridge.OutcomeFlag.SENDER_RELAY_ACK_RELAYED ->
                    WssDiag.OutcomeFlag.SENDER_RELAY_ACK_RELAYED
                WssDiagBridge.OutcomeFlag.NONE -> WssDiag.OutcomeFlag.NONE
            },
            dedupGate = dedupGate?.let {
                when (it) {
                    WssDiagBridge.DedupGate.FRESH -> WssDiag.DedupGate.FRESH
                    WssDiagBridge.DedupGate.DUPLICATE -> WssDiag.DedupGate.DUPLICATE
                    WssDiagBridge.DedupGate.REACK -> WssDiag.DedupGate.REACK
                    WssDiagBridge.DedupGate.UNKNOWN -> WssDiag.DedupGate.UNKNOWN
                }
            },
        )
    }
}
