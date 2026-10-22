package org.staacks.alpharemote.service

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import androidx.core.app.ActivityCompat
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.R
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.ButtonCode
import org.staacks.alpharemote.camera.CAButton
import org.staacks.alpharemote.camera.CACountdown
import org.staacks.alpharemote.camera.CAJog
import org.staacks.alpharemote.camera.CAWaitFor
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionStep
import org.staacks.alpharemote.camera.CameraBLE
import org.staacks.alpharemote.camera.normalizeCameraAddress
import org.staacks.alpharemote.camera.CameraStateIdentified
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.camera.ReportedBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.LinkedList


class AlphaRemoteService : CompanionDeviceService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private var timer: Job? = null
    private var connectionCollector: Job? = null
    private var wakeLockRenewal: Job? = null
    private var manualHoldTimeout: Job? = null
    private var waitingFor: CAWaitFor? = null
    private var focusBeforePress: ReportedBoolean? = null
    private var sequenceGeneration = 0L
    private var notificationUI: NotificationUI? = null
    private lateinit var selfTimerSound: SelfTimerSound
    private var selfTimerSoundEnabled = false

    private lateinit var pendingActionsWakeLock: PowerManager.WakeLock
    private var cameraBLE: CameraBLE? = null
    private val presence = CameraPresence()
    private var disposingConnection = false
    private val pendingActionSteps = LinkedList<CameraActionStep>()

    companion object {

        private var activeService: AlphaRemoteService? = null

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceStateGone())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        fun disconnect() {
            activeService?.let { service ->
                service.scope.launch {
                    service.disposeConnection()
                    service.stopSelf()
                }
            }
        }

        fun sendCameraAction(context: Context, cameraAction: CameraAction, down: Boolean = true, up: Boolean = true) {
            if (serviceState.value !is ServiceRunning)
                return
            val intent = Intent(context, AlphaRemoteService::class.java).apply {
                action = BUTTON_INTENT_ACTION
                putExtra(BUTTON_INTENT_CAMERA_ACTION_EXTRA, cameraAction as Serializable)
                putExtra(BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, down)
                putExtra(BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, up)
            }
            context.startService(intent)
        }

        const val BUTTON_INTENT_ACTION = "NOTIFICATION_BUTTON"
        const val BUTTON_INTENT_CAMERA_ACTION_EXTRA = "camera_action"
        const val BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA = "down"
        const val BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA = "up"

        const val ADVANCED_SEQUENCE_INTENT_ACTION = "ADVANCED_SEQUENCE"
        const val ADVANCED_SEQUENCE_INTENT_BULB_DURATION_EXTRA = "duration"
        const val ADVANCED_SEQUENCE_INTENT_INTERVAL_DURATION_EXTRA = "interval"
        const val ADVANCED_SEQUENCE_INTENT_INTERVAL_COUNT_EXTRA = "count"
        const val ADVANCED_SEQUENCE_INTENT_INTERVAL_REFOCUS_EXTRA = "refocus"
        const val ADVANCED_SEQUENCE_INTENT_FOCUS_BRACKETING_AMOUNT_EXTRA = "focus_step"

        var broadcastControl = false
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        pendingActionsWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlphaRemoteService::PendingActionsWakeLock")
        }
        pendingActionsWakeLock.setReferenceCounted(false)
        selfTimerSound = SelfTimerSound(applicationContext, scope)
        scope.launch {
            SettingsStore(application).selfTimerSound.collect { enabled ->
                selfTimerSoundEnabled = enabled
                val step = pendingActionSteps.peek() as? CACountdown
                val deadline = (serviceState.value as? ServiceRunning)?.countdown
                if (enabled && step?.audible == true && deadline != null) selfTimerSound.start(deadline)
                else selfTimerSound.stop()
            }
        }
    }

    override fun onDeviceAppeared(address: String) {
        Log.d(MainActivity.TAG, "Device appeared: $address")
        try {
            super.onDeviceAppeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}
        if (cameraBLE != null && cameraBLE?.address != normalizeCameraAddress(address)) return
        presence.nearby = true

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MainActivity.TAG, "Missing Bluetooth permission. Launching activity instead.")
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_settings)
            }
            startActivity(intent)
            stopSelf()
            return
        }

        val settingsStore = SettingsStore(application)
        notificationUI = notificationUI ?: (NotificationUI(applicationContext).also { notificationUI ->
            scope.launch {
                settingsStore.customButtonSettings.stateIn(
                    scope = this,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SettingsStore.CustomButtonSettings(null, 1.0f)
                ).collectLatest {
                    notificationUI.updateCustomButtons(it.customButtonList, it.scale)
                }
            }
            scope.launch {
                settingsStore.permissions.collectLatest {
                    if (it.notification) //Refresh notification if notification permission has been granted after it was not granted previously
                        notificationUI.updateNotification()
                    broadcastControl = it.broadcastControl
                }
            }
        })

        if (cameraBLE == null) {
            cancelPendingActionSteps()
            val client = CameraBLE(
                scope, application, address, ::onConnect, ::onDisconnect, ::onOperationsDrained,
                onActionFailed = { reason ->
                    Log.w(MainActivity.TAG, "Cancelling camera action without disconnecting: $reason")
                    cancelPendingActionSteps()
                }
            )
            cameraBLE = client
            connectionCollector = scope.launch {
                client.cameraState.collect { cameraState ->
                    if (cameraBLE !== client) return@collect
                    _serviceState.update {
                        (it as? ServiceRunning)?.copy(cameraState = cameraState) ?: ServiceRunning(cameraState, null, null)
                    }
                    notificationUI?.onCameraStateUpdate(cameraState)
                    when (cameraState) {
                        is CameraStateReady -> {
                            checkWaitAction(cameraState)
                            updateManualHoldTimeout(cameraState)
                            updateWakeLock()
                        }
                        is CameraStateIdentified -> settingsStore.setCameraId(cameraState.name, cameraState.address)
                        else -> cancelPendingActionSteps()
                    }
                }
            }
            client.start()
        } else {
            Log.w(MainActivity.TAG, "onDeviceAppeared ignored as cameraBLE has already been instantiated.")
            cameraBLE?.start()
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { onDeviceAppeared(it) }
    }

    override fun onDeviceDisappeared(address: String) {
        Log.d(MainActivity.TAG, "Device disappeared: $address")
        try {
            super.onDeviceDisappeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}
        if (cameraBLE?.address == normalizeCameraAddress(address)) {
            presence.nearby = false
            if (presence.canDispose) {
                disposeConnection()
                stopSelf()
            } else {
                Log.i(MainActivity.TAG, "Companion advertising disappeared; retaining the active Bluetooth connection")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { onDeviceDisappeared(it) }
    }

    private fun onConnect() {
        Log.d(MainActivity.TAG, "onConnect")
        presence.connected = true
        notificationUI?.let {
            startForeground(
                it.notificationId,
                it.start(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        }
    }

    private fun onDisconnect() {
        Log.d(MainActivity.TAG, "onDisconnect")
        presence.connected = false
        if (!disposingConnection && presence.canDispose) {
            disposeConnection()
            stopSelf()
            return
        }
        cancelPendingActionSteps()
        cameraBLE?.cameraState?.value?.let { state ->
            _serviceState.value = ServiceRunning(state, null, null)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationUI?.stop()
        // Retain the auto-connect client while the companion device is present.
    }

    private fun disposeConnection() {
        if (disposingConnection) return
        disposingConnection = true
        cancelPendingActionSteps()
        connectionCollector?.cancel()
        connectionCollector = null
        cameraBLE?.disconnectFromDevice()
        cameraBLE = null
        _serviceState.value = ServiceStateGone()
        releaseWakeLock()
        presence.clear()
        disposingConnection = false
    }

    override fun onDestroy() {
        disposeConnection()
        scope.cancel()
        notificationUI?.stop()
        if (activeService === this) activeService = null
        broadcastControl = false
        super.onDestroy()
    }

    private fun executeCameraAction(cameraAction: CameraAction, down: Boolean, up: Boolean) {
        var translatedUp = up
        var translatedDown = down

        // Translate toggle release to down or up depending on button state
        if (cameraAction.toggle) {
            translatedDown = false // Toggle only acts on button release. Do not pass through down events
            if (translatedUp) {
                (cameraBLE?.cameraState?.value as? CameraStateReady)?.let { cameraState ->
                    translatedUp = cameraAction.preset.template.referenceButton in cameraState.pressedButtons
                    translatedDown = !translatedUp
                }
            }
        }

        if (translatedDown && translatedUp) //Simple click, i.e. button in notification area
            startCameraAction(cameraAction.getClickStepList(this))
        else if (translatedDown) //Button released
            startCameraAction(cameraAction.getPressStepList(this))
        else if (translatedUp) //Button pressed
            startCameraAction(cameraAction.getReleaseStepList())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(MainActivity.TAG, "onStartCommand: $intent")
        if (cameraBLE?.cameraState?.value !is CameraStateReady) return START_NOT_STICKY
        when (intent?.action) {
            BUTTON_INTENT_ACTION -> {
                val cameraAction = intent.getSerializableExtra(BUTTON_INTENT_CAMERA_ACTION_EXTRA) as CameraAction
                val down = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, true)
                val up = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, true)

                executeCameraAction(cameraAction, down, up)
            }
            ADVANCED_SEQUENCE_INTENT_ACTION -> {
                val bulbDuration = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_BULB_DURATION_EXTRA) as Float
                val intervalDuration = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_INTERVAL_DURATION_EXTRA) as Float
                val intervalCount = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_INTERVAL_COUNT_EXTRA) as Int
                val focusBracketingAmount = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_FOCUS_BRACKETING_AMOUNT_EXTRA) as Float
                if (listOf(bulbDuration, intervalDuration, focusBracketingAmount).any { SequenceSafety.countdownMillis(it) == null }) {
                    Log.w(MainActivity.TAG, "Invalid sequence duration")
                    return START_NOT_STICKY
                }

                startCameraAction(advancedSequence(
                    bulbDuration, intervalDuration, intervalCount, focusBracketingAmount,
                    intent.getBooleanExtra(ADVANCED_SEQUENCE_INTENT_INTERVAL_REFOCUS_EXTRA, false),
                    getString(R.string.camera_advanced_interval_timer_label),
                    getString(R.string.camera_advanced_bulb_timer_label),
                    getString(R.string.camera_advanced_focus_bracketing_timer_label),
                ))
            }
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch {
            SettingsStore(application).getCustomButtonList().let { customButtonList ->
                SettingsStore(application).getNotificationButtonSize()?.let { notificationButtonSize ->
                    notificationUI?.updateCustomButtons(customButtonList, notificationButtonSize)
                }
            }
        }
    }

    fun cancelPendingActionSteps(): Boolean {
        val pendingStepsCancelled = pendingActionSteps.isNotEmpty()
        sequenceGeneration++
        timer?.cancel()
        timer = null
        selfTimerSound.stop()
        waitingFor = null
        focusBeforePress = null
        manualHoldTimeout?.cancel()
        manualHoldTimeout = null
        pendingActionSteps.clear()
        // Discard unsent presses first; keep an accepted write serialized ahead of releases.
        cameraBLE?.releaseAllControls()
        updatePendingActionStatistics()
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        return pendingStepsCancelled
    }

    private fun isLongRunningSequence(steps: List<CameraActionStep>): Boolean {
        for (step in steps) {
            when (step) {
                is CACountdown -> return true
                is CAWaitFor -> return true
                else -> {}
            }
        }
        return false
    }

    fun startCameraAction(steps: List<CameraActionStep>) {
        if (cameraBLE?.cameraState?.value !is CameraStateReady) return
        if (steps.any { it is CACountdown && SequenceSafety.countdownMillis(it.duration) == null }) {
            cancelPendingActionSteps()
            return
        }
        // Short press/release pairs already in GATT must stay ordered during a normal click.
        if (pendingActionSteps.isNotEmpty() || steps.isEmpty()) {
            if (cancelPendingActionSteps() && isLongRunningSequence(steps)) return
        }
        pendingActionSteps.addAll(steps)
        updateWakeLock()
        executeNextCameraActionStep()
    }

    fun updatePendingActionStatistics() {
        var pendingTriggerCount = 0
        for (actionStep in pendingActionSteps) {
            if (actionStep is CAButton && actionStep.isSequenceTrigger)
                pendingTriggerCount++
        }
        updateWakeLock()
        _serviceState.update {
            (it as? ServiceRunning)?.copy(pendingTriggerCount = pendingTriggerCount) ?: it
        }
    }

    fun executeNextCameraActionStep() {
        updatePendingActionStatistics()
        while ((pendingActionSteps.peek() is CAButton || pendingActionSteps.peek() is CAJog)) {
            pendingActionSteps.poll()?.let {
                updatePendingActionStatistics()
                if (it is CAButton && it.pressed && it.button == ButtonCode.SHUTTER_HALF) {
                    focusBeforePress = (cameraBLE?.cameraState?.value as? CameraStateReady)?.focus
                }
                cameraBLE?.executeCameraActionStep(it)
            }
        }
        updatePendingActionStatistics()
        (cameraBLE?.cameraState?.value as? CameraStateReady)?.let { updateManualHoldTimeout(it) }
        (pendingActionSteps.peek() as? CACountdown)?.let { step ->
            val time = SequenceSafety.countdownMillis(step.duration) ?: run {
                cancelPendingActionSteps()
                return
            }
            if (time == 0L) {
                pendingActionSteps.removeFirst()
                executeNextCameraActionStep()
                return
            }
            timer?.cancel()
            val generation = sequenceGeneration
            timer = scope.launch {
                delay(time)
                if (generation == sequenceGeneration && pendingActionSteps.peek() === step) countdownActionComplete()
            }
            val targetTime = SystemClock.elapsedRealtime() + time
            _serviceState.update {
                (it as? ServiceRunning)?.copy(countdown = targetTime, countdownLabel = step.label) ?: it
            }
            notificationUI?.showCountdown(targetTime, step.label)
            if (step.audible && selfTimerSoundEnabled) selfTimerSound.start(targetTime)
            else selfTimerSound.stop()
            return
        }
        // Arm the wait directly from the client; persisting camera identity can suspend its collector.
        (cameraBLE?.cameraState?.value as? CameraStateReady)?.let { checkWaitAction(it) }
    }

    fun countdownActionComplete() {
        selfTimerSound.stop()
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        val nextAction = pendingActionSteps.peek()
        if (nextAction is CACountdown) {
            pendingActionSteps.removeFirst()
            executeNextCameraActionStep()
        }
    }

    fun checkWaitAction(state: CameraStateReady) {
        val nextAction = pendingActionSteps.peek() as? CAWaitFor ?: return
        if (nextAction.isSatisfiedBy(state, focusBeforePress, cameraBLE?.hasPendingOperations == true)) {
            timer?.cancel()
            timer = null
            waitingFor = null
            pendingActionSteps.removeFirst()
            executeNextCameraActionStep()
        } else if (waitingFor !== nextAction) {
            waitingFor = nextAction
            timer?.cancel()
            val generation = sequenceGeneration
            timer = scope.launch {
                delay(SequenceSafety.waitTimeoutMillis(nextAction))
                if (generation == sequenceGeneration && pendingActionSteps.peek() === nextAction) {
                    Log.w(MainActivity.TAG, "Timed out waiting for ${nextAction.target}")
                    cancelPendingActionSteps()
                }
            }
        }
    }

    private fun hasUnsafeHold(state: CameraStateReady?): Boolean = state != null &&
            (ButtonCode.SHUTTER_FULL in state.pressedButtons || state.pressedJogs.isNotEmpty())

    private fun updateManualHoldTimeout(state: CameraStateReady) {
        if (pendingActionSteps.isNotEmpty() || !hasUnsafeHold(state)) {
            manualHoldTimeout?.cancel()
            manualHoldTimeout = null
        } else if (manualHoldTimeout == null) {
            manualHoldTimeout = scope.launch {
                delay(SequenceSafety.MANUAL_HOLD_TIMEOUT_MS)
                Log.w(MainActivity.TAG, "Releasing remote controls after a missing button-up event")
                cancelPendingActionSteps()
            }
        }
    }

    private fun onOperationsDrained() {
        (cameraBLE?.cameraState?.value as? CameraStateReady)?.let { checkWaitAction(it) }
        updateWakeLock()
    }

    private fun updateWakeLock() {
        val state = cameraBLE?.cameraState?.value as? CameraStateReady
        val needsPower = pendingActionSteps.isNotEmpty() || cameraBLE?.hasPendingOperations == true || hasUnsafeHold(state)
        if (!needsPower) {
            releaseWakeLock()
        } else if (wakeLockRenewal == null) {
            // A lost callback or a blocked dispatcher cannot leave an indefinite Android wake lock.
            pendingActionsWakeLock.acquire(SequenceSafety.WAKE_LOCK_LEASE_MS)
            wakeLockRenewal = scope.launch {
                while (true) {
                    delay(SequenceSafety.WAKE_LOCK_RENEWAL_MS)
                    pendingActionsWakeLock.acquire(SequenceSafety.WAKE_LOCK_LEASE_MS)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRenewal?.cancel()
        wakeLockRenewal = null
        if (pendingActionsWakeLock.isHeld) pendingActionsWakeLock.release()
    }

}
