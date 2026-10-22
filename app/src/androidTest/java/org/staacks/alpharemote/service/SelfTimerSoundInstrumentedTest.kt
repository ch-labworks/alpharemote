package org.staacks.alpharemote.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.CACountdown
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset

@RunWith(AndroidJUnit4::class)
class SelfTimerSoundInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun customSelfTimerIsAudibleAndHalfSecondShutterHoldIsSilent() {
        val action = CameraAction(false, 6f, 0.5f, null, CameraActionPreset.SHUTTER)
        val countdowns = action.getClickStepList(context).filterIsInstance<CACountdown>()
        assertEquals(listOf(6f, 0.5f), countdowns.map { it.duration })
        assertEquals(listOf(true, false), countdowns.map { it.audible })
    }

    @Test fun builtInThreeSecondSelfTimerIsAudible() {
        val action = CameraAction(false, 3f, null, null, CameraActionPreset.TRIGGER_ONCE)
        assertTrue(action.getClickStepList(context).filterIsInstance<CACountdown>().single().audible)
    }

    @Test fun soundPreferencePersistsAcrossSettingsStoreInstances() = runBlocking {
        val settings = SettingsStore(context)
        val original = settings.selfTimerSound.first()
        try {
            settings.setSelfTimerSound(false)
            assertFalse(SettingsStore(context).selfTimerSound.first())
            settings.setSelfTimerSound(true)
            assertTrue(SettingsStore(context).selfTimerSound.first())
        } finally {
            settings.setSelfTimerSound(original)
        }
    }
}
