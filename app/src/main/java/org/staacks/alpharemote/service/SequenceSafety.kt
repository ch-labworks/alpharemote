package org.staacks.alpharemote.service

import org.staacks.alpharemote.camera.CAWaitFor
import org.staacks.alpharemote.camera.WaitTarget
import kotlin.math.roundToLong

internal object SequenceSafety {
    const val WAKE_LOCK_LEASE_MS = 120_000L
    const val WAKE_LOCK_RENEWAL_MS = 60_000L
    const val MANUAL_HOLD_TIMEOUT_MS = 60_000L
    const val MAX_COUNTDOWN_MS = 24 * 60 * 60 * 1_000L

    fun countdownMillis(seconds: Float): Long? {
        if (!seconds.isFinite() || seconds < 0) return null
        val millis = (seconds.toDouble() * 1_000).roundToLong()
        return millis.takeIf { it <= MAX_COUNTDOWN_MS }
    }

    fun waitTimeoutMillis(wait: CAWaitFor): Long = when (wait.target) {
        WaitTarget.FOCUS -> 15_000L
        WaitTarget.SHUTTER -> if (wait.invert) 30 * 60 * 1_000L else 30_000L
        WaitTarget.RECORDING -> 30_000L
    }
}
