package org.staacks.alpharemote.camera

/** Single client ownership, including invalidation before asynchronous disconnect callbacks. */
internal class BLEConnectionOwner<T : Any>(
    private val disconnect: (T) -> Unit,
    private val close: (T) -> Unit
) {
    var client: T? = null
        private set
    var disposed = false
        private set

    fun open(create: () -> T?) {
        if (!disposed && client == null) client = create()
    }

    fun owns(candidate: T?): Boolean = !disposed && candidate != null && candidate === client

    fun release() {
        val old = client ?: return
        client = null
        try {
            disconnect(old)
        } finally {
            close(old)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        release()
    }
}
