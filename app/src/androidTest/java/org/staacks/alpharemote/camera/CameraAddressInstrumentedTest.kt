package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothAdapter
import android.net.MacAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraAddressInstrumentedTest {
    @Test fun companionAddressPassesAndroidBluetoothValidationAfterNormalization() {
        val address = MacAddress.fromString("A4:C1:38:9E:AB:DF").toString()
        assertFalse(BluetoothAdapter.checkBluetoothAddress(address))
        assertTrue(BluetoothAdapter.checkBluetoothAddress(normalizeCameraAddress(address)))
    }
}
