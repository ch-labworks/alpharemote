package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothGattCharacteristic

sealed class CameraBLEOperation

data class CameraBLEWrite(
    val characteristic: BluetoothGattCharacteristic,
    val data: ByteArray,
    val step: CameraActionStep,
    val safetyRelease: Boolean = false
) : CameraBLEOperation()

data class CameraBLERead(
    val characteristic: BluetoothGattCharacteristic,
    val resultCallback: (Int, ByteArray) -> Unit
) : CameraBLEOperation()

data class CameraBLESubscribe(
    val characteristic: BluetoothGattCharacteristic
) : CameraBLEOperation()
