package org.staacks.alpharemote.camera

import org.junit.Assert.*
import org.junit.Test

class BLEConnectionOwnerTest {
    @Test fun reconnectRetainsOneClientUntilExplicitRelease() {
        var created = 0
        var closed = 0
        val owner = BLEConnectionOwner<Any>({}, { closed++ })
        owner.open { created++; Any() }
        val client = owner.client
        repeat(10) { owner.open { created++; Any() } }
        assertEquals(1, created)
        assertEquals(0, closed)
        assertTrue(owner.owns(client))
        owner.dispose()
        owner.dispose()
        assertEquals(1, closed)
    }

    @Test fun releaseInvalidatesCallbacksBeforeDisconnectAndClosesDespiteException() {
        var closed = 0
        lateinit var owner: BLEConnectionOwner<Any>
        owner = BLEConnectionOwner(
            disconnect = { old ->
                assertFalse(owner.owns(old))
                throw SecurityException("Permission revoked")
            },
            close = { closed++ }
        )
        owner.open { Any() }
        try {
            owner.release()
            fail("Expected revoked permission")
        } catch (_: SecurityException) { }
        assertNull(owner.client)
        assertEquals(1, closed)
        owner.dispose()
        assertEquals(1, closed)
    }

    @Test fun callbacksFromReplacedOrDisposedClientAreRejected() {
        val closed = mutableListOf<Any>()
        val owner = BLEConnectionOwner<Any>({}, { closed += it })
        owner.open { Any() }
        val old = owner.client
        owner.release()
        owner.open { Any() }
        val replacement = owner.client
        assertFalse(owner.owns(old))
        assertFalse(owner.owns(null))
        assertTrue(owner.owns(replacement))
        owner.dispose()
        owner.open { fail("Disposed owner must not create a new client"); Any() }
        assertFalse(owner.owns(replacement))
        assertEquals(listOf(old, replacement), closed)
    }

    @Test fun repeatedFailureRecoveryNeverAccumulatesClients() {
        var openClients = 0
        var closeCount = 0
        val owner = BLEConnectionOwner<Any>({}, { openClients--; closeCount++ })
        repeat(100) {
            owner.open { openClients++; Any() }
            assertEquals(1, openClients)
            owner.release()
            assertEquals(0, openClients)
        }
        owner.dispose()
        assertEquals(100, closeCount)
    }
}
