package org.staacks.alpharemote.camera

import java.util.Locale

// MacAddress.toString() uses lowercase, but BluetoothAdapter.getRemoteDevice(String)
// requires uppercase. Use the same representation for presence and disappearance events.
internal fun normalizeCameraAddress(address: String): String = address.uppercase(Locale.ROOT)
