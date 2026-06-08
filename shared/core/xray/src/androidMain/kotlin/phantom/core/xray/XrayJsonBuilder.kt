// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Renders a Stage 5E client-side Xray-core configuration JSON for a single
 * VLESS+REALITY outbound and a single SOCKS5 inbound bound to localhost.
 *
 * Why hand-build the JSON instead of writing a typed schema: Xray-core's
 * config surface is hundreds of fields wide and changes between minor
 * releases; trying to mirror it as kotlinx.serialization classes would be a
 * fragile, low-value abstraction. The on-wire shape we need is small and
 * stable — a dozen leaf fields wired into the well-documented VLESS+REALITY
 * client template.
 *
 * The "log: loglevel: warning" level is the production default: Xray's
 * `info` level leaks per-connection peer addresses to logcat, which we
 * don't want in production (privacy of the user's contact graph).
 * `warning` keeps the unrecoverable failure messages we care about and
 * drops the rest. The level is now caller-controlled via
 * [XrayServiceConfig.loglevel] so debug builds — specifically the
 * RC-DIRECT-STABILITY1 §14 Arm G diagnostic — can pass `debug` to see
 * Reality handshake / uTLS / splice events that warning hides. Release
 * keeps the default via `OperatorXrayConfig.toConfig(...)`.
 *
 * Reference template: <https://xtls.github.io/en/config/transports/reality.html>
 */
internal fun buildXrayClientConfig(config: XrayServiceConfig): String {
    val root: JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("loglevel", config.loglevel)
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("port", config.socksPort)
                put("protocol", "socks")
                putJsonObject("settings") {
                    // No userpass — local-only listener, the OS already
                    // sandboxes 127.0.0.1 to in-process callers on Android.
                    put("auth", "noauth")
                    put("udp", true)
                }
                putJsonObject("sniffing") {
                    // Sniffing surfaces the real destination host so Xray's
                    // routing rules can match on domain, not just IP. Cheap
                    // on the wire (parses the first packet only).
                    put("enabled", true)
                    putJsonArray("destOverride") {
                        add("http")
                        add("tls")
                    }
                }
            }
        }
        putJsonArray("outbounds") {
            addJsonObject {
                put("tag", "reality-out")
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", config.serverHost)
                            put("port", config.serverPort)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", config.uuid)
                                    // VLESS+REALITY mandates encryption=none
                                    // at the VLESS layer because the wire is
                                    // already inside the REALITY-mirrored
                                    // TLS session. Adding another layer would
                                    // break the TLS-fingerprint mimicry.
                                    put("encryption", "none")
                                    put("flow", "xtls-rprx-vision")
                                }
                            }
                        }
                    }
                }
                putJsonObject("streamSettings") {
                    put("network", "tcp")
                    put("security", "reality")
                    putJsonObject("realitySettings") {
                        // utls fingerprint = chrome — most common on the
                        // wire, blends into the carrier's existing traffic.
                        put("fingerprint", "chrome")
                        put("serverName", config.sni)
                        put("publicKey", config.publicKey)
                        put("shortId", config.shortId)
                        // spiderX is unused by the server-side
                        // implementation we ship; leaving it absent matches
                        // the operator-side default.
                    }
                }
            }
            // Direct outbound for traffic that is NOT routed via REALITY
            // (e.g. localhost loops). Falls through routing rules below.
            addJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
            }
            // Block-hole for sinkhole rules, kept for completeness even
            // though Stage 5E.B has no domain-blocklist rule yet.
            addJsonObject {
                put("tag", "block")
                put("protocol", "blackhole")
            }
        }
        putJsonObject("routing") {
            put("domainStrategy", "AsIs")
            putJsonArray("rules") {
                // Loopback and link-local stay direct so Xray itself doesn't
                // try to route its own management traffic through REALITY.
                addJsonObject {
                    put("type", "field")
                    putJsonArray("ip") {
                        add("127.0.0.0/8")
                        add("::1/128")
                    }
                    put("outboundTag", "direct")
                }
                // Everything else exits through the REALITY outbound. The
                // server-side routing then filters to onion-only egress
                // (see deploy/xray/config.json.template).
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") {
                        add("socks-in")
                    }
                    put("outboundTag", "reality-out")
                }
            }
        }
    }
    return root.toString()
}

/**
 * Wrapper for libXray's `runXrayFromJSON` response shape:
 * `{"success":true,"data":...}` — used by the implementation to decide
 * whether the JNI call succeeded or to surface the error message.
 */
internal data class XrayResponse(val success: Boolean, val data: String?)

/**
 * libXray returns base64-encoded JSON for both request and response on its
 * runXrayFromJSON entry point. The schema is a thin wrapper around the
 * RunXrayFromJSONRequest fields plus a base64 result envelope.
 *
 * The response shape varies by libXray version *and* by code path:
 *   - `{"success": true, "data": null}`               — happy path
 *   - `{"success": false, "data": "error message"}`   — string error
 *   - `{"success": false, "data": {"code":1,"msg":…}} — structured error
 *   - `{"success": false, "error": "EADDRINUSE …"}`   — bind / listen
 *     errors (libXray >= 2024-late takes the inbound failure straight
 *     from the inbound listener and puts it under `error`, leaving
 *     `data` absent; we hit this with the SOCKS port collision on
 *     Vladislav's Tecno МТС test 2026-05-10).
 *
 * Earlier impl looked at `data` only and collapsed structured errors
 * via `toString().trim('"')`. Now we walk the well-known message-bearing
 * fields in order and unwrap each element type explicitly.
 */
internal fun parseXrayResponse(json: String): XrayResponse {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
        ?: return XrayResponse(success = false, data = "malformed response: $json")

    val success = (obj["success"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.booleanOrNull == true

    // libXray names the diagnostic field differently across versions /
    // failure paths — `data` for happy-path output and structured errors,
    // `error` for inbound bind/listen failures. Walking both means we
    // surface the real reason instead of "no detail".
    val data: String? = listOf("data", "error", "msg", "message")
        .firstNotNullOfOrNull { key -> extractDiagnosticString(obj[key]) }
    return XrayResponse(success = success, data = data)
}

private fun extractDiagnosticString(element: kotlinx.serialization.json.JsonElement?): String? =
    when (element) {
        null -> null
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive ->
            // Strings come through with quotes from .toString(); contentOrNull
            // gives us the raw value. Numbers / bools are stringified verbatim.
            element.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
        else -> element.toString().takeIf { it.isNotEmpty() && it != "null" }
    }

private val kotlinx.serialization.json.JsonPrimitive.booleanOrNull: Boolean?
    get() = if (isString) null else content.toBooleanStrictOrNull()

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
