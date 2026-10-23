package com.anonh4x.lagswitch

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Bounded FIFO. Each deadline is measured when data arrives, before queue backpressure. */
internal class DelayQueue<T>(
    capacity: Int,
    private val delayMillis: () -> Long,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<T>(val value: T, val arrivedAtMillis: Long, val delayAtArrivalMillis: Long)
    private val channel = Channel<Entry<T>>(capacity)
    init { require(capacity > 0) }

    suspend fun offer(value: T) {
        // Timestamp arrival before send can suspend because the bounded queue is full.
        // This maintains a fixed delay instead of adding another second under load.
        val entry = Entry(value, clockMillis(), delayMillis().coerceAtLeast(0L))
        channel.send(entry)
    }

    /** Returns null after close and after accepted entries are drained. */
    suspend fun take(): T? {
        val entry = channel.receiveCatching().getOrNull() ?: return null
        // Turning lag off releases already-queued packets promptly instead of waiting out
        // the old deadline. Check periodically so a long configured delay is interruptible.
        while (entry.delayAtArrivalMillis > 0L && delayMillis() > 0L) {
            val remaining = entry.arrivedAtMillis + entry.delayAtArrivalMillis - clockMillis()
            if (remaining <= 0L) break
            delay(minOf(remaining, 50L))
        }
        return entry.value
    }

    fun close() = channel.close()
    fun cancel() = channel.cancel()
}
