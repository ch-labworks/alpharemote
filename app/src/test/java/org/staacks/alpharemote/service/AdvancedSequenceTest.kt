package org.staacks.alpharemote.service

import org.junit.Assert.*
import org.junit.Test
import org.staacks.alpharemote.camera.*

class AdvancedSequenceTest {
    private fun sequence(refocus: Boolean = false, bulb: Float = 0f, bracket: Float = 0f, interval: Float = 6f, count: Int = 3) =
        advancedSequence(bulb, interval, count, bracket, refocus, "Interval", "Bulb", "Focus")

    @Test fun uncheckedHoldsHalfPressAcrossAllIntervalsAndOnlyPulsesFullShutter() {
        val steps = sequence()
        assertEquals(CAButton(true, ButtonCode.SHUTTER_HALF), steps.first())
        assertEquals(CAButton(false, ButtonCode.SHUTTER_HALF), steps.last())
        assertEquals(2, steps.filterIsInstance<CAButton>().count { it.button == ButtonCode.SHUTTER_HALF })
        assertEquals(3, steps.filterIsInstance<CAButton>().count { it.isSequenceTrigger })
        assertTrue(steps.filterIsInstance<CAWaitFor>().isEmpty())
        assertEquals(List(3) { listOf(
            CACountdown("Interval", 6f),
            CAButton(true, ButtonCode.SHUTTER_FULL, true),
            CAButton(false, ButtonCode.SHUTTER_FULL),
        ) }.flatten(), steps.drop(1).dropLast(1))
    }

    @Test fun checkedWaitsForFreshFocusBeforeEveryShotAndReleasesBetweenShots() {
        val steps = sequence(refocus = true)
        assertEquals(CAButton(false, ButtonCode.SHUTTER_HALF), steps.first())
        assertEquals(List(3) { listOf(
            CACountdown("Interval", 6f),
            CAButton(true, ButtonCode.SHUTTER_HALF),
            CAWaitFor(WaitTarget.FOCUS, requireFreshFocus = true),
            CAButton(true, ButtonCode.SHUTTER_FULL, true),
            CAButton(false, ButtonCode.SHUTTER_FULL),
            CAButton(false, ButtonCode.SHUTTER_HALF),
        ) }.flatten(), steps.drop(1))
    }

    @Test fun bulbKeepsHalfPressWhileOpeningAndClosingExposureInBothModes() {
        for (refocus in listOf(false, true)) {
            val steps = sequence(refocus, bulb = 5f)
            var halfHeld = false
            var exposures = 0
            steps.forEach { step ->
                if (step is CAButton && step.button == ButtonCode.SHUTTER_HALF) halfHeld = step.pressed
                if (step is CAButton && step.button == ButtonCode.SHUTTER_FULL && step.pressed) {
                    assertTrue(halfHeld)
                    exposures++
                }
                if (step is CACountdown && step.label == "Bulb") assertTrue(halfHeld)
            }
            assertEquals(6, exposures)
            assertFalse(halfHeld)
        }
    }

    @Test fun bracketingRetainsManualFocusStepsAndIgnoresAutofocusPreference() {
        val steps = sequence(bracket = 2f)
        assertEquals(steps, sequence(refocus = true, bracket = 2f))
        assertFalse(steps.filterIsInstance<CAWaitFor>().any { it.target == WaitTarget.FOCUS })
        assertEquals(2, steps.filterIsInstance<CAJog>().count { it.pressed })
        assertEquals(2, steps.filterIsInstance<CAJog>().count { !it.pressed })
    }

    @Test fun zeroIntervalStillReleasesHalfPressAtCompletion() {
        val steps = sequence(interval = 0f, count = 1)
        assertTrue(steps.none { it is CACountdown })
        assertEquals(CAButton(false, ButtonCode.SHUTTER_HALF), steps.last())
        assertEquals(1, steps.filterIsInstance<CAButton>().count { it.isSequenceTrigger })
    }

    @Test fun abortAtAnyPointCanReleaseEveryAcceptedButtonAndJog() {
        for (refocus in listOf(false, true)) for (bulb in listOf(0f, 5f)) for (bracket in listOf(0f, 2f)) {
            val controls = PendingCameraControls()
            sequence(refocus, bulb, bracket).forEach { step ->
                controls.accepted(step)
                controls.acknowledged(step)
                // Every held control has a corresponding cleanup release, including half-press.
                assertEquals(controls.pressedButtons, controls.releases().filterIsInstance<CAButton>().map { it.button }.toSet())
                assertEquals(controls.pressedJogs, controls.releases().filterIsInstance<CAJog>().map { it.jog }.toSet())
            }
            assertTrue(controls.releases().isEmpty())
        }
    }
}
