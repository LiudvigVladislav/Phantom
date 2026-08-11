// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

/**
 * Direct WSS Yota-First diagnostic — in-memory pin store, main-source-set,
 * present in every APK variant.
 *
 * Reader lives here (in `src/main/`) so production transport code
 * (`HybridRelayTransport`) can consult the guard without a compile-time
 * dependency on debug-only classes.
 *
 * Writer lives ONLY in `src/debug/` (`DiagnosticCommandReceiver`) — the
 * mutation is invoked exclusively by that receiver, which is declared
 * only in the debug `AndroidManifest.xml` overlay. In a release build the
 * receiver is physically absent from the merged manifest, so the guard
 * never receives a broadcast and `current()` returns [PinState.NONE_UNSET]
 * for the lifetime of the process. This is the load-bearing safety
 * property behind §11 lock 1.
 *
 * Contract sheet:
 * `docs/tracks/direct-wss/direct-wss-yota-contract.md` §§4, 6, 11.
 */
object DiagnosticTransportGuard {

    /**
     * Debug-only enum of pin modes read by [HybridRelayTransport.send].
     * `NONE` = production behaviour (state-machine picks WSS or REST).
     * `WSS` / `REST` = fail-closed pins per §6.
     */
    enum class Pin { NONE, WSS, REST }

    /**
     * Stable device role baked into the diagnostic APK variant. The
     * value is not sender/recipient — it is `phone` or `emulator`,
     * fixed for the device from install time (see §4 REDLINE-2 P0-3
     * fix). Set once by the debug receiver at bootstrap; NEVER
     * mutated per cell.
     */
    enum class EmitterId { UNSET, PHONE, EMULATOR }

    data class PinState(
        val pin: Pin,
        val runId: String,
        val cellId: String,
    ) {
        companion object {
            val NONE_UNSET: PinState = PinState(Pin.NONE, runId = "", cellId = "")
        }
    }

    @Volatile
    private var pinState: PinState = PinState.NONE_UNSET

    @Volatile
    private var emitterId: EmitterId = EmitterId.UNSET

    /**
     * Populated by debug boot init with a lambda reading
     * `AppContainer.transportPreferences.privacyMode`. HRT calls it
     * to observe the actually-selected outer transport arm at send
     * time — this is the §12 P0-3 fix. In release the reader is
     * never installed and the field stays null → HRT logs
     * `outer_transport=unknown`.
     *
     * Returns one of `"direct"`, `"reality"`, `"tor"` — never
     * throws.
     */
    @Volatile
    var outerArmReader: (() -> String)? = null

    fun current(): PinState = pinState

    fun currentEmitterId(): EmitterId = emitterId

    fun currentOuterArm(): String = outerArmReader?.invoke() ?: "unknown"

    /**
     * Only invoked by [DiagnosticCommandReceiver] in the debug source
     * set. If a release build ever reaches this method, no receiver
     * exists to call it, so it stays inert.
     */
    fun set(newState: PinState) {
        pinState = newState
    }

    fun setEmitterId(id: EmitterId) {
        emitterId = id
    }

    /**
     * Reset both fields — used only by focused tests to avoid state
     * leakage across tests in the same JVM.
     */
    internal fun reset() {
        pinState = PinState.NONE_UNSET
        emitterId = EmitterId.UNSET
        outerArmReader = null
    }
}
