package org.staacks.alpharemote.camera

/** Serialized by the caller. A missing callback invalidates the connection; rejection need not. */
internal class BLEOperationQueue<T : Any>(
    private val start: (T) -> Boolean,
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
    private val onFailure: (String) -> Unit,
    private val onIdle: () -> Unit = {},
    private val onRejected: (T, String) -> Unit = { _, reason -> onFailure(reason) }
) {
    private val pending = ArrayDeque<T>()
    private var current: T? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var generation = 0L
    private var failures = 0L
    val isBusy: Boolean get() = current != null || pending.isNotEmpty()

    fun enqueue(operation: T): Boolean {
        val previousFailures = failures
        pending.addLast(operation)
        drain()
        return previousFailures == failures
    }

    fun complete(matches: (T) -> Boolean, beforeNext: (T) -> Unit = {}) {
        val operation = current ?: return
        if (!matches(operation)) return
        cancelTimeout?.invoke()
        cancelTimeout = null
        current = null
        generation++
        beforeNext(operation)
        drain()
    }

    fun fail(reason: String) {
        failures++
        clear()
        onFailure(reason)
    }

    /** Keep the accepted request; safety releases must wait for its callback. */
    fun discardPending() { pending.clear() }

    fun clear() {
        pending.clear()
        current = null
        generation++
        cancelTimeout?.invoke()
        cancelTimeout = null
    }

    private fun drain() {
        if (current != null) return
        val operation = pending.removeFirstOrNull()
        if (operation == null) {
            onIdle()
            return
        }
        current = operation
        val requestGeneration = ++generation
        val accepted = try {
            start(operation)
        } catch (e: Exception) {
            fail("Bluetooth request failed: ${e.message}")
            return
        }
        if (!accepted) {
            failures++
            clear()
            onRejected(operation, "Bluetooth request was rejected.")
            if (!isBusy) onIdle()
        } else if (current === operation) {
            cancelTimeout = scheduleTimeout {
                if (generation == requestGeneration && current === operation) fail("Bluetooth request timed out.")
            }
        }
    }
}
