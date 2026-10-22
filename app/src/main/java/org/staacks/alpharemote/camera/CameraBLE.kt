package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.staacks.alpharemote.MainActivity
import java.util.UUID
import kotlin.experimental.and

// Sony's remote protocol: https://github.com/coral/freemote
// https://gregleeds.com/reverse-engineering-sony-camera-bluetooth/
// All connection, queue and state mutations run on the main dispatcher.
class CameraBLE(
    scope: CoroutineScope,
    context: Context,
    address: String,
    private val onConnect: () -> Unit,
    private val onDisconnect: () -> Unit,
    private val onOperationsDrained: () -> Unit = {},
    private val onActionFailed: (String) -> Unit = {}
) {
    val address: String = normalizeCameraAddress(address)
    private val context = context.applicationContext
    private val connectionScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate
    )
    private val remoteServiceUUID = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")
    private val commandCharacteristicUUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val statusCharacteristicUUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val genericAccessServiceUUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val nameCharacteristicUUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    private val configDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val adapter = (this.context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var device: BluetoothDevice? = null
    private val connection = BLEConnectionOwner<BluetoothGatt>(
        disconnect = {
            try { it.disconnect() } catch (e: SecurityException) {
                Log.w(MainActivity.TAG, "Bluetooth disconnect failed", e)
            }
        },
        close = {
            try { it.close() } catch (e: SecurityException) {
                Log.w(MainActivity.TAG, "Bluetooth close failed", e)
            }
        }
    )
    private val gatt: BluetoothGatt? get() = connection.client
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var name: String? = null
    private val disposed: Boolean get() = connection.disposed
    private var recoveryAttempts = 0
    private var recoveryJob: Job? = null
    private var discoveryTimeout: Job? = null
    private var rediscoverRequested = false
    private val pendingControls = PendingCameraControls()

    private val _cameraState = MutableStateFlow<CameraState>(CameraStateGone())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val operations = BLEOperationQueue<CameraBLEOperation>(
        start = ::startOperation,
        scheduleTimeout = { expired ->
            val timeout = connectionScope.launch {
                delay(OPERATION_TIMEOUT_MS)
                expired()
            }
            val cancel: () -> Unit = { timeout.cancel() }
            cancel
        },
        onFailure = ::failConnection,
        onIdle = ::operationsDrained,
        onRejected = { operation, reason ->
            if (operation is CameraBLEWrite && cameraState.value is CameraStateReady) {
                commandFailed(operation, reason)
            } else {
                failConnection(reason)
            }
        }
    )
    val hasPendingOperations: Boolean get() = operations.isBusy

    /** Check ownership after dispatch, so posted callbacks from a replaced client are ignored too. */
    private fun dispatch(client: BluetoothGatt?, action: () -> Unit) {
        connectionScope.launch {
            if (connection.owns(client)) action()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) = dispatch(gatt) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    onConnect()
                } catch (e: RuntimeException) {
                    failConnection("Could not start camera service: ${e.message}")
                    return@dispatch
                }
                discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Retain one auto-connect client while Android still sees the companion device.
                resetConnectionState()
                _cameraState.value = CameraStateGone()
                onDisconnect()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Bluetooth connection failed ($status).")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) = dispatch(gatt) {
            if (rediscoverRequested) return@dispatch
            discoveryTimeout?.cancel()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Service discovery failed ($status).")
                return@dispatch
            }
            val remote = gatt?.getService(remoteServiceUUID)
            commandCharacteristic = remote?.getCharacteristic(commandCharacteristicUUID)
            statusCharacteristic = remote?.getCharacteristic(statusCharacteristicUUID)
            val statusChar = statusCharacteristic
            if (commandCharacteristic == null || statusChar == null) {
                failConnection("Remote service not found.")
                return@dispatch
            }
            gatt?.getService(genericAccessServiceUUID)?.getCharacteristic(nameCharacteristicUUID)?.let { characteristic ->
                enqueue(CameraBLERead(characteristic) { result, value ->
                    if (result == BluetoothGatt.GATT_SUCCESS) {
                        name = value.toString(Charsets.UTF_8)
                        _cameraState.value = CameraStateIdentified(name!!, address)
                    }
                })
            }
            enqueue(CameraBLESubscribe(statusChar))
        }

        override fun onServiceChanged(gatt: BluetoothGatt) = dispatch(gatt) {
            // Drain the accepted request before discovery. Starting a new request immediately
            // could misattribute the old callback to the same characteristic after discovery.
            rediscoverRequested = true
            _cameraState.value = CameraStateConnecting()
            operations.discardPending()
            if (!operations.isBusy) operationsDrained()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) = dispatch(gatt) {
            finishWrite(characteristic, status)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value?.clone() ?: byteArrayOf()
                dispatch(gatt) { finishRead(characteristic, status, value) }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            dispatch(gatt) { finishRead(characteristic, status, value) }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) = dispatch(gatt) {
            operations.complete({ it is CameraBLESubscribe && descriptor?.uuid == configDescriptorUUID && descriptor.characteristic === it.characteristic }) {
                if (rediscoverRequested) return@complete
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _cameraState.value = CameraStateReady(name, ReportedBoolean(), ReportedBoolean(), ReportedBoolean(), emptySet(), emptySet())
                    // Clear any remote hold left behind by an interrupted connection.
                    releaseAllControls()
                } else {
                    failConnection("Status subscription failed ($status).")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value?.clone() ?: byteArrayOf()
                dispatch(gatt) { if (characteristic === statusCharacteristic) updateStatus(value) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            dispatch(gatt) { if (characteristic === statusCharacteristic) updateStatus(value) }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            connectionScope.launch {
                if (disposed) return@launch
                try {
                    if (device?.bondState == BluetoothDevice.BOND_BONDED && cameraState.value is CameraStateNotBonded) {
                        start()
                    } else if (device?.bondState != BluetoothDevice.BOND_BONDED) {
                        closeGatt()
                        _cameraState.value = CameraStateNotBonded()
                        onDisconnect()
                    }
                } catch (e: SecurityException) {
                    failConnection("Bluetooth permission was revoked.")
                }
            }
        }
    }

    init {
        this.context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    /** Called after the service has taken ownership and installed its state collector. */
    fun start() {
        if (disposed || gatt != null) return
        device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(MainActivity.TAG, "Invalid camera address", e)
            failConnection("Invalid camera address.")
            return
        }
        try {
            if (device?.bondState != BluetoothDevice.BOND_BONDED) {
                _cameraState.value = CameraStateNotBonded()
                return
            }
            _cameraState.value = CameraStateConnecting()
            connection.open { device?.connectGatt(context, true, callback) }
            if (gatt == null) failConnection("Could not create Bluetooth client.")
        } catch (e: SecurityException) {
            failConnection("Bluetooth permission was revoked.")
        } catch (e: IllegalArgumentException) {
            Log.e(MainActivity.TAG, "Could not open Bluetooth connection", e)
            failConnection("Could not open Bluetooth connection: ${e.message}")
        }
    }

    private fun discoverServices() {
        _cameraState.value = CameraStateConnecting()
        discoveryTimeout?.cancel()
        try {
            if (gatt?.discoverServices() != true) {
                failConnection("Service discovery was rejected.")
                return
            }
            discoveryTimeout = connectionScope.launch {
                delay(OPERATION_TIMEOUT_MS)
                failConnection("Service discovery timed out.")
            }
        } catch (e: SecurityException) {
            failConnection("Bluetooth permission was revoked.")
        }
    }

    private fun resetConnectionState() {
        discoveryTimeout?.cancel()
        rediscoverRequested = false
        operations.clear()
        commandCharacteristic = null
        statusCharacteristic = null
    }

    private fun closeGatt() {
        resetConnectionState()
        try {
            connection.release()
        } catch (e: SecurityException) {
            Log.w(MainActivity.TAG, "Bluetooth cleanup failed", e)
        }
    }

    fun disconnectFromDevice() {
        if (disposed) return
        recoveryJob?.cancel()
        resetConnectionState()
        try {
            connection.dispose()
        } catch (e: SecurityException) {
            Log.w(MainActivity.TAG, "Bluetooth cleanup failed", e)
        } finally {
            context.unregisterReceiver(bondStateReceiver)
            connectionScope.cancel()
            _cameraState.value = CameraStateGone()
            onDisconnect()
        }
    }

    private fun failConnection(reason: String) {
        if (disposed) return
        Log.e(MainActivity.TAG, reason)
        closeGatt()
        _cameraState.value = CameraStateError(null, reason)
        onDisconnect()
        // Retry setup at most twice, without replaying an uncertain camera write.
        if (recoveryAttempts++ < MAX_RECOVERY_ATTEMPTS) {
            recoveryJob?.cancel()
            recoveryJob = connectionScope.launch {
                delay(RECOVERY_DELAY_MS)
                start()
            }
        }
    }

    private fun enqueue(operation: CameraBLEOperation): Boolean =
        !disposed && gatt != null && operations.enqueue(operation)

    @Suppress("DEPRECATION")
    private fun startOperation(operation: CameraBLEOperation): Boolean {
        val client = gatt ?: return false
        return try {
            val accepted = when (operation) {
                is CameraBLEWrite -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    client.writeCharacteristic(operation.characteristic, operation.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    operation.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    operation.characteristic.setValue(operation.data) && client.writeCharacteristic(operation.characteristic)
                }
                is CameraBLERead -> client.readCharacteristic(operation.characteristic)
                is CameraBLESubscribe -> {
                    val descriptor = operation.characteristic.getDescriptor(configDescriptorUUID) ?: return false
                    if (!client.setCharacteristicNotification(operation.characteristic, true)) return false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && client.writeDescriptor(descriptor)
                    }
                }
            }
            if (accepted && operation is CameraBLEWrite) pendingControls.accepted(operation.step)
            accepted
        } catch (e: SecurityException) {
            Log.w(MainActivity.TAG, "Bluetooth permission was revoked", e)
            throw e // Permission loss invalidates the connection; a busy/rejected command does not.
        }
    }

    private fun finishWrite(characteristic: BluetoothGattCharacteristic?, status: Int) {
        operations.complete({ it is CameraBLEWrite && it.characteristic === characteristic }) { operation ->
            val write = operation as CameraBLEWrite
            if (status == BluetoothGatt.GATT_SUCCESS) pendingControls.acknowledged(write.step)
            if (!rediscoverRequested && status != BluetoothGatt.GATT_SUCCESS) {
                commandFailed(write, if (status == 144) "Camera Bluetooth remote is disabled." else "Camera command failed ($status).")
                if (status == 144) _cameraState.value = CameraStateRemoteDisabled()
            }
        }
    }

    private fun commandFailed(write: CameraBLEWrite, reason: String) {
        Log.w(MainActivity.TAG, reason)
        // A completed/rejected write does not mean the link is dead. Never replay a press.
        // Cleanup writes are best effort and must not recursively trigger more cleanup.
        if (write.safetyRelease) return
        operations.discardPending()
        onActionFailed(reason)
    }

    private fun finishRead(characteristic: BluetoothGattCharacteristic, status: Int, value: ByteArray) {
        operations.complete({ it is CameraBLERead && it.characteristic === characteristic }) {
            if (!rediscoverRequested) (it as CameraBLERead).resultCallback(status, value)
        }
    }

    private fun operationsDrained() {
        if (rediscoverRequested) {
            rediscoverRequested = false
            discoverServices()
        } else {
            if (cameraState.value is CameraStateReady) recoveryAttempts = 0
            onOperationsDrained()
        }
    }

    private fun updateStatus(value: ByteArray) {
        if (value.size < 3) return
        _cameraState.update { state ->
            val ready = when (state) {
                is CameraStateReady -> state
                is CameraStateRemoteDisabled -> CameraStateReady(name, ReportedBoolean(), ReportedBoolean(), ReportedBoolean(), pendingControls.pressedButtons, pendingControls.pressedJogs)
                else -> return@update state
            }
            val reported = ReportedBoolean(value[2].and(0x20.toByte()) != 0.toByte())
            when (value[1]) {
                0x3f.toByte() -> ready.copy(focus = reported)
                0xa0.toByte() -> ready.copy(shutter = reported)
                0xd5.toByte() -> ready.copy(recording = reported)
                else -> state
            }
        }
    }

    fun executeCameraActionStep(step: CameraActionStep) {
        sendCameraActionStep(step, safetyRelease = false)
    }

    private fun sendCameraActionStep(step: CameraActionStep, safetyRelease: Boolean) {
        if (disposed || cameraState.value !is CameraStateReady) return
        val characteristic = commandCharacteristic ?: return
        val data = when (step) {
            is CAButton -> byteArrayOf(0x01, step.getCode())
            is CAJog -> byteArrayOf(0x02, step.getCode(), if (step.pressed) step.step else 0x00)
            else -> return
        }
        if (!enqueue(CameraBLEWrite(characteristic, data, step, safetyRelease))) return
        // Track requested state immediately so rapid toggle clicks remain ordered, even before ACK.
        // Immediately rejected requests return above without marking a button as pressed.
        _cameraState.update { state ->
            if (state !is CameraStateReady) state else when (step) {
                is CAButton -> state.copy(pressedButtons = if (step.pressed) state.pressedButtons + step.button else state.pressedButtons - step.button)
                is CAJog -> state.copy(pressedJogs = if (step.pressed) state.pressedJogs + step.jog else state.pressedJogs - step.jog)
            }
        }
    }

    fun releaseAllControls() {
        if (disposed || cameraState.value !is CameraStateReady) return
        operations.discardPending()
        _cameraState.update { state ->
            (state as? CameraStateReady)?.copy(pressedButtons = emptySet(), pressedJogs = emptySet()) ?: state
        }
        // Do not send unrelated AF/zoom/etc. releases: some models reject unsupported controls.
        pendingControls.releases().forEach { sendCameraActionStep(it, safetyRelease = true) }
    }

    companion object {
        private const val OPERATION_TIMEOUT_MS = 30_000L
        private const val RECOVERY_DELAY_MS = 1_000L
        private const val MAX_RECOVERY_ATTEMPTS = 2
    }
}
