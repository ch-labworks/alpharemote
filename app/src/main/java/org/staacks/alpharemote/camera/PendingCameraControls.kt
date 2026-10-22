package org.staacks.alpharemote.camera

/** Keep uncertain holds until a release is acknowledged, including across reconnections. */
internal class PendingCameraControls {
    private val buttons = mutableSetOf<ButtonCode>()
    private val jogs = mutableSetOf<JogCode>()
    val pressedButtons: Set<ButtonCode> get() = buttons.toSet()
    val pressedJogs: Set<JogCode> get() = jogs.toSet()

    fun accepted(step: CameraActionStep) {
        when (step) {
            is CAButton -> if (step.pressed) buttons.add(step.button)
            is CAJog -> if (step.pressed) jogs.add(step.jog)
            else -> Unit
        }
    }

    fun acknowledged(step: CameraActionStep) {
        when (step) {
            is CAButton -> if (!step.pressed) buttons.remove(step.button)
            is CAJog -> if (!step.pressed) jogs.remove(step.jog)
            else -> Unit
        }
    }

    fun releases(): List<CameraActionStep> =
        ButtonCode.entries.filter { it in buttons }.map { CAButton(false, it) } +
                JogCode.entries.filter { it in jogs }.map { CAJog(false, 0, it) }
}
