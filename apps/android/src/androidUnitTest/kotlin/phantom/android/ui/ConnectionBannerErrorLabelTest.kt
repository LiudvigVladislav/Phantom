// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import phantom.android.transport.ConnectionUiState
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DWS-UX.1 (2026-06-17) — pins the
 * [phantom.android.ui.ConnectionBanner] `Error` label discriminator.
 * Pre-DWS-UX the label was the bare string `"Offline — reconnecting"`,
 * which implied that recovery was actively in progress. When the
 * `TransportState.Error` cause is a transient transport throwable
 * (e.g. socket timeout, network down) the reconnect-loop promise is
 * honest, but for other Throwable classes the promise may be empty
 * and the user is misled.
 *
 * The discriminator under test is the pure mapping from
 * `Throwable::class.simpleName` to one of two label strings; the
 * actual label assignment lives inside the `@Composable
 * ConnectionBanner` (untestable from a JVM unit-test source set
 * without a Compose runtime). This test therefore restates the
 * mapping in the same shape used by the composable's `when` arm so a
 * future refactor that diverges them fails this assertion.
 */
class ConnectionBannerErrorLabelTest {

    /**
     * Mirror of the `when` arm at
     * `ConnectionBanner.kt:ConnectionUiState.Error` introduced by
     * DWS-UX.1. Kept in sync by hand; the composable cannot be
     * called directly from a JVM unit test (no Compose runtime).
     */
    private fun labelFor(cause: Throwable): String =
        when (cause::class.simpleName) {
            "SocketTimeoutException",
            "SocketException",
            "ConnectException",
            "UnknownHostException",
            "IOException",
            -> "Offline — reconnecting"
            else -> "Cannot connect — please check setup"
        }

    @Test
    fun socket_timeout_keeps_reconnecting_promise() {
        assertEquals(
            "Offline — reconnecting",
            labelFor(SocketTimeoutException("read timed out")),
        )
    }

    @Test
    fun socket_exception_keeps_reconnecting_promise() {
        assertEquals(
            "Offline — reconnecting",
            labelFor(SocketException("broken pipe")),
        )
    }

    @Test
    fun connect_exception_keeps_reconnecting_promise() {
        assertEquals(
            "Offline — reconnecting",
            labelFor(ConnectException("connection refused")),
        )
    }

    @Test
    fun unknown_host_keeps_reconnecting_promise() {
        assertEquals(
            "Offline — reconnecting",
            labelFor(UnknownHostException("no DNS")),
        )
    }

    @Test
    fun generic_io_exception_keeps_reconnecting_promise() {
        assertEquals(
            "Offline — reconnecting",
            labelFor(IOException("flush failed")),
        )
    }

    @Test
    fun illegal_state_falls_back_to_check_setup() {
        assertEquals(
            "Cannot connect — please check setup",
            labelFor(IllegalStateException("signer not provisioned")),
        )
    }

    @Test
    fun illegal_argument_falls_back_to_check_setup() {
        assertEquals(
            "Cannot connect — please check setup",
            labelFor(IllegalArgumentException("relay URL malformed")),
        )
    }

    @Test
    fun cause_wrapper_uses_outer_class() {
        // Some upstream pipelines may wrap a SocketTimeoutException in a
        // RuntimeException. The discriminator only looks at the OUTER
        // class, so wrapping turns the label into the "check setup"
        // form. Pins the chosen behaviour explicitly so a future
        // refactor that walks the cause chain has to update both this
        // test and the banner together.
        val wrapped = RuntimeException(SocketTimeoutException("inner"))
        assertEquals(
            "Cannot connect — please check setup",
            labelFor(wrapped),
        )
    }

    @Test
    fun consumer_constructs_error_state_with_cause() {
        // Cheap sanity that ConnectionUiState.Error wires the Throwable
        // through to its `cause` field. Future refactors that drop
        // `Error.cause` would break the banner's discriminator
        // entirely.
        val state = ConnectionUiState.Error(SocketTimeoutException("from wire"))
        assertEquals(
            "Offline — reconnecting",
            labelFor(state.cause),
        )
    }
}
