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
    val description: String,
)

internal object Wire1Variants {

    private val all = listOf(
        Wire1Variant(
            slug = "baseline",
            flow = "xtls-rprx-vision",
            network = "tcp",
            serverPort = 8443,
            description = "Production-equivalent Reality: XTLS-Vision + raw TCP",
        ),
        Wire1Variant(
            slug = "drop-vision",
            flow = "",
            network = "tcp",
            serverPort = 8444,
            description =
                "Plain VLESS without XTLS-Vision over raw TCP via diagnostic " +
                ":8444 inbound. Splice-race discriminator: removing Vision " +
                "removes the CopyRawConnIfExist zero-copy path that is the " +
                "leading hypothesis for the multi-segment write stall.",
        ),
        // Variants 3 (`xhttp`) and 4 (`httpupgrade`) are added in
        // subsequent commits ONLY after Variant 2 field result is in.
        // The runner's `toXrayServiceConfig` fails fast for un-listed
        // slugs to prevent a misconfigured run from silently producing
        // evidence under the wrong config shape.
    )

    fun bySlug(slug: String): Wire1Variant? = all.firstOrNull { it.slug == slug }
}
