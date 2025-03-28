package com.signify.hue.flutterreactiveble.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
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

    val connectionUpdateSubject: PublishSubject<com.signify.hue.flutterreactiveble.ble.ConnectionUpdate>
    val centralConnectionUpdateSubject: PublishSubject<com.signify.hue.flutterreactiveble.ble.ConnectionUpdate>
    val charRequestSubject: BehaviorSubject<com.signify.hue.flutterreactiveble.ble.CharOperationResult>

    val didModifyServicesSubject: PublishSubject<Int>

    fun initializeClient()
    fun scanForDevices(services: List<ParcelUuid>, scanMode: ScanMode, requireLocationServicesEnabled: Boolean): Observable<com.signify.hue.flutterreactiveble.ble.ScanInfo>
    fun connectToDevice(deviceId: String, timeout: Duration)
    fun disconnectDevice(deviceId: String)
    fun disconnectAllDevices()
    fun discoverServices(deviceId: String): Single<RxBleDeviceServices>
    fun clearGattCache(deviceId: String): Completable
    fun readCharacteristic(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
    ): Single<CharOperationResult>
    //fun readCharacteristic(deviceId: String, characteristic: UUID): Single<CharOperationResult>
    fun setupNotification(deviceId: String, service: UUID, characteristic: UUID): Observable<ByteArray>
    //fun setupNotification(deviceId: String, characteristic: UUID): Observable<ByteArray>
    fun writeCharacteristicWithResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>
    fun writeCharacteristicWithoutResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>
    /*
    fun writeCharacteristicWithResponse(
        deviceId: String,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>
    fun writeCharacteristicWithoutResponse(
        deviceId: String,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult>
    */
    fun negotiateMtuSize(deviceId: String, size: Int): Single<MtuNegotiateResult>
    fun observeBleStatus(): Observable<BleStatus>
    fun requestConnectionPriority(deviceId: String, priority: ConnectionPriority):
            Single<RequestConnectionPriorityResult>
    fun startAdvertising()//: Observable<ConnectionUpdate>
    fun stopAdvertising()
    fun addGattService()
    fun addGattCharacteristic()
    fun startGattServer()
    fun stopGattServer()
    fun checkIfOldInetBoxBondingExists(deviceId: String): Boolean
    fun removeInetBoxBonding(deviceId: String, forceDelete: Boolean, context: Context?): Boolean
    fun writeLocalCharacteristic(
        deviceId: String,
        characteristic: UUID,
        value: ByteArray
    )

    fun isDeviceConnected(deviceId: String): Boolean
}
