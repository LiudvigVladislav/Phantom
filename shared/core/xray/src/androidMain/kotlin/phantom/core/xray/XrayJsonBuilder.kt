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
 * The "log: loglevel: warning" level is deliberate: Xray's `info` level
 * leaks per-connection peer addresses to logcat, which we don't want in
 * production (privacy of the user's contact graph). `warning` keeps the
 * unrecoverable failure messages we care about and drops the rest.
 *
 * Reference template: <https://xtls.github.io/en/config/transports/reality.html>
 */
internal fun buildXrayClientConfig(config: XrayServiceConfig): String {
    val root: JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("loglevel", "warning")
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
 */
internal fun parseXrayResponse(json: String): XrayResponse {
    // Parse minimally — we only need success + an optional message string.
    // Avoid pulling in the kotlinx-serialization plugin processor for this
    // one-shot decode; manual JsonObject access is sufficient.
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
        ?: return XrayResponse(success = false, data = "malformed response: $json")
    val successElement = obj["success"]
    val success = successElement?.toString() == "true"
    val data = obj["data"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
    return XrayResponse(success = success, data = data)
}
