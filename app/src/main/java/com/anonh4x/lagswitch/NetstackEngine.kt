package com.anonh4x.lagswitch

import android.net.VpnService
import android.util.Log
import com.anonh4x.bridge.Bridge
import com.anonh4x.bridge.Handler
import com.anonh4x.bridge.TCPConn
import com.anonh4x.bridge.Tunnel
import com.anonh4x.bridge.UDPConn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** gVisor translates TUN packets into flows; protected sockets relay them upstream. */
internal class NetstackEngine(
    private val vpnService: VpnService,
    private val delayEnabled: () -> Boolean,
    private val configuredDelayMs: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val closeActions = ConcurrentHashMap.newKeySet<() -> Unit>()
    @Volatile private var tunnel: Tunnel? = null

    fun start(tunFd: Int) {
        check(tunnel == null) { "netstack already started" }
        val handler = object : Handler {
            override fun handleTCP(srcIp: String, srcPort: Long, dstIp: String, dstPort: Long, conn: TCPConn) {
                val label = "TCP ${srcIp}:${srcPort} -> ${dstIp}:${dstPort}"
                launchFlow(label, { runCatching { conn.close() } }) { relayTcp(dstIp, dstPort.toInt(), conn, label) }
            }
            override fun handleUDP(srcIp: String, srcPort: Long, dstIp: String, dstPort: Long, conn: UDPConn) {
                val label = "UDP ${srcIp}:${srcPort} -> ${dstIp}:${dstPort}"
                launchFlow(label, { runCatching { conn.close() } }) { relayUdp(dstIp, dstPort.toInt(), conn, label) }
            }
            override fun log(level: Long, msg: String) {
                when (level.toInt()) {
                    0 -> Log.d(TAG, msg)
                    1 -> Log.i(TAG, msg)
                    2 -> Log.w(TAG, msg)
                    else -> Log.e(TAG, msg)
                }
            }
        }
        // LagVpnService owns and closes this FD if startup fails or when stopped.
        tunnel = Bridge.newTunnel(tunFd.toLong(), MTU.toLong(), "RELAY", handler)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        closeActions.toList().forEach { action -> runCatching { action() } }
        scope.cancel()
        runCatching { tunnel?.stop() }
        tunnel = null
    }

    private fun launchFlow(label: String, closeOnReject: () -> Unit, block: suspend () -> Unit) {
        if (stopped.get()) { closeOnReject(); return }
        val flow = scope.launch {
            try { block() }
            catch (t: Throwable) {
                if (t !is CancellationException && !stopped.get()) Log.w(TAG, "${label} failed: ${t.message}")
            }
        }
        flow.invokeOnCompletion { cause -> if (cause != null || stopped.get()) runCatching { closeOnReject() } }
    }

    private suspend fun relayTcp(dstIp: String, dstPort: Int, appSide: TCPConn, label: String) {
        val closeApp: () -> Unit = { runCatching { appSide.close() }; Unit }
        closeActions.add(closeApp)
        val socket = Socket()
        val closeSocket: () -> Unit = { runCatching { socket.close() }; Unit }
        closeActions.add(closeSocket)
        val closed = AtomicBoolean(false)
        fun closeFlow() {
            if (closed.compareAndSet(false, true)) { closeSocket(); closeApp() }
        }
        try {
            if (!vpnService.protect(socket)) throw IOException("VpnService.protect(Socket) failed")
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), CONNECT_TIMEOUT_MS)
            val bypassDelay = dstPort == 53 || dstPort == 853
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            coroutineScope {
                val upload = launch(Dispatchers.IO) {
                    try {
                        pipeBytes(
                            read = { buffer -> try { appSide.read(buffer).toInt() } catch (_: Exception) { -1 } },
                            write = { bytes -> output.write(bytes); output.flush() },
                            delayTraffic = !bypassDelay, abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                val download = launch(Dispatchers.IO) {
                    try {
                        pipeBytes(
                            read = { buffer -> input.read(buffer) },
                            write = { bytes -> appSide.write(bytes) },
                            delayTraffic = !bypassDelay, abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                joinAll(upload, download)
            }
        } finally {
            closeFlow()
            closeActions.remove(closeApp)
            closeActions.remove(closeSocket)
            Log.d(TAG, "closed ${label}")
        }
    }

    private suspend fun relayUdp(dstIp: String, dstPort: Int, appSide: UDPConn, label: String) {
        val closeApp: () -> Unit = { runCatching { appSide.close() }; Unit }
        closeActions.add(closeApp)
        val socket = DatagramSocket()
        val closeSocket: () -> Unit = { runCatching { socket.close() }; Unit }
        closeActions.add(closeSocket)
        val closed = AtomicBoolean(false)
        fun closeFlow() {
            if (closed.compareAndSet(false, true)) { closeSocket(); closeApp() }
        }
        try {
            if (!vpnService.protect(socket)) throw IOException("VpnService.protect(DatagramSocket) failed")
            socket.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort))
            val bypassDelay = dstPort == 53 || dstPort == 853
            coroutineScope {
                val upload = launch(Dispatchers.IO) {
                    try {
                        pipeDatagrams(
                            read = { appSide.receive() },
                            write = { bytes -> socket.send(DatagramPacket(bytes, bytes.size)) },
                            delayTraffic = !bypassDelay, abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                val download = launch(Dispatchers.IO) {
                    try {
                        pipeDatagrams(
                            read = {
                                val buffer = ByteArray(MAX_UDP_PAYLOAD)
                                val packet = DatagramPacket(buffer, buffer.size)
                                socket.receive(packet)
                                buffer.copyOf(packet.length)
                            },
                            write = { bytes -> appSide.send(bytes) },
                            delayTraffic = !bypassDelay, abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                joinAll(upload, download)
            }
        } finally {
            closeFlow()
            closeActions.remove(closeApp)
            closeActions.remove(closeSocket)
            Log.d(TAG, "closed ${label}")
        }
    }

    private suspend fun pipeBytes(
        read: (ByteArray) -> Int, write: (ByteArray) -> Unit,
        delayTraffic: Boolean, abortFlow: () -> Unit,
    ) = coroutineScope {
        val queue = DelayQueue<ByteArray>(TCP_QUEUE_ITEMS, { if (delayTraffic && delayEnabled()) configuredDelayMs().coerceIn(0L, 3000L) else 0L })
        val producer = launch(Dispatchers.IO) {
            val buffer = ByteArray(TCP_CHUNK_SIZE)
            try {
                while (isActive) {
                    val count = read(buffer)
                    if (count < 0) break
                    if (count > 0) queue.offer(buffer.copyOf(count))
                }
            } finally { queue.close() }
        }
        var drainedNormally = false
        try {
            while (true) {
                val bytes = queue.take() ?: break
                write(bytes)
            }
            drainedNormally = true
        } finally {
            if (!drainedNormally) abortFlow()
            if (!producer.isCompleted) { producer.cancel(); queue.cancel() }
        }
        producer.join()
    }

    private suspend fun pipeDatagrams(
        read: () -> ByteArray?, write: (ByteArray) -> Unit,
        delayTraffic: Boolean, abortFlow: () -> Unit,
    ) = coroutineScope {
        val queue = DelayQueue<ByteArray>(UDP_QUEUE_ITEMS, { if (delayTraffic && delayEnabled()) DELAY_MS else 0L })
        val producer = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val packet = read() ?: break
                    queue.offer(packet.copyOf())
                }
            } finally { queue.close() }
        }
        var drainedNormally = false
        try {
            while (true) {
                val packet = queue.take() ?: break
                write(packet)
            }
            drainedNormally = true
        } finally {
            if (!drainedNormally) abortFlow()
            if (!producer.isCompleted) { producer.cancel(); queue.cancel() }
        }
        producer.join()
    }

    companion object {
        private const val TAG = "LagSwitchNetstack"
        private const val MTU = 1500
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TCP_CHUNK_SIZE = 8 * 1024
        private const val TCP_QUEUE_ITEMS = 16
        private const val UDP_QUEUE_ITEMS = 8
        private const val MAX_UDP_PAYLOAD = 65_535
    }
}
