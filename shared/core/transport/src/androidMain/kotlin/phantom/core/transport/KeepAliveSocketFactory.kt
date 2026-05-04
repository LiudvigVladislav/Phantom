// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

// TCP-layer keepalive defends NAT/CGN/stateful-firewall idle timeouts that
// app-level WebSocket pings cannot reach. Russian carriers (Megafon, MTS,
// Beeline) deploy carrier-grade NAT with idle timeouts in the 50-90 s range;
// when an idle WSS connection traverses such a NAT, the entry is dropped and
// any subsequent packet (including the next ping) gets a TCP RST.
//
// ADR-014 (Transport TCP Keepalive Strategy, 2026-05-04):
// 4-test matrix on MTS WiFi confirmed that VPN tunnels (which themselves
// emit keepalives every 10-25 s) bypass the NAT timeout, while bare WSS
// without any TCP keepalive does not. Layer 2 fix: enable SO_KEEPALIVE
// with parameters tuned tighter than typical NAT timeouts:
//
//   TCP_KEEPIDLE  = 30 s   (start probing after 30 s of TX inactivity)
//   TCP_KEEPINTVL = 10 s   (probe every 10 s if no ACK received)
//   TCP_KEEPCNT   =  3     (give up + emit RST after 3 failed probes)
//
// Total dead-detection window: 30 + 3*10 = 60 s. Aligned with industry
// guidance for cellular WSS.
//
// Implementation notes:
//
// * Android exposes `Socket.setKeepAlive(true)` (== SO_KEEPALIVE) but does
//   NOT expose the three timing constants through the public API. We use
//   `android.system.Os.setsockoptInt` with raw IPPROTO_TCP / TCP_KEEPIDLE /
//   TCP_KEEPINTVL / TCP_KEEPCNT integer constants. These values are
//   ABI-stable in the Linux kernel since 2.4 and used by every NDK
//   networking library.
// * The FileDescriptor backing a Socket is reachable via package-private
//   `getFileDescriptor$()` on AOSP (preferred where available) or via
//   reflection on `impl.fd` (fallback for very old devices). Failures are
//   logged and swallowed: the connection still works without our timing
//   tuning, just less defended against CGN.
// * Socket subclassing on the SocketFactory path is the cleanest hook
//   because OkHttp creates an unconnected Socket then later calls connect().
//   We override connect() so the FD is allocated before we attempt the
//   setsockopt calls.

private const val IPPROTO_TCP = 6
private const val TCP_KEEPIDLE = 4
private const val TCP_KEEPINTVL = 5
private const val TCP_KEEPCNT = 6

private const val KEEPIDLE_SECONDS = 30
private const val KEEPINTVL_SECONDS = 10
private const val KEEPCNT_PROBES = 3

private const val TAG = "PhantomKeepAlive"

internal object KeepAliveTuner {

    fun applyAfterConnect(socket: Socket) {
        runCatching {
            socket.keepAlive = true
            val fd = extractFd(socket) ?: run {
                Log.w(TAG, "FileDescriptor extraction returned null; SO_KEEPALIVE on but timing left at kernel defaults")
                return
            }
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPIDLE, KEEPIDLE_SECONDS)
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPINTVL, KEEPINTVL_SECONDS)
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPCNT, KEEPCNT_PROBES)
            Log.d(
                TAG,
                "TCP keepalive applied: idle=${KEEPIDLE_SECONDS}s intvl=${KEEPINTVL_SECONDS}s probes=$KEEPCNT_PROBES",
            )
        }.onFailure {
            Log.w(TAG, "TCP keepalive setup failed: ${it::class.simpleName}: ${it.message}")
        }
    }

    private fun extractFd(socket: Socket): FileDescriptor? {
        // AOSP-preferred path: Socket.getFileDescriptor$() — package-private
        // method exposed via @hide in AOSP. Reflection finds it.
        runCatching {
            val m = Socket::class.java.getDeclaredMethod("getFileDescriptor\$")
            m.isAccessible = true
            return m.invoke(socket) as? FileDescriptor
        }
        // Fallback: descend into Socket.impl.fd. Works on older AOSP
        // and ART implementations where getFileDescriptor$ is missing.
        return runCatching {
            val implField = Socket::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(socket) ?: return null
            // SocketImpl.fd is protected — walk up to find the field.
            var cls: Class<*>? = impl.javaClass
            while (cls != null) {
                runCatching {
                    val f = cls.getDeclaredField("fd")
                    f.isAccessible = true
                    return f.get(impl) as? FileDescriptor
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()
    }
}

// Socket subclass that tunes TCP keepalive immediately after the underlying
// kernel socket is connected. OkHttp's RealConnection.connectSocket() pulls
// an unconnected Socket from the factory, then calls connect(addr, timeout).
// We override that exact entry point.
private class KeepAliveSocket : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        super.connect(endpoint, timeout)
        KeepAliveTuner.applyAfterConnect(this)
    }
    override fun connect(endpoint: SocketAddress?) {
        super.connect(endpoint)
        KeepAliveTuner.applyAfterConnect(this)
    }
}

// SocketFactory wired into OkHttpClient.Builder.socketFactory(...). For the
// no-arg createSocket() form (the only form OkHttp uses for outbound
// HTTPS/WSS) we return our subclass. The other variants — kept for SPI
// completeness — eagerly create a connected Socket then tune it.
class KeepAliveSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = KeepAliveSocket()

    override fun createSocket(host: String, port: Int): Socket =
        Socket(host, port).also { KeepAliveTuner.applyAfterConnect(it) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = Socket(host, port, localHost, localPort).also { KeepAliveTuner.applyAfterConnect(it) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(host, port).also { KeepAliveTuner.applyAfterConnect(it) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = Socket(address, port, localAddress, localPort).also { KeepAliveTuner.applyAfterConnect(it) }
}
