package com.signify.hue.flutterreactiveble.ble

import android.os.ParcelUuid
import com.polidea.rxandroidble2.RxBleDeviceServices
import com.signify.hue.flutterreactiveble.model.ScanMode
import com.signify.hue.flutterreactiveble.utils.Duration
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import java.util.UUID

@Suppress("TooManyFunctions")
interface BleClient {

    val centralConnectionUpdateSubject: BehaviorSubject<ConnectionUpdate>
    val charRequestSubject: BehaviorSubject<CharOperationResult>
    val connectionUpdateSubject: BehaviorSubject<ConnectionUpdate>

    val didModifyServicesSubject: PublishSubject<Int>

    fun initializeClient()

    fun scanForDevices(
        services: List<ParcelUuid>,
        scanMode: ScanMode,
        requireLocationServicesEnabled: Boolean,
    ): Observable<ScanInfo>

    fun connectToDevice(
        deviceId: String,
        timeout: Duration,
    )

    fun disconnectDevice(deviceId: String)

    fun disconnectAllDevices()

    fun discoverServices(deviceId: String): Single<RxBleDeviceServices>

    fun clearGattCache(deviceId: String): Completable

    fun readCharacteristic(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
    ): Single<CharOperationResult>

    fun setupNotification(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
    ): Observable<ByteArray>

    fun writeCharacteristicWithResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray,
    ): Single<CharOperationResult>

    fun writeCharacteristicWithoutResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray,
    ): Single<CharOperationResult>

    fun negotiateMtuSize(deviceId: String, size: Int): Single<MtuNegotiateResult>
    fun observeBleStatus(): Observable<BleStatus>
    fun readRssi(deviceId: String): Single<Int>
    fun requestConnectionPriority(deviceId: String, priority: ConnectionPriority):
            Single<RequestConnectionPriorityResult>

    fun startAdvertising()//: Observable<ConnectionUpdate>
    fun stopAdvertising()
    fun addGattService()
    fun addGattCharacteristic()
    fun startGattServer()
    fun stopGattServer()
    fun checkIfOldInetBoxBondingExists(deviceId: String): Boolean
    fun removeInetBoxBonding(deviceId: String, forceDelete: Boolean): Boolean
    fun writeLocalCharacteristic(
        deviceId: String,
        characteristic: UUID,
        value: ByteArray
    )

    fun isDeviceConnected(deviceId: String): Boolean

    @Deprecated("Use implementation of readCharacteristic using a characteristicInstanceId")
    fun readCharacteristic(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
    ): Single<CharOperationResult>

    @Deprecated("Use implementation of setupNotification using a characteristicInstanceId")
    fun setupNotification(
        deviceId: String,
        service: UUID,
        characteristic: UUID
    ): Observable<ByteArray>

    @Deprecated("Use implementation of writeCharacteristicWithResponse using a characteristicInstanceId")
    fun writeCharacteristicWithResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>

    @Deprecated("Use implementation of writeCharacteristicWithResponse using a characteristicInstanceId")
    fun writeCharacteristicWithoutResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>
}
