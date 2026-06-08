// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.libxray.test

/**
 * RC-LIBXRAY-REALITY-WIRE1 (Trek 1) — variant catalogue.
 *
 * The Trek 1 mini-lock defines four variants in order:
 *   1. baseline       — `flow=xtls-rprx-vision`, `network=tcp`
 *   2. drop-vision    — `flow=""`              , `network=tcp`
 *   3. xhttp          — `flow=""`              , `network=xhttp`
 *   4. httpupgrade    — `flow=""`              , `network=httpupgrade`
 *
 * Per the first-test-order gate, only Variant 1 (baseline) is wired in
 * this commit. Variants 2-4 are intentionally absent so that
 * `Wire1Runner.toXrayServiceConfig` raises if `--es variant <slug>` is
 * passed for an un-implemented slug — preventing a misconfigured run
 * from silently producing evidence under the wrong config shape.
 *
 * The decisive next step is Variant 2 (`drop-vision`). It will be added
 * in a follow-up commit on this branch ONLY after baseline reproduces
 * the single-segment stall pattern on this standalone APK. See
 * `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`
 * first-test-order section for the rationale.
 */
internal data class Wire1Variant(
    val slug: String,
    val flow: String,
    val network: String,
    val description: String,
)

internal object Wire1Variants {

    private val all = listOf(
        Wire1Variant(
            slug = "baseline",
            flow = "xtls-rprx-vision",
            network = "tcp",
            description = "Production-equivalent Reality: XTLS-Vision + raw TCP",
        ),
        // Variants 2-4 are intentionally NOT listed here. The Trek 1
        // mini-lock first-test-order gate requires baseline reproduction
        // before any of them are added. See the runner's
        // `toXrayServiceConfig` for the fail-fast guard that enforces
        // this gate.
    )

    fun bySlug(slug: String): Wire1Variant? = all.firstOrNull { it.slug == slug }
}
