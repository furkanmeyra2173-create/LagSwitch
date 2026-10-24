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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** gVisor translates TUN packets into flows; protected sockets relay them upstream. */
internal class NetstackEngine(
    private val vpnService: VpnService,
    private val delayEnabled: () -> Boolean,
    private val configuredUploadDelayMs: () -> Long,
    private val configuredDownloadDelayMs: () -> Long,
    private val configuredJitterMs: () -> Long,
    private val configuredLossPercent: () -> Int,
    private val configuredBurstLossPercent: () -> Int,
    private val configuredDuplicationPercent: () -> Int,
    private val configuredCorruptionPercent: () -> Int,
    private val configuredReorderPercent: () -> Int,
    private val configuredBandwidthKbps: () -> Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLimiter = BandwidthLimiter()
    private val downloadLimiter = BandwidthLimiter()
    private val stopped = AtomicBoolean(false)
    private val activeFlows = AtomicInteger(0)
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
        val count = activeFlows.incrementAndGet()
        if (count > MAX_ACTIVE_FLOWS) {
            activeFlows.decrementAndGet()
            closeOnReject()
            Log.w(TAG, "Rejecting ${label}: active-flow safety limit reached")
            return
        }
        val flow = scope.launch {
            try { block() }
            catch (t: Throwable) {
                if (t !is CancellationException && !stopped.get()) Log.w(TAG, "${label} failed: ${t.message}")
            }
        }
        flow.invokeOnCompletion { cause ->
            activeFlows.decrementAndGet()
            if (cause != null || stopped.get()) runCatching { closeOnReject() }
        }
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
                            delayTraffic = !bypassDelay, baseDelayMs = configuredUploadDelayMs, limiter = uploadLimiter,
                            bandwidthKbps = configuredBandwidthKbps, abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                val download = launch(Dispatchers.IO) {
                    try {
                        pipeBytes(
                            read = { buffer -> input.read(buffer) },
                            write = { bytes -> appSide.write(bytes) },
                            delayTraffic = !bypassDelay, baseDelayMs = configuredDownloadDelayMs, limiter = downloadLimiter,
                            bandwidthKbps = configuredBandwidthKbps, abortFlow = ::closeFlow,
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
            val receiveBuffer = ByteArray(MAX_UDP_PAYLOAD)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            coroutineScope {
                val upload = launch(Dispatchers.IO) {
                    try {
                        pipeDatagrams(
                            read = { appSide.receive() },
                            write = { bytes -> socket.send(DatagramPacket(bytes, bytes.size)) },
                            delayTraffic = !bypassDelay, baseDelayMs = configuredUploadDelayMs, limiter = uploadLimiter,
                            lossPercent = configuredLossPercent, burstLossPercent = configuredBurstLossPercent,
                            duplicationPercent = configuredDuplicationPercent, corruptionPercent = configuredCorruptionPercent,
                            reorderPercent = configuredReorderPercent, bandwidthKbps = configuredBandwidthKbps,
                            abortFlow = ::closeFlow,
                        )
                    } finally { closeFlow() }
                }
                val download = launch(Dispatchers.IO) {
                    try {
                        pipeDatagrams(
                            read = {
                                receivePacket.length = receiveBuffer.size
                                socket.receive(receivePacket)
                                receiveBuffer.copyOf(receivePacket.length)
                            },
                            write = { bytes -> appSide.send(bytes) },
                            delayTraffic = !bypassDelay, baseDelayMs = configuredDownloadDelayMs, limiter = downloadLimiter,
                            lossPercent = configuredLossPercent, burstLossPercent = configuredBurstLossPercent,
                            duplicationPercent = configuredDuplicationPercent, corruptionPercent = configuredCorruptionPercent,
                            reorderPercent = configuredReorderPercent, bandwidthKbps = configuredBandwidthKbps,
                            abortFlow = ::closeFlow,
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

    private fun sampleDelayMs(baseDelayMs: () -> Long): Long {
        val base = baseDelayMs().coerceIn(0L, 3000L)
        val jitter = configuredJitterMs().coerceIn(0L, 1000L)
        if (jitter == 0L) return base
        val offset = Random.nextLong(-jitter, jitter + 1L)
        return (base + offset).coerceIn(0L, 3000L)
    }

    private suspend fun pipeBytes(
        read: (ByteArray) -> Int, write: (ByteArray) -> Unit,
        delayTraffic: Boolean, baseDelayMs: () -> Long, limiter: BandwidthLimiter,
        bandwidthKbps: () -> Int, abortFlow: () -> Unit,
    ) = coroutineScope {
        val queue = DelayQueue<ByteArray>(TCP_QUEUE_ITEMS, { if (delayTraffic && delayEnabled()) sampleDelayMs(baseDelayMs) else 0L })
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
                if (delayTraffic && delayEnabled()) limiter.pace(bytes.size, bandwidthKbps())
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
        delayTraffic: Boolean, baseDelayMs: () -> Long, limiter: BandwidthLimiter,
        lossPercent: () -> Int, burstLossPercent: () -> Int,
        duplicationPercent: () -> Int, corruptionPercent: () -> Int, reorderPercent: () -> Int,
        bandwidthKbps: () -> Int, abortFlow: () -> Unit,
    ) = coroutineScope {
        val queue = DelayQueue<ByteArray>(UDP_QUEUE_ITEMS, { if (delayTraffic && delayEnabled()) sampleDelayMs(baseDelayMs) else 0L })
        val producer = launch(Dispatchers.IO) {
            var burstRemaining = 0
            var heldForReorder: ByteArray? = null

            suspend fun enqueuePossiblyReordered(packet: ByteArray) {
                val pending = heldForReorder
                val reorderChance = if (delayTraffic && delayEnabled()) reorderPercent().coerceIn(0, 100) else 0
                if (pending != null) {
                    if (reorderChance > 0 && Random.nextInt(100) < reorderChance) {
                        queue.offer(packet)
                        queue.offer(pending)
                    } else {
                        queue.offer(pending)
                        queue.offer(packet)
                    }
                    heldForReorder = null
                } else if (reorderChance > 0 && Random.nextInt(100) < reorderChance) {
                    heldForReorder = packet
                } else {
                    queue.offer(packet)
                }
            }

            try {
                while (isActive) {
                    val incoming = read() ?: break
                    val impair = delayTraffic && delayEnabled()
                    if (impair) {
                        if (burstRemaining > 0) {
                            burstRemaining--
                            continue
                        }
                        val loss = lossPercent().coerceIn(0, 100)
                        if (loss > 0 && Random.nextInt(100) < loss) continue

                        val burstChance = burstLossPercent().coerceIn(0, 100)
                        if (burstChance > 0 && Random.nextInt(100) < burstChance) {
                            // Drop this datagram and a short follow-up burst to simulate a bad radio link.
                            burstRemaining = Random.nextInt(1, 5)
                            continue
                        }
                    }

                    var outgoing = incoming
                    val corruptionChance = if (impair) corruptionPercent().coerceIn(0, 100) else 0
                    if (outgoing.isNotEmpty() && corruptionChance > 0 && Random.nextInt(100) < corruptionChance) {
                        outgoing = outgoing.copyOf()
                        val index = Random.nextInt(outgoing.size)
                        outgoing[index] = (outgoing[index].toInt() xor (1 shl Random.nextInt(8))).toByte()
                    }
                    enqueuePossiblyReordered(outgoing)

                    val duplicateChance = if (impair) duplicationPercent().coerceIn(0, 100) else 0
                    if (duplicateChance > 0 && Random.nextInt(100) < duplicateChance) {
                        enqueuePossiblyReordered(outgoing.copyOf())
                    }
                }
            } finally {
                val pending = heldForReorder
                heldForReorder = null
                try {
                    if (pending != null && isActive) queue.offer(pending)
                } finally {
                    queue.close()
                }
            }
        }
        var drainedNormally = false
        try {
            while (true) {
                val packet = queue.take() ?: break
                if (delayTraffic && delayEnabled()) limiter.pace(packet.size, bandwidthKbps())
                write(packet)
            }
            drainedNormally = true
        } finally {
            if (!drainedNormally) abortFlow()
            if (!producer.isCompleted) { producer.cancel(); queue.cancel() }
        }
        producer.join()
    }

    /** Rate limiter shared by all flows in one direction for an aggregate bandwidth cap. */
    private class BandwidthLimiter {
        private val mutex = Mutex()
        private var nextAvailableNanos = 0L

        suspend fun pace(byteCount: Int, kbps: Int) {
            if (byteCount <= 0) return
            if (kbps <= 0) {
                mutex.withLock { nextAvailableNanos = 0L }
                return
            }
            val waitNanos = mutex.withLock {
                val now = System.nanoTime()
                val sendAt = maxOf(now, nextAvailableNanos)
                // kbps is kilobits/second; reserve serialization time for each outgoing chunk.
                val transmitNanos = (byteCount.toDouble() * 8_000_000.0 / kbps.toDouble())
                    .toLong().coerceAtLeast(1L)
                nextAvailableNanos = sendAt + transmitNanos
                (sendAt - now).coerceAtLeast(0L)
            }
            if (waitNanos > 0L) delay((waitNanos + 999_999L) / 1_000_000L)
        }
    }

    companion object {
        private const val TAG = "LagSwitchNetstack"
        private const val MTU = 1500
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TCP_CHUNK_SIZE = 8 * 1024
        private const val TCP_QUEUE_ITEMS = 16
        private const val UDP_QUEUE_ITEMS = 8
        private const val MAX_UDP_PAYLOAD = 65_535
        private const val MAX_ACTIVE_FLOWS = 256
    }
}
