package org.staacks.alpharemote.camera

import org.junit.Assert.*
import org.junit.Test

class BLEOperationQueueTest {
    private class Request(val name: String)
    private class Timeout(val expire: () -> Unit) {
        var cancelled = false
    }
    private class Fixture {
        val started = mutableListOf<Request>()
        val timeouts = mutableListOf<Timeout>()
        val failures = mutableListOf<String>()
        var accept = true
        var throwOnStart = false
        var idleCount = 0
        val queue = BLEOperationQueue<Request>(
            start = {
                started += it
                if (throwOnStart) throw SecurityException("Permission revoked")
                accept
            },
            scheduleTimeout = { expire ->
                val timeout = Timeout(expire)
                timeouts += timeout
                val cancel: () -> Unit = { timeout.cancelled = true }
                cancel
            },
            onFailure = { failures += it },
            onIdle = { idleCount++ }
        )
        fun complete(request: Request, callback: (Request) -> Unit = {}) = queue.complete({ it === request }, callback)
    }

    @Test fun onlyAcceptedCallbackAdvancesQueue() {
        val f = Fixture()
        val press = Request("press")
        val release = Request("release")
        f.queue.enqueue(press)
        f.queue.enqueue(release)
        assertEquals(listOf(press), f.started)
        f.complete(Request("unrelated callback"))
        assertEquals(listOf(press), f.started)
        f.complete(press)
        assertTrue(f.timeouts.first().cancelled)
        assertEquals(listOf(press, release), f.started)
        f.complete(release)
        assertFalse(f.queue.isBusy)
        assertEquals(1, f.idleCount)
    }

    @Test fun rejectedRequestDoesNotLeaveQueueBusy() {
        val f = Fixture()
        f.accept = false
        f.queue.enqueue(Request("rejected read"))
        assertFalse(f.queue.isBusy)
        assertEquals(1, f.failures.size)
        assertTrue(f.timeouts.isEmpty())
        f.accept = true
        val next = Request("new connection subscription")
        f.queue.enqueue(next)
        f.complete(next)
        assertFalse(f.queue.isBusy)
    }

    @Test fun thrownRequestIsHandledLikeInitiationFailure() {
        val f = Fixture()
        f.throwOnStart = true
        f.queue.enqueue(Request("permission revoked"))
        assertFalse(f.queue.isBusy)
        assertEquals(1, f.failures.size)
    }

    @Test fun timeoutDropsQueuedPressesAndNeverReplaysAnUncertainWrite() {
        val f = Fixture()
        val uncertain = Request("record")
        f.queue.enqueue(uncertain)
        f.queue.enqueue(Request("another shutter press"))
        f.timeouts.single().expire()
        assertEquals(listOf(uncertain), f.started)
        assertEquals(1, f.failures.size)
        assertFalse(f.queue.isBusy)
        f.complete(uncertain)
        assertEquals(listOf(uncertain), f.started)
    }

    @Test fun oldTimeoutCannotFailNewOperation() {
        val f = Fixture()
        val first = Request("old")
        val second = Request("new")
        f.queue.enqueue(first)
        val staleTimeout = f.timeouts.single()
        f.complete(first)
        f.queue.enqueue(second)
        staleTimeout.expire() // Simulate an already-posted timeout after cancellation.
        assertTrue(f.failures.isEmpty())
        assertTrue(f.queue.isBusy)
        f.complete(second)
        assertFalse(f.queue.isBusy)
    }

    @Test fun cancellationDropsUnsentPressesButSerializesSafetyReleaseAfterAcceptedWrite() {
        val f = Fixture()
        val accepted = Request("accepted shutter press")
        val unsent = Request("unsent repeated press")
        val release = Request("safety release")
        f.queue.enqueue(accepted)
        f.queue.enqueue(unsent)
        f.queue.discardPending()
        f.queue.enqueue(release)
        assertEquals(listOf(accepted), f.started)
        f.complete(accepted)
        assertEquals(listOf(accepted, release), f.started)
        f.complete(release)
        assertFalse(f.queue.isBusy)
    }

    @Test fun failedCompletionClearsFollowingRequestsBeforeTheyCanRun() {
        val f = Fixture()
        val subscription = Request("subscribe")
        f.queue.enqueue(subscription)
        f.queue.enqueue(Request("shutter"))
        f.complete(subscription) { f.queue.fail("Subscription failed") }
        assertEquals(listOf(subscription), f.started)
        assertFalse(f.queue.isBusy)
        assertEquals(listOf("Subscription failed"), f.failures)
    }

    @Test fun clearingQueueCancelsDeadlineAndIgnoresOldCallback() {
        val f = Fixture()
        val old = Request("old request")
        f.queue.enqueue(old)
        val deadline = f.timeouts.single()
        f.queue.clear()
        assertTrue(deadline.cancelled)
        val replacement = Request("replacement connection")
        f.queue.enqueue(replacement)
        f.complete(old)
        deadline.expire()
        assertTrue(f.failures.isEmpty())
        assertTrue(f.queue.isBusy)
        f.complete(replacement)
        assertFalse(f.queue.isBusy)
    }

    @Test fun oldTimeoutCannotFailSameRequestObjectAfterQueueReset() {
        val f = Fixture()
        val request = Request("reused request")
        f.queue.enqueue(request)
        val staleTimeout = f.timeouts.single()
        f.queue.clear()
        f.queue.enqueue(request)
        staleTimeout.expire()
        assertTrue(f.failures.isEmpty())
        assertTrue(f.queue.isBusy)
        f.complete(request)
        assertFalse(f.queue.isBusy)
    }

    @Test fun rediscoveryWaitsForAcceptedRequestBeforeStartingNewSetup() {
        val started = mutableListOf<Request>()
        val read = Request("old name read")
        val obsoleteSubscription = Request("old subscription")
        val freshSetup = Request("new setup after service change")
        var rediscover = false
        lateinit var queue: BLEOperationQueue<Request>
        queue = BLEOperationQueue(
            start = { started += it; true },
            scheduleTimeout = { {} },
            onFailure = { fail(it) },
            onIdle = {
                if (rediscover) {
                    rediscover = false
                    queue.enqueue(freshSetup)
                }
            }
        )
        queue.enqueue(read)
        queue.enqueue(obsoleteSubscription)
        rediscover = true
        queue.discardPending()
        assertEquals(listOf(read), started)
        queue.complete({ it === read })
        assertEquals(listOf(read, freshSetup), started)
        queue.complete({ it === freshSetup })
        assertFalse(queue.isBusy)
    }
}
