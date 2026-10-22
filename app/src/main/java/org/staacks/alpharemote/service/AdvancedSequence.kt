package org.staacks.alpharemote.service

import org.staacks.alpharemote.camera.*

/** Builds the remote button sequence; interval duration retains its existing delay semantics. */
fun advancedSequence(
    bulbDuration: Float,
    intervalDuration: Float,
    intervalCount: Int,
    focusBracketingAmount: Float,
    refocusAfterEveryShot: Boolean,
    intervalLabel: String,
    bulbLabel: String,
    focusLabel: String,
): List<CameraActionStep> = buildList {
    // Bracketing requires MF and retains its preview-dismissal half-press pulses.
    val bracketing = focusBracketingAmount > 0
    val refocus = refocusAfterEveryShot && !bracketing
    fun half(pressed: Boolean) { add(CAButton(pressed, ButtonCode.SHUTTER_HALF)) }
    fun shutter(trigger: Boolean = false) {
        add(CAButton(true, ButtonCode.SHUTTER_FULL, isSequenceTrigger = trigger))
        if (trigger && bulbDuration > 0) add(CAWaitFor(WaitTarget.SHUTTER))
        add(CAButton(false, ButtonCode.SHUTTER_FULL))
    }

    if (!refocus && !bracketing) half(true)
    // Release any half-press left by a manual focus button before starting autofocus mode.
    if (refocus) half(false)
    repeat(intervalCount.coerceAtLeast(1)) { shot ->
        if (bracketing) half(true)
        if (intervalDuration > 0) add(CACountdown(intervalLabel, intervalDuration))
        if (bracketing && shot > 0) {
            add(CAWaitFor(WaitTarget.SHUTTER, invert = true))
            half(true) // Dismiss the camera's immediate preview before moving the lens.
            half(false)
            add(CAJog(true, JogCode.FOCUS_FAR.maxStep, JogCode.FOCUS_FAR))
            add(CACountdown(focusLabel, focusBracketingAmount))
            add(CAJog(false, JogCode.FOCUS_FAR.maxStep, JogCode.FOCUS_FAR))
        }
        if (refocus) {
            half(true)
            add(CAWaitFor(WaitTarget.FOCUS, requireFreshFocus = true))
        }
        shutter(trigger = true)
        if (bracketing) half(false)
        if (bulbDuration > 0) {
            add(CACountdown(bulbLabel, bulbDuration))
            if (bracketing) half(true)
            shutter() // Close bulb without releasing/reacquiring autofocus during the exposure.
            if (bracketing) half(false)
        }
        if (refocus) half(false)
    }
    if (!refocus && !bracketing) half(false)
}
