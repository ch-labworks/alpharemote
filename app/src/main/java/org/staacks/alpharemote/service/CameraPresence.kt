package org.staacks.alpharemote.service

/** Advertising presence and an established GATT connection are independent signals. */
internal class CameraPresence {
    var nearby = false
    var connected = false
    val canDispose: Boolean get() = !nearby && !connected

    fun clear() {
        nearby = false
        connected = false
    }
}
