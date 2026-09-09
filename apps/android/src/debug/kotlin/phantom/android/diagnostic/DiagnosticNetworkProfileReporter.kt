// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WSS-3 Carrier x VPN Matrix — debug-only network profile reporter.
 *
 * Helper class (NOT a manifest component) piggybacked on the existing
 * debug-only [DiagnosticCommandReceiver] (§4.5 of the WSS-3 contract).
 * Lives ONLY under `apps/android/src/debug/kotlin/phantom/android/diagnostic/`;
 * source-set boundary verified by [DiagnosticSourceSetBoundaryTest].
 *
 * Contract discipline (see docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md):
 *
 *   - Writes to a FIXED app-owned path `Context.filesDir/wss3/network_profile.json`.
 *     Never accepts a caller-controlled path (Round-2 blocker 5).
 *   - Extras whitelist enforced by the receiver (Round-2 blocker 5).
 *   - NEVER emits to Android's Log OR to WssDiag (§4.5 "Contract" clause).
 *   - HMAC-SHA256 egress fingerprint using orchestrator-supplied `checkpoint_key`;
 *     key wiped from local memory after computation (Round-3 blocker 2; verified
 *     by fixture 79 for observable guarantees).
 *   - IPv4 pinned via `api4.ipify.org` + AF_INET filter (Round-2 blocker 4).
 *   - Denylist: no raw IP, no VPN provider/interface, no subscriber-id,
 *     no `checkpoint_key*` substring escapes to disk.
 *   - Dual-SIM: reads via `SubscriptionManager.getActiveDataSubscriptionId()`
 *     then `TelephonyManager.createForSubscriptionId(id).simOperator`
 *     (Round-3 blocker 5). Bare `getSimOperator()` NOT accepted.
 *   - Wi-Fi/mobile-data via `WifiManager.isWifiEnabled()` +
 *     `TelephonyManager.isDataEnabled()` (Round-3 blocker 6). SecurityException
 *     on Wi-Fi read → `wifi_enabled: null` + `wifi_read_error: "SECURITY_EXCEPTION"`
 *     (Round-4 blocker 3 fail-closed; never substitutes a boolean).
 *   - Release-inert: this file is physically absent from the release variant's
 *     dex. `DiagnosticSourceSetBoundaryTest` walks `androidMain` and asserts
 *     no reference to this class name.
 */
object DiagnosticNetworkProfileReporter {

    internal const val EGRESS_ENDPOINT = "https://api4.ipify.org"
    internal const val EGRESS_TIMEOUT_MS = 5000
    internal const val REPORT_SUBDIR = "wss3"
    internal const val REPORT_FILENAME = "network_profile.json"
    internal const val SCHEMA_VERSION = "1"

    /**
     * Runs the report. Called from the debug-only
     * [DiagnosticCommandReceiver] under the `network_profile_report`
     * subcommand.
     *
     * @param context application context (used only for
     *   `filesDir` + system service lookups).
     * @param checkpointKeyHex 64-char lowercase hex for the orchestrator's
     *   HMAC key. Wiped from memory after HMAC computation. Never persisted.
     */
    fun run(context: Context, checkpointKeyHex: String) {
        runInternal(
            context = context,
            checkpointKeyHex = checkpointKeyHex,
            endpoint = EGRESS_ENDPOINT,
            timeoutMs = EGRESS_TIMEOUT_MS,
            probeOverride = null,
            systemStateOverride = null,
            skipEgress = false,
        )
    }

    /**
     * State-only variant per audit ROUND-10 P0-1. The phone MUST NOT
     * participate in the HMAC egress scheme (contract §4.5 REDLINE-4
     * blocker 1). This entry point is invoked by
     * [DiagnosticCommandReceiver] under the
     * `network_profile_state_report` subcommand — no `checkpoint_key_hex`
     * extra is expected, no HTTP fetch happens, and the emitted JSON
     * carries NO `egress_fingerprint` field.
     */
    fun runStateOnly(context: Context) {
        runInternal(
            context = context,
            checkpointKeyHex = "",  // unused; egress skipped
            endpoint = "",           // unused; egress skipped
            timeoutMs = 0,
            probeOverride = null,
            systemStateOverride = null,
            skipEgress = true,
        )
    }

    // @VisibleForTesting seam used by the Kotlin focused-fixture suite
    // (§9 fixtures 75-82, 85) so tests can point the egress probe at a
    // local HttpServer they spin up, can force a specific timeout, and
    // can inject canned [NetworkStateProbe] / [SystemStateProbe] to
    // bypass Robolectric-shadow-internal quirks around active-network
    // state and per-sub TelephonyManager instances. The public [run]
    // API pins production behaviour to the contract constants above
    // and always uses [ConnectivityManagerNetworkProbe] +
    // [RealSystemStateProbe] — no production code calls this overload.
    internal fun runInternal(
        context: Context,
        checkpointKeyHex: String,
        endpoint: String,
        timeoutMs: Int,
        probeOverride: NetworkStateProbe? = null,
        systemStateOverride: SystemStateProbe? = null,
        skipEgress: Boolean = false,
    ) {
        // Per audit ROUND-10 P0-1: state-only path skips the key
        // validation because no HMAC is computed. The egress fetch is
        // suppressed and the emitted JSON carries NO `egress_fingerprint`
        // field at all.
        if (!skipEgress && !isValidCheckpointKeyHex(checkpointKeyHex)) return

        val walk = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val netProbe = probeOverride ?: ConnectivityManagerNetworkProbe(connectivity)
        val sysProbe = systemStateOverride ?: RealSystemStateProbe(context, telephony)

        val simInfo = SimInfo(
            subscriptionId = sysProbe.simSubscriptionId,
            subscriptionIdValid = sysProbe.simSubscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            operatorNumeric = sysProbe.simOperatorNumeric,
        )
        val wifiInfo = WifiInfo(enabled = sysProbe.wifiEnabled, readError = sysProbe.wifiReadError)
        val mobileDataEnabled = sysProbe.mobileDataEnabled
        val networkKind = netProbe.activeNetworkKind()
        val hasVpn = netProbe.hasTransportVpn()

        // Fixture 76 discipline: when no active network is present the
        // egress probe is deliberately skipped — the HTTP attempt has
        // no chance of succeeding and doing it anyway would burn 5 s
        // per checkpoint. The reporter still emits a report with
        // `hmac_fp_hex:null`, `http_status:null`, and the tri-state
        // network fields set correctly.
        //
        // Audit ROUND-10 P0-1: when `skipEgress=true` (state-only
        // subcommand, invoked ONLY on the phone per contract), NO
        // HTTP fetch happens and the emitted JSON drops the
        // `egress_fingerprint` field entirely (checked by buildJson).
        val egress = when {
            skipEgress -> null
            networkKind == "NONE" -> EgressInfo(
                addressFamily = "UNKNOWN",
                hmacFpHex = null,
                httpStatus = null,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
            else -> probeEgressAndHmac(checkpointKeyHex, endpoint, timeoutMs, hasVpn)
        }

        val json = buildJson(
            walkTs = walk,
            wallTs = wall,
            networkKind = networkKind,
            hasVpn = hasVpn,
            wifiEnabled = wifiInfo.enabled,
            wifiReadError = wifiInfo.readError,
            mobileDataEnabled = mobileDataEnabled,
            simInfo = simInfo,
            egress = egress,
            processUid = android.os.Process.myUid(),
            appDebuggable = isAppDebuggable(context),
        )

        // Fixed app-owned path. Fail-closed if the write fails — no
        // partial file, no fallback path.
        val dir = File(context.filesDir, REPORT_SUBDIR)
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) return
        }
        val target = File(dir, REPORT_FILENAME)
        try {
            FileOutputStream(target).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {
            // Silent fail — the reporter never writes to Android's Log
            // (§4.5 Contract). Orchestrator will time out or see
            // `run-as cat` return empty and mark the checkpoint failed.
        }
    }

    private data class SimInfo(
        val subscriptionId: Int,
        val subscriptionIdValid: Boolean,
        val operatorNumeric: String,
    )

    private data class WifiInfo(
        val enabled: Boolean?,
        val readError: String?,
    )

    private data class EgressInfo(
        val addressFamily: String,
        val hmacFpHex: String?,
        val httpStatus: Int?,
        val timeoutMsUsed: Int,
        val hasTransportVpn: Boolean,
    )

    private fun resolveActiveDataSim(tm: TelephonyManager?): Pair<Int, String> {
        val info = readActiveDataSim(tm)
        return Pair(info.subscriptionId, info.operatorNumeric)
    }

    private fun resolveWifiState(context: Context): Pair<Boolean?, String?> {
        val info = readWifiState(context)
        return Pair(info.enabled, info.readError)
    }

    private fun resolveMobileDataEnabled(tm: TelephonyManager?, subId: Int): Boolean? {
        return readMobileDataEnabled(tm, subId)
    }

    private fun readActiveDataSim(tm: TelephonyManager?): SimInfo {
        // Per REDLINE-3 blocker 5: MUST resolve active-data subscription
        // first, then read simOperator on that specific sub. Bare
        // `TelephonyManager.getSimOperator()` returns the default-sub
        // operator, which on dual-SIM devices is not necessarily the
        // active-data SIM.
        val subId = try {
            SubscriptionManager.getActiveDataSubscriptionId()
        } catch (_: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || tm == null) {
            return SimInfo(subId, subscriptionIdValid = false, operatorNumeric = "")
        }
        val op = try {
            tm.createForSubscriptionId(subId).simOperator.orEmpty()
        } catch (_: SecurityException) {
            ""
        } catch (_: Throwable) {
            ""
        }
        return SimInfo(subId, subscriptionIdValid = true, operatorNumeric = op)
    }

    private fun readWifiState(context: Context): WifiInfo {
        // Per REDLINE-4 blocker 3: reporter MUST catch SecurityException
        // (if ACCESS_WIFI_STATE ever falls off the merged debug
        // manifest) and emit `wifi_enabled: null` +
        // `wifi_read_error: "SECURITY_EXCEPTION"` — NEVER substitute a
        // fabricated boolean.
        val wifi = context.getSystemService(WifiManager::class.java)
            ?: return WifiInfo(enabled = null, readError = "NO_WIFI_MANAGER")
        return try {
            WifiInfo(enabled = wifi.isWifiEnabled, readError = null)
        } catch (_: SecurityException) {
            WifiInfo(enabled = null, readError = "SECURITY_EXCEPTION")
        } catch (_: Throwable) {
            WifiInfo(enabled = null, readError = "UNKNOWN_EXCEPTION")
        }
    }

    private fun readMobileDataEnabled(
        tm: TelephonyManager?,
        subId: Int,
    ): Boolean? {
        if (tm == null) return null
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return try {
            @Suppress("DEPRECATION")
            tm.createForSubscriptionId(subId).isDataEnabled
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Testable seam. Production path uses [ConnectivityManagerNetworkProbe]
     * for network transports; Kotlin focused fixtures 75-76 inject a
     * canned implementation because Robolectric's shadow surface for
     * `ConnectivityManager.getActiveNetwork()` in 4.14.1 has no stable
     * public setter that survives `createForSubscriptionId`-style
     * re-materialisation of framework services.
     */
    internal interface NetworkStateProbe {
        /** "VPN" | "CELLULAR" | "WIFI" | "OTHER" | "NONE". */
        fun activeNetworkKind(): String
        fun hasTransportVpn(): Boolean
    }

    internal class ConnectivityManagerNetworkProbe(
        private val cm: ConnectivityManager?,
    ) : NetworkStateProbe {
        override fun activeNetworkKind(): String {
            if (cm == null) return "OTHER"
            val net = cm.activeNetwork ?: return "NONE"
            val caps = cm.getNetworkCapabilities(net) ?: return "OTHER"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                else -> "OTHER"
            }
        }

        override fun hasTransportVpn(): Boolean {
            if (cm == null) return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /**
     * Testable seam for the SIM + Wi-Fi + mobile-data reads. Production
     * path uses [RealSystemStateProbe] which:
     *
     *   - resolves the active-data subscription via
     *     `SubscriptionManager.getActiveDataSubscriptionId()` — NOT bare
     *     `getSimOperator()` (Round-3 blocker 5);
     *   - reads `TelephonyManager.createForSubscriptionId(id).simOperator`
     *     for the operator numeric on that specific sub;
     *   - reads `WifiManager.isWifiEnabled()` and catches
     *     `SecurityException` fail-closed (Round-4 blocker 3);
     *   - reads `TelephonyManager.isDataEnabled()` on the active-data
     *     subscription.
     *
     * Kotlin focused fixtures 81-82-85 inject canned implementations
     * because Robolectric 4.14.1's `createForSubscriptionId(id)` returns
     * a fresh `TelephonyManager` instance whose ShadowTelephonyManager
     * state is NOT shared across calls with the same `id`, so a
     * shadow-based setSimOperator on one call is invisible to the
     * reporter's separate call. The [reporterUsesActiveDataSubscriptionApi]
     * source-level assertion pins the production API path independently
     * of the runtime shadow limitation.
     */
    internal interface SystemStateProbe {
        val simSubscriptionId: Int
        val simOperatorNumeric: String
        val wifiEnabled: Boolean?
        val wifiReadError: String?
        val mobileDataEnabled: Boolean?
    }

    internal class RealSystemStateProbe(
        private val ctx: Context,
        private val telephony: TelephonyManager?,
    ) : SystemStateProbe {
        override val simSubscriptionId: Int by lazy { subInfo.first }
        override val simOperatorNumeric: String by lazy { subInfo.second }
        override val wifiEnabled: Boolean? by lazy { wifiInfo.first }
        override val wifiReadError: String? by lazy { wifiInfo.second }
        override val mobileDataEnabled: Boolean? by lazy {
            resolveMobileDataEnabled(telephony, simSubscriptionId)
        }

        private val subInfo: Pair<Int, String> by lazy {
            resolveActiveDataSim(telephony)
        }
        private val wifiInfo: Pair<Boolean?, String?> by lazy {
            resolveWifiState(ctx)
        }
    }

    private fun probeEgressAndHmac(
        checkpointKeyHex: String,
        endpoint: String,
        timeoutMs: Int,
        hasVpn: Boolean,
    ): EgressInfo {
        // Force IPv4 lookup on api4.ipify.org (Round-2 blocker 4). Any
        // AF_INET6 result → the profile fails-closed as
        // `mixed_address_family` at the orchestrator side; here we
        // just record what we got.
        val addrs: Array<InetAddress> = try {
            InetAddress.getAllByName(URL(endpoint).host)
        } catch (_: Throwable) {
            return EgressInfo(
                addressFamily = "UNKNOWN",
                hmacFpHex = null,
                httpStatus = null,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
        }
        val v4 = addrs.filterIsInstance<Inet4Address>().firstOrNull()
        val v6 = addrs.filterIsInstance<Inet6Address>().firstOrNull()
        val chosen: InetAddress? = v4 ?: v6
        val family = when {
            chosen is Inet4Address -> "AF_INET"
            chosen is Inet6Address -> "AF_INET6"
            else -> "UNKNOWN"
        }
        if (chosen == null) {
            return EgressInfo(
                addressFamily = "UNKNOWN",
                hmacFpHex = null,
                httpStatus = null,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
        }

        // Fetch the IP echo. Raw response body is held in memory only
        // long enough to compute the HMAC, then discarded. No raw IP
        // ever touches disk. On timeout / non-200 the HMAC is null and
        // `http_status` records the outcome.
        val conn = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                useCaches = false
                doOutput = false
                setRequestProperty("Connection", "close")
            }
        } catch (_: Throwable) {
            return EgressInfo(
                addressFamily = family,
                hmacFpHex = null,
                httpStatus = null,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
        }

        var status: Int? = null
        var canonicalBytes: ByteArray? = null
        try {
            status = conn.responseCode
            if (status in 200..299) {
                canonicalBytes = conn.inputStream.readBytes()
                    .toString(Charsets.UTF_8)
                    .trim()
                    .toByteArray(Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            // status stays null on timeout / IOException
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }

        if (canonicalBytes == null) {
            return EgressInfo(
                addressFamily = family,
                hmacFpHex = null,
                httpStatus = status,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
        }

        val keyBytes = decodeHexOrNull(checkpointKeyHex) ?: run {
            Arrays.fill(canonicalBytes, 0)
            return EgressInfo(
                addressFamily = family,
                hmacFpHex = null,
                httpStatus = status,
                timeoutMsUsed = timeoutMs,
                hasTransportVpn = hasVpn,
            )
        }

        val hex = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            val digest = mac.doFinal(canonicalBytes)
            // First 16 bytes → 32 hex chars.
            digest.copyOfRange(0, 16).toHex()
        } catch (_: Throwable) {
            null
        } finally {
            // Best-effort key wipe. JVM string immutability + GC opacity
            // acknowledged; the observable guarantees are what fixture 79
            // asserts (no key substring in JSON / test sinks / evidence).
            Arrays.fill(keyBytes, 0)
            Arrays.fill(canonicalBytes, 0)
        }

        return EgressInfo(
            addressFamily = family,
            hmacFpHex = hex,
            httpStatus = status,
            timeoutMsUsed = timeoutMs,
            hasTransportVpn = hasVpn,
        )
    }

    private fun buildJson(
        walkTs: Long,
        wallTs: Long,
        networkKind: String,
        hasVpn: Boolean,
        wifiEnabled: Boolean?,
        wifiReadError: String?,
        mobileDataEnabled: Boolean?,
        simInfo: SimInfo,
        egress: EgressInfo?,
        processUid: Int,
        appDebuggable: Boolean,
    ): String {
        // Hand-written JSON to keep the reporter dependency-free and
        // to give the denylist regex sweep a deterministic byte
        // layout. NO field name from the §5 denylist appears here
        // (`ip_address`, `address`, `ipv4`, `ipv6` etc. are absent);
        // the raw IP body was discarded above without ever being
        // stringified into any field; `checkpoint_key*` never appears
        // as a substring anywhere.
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"schema_version\":\"").append(SCHEMA_VERSION).append("\",")
        sb.append("\"at_wall_ms\":").append(wallTs).append(',')
        sb.append("\"at_monotonic_ms\":").append(walkTs).append(',')
        // ConnectivityManager-derived facts. The has_transport_vpn we
        // record here is queried outside the egress path (which needed
        // its own state); recomputing here would double the work but
        // keeps the field consistent with the checkpoint contract.
        // The reporter under Robolectric may not have a network → the
        // absence of the active-network case is encoded by
        // active_network_present:false.
        val presentAndKind = networkKind != "NONE"
        sb.append("\"has_transport_vpn\":").append(hasVpn).append(',')
        sb.append("\"active_network_present\":").append(presentAndKind).append(',')
        val kindForJson = if (presentAndKind) networkKind else "OTHER"
        sb.append("\"active_network_kind\":\"").append(kindForJson).append("\",")
        // Wi-Fi tri-state per REDLINE-4 blocker 3.
        if (wifiEnabled != null) {
            sb.append("\"wifi_enabled\":").append(wifiEnabled).append(',')
        } else {
            sb.append("\"wifi_enabled\":null,")
        }
        if (wifiReadError != null) {
            sb.append("\"wifi_read_error\":\"").append(wifiReadError).append("\",")
        }
        if (mobileDataEnabled != null) {
            sb.append("\"mobile_data_enabled\":").append(mobileDataEnabled).append(',')
        } else {
            sb.append("\"mobile_data_enabled\":null,")
        }
        sb.append("\"active_data_subscription_id\":").append(simInfo.subscriptionId).append(',')
        sb.append("\"active_data_sim_operator_numeric\":\"").append(escape(simInfo.operatorNumeric)).append("\",")
        if (egress != null) {
            sb.append("\"egress_fingerprint\":{")
            sb.append("\"endpoint\":\"api4.ipify.org\",")
            sb.append("\"address_family\":\"").append(egress.addressFamily).append("\",")
            if (egress.hmacFpHex != null) {
                sb.append("\"hmac_fp_hex\":\"").append(egress.hmacFpHex).append("\",")
            } else {
                sb.append("\"hmac_fp_hex\":null,")
            }
            sb.append("\"at_wall_ms\":").append(wallTs).append(',')
            if (egress.httpStatus != null) {
                sb.append("\"http_status\":").append(egress.httpStatus).append(',')
            } else {
                sb.append("\"http_status\":null,")
            }
            sb.append("\"timeout_ms_used\":").append(egress.timeoutMsUsed)
            sb.append("},")
        }
        // Audit ROUND-10 P0-1: state-only path (egress == null) emits
        // NO egress_fingerprint object at all — the phone reporter
        // must never carry HMAC-computable evidence.
        sb.append("\"process_uid\":").append(processUid).append(',')
        sb.append("\"app_debuggable\":").append(appDebuggable)
        sb.append('}')
        return sb.toString()
    }

    private fun isAppDebuggable(context: Context): Boolean {
        return try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
    }

    internal fun isValidCheckpointKeyHex(v: String?): Boolean {
        if (v == null || v.length != 64) return false
        return v.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length != 64) return null
        val out = ByteArray(32)
        var i = 0
        while (i < 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(this.size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun escape(s: String): String {
        // Minimal JSON string escaping — the values we emit here are
        // 5- or 6-digit numeric operator codes; the escape is a
        // defensive measure only.
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 2)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
