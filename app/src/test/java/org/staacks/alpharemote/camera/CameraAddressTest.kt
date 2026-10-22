package org.staacks.alpharemote.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAddressTest {
    @Test fun companionMacAddressIsConvertedToBluetoothLookupFormat() {
        // AssociationInfo.deviceMacAddress.toString() supplies lowercase hex digits.
        assertEquals("A4:C1:38:9E:AB:DF", normalizeCameraAddress("a4:c1:38:9e:ab:df"))
    }

    @Test fun legacyAndNewPresenceCallbacksReferToTheSameCamera() {
        val legacyAddress = "A4:C1:38:9E:AB:DF"
        val associationAddress = "a4:c1:38:9e:ab:df"
        assertEquals(normalizeCameraAddress(legacyAddress), normalizeCameraAddress(associationAddress))
    }

    @Test fun malformedAddressesArePreservedForPlatformValidation() {
        assertEquals("A4:C1:38:9E:AB", normalizeCameraAddress("a4:c1:38:9e:ab"))
        assertEquals("A4-C1-38-9E-AB-DF", normalizeCameraAddress("a4-c1-38-9e-ab-df"))
    }
}
