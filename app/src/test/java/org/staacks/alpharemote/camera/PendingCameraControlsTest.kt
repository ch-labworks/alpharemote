package org.staacks.alpharemote.camera

import org.junit.Assert.*
import org.junit.Test

class PendingCameraControlsTest {
    @Test fun shutterCleanupDoesNotSendUnsupportedAfOrZoomCommands() {
        val controls = PendingCameraControls()
        controls.accepted(CAButton(true, ButtonCode.SHUTTER_HALF))
        controls.accepted(CAButton(true, ButtonCode.SHUTTER_FULL))
        assertEquals(listOf(CAButton(false, ButtonCode.SHUTTER_FULL), CAButton(false, ButtonCode.SHUTTER_HALF)), controls.releases())
    }

    @Test fun queuedOrFailedReleaseDoesNotForgetAnUncertainHold() {
        val controls = PendingCameraControls()
        val release = CAButton(false, ButtonCode.SHUTTER_FULL)
        controls.accepted(CAButton(true, ButtonCode.SHUTTER_FULL))
        controls.accepted(release)
        assertEquals(listOf(release), controls.releases())
        // Reconnection can release the hold again; only an ACK clears it.
        controls.acknowledged(release)
        assertTrue(controls.releases().isEmpty())
    }

    @Test fun freshConnectionDoesNotSendUnrequestedControls() {
        val controls = PendingCameraControls()
        assertTrue(controls.releases().isEmpty())
        controls.accepted(CAJog(true, 32, JogCode.FOCUS_FAR))
        assertEquals(listOf(CAJog(false, 0, JogCode.FOCUS_FAR)), controls.releases())
        controls.acknowledged(CAJog(false, 0, JogCode.FOCUS_FAR))
        assertTrue(controls.releases().isEmpty())
    }
}
