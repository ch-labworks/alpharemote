package org.staacks.alpharemote.service

/** Audio follows the existing shutter deadline; it never owns or completes a camera action. */
internal class SelfTimerBeeper(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val beep: () -> Unit,
    private val stopSound: () -> Unit
) {
    private var cancelTick: (() -> Unit)? = null
    private var generation = 0L

    fun start(deadline: Long) {
        stop()
        tick(deadline, generation)
    }

    fun stop() {
        generation++
        cancelTick?.invoke()
        cancelTick = null
        stopSound()
    }

    private fun tick(deadline: Long, expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        val remaining = deadline - now()
        if (remaining <= 0) {
            stop()
            return
        }
        beep()
        val interval = if (remaining <= 3_000L) 500L else minOf(1_000L, remaining - 3_000L)
        cancelTick = schedule(minOf(interval, remaining)) {
            if (expectedGeneration == generation) {
                cancelTick = null
                tick(deadline, expectedGeneration)
            }
        }
    }
}
