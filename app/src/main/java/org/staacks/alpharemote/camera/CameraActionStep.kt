package org.staacks.alpharemote.camera

import kotlin.experimental.or

sealed class CameraActionStep

data class CACountdown (
    val label: String,
    val duration: Float,
    val audible: Boolean = false
) : CameraActionStep()

data class CAWaitFor (
    val target: WaitTarget,
    val invert: Boolean = false,
    val requireFreshFocus: Boolean = false,
) : CameraActionStep() {
    fun isSatisfiedBy(state: CameraStateReady, focusBeforePress: ReportedBoolean?, operationsPending: Boolean): Boolean {
        val reported = when (target) {
            WaitTarget.FOCUS -> state.focus
            WaitTarget.SHUTTER -> state.shutter
            WaitTarget.RECORDING -> state.recording
        }
        // Focus reports are replaced on every notification. Identity avoids clock-resolution
        // races and rejects the previous shot's focus success, even if it is still true.
        if (requireFreshFocus && (operationsPending || reported === focusBeforePress)) return false
        return reported.lastChange != null && reported.state != invert
    }
}

data class CAButton (
    val pressed: Boolean,
    val button: ButtonCode,
    val isSequenceTrigger: Boolean = false,
) : CameraActionStep() {
    fun getCode(): Byte {
        return button.code or (if (pressed) 0x01 else 0x00)
    }
}

data class CAJog (
    val pressed: Boolean,
    val step: Byte,
    val jog: JogCode,
) : CameraActionStep() {
    fun getCode(): Byte {
        return jog.code or (if (pressed) 0x01 else 0x00)
    }
}

enum class WaitTarget {
    FOCUS, SHUTTER, RECORDING
}

enum class ButtonCode(val code: Byte) {
    SHUTTER_FULL(0x08),
    SHUTTER_HALF(0x06),
    RECORD(0x0e),
    AF_ON(0x14),
    C1(0x20)
}

enum class JogCode(val code: Byte, val minStep: Byte, val maxStep: Byte) {
    ZOOM_IN(0x44, 0x10, 0x7f),
    ZOOM_OUT(0x46, 0x10, 0x7f),
    FOCUS_NEAR(0x6a, 0x00, 0x7f),
    FOCUS_FAR(0x6c, 0x00, 0x7f)
}
