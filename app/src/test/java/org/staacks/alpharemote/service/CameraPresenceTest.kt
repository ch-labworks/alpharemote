package org.staacks.alpharemote.service

import org.junit.Assert.*
import org.junit.Test

class CameraPresenceTest {
    @Test fun disappearingAdvertisementsDoNotDisposeAConnectedCamera() {
        val presence = CameraPresence()
        presence.nearby = true
        presence.connected = true
        presence.nearby = false
        assertFalse(presence.canDispose)
        presence.connected = false
        assertTrue(presence.canDispose)
    }

    @Test fun temporaryGattDisconnectCanReconnectWhileCameraRemainsNearby() {
        val presence = CameraPresence()
        presence.nearby = true
        presence.connected = true
        presence.connected = false
        assertFalse(presence.canDispose)
        presence.connected = true
        assertFalse(presence.canDispose)
    }

    @Test fun repeatedPresenceEventsCannotUnderflowACounterOrPrematurelyDispose() {
        val presence = CameraPresence()
        presence.connected = true
        repeat(10) { presence.nearby = true }
        repeat(10) { presence.nearby = false }
        assertFalse(presence.canDispose)
        presence.clear()
        assertTrue(presence.canDispose)
    }
}
