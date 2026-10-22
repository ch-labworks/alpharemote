package org.staacks.alpharemote.service

import org.junit.Assert.*
import org.junit.Test

class SelfTimerBeeperTest {
    private class Clock {
        class Task(val at: Long, val action: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
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
    private class Fixture {
        val clock = Clock()
        val beeps = mutableListOf<Long>()
        var soundOpen = false
        val beeper = SelfTimerBeeper(
            now = { clock.now },
            schedule = clock::schedule,
            beep = { beeps += clock.now; soundOpen = true },
            stopSound = { soundOpen = false }
        )
    }

    @Test fun sixSecondSelfTimerSpeedsUpAtThreeSecondsAndStopsBeforeHold() {
        val f = Fixture()
        f.beeper.start(6_000)
        f.clock.advanceTo(6_500)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 3_500L, 4_000L, 4_500L, 5_000L, 5_500L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun builtInThreeSecondTimerBeepsUntilItsDeadline() {
        val f = Fixture()
        f.beeper.start(3_000)
        f.clock.advanceTo(4_000)
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun cancellationStopsToneAndEveryFutureBeep() {
        val f = Fixture()
        f.beeper.start(6_000)
        f.clock.advanceTo(1_100)
        f.beeper.stop()
        f.clock.advanceTo(10_000)
        assertEquals(listOf(0L, 1_000L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun staleTickCannotBeepOrCancelAReplacementCountdown() {
        val f = Fixture()
        f.beeper.start(6_000)
        val stale = f.clock.tasks.single()
        f.clock.advanceTo(100)
        f.beeper.start(1_100)
        stale.action() // Simulate a callback already posted before stop/restart.
        f.clock.advanceTo(1_100)
        assertEquals(listOf(0L, 100L, 600L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun lateCallbackDoesNotReplayMissedBeepsOrExtendTheCountdown() {
        val f = Fixture()
        f.beeper.start(6_000)
        val delayed = f.clock.tasks.removeAt(0)
        f.clock.now = 5_750
        delayed.action()
        f.clock.advanceTo(6_500)
        assertEquals(listOf(0L, 5_750L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun expiredAndShortCountdownsDoNotLeaveAnOpenTone() {
        val f = Fixture()
        f.beeper.start(0)
        assertTrue(f.beeps.isEmpty())
        f.beeper.start(50)
        f.clock.advanceTo(50)
        assertEquals(listOf(0L), f.beeps)
        assertFalse(f.soundOpen)
    }

    @Test fun MutingBeepsDoesNotCancelTheIndependentShutterTimer() {
        val f = Fixture()
        var shutterAt: Long? = null
        f.clock.schedule(6_000) { shutterAt = f.clock.now }
        f.beeper.start(6_000)
        f.clock.advanceTo(1_500)
        f.beeper.stop()
        f.clock.advanceTo(6_000)
        assertEquals(6_000L, shutterAt)
        assertEquals(listOf(0L, 1_000L), f.beeps)
    }
}
