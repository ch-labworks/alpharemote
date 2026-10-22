package org.staacks.alpharemote.service

import org.junit.Assert.*
import org.junit.Test
import org.staacks.alpharemote.camera.CAWaitFor
import org.staacks.alpharemote.camera.WaitTarget

class SequenceSafetyTest {
    @Test fun invalidOrUnboundedCountdownCannotHoldWakeLock() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, Float.MAX_VALUE).forEach {
            assertNull(SequenceSafety.countdownMillis(it))
        }
        assertNull(SequenceSafety.countdownMillis(86_401f))
    }

    @Test fun legitimateLongBulbCountdownSurvivesMultipleWakeLockLeases() {
        val exposure = SequenceSafety.countdownMillis(3_600f)
        assertEquals(3_600_000L, exposure)
        assertTrue(exposure!! > SequenceSafety.WAKE_LOCK_LEASE_MS)
        assertTrue(SequenceSafety.WAKE_LOCK_RENEWAL_MS < SequenceSafety.WAKE_LOCK_LEASE_MS)
        assertEquals(86_400_000L, SequenceSafety.countdownMillis(86_400f))
    }

    @Test fun feedbackWaitsAreBoundedWithoutTreatingLongExposureAsAutofocus() {
        val focus = SequenceSafety.waitTimeoutMillis(CAWaitFor(WaitTarget.FOCUS))
        val opening = SequenceSafety.waitTimeoutMillis(CAWaitFor(WaitTarget.SHUTTER))
        val closing = SequenceSafety.waitTimeoutMillis(CAWaitFor(WaitTarget.SHUTTER, true))
        assertTrue(focus > 0)
        assertTrue(opening > 0)
        assertTrue(closing > 5 * 60 * 1_000L)
        assertTrue(closing > opening)
        assertTrue(closing < SequenceSafety.MAX_COUNTDOWN_MS)
    }
}
