package io.ensomi.android.chart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PreparationQueueTest {
    @Test fun yieldsImmediatelyToAChartAndRetriesOnlyAfterTheChartFinishes() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val started = Channel<String>(Channel.UNLIMITED)
            val releases = Channel<Boolean>(Channel.UNLIMITED)
            val stopped = mutableListOf<String>()
            val queue = PreparationQueue<String>(scope, prepare = {
                started.send(it)
                releases.receive()
            }, stop = { stopped += it })
            queue.update(listOf("a", "b"))
            queue.allow(true)
            assertEquals("a", withTimeout(1000) { started.receive() })
            queue.allow(false) // A chart takes the worker; cancellation must be signalled synchronously.
            assertEquals(listOf("a"), stopped)
            releases.send(false)
            yield()
            assertTrue(started.tryReceive().isFailure)
            queue.allow(true) // The chart finished.
            assertEquals("a", withTimeout(1000) { started.receive() })
            releases.send(true)
            assertEquals("b", withTimeout(1000) { started.receive() })
            releases.send(true)
            yield()
            assertTrue(started.tryReceive().isFailure)
        } finally { scope.cancel() }
    }

    @Test fun staysQuietForPlayAndPreparesNewLibrarySongsOnce() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val started = Channel<String>(Channel.UNLIMITED)
            val done = CompletableDeferred<Boolean>()
            val queue = PreparationQueue<String>(scope, prepare = { started.send(it); done.await() }, stop = {})
            queue.update(listOf("a"))
            yield()
            assertTrue(started.tryReceive().isFailure)
            queue.allow(true)
            assertEquals("a", withTimeout(1000) { started.receive() })
            queue.allow(false)
            queue.update(listOf("a", "b"))
            done.complete(true)
            yield()
            assertTrue(started.tryReceive().isFailure)
            queue.allow(true)
            assertEquals("b", withTimeout(1000) { started.receive() })
            yield()
            queue.update(listOf("a", "b"))
            yield()
            assertTrue(started.tryReceive().isFailure)
        } finally { scope.cancel() }
    }
}
