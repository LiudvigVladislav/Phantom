// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.libxray.test

/**
 * RC-LIBXRAY-REALITY-WIRE1 (Trek 1) — variant catalogue.
 *
 * The Trek 1 mini-lock defines four variants in order:
 *   1. baseline       — `flow=xtls-rprx-vision`, `network=tcp`, `:8443`
 *   2. drop-vision    — `flow=""`              , `network=tcp`, `:8444` diagnostic inbound
 *   3. xhttp          — `flow=""`              , `network=xhttp`     (added after Variant 2)
 *   4. httpupgrade    — `flow=""`              , `network=httpupgrade` (added after Variant 3)
 *
 * Variant 2 (`drop-vision`) is added in this commit after the standalone
 * Variant 1 (`baseline`) field run on 2026-06-09 reproduced the Arm G
 * v10/v11 wire-stall in 50/50 sessions (every flow: single 1440-byte
 * client segment, TLS record header declaring 1722-1818 bytes, missing
 * 287-383 bytes continuation, server FIN seq=1 ack=1441 after ~20 s).
 *
 * The architectural reasoning behind Variant 2 follows the claude.md
 * analysis: if the stall is the XTLS-Vision splice-handoff race
 * documented in Xray-core issue #4878, dropping the `xtls-rprx-vision`
 * flow forces libXray's outbound onto the non-Vision write path, which
 * does NOT take the `CopyRawConnIfExist` zero-copy splice. If
 * `drop-vision` then passes /health reliably with multi-segment
 * ClientHello on the wire, the splice-race hypothesis is materially
 * confirmed and the production fix path is either to drop Vision on the
 * embedded Android client or switch the Reality stream transport off
 * raw TCP (Variants 3-4). If `drop-vision` STILL shows the same
 * single-segment stall, Vision is not sufficient — the bug is deeper
 * in libXray's gomobile-bound write path and Variants 3-4 (HTTP-framed
 * transports) become the next discriminator.
 *
 * Variants 3-4 remain absent from this commit. They are added only
 * after Variant 2's field result is in and analyzed.
 */
internal data class Wire1Variant(
    val slug: String,
    val flow: String,
    val network: String,
    /**
     * Server-side Reality endpoint port for this variant.
     *
     * `baseline` uses production `:8443` because its client config is
     * byte-for-byte equivalent to production (`flow=xtls-rprx-vision`,
     * `network=tcp`) and the production server's existing user already
     * accepts it. Variants that change `flow` or `network` away from
     * production MUST point at a separate diagnostic inbound on a
     * different port (Arm A.2 pattern) so production `:8443` is never
     * touched — per the Trek 1 mini-lock Hard Gate 3.
     */
    val serverPort: Int,
    /**
     * HTTP path used by `xhttp` / `httpupgrade` variants. Empty for
     * `tcp` variants. Must match the diagnostic Reality inbound's
     * `xhttpSettings.path` (server-side template renders the exact same
     * constant via `deploy/xray-wire1-test/config.json.template`).
     */
    val xhttpPath: String,
    val description: String,
)

internal object Wire1Variants {

    /**
     * Path constant shared between the Android client (here) and the
     * server-side diagnostic Reality inbound (see
     * `deploy/xray-wire1-test/config.json.template`). Any change to
     * this constant MUST be paired with a server-side template re-
     * render or the xhttp handshake fails at the routing layer with no
     * relation to the wire-stall discriminator we are testing.
     */
    private const val WIRE1_XHTTP_PATH = "/wire1-xhttp-test"

    private val all = listOf(
        Wire1Variant(
            slug = "baseline",
            flow = "xtls-rprx-vision",
            network = "tcp",
            serverPort = 8443,
            xhttpPath = "",
            description = "Production-equivalent Reality: XTLS-Vision + raw TCP",
        ),
        Wire1Variant(
            slug = "drop-vision",
            flow = "",
            network = "tcp",
            serverPort = 8444,
            xhttpPath = "",
            description =
                "Plain VLESS without XTLS-Vision over raw TCP via diagnostic " +
                ":8444 inbound. Splice-race discriminator: removing Vision " +
                "removes the CopyRawConnIfExist zero-copy path that is the " +
                "leading hypothesis for the multi-segment write stall.",
        ),
        Wire1Variant(
            slug = "drop-vision-xhttp",
            flow = "",
            network = "xhttp",
            serverPort = 8445,
            xhttpPath = WIRE1_XHTTP_PATH,
            description =
                "Plain VLESS without XTLS-Vision over xhttp (HTTP-framed " +
                "stream) via diagnostic :8445 inbound. Stream-transport " +
                "discriminator after the 2026-06-09 Variant 2 result " +
                "showed that removing Vision alone did NOT remove the " +
                "single-segment incomplete-ClientHello stall (50/50 fail, " +
                "byte-identical wire signature to Variant 1 baseline). If " +
                "this variant PASSES, the multi-segment raw-TCP write " +
                "completion path itself is the bug and the production fix " +
                "is to switch Reality stream transport to xhttp. If this " +
                "variant FAILS with the same one-segment shape, the bug " +
                "is upstream of the stream transport layer entirely.",
        ),
        // Variant 4 (`httpupgrade`) is intentionally NOT batched with
        // Variant 3 because per the official Xray documentation
        // `realitySettings` is valid with `raw` / `xhttp` / `grpc` but
        // NOT `httpupgrade`. Adding `httpupgrade` blindly as a Reality
        // variant would produce an ambiguous FAIL — we could not tell
        // whether the wire stall remained because the splice/write
        // hypothesis still holds OR because the Xray runtime rejected
        // the config combination silently. V4 is reserved for a future
        // commit ONLY after a config-validation step (e.g. `xray run
        // -test`) confirms the running Xray version accepts the
        // combination, OR it is reframed as a non-Reality control
        // (e.g. `security=tls` with httpupgrade).
    )

    fun bySlug(slug: String): Wire1Variant? = all.firstOrNull { it.slug == slug }
}
