package org.staacks.alpharemote.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.staacks.alpharemote.MainActivity

/** Optional phone-side sound. Audio failures must never interrupt Bluetooth or the shutter timer. */
internal class SelfTimerSound(context: Context, scope: CoroutineScope) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var tone: ToneGenerator? = null
    private var failed = false
    private val beeper = SelfTimerBeeper(
        now = SystemClock::elapsedRealtime,
        schedule = { millis, action ->
            val tick = scope.launch { delay(millis); action() }
            val cancel: () -> Unit = { tick.cancel() }
            cancel
        },
        beep = ::beep,
        stopSound = ::releaseTone
    )

    fun start(deadline: Long) {
        failed = false
        beeper.start(deadline)
    }

    fun stop() = beeper.stop()

    private fun beep() {
        if (failed) return
        try {
            if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0 ||
                notificationManager?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                releaseTone()
                return
            }
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).also { tone = it }
            if (!generator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)) {
                failed = true
                releaseTone()
            }
        } catch (e: RuntimeException) {
            failed = true
            Log.w(MainActivity.TAG, "Self-timer sound unavailable; countdown continues", e)
            releaseTone()
        }
    }

    private fun releaseTone() {
        val generator = tone ?: return
        tone = null
        try {
            try { generator.stopTone() } finally { generator.release() }
        } catch (e: RuntimeException) {
            Log.w(MainActivity.TAG, "Could not release self-timer sound", e)
        }
    }
}
