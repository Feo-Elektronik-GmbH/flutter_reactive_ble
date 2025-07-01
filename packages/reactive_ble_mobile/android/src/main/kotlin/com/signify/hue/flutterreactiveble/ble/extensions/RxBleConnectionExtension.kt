package com.signify.hue.flutterreactiveble.ble.extensions

import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleConnection
import io.reactivex.Single
import java.util.UUID

@Deprecated("use new api")
fun RxBleConnection.writeCharWithResponse(
    serviceUuid: UUID,
    charUuid: UUID,
    value: ByteArray
): Single<ByteArray> =
    executeWrite(serviceUuid, charUuid, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

@Deprecated("use new api")
fun RxBleConnection.writeCharWithoutResponse(
    serviceUuid: UUID,
    charUuid: UUID,
    value: ByteArray
): Single<ByteArray> =
    executeWrite(serviceUuid, charUuid, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

@Deprecated("use new api")
fun RxBleConnection.readChar(serviceUuid: UUID, charUuid: UUID) =
    this.discoverServices().flatMap { services -> services.getService(serviceUuid) }
        .map { service -> service.getCharacteristic(charUuid) }
        .flatMap { characteristic -> this.readCharacteristic(characteristic) }

@Deprecated("use new api")
private fun RxBleConnection.executeWrite(
    serviceUuid: UUID,
    charUuid: UUID,
    value: ByteArray,
    writeType: Int
) =
    this.discoverServices().flatMap { services -> services.getService(serviceUuid) }
        .map { service -> service.getCharacteristic(charUuid) }
        .flatMap { characteristic ->
            characteristic.writeType = writeType
            this.writeCharacteristic(characteristic, value)
        }

// ##### NEW API #####

fun RxBleConnection.resolveCharacteristic(
    uuid: UUID,
    instanceId: Int,
): Single<BluetoothGattCharacteristic> =
    discoverServices().flatMap { services ->
        Single.just(
            services.bluetoothGattServices.flatMap { service ->
                service.characteristics.filter {
                    it.uuid == uuid && it.instanceId == instanceId
                }
            }.single(),
        )
    }

fun RxBleConnection.writeCharWithResponse(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
): Single<ByteArray> {
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    return writeCharacteristic(characteristic, value)
}

fun RxBleConnection.writeCharWithoutResponse(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
): Single<ByteArray> {
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    return writeCharacteristic(characteristic, value)
}
