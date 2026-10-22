package org.staacks.alpharemote.service

import org.junit.Assert.*
import org.junit.Test
import org.staacks.alpharemote.camera.*

class CustomTimerConnectionTest {
    private class Clock {
        private class Task(val at: Long, val action: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        var now = 0L
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action)
            tasks += task
            return { task.cancelled = true }
        }
        fun advanceTo(target: Long) {
            while (true) {
                val task = tasks.filter { it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                if (!task.cancelled) task.action()
            }
            now = target
        }
    }

    @Test fun sixSecondTimerAndHalfSecondHoldKeepHealthyGattConnected() {
        val clock = Clock()
        var disconnects = 0
        val connection = BLEConnectionOwner<Any>({ disconnects++ }, {})
        connection.open { Any() }
        val presence = CameraPresence().apply { nearby = true; connected = true }
        val controls = PendingCameraControls()
        val sent = mutableListOf<Pair<Long, CameraActionStep>>()
        lateinit var queue: BLEOperationQueue<CameraActionStep>
        queue = BLEOperationQueue(
            start = { step ->
                controls.accepted(step)
                sent += clock.now to step
                clock.schedule(20) {
                    queue.complete({ it === step }) { controls.acknowledged(it) }
                }
                true
            },
            scheduleTimeout = { clock.schedule(30_000, it) },
            onFailure = { connection.release() }
        )
        val timer = SequenceSafety.countdownMillis(6f)!!
        val hold = SequenceSafety.countdownMillis(0.5f)!!
        val shutter = CameraActionPreset.SHUTTER.template
        clock.schedule(timer) {
            shutter.press.forEach { queue.enqueue(it) }
            clock.schedule(hold) { shutter.release.forEach { queue.enqueue(it) } }
        }
        clock.schedule(3_000) {
            presence.nearby = false
            if (presence.canDispose) connection.release()
        }
        clock.advanceTo(5_999)
        assertTrue(sent.isEmpty())
        clock.advanceTo(6_499)
        assertEquals(shutter.press, sent.map { it.second })
        clock.advanceTo(6_600)
        assertEquals(shutter.press + shutter.release, sent.map { it.second })
        assertEquals(listOf(6_000L, 6_020L, 6_500L, 6_520L), sent.map { it.first })
        assertTrue(controls.releases().isEmpty())
        assertFalse(queue.isBusy)
        clock.advanceTo(40_000) // Cancelled GATT deadlines must not disconnect later.
        assertEquals(0, disconnects)
        assertNotNull(connection.client)
    }

    @Test fun rejectedButtonDoesNotCloseAnOtherwiseHealthyConnection() {
        var disconnects = 0
        var rejected = 0
        val connection = BLEConnectionOwner<Any>({ disconnects++ }, {})
        connection.open { Any() }
        val queue = BLEOperationQueue<CameraActionStep>(
            start = { false },
            scheduleTimeout = { fail("Rejected requests cannot await callbacks"); {} },
            onFailure = { connection.release() },
            onRejected = { _, _ -> rejected++ }
        )
        assertFalse(queue.enqueue(CAButton(true, ButtonCode.SHUTTER_FULL)))
        assertEquals(1, rejected)
        assertEquals(0, disconnects)
        assertFalse(queue.isBusy)
        assertNotNull(connection.client)
    }
}
