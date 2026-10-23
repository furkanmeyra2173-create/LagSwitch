package com.anonh4x.lagswitch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Bounded FIFO. Deadlines are assigned only after a send enters the channel. */
internal class DelayQueue<T>(
    capacity: Int,
    private val delayMillis: () -> Long,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<T>(val value: T, val deadline: CompletableDeferred<Long>)
    private val channel = Channel<Entry<T>>(capacity)
    init { require(capacity > 0) }

    suspend fun offer(value: T) {
        val entry = Entry(value, CompletableDeferred())
        channel.send(entry)
        entry.deadline.complete(clockMillis() + delayMillis().coerceAtLeast(0L))
    }

    /** Returns null after close and after accepted entries are drained. */
    suspend fun take(): T? {
        val entry = channel.receiveCatching().getOrNull() ?: return null
        val remaining = entry.deadline.await() - clockMillis()
        if (remaining > 0L) delay(remaining)
        return entry.value
    }

    fun close() = channel.close()
    fun cancel() = channel.cancel()
}
