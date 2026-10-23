package com.anonh4x.lagswitch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DelayQueueTest {
    @Test fun queuedItemWaitsAtLeastConfiguredDelay() = runBlocking {
        val queue = DelayQueue<String>(2, { 120L })
        val start = System.nanoTime()
        queue.offer("packet")
        queue.close()
        assertEquals("packet", queue.take())
        assertTrue("item released too early", (System.nanoTime() - start) / 1_000_000L >= 100L)
        assertNull(queue.take())
    }
    @Test fun zeroDelayPassesThrough() = runBlocking {
        val queue = DelayQueue<String>(1, { 0L })
        val start = System.nanoTime()
        queue.offer("packet")
        queue.close()
        assertEquals("packet", queue.take())
        assertTrue("unexpected wait", (System.nanoTime() - start) / 1_000_000L < 100L)
    }
    @Test fun boundedQueueBackpressuresAndPreservesOrder() = runBlocking {
        val queue = DelayQueue<String>(1, { 0L })
        val finished = CompletableDeferred<Unit>()
        val producer = launch {
            queue.offer("first")
            queue.offer("second")
            finished.complete(Unit)
        }
        yield()
        assertFalse("one-slot queue should block its second offer", finished.isCompleted)
        assertEquals("first", queue.take())
        withTimeout(1_000L) { finished.await() }
        queue.close()
        assertEquals("second", queue.take())
        assertNull(queue.take())
        producer.join()
    }
}
