package com.signify.hue.flutterreactiveble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.VisibleForTesting
import com.polidea.rxandroidble2.LogConstants
import com.polidea.rxandroidble2.LogOptions
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.RxBleDeviceServices
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import com.signify.hue.flutterreactiveble.ble.extensions.readChar
import com.signify.hue.flutterreactiveble.ble.extensions.writeCharWithResponse
import com.signify.hue.flutterreactiveble.ble.extensions.writeCharWithoutResponse
import com.signify.hue.flutterreactiveble.converters.extractManufacturerData
import com.signify.hue.flutterreactiveble.model.ScanMode
import com.signify.hue.flutterreactiveble.model.toScanSettings
import com.signify.hue.flutterreactiveble.utils.Duration
import com.signify.hue.flutterreactiveble.utils.toBleState
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.bluetooth.*
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.signify.hue.flutterreactiveble.utils.BuildConfig
import kotlin.random.Random

private const val tag: String = "ReactiveBleClient"

private var mBluetoothGattServer: BluetoothGattServer? = null
private var mBluetoothGatt: BluetoothGatt? = null
private lateinit var mCentralBluetoothDevice: BluetoothDevice

@Suppress("TooManyFunctions")
open class ReactiveBleClient(private val context: Context) : BleClient {
    private val connectionQueue = ConnectionQueue()
    private val allConnections = CompositeDisposable()
    private var serviceUUIDsList = ArrayList<String>()
    private var bondedStateActiveBefore = false;
    private var deviceId: String = "";

    companion object {
        // this needs to be in companion update since backgroundisolates respawn the eventchannels
        // Fix for https://github.com/PhilipsHue/flutter_reactive_ble/issues/277
        private val connectionUpdateBehaviorSubject: PublishSubject<ConnectionUpdate> =
            PublishSubject.create()
        private val centralConnectionUpdateBehaviorSubject: PublishSubject<ConnectionUpdate> =
            PublishSubject.create()
        private val charRequestBehaviorSubject: BehaviorSubject<CharOperationResult> =
            BehaviorSubject.create()

        // Changes, if a new device connects with the iNetBox
        private val didModifyServicesBehaviourSubject: PublishSubject<Int> =
            PublishSubject.create()
        lateinit var rxBleClient: RxBleClient
            internal set
        internal var activeConnections = mutableMapOf<String, DeviceConnector>()
        lateinit var ctx: Context

        internal var gattServices = mutableMapOf<String, BluetoothGattService>()

        private var advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d("adv", "success")
                super.onStartSuccess(settingsInEffect)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.d("adv", errorCode.toString())
                super.onStartFailure(errorCode)
            }
        }
    }

    override val connectionUpdateSubject: PublishSubject<ConnectionUpdate>
        get() = connectionUpdateBehaviorSubject

    override val centralConnectionUpdateSubject: PublishSubject<ConnectionUpdate>
        get() = centralConnectionUpdateBehaviorSubject

    override val charRequestSubject: BehaviorSubject<CharOperationResult>
        get() = charRequestBehaviorSubject

    override val didModifyServicesSubject: PublishSubject<Int>
        get() = didModifyServicesBehaviourSubject

    var CccdUUID: String = "00002902-0000-1000-8000-00805f9b34fb"

    //https://medium.com/@martijn.van.welie/making-android-ble-work-part-4-72a0b85cb442
    var SrvUUID1: String = "d0611e78-bbb4-4591-a5f8-487910ae4366"
    var SrvUUID2: String = "ad0badb1-5b99-43cd-917a-a77bc549e3cc"
    var SrvUUID3: String = "73a58d00-c5a1-4f8e-8f55-1def871ddc81"
    var UartSrvUUID: String = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"

    override fun initializeClient() {
        activeConnections = mutableMapOf()
        rxBleClient = RxBleClient.create(context)
        ctx = context

        gattServices = mutableMapOf()
    }

    /*yes spread operator is not performant but after kotlin v1.60 it is less bad and it is also the
    recommended way to call varargs in java https://kotlinlang.org/docs/reference/java-interop.html#java-varargs
    */
    @Suppress("SpreadOperator")
    override fun scanForDevices(
        services: List<ParcelUuid>,
        scanMode: ScanMode,
        requireLocationServicesEnabled: Boolean
    ): Observable<ScanInfo> {

        val filters = services.map { service ->
            ScanFilter.Builder()
                .setServiceUuid(service)
                .build()
        }.toTypedArray()

        return rxBleClient.scanBleDevices(
            ScanSettings.Builder()
                .setScanMode(scanMode.toScanSettings())
                .setLegacy(false)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setShouldCheckLocationServicesState(requireLocationServicesEnabled)
                .build(),
            *filters
        )
            .map { result ->
                ScanInfo(result.bleDevice.macAddress, result.scanRecord.deviceName
                    ?: result.bleDevice.name ?: "",
                    result.rssi,
                    result.scanRecord.serviceData?.mapKeys { it.key.uuid } ?: emptyMap(),
                    result.scanRecord.serviceUuids?.map { it.uuid } ?: emptyList(),
                    extractManufacturerData(result.scanRecord.manufacturerSpecificData))
            }
    }

    override fun connectToDevice(deviceId: String, timeout: Duration) {
        Log.i(tag, "connectToDevice")
        this.deviceId = deviceId
        Log.i(tag, "for id: $deviceId")
        allConnections.add(
            getConnection(deviceId, timeout)
                .subscribe({ result ->
                    when (result) {
                        is EstablishedConnection -> {
                        }

                        is EstablishConnectionFailure -> {
                            connectionUpdateBehaviorSubject.onNext(
                                ConnectionUpdateError(
                                    deviceId,
                                    result.errorMessage
                                )
                            )
                        }
                    }
                }, { error ->
                    connectionUpdateBehaviorSubject.onNext(
                        ConnectionUpdateError(
                            deviceId, error?.message
                                ?: "unknown error"
                        )
                    )
                })
        )
        Log.i(tag, "connect finished")
    }

    override fun disconnectDevice(deviceId: String) {
        Log.i(tag, "disconnect")
        if (mBluetoothGatt == null) {
            Log.i(tag, "mBluetoothGatt is null, won't disconnect")
        }
        mBluetoothGatt?.disconnect()
        if (activeConnections[deviceId] == null) {
            Log.i(tag, "No active connections found")
        }
        activeConnections[deviceId]?.disconnectDevice(deviceId)
        activeConnections.remove(deviceId)
    }

    override fun disconnectAllDevices() {
        activeConnections.forEach { (device, connector) -> connector.disconnectDevice(device) }
        allConnections.dispose()
    }

    override fun clearGattCache(deviceId: String): Completable =
        activeConnections[deviceId]?.let(DeviceConnector::clearGattCache)
            ?: Completable.error(IllegalStateException("Device is not connected"))

    override fun discoverServices(deviceId: String): Single<RxBleDeviceServices> {

        return getConnection(deviceId).flatMapSingle { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection ->
                    if (rxBleClient.getBleDevice(connectionResult.deviceId).bluetoothDevice.bondState == BOND_BONDING) {
                        Single.error(Exception("Bonding is in progress wait for bonding to be finished before executing more operations on the device"))
                    } else {
                        connectionResult.rxConnection.discoverServices(60L, TimeUnit.SECONDS)
                    }

                is EstablishConnectionFailure -> Single.error(Exception(connectionResult.errorMessage))
            }
        }.firstOrError()
    }

    override fun readCharacteristic(
        deviceId: String,
        service: UUID,
        characteristic: UUID
    ): Single<CharOperationResult> =
        executeReadOperation(
            deviceId,
            service,
            characteristic,
        )

    /*
        override fun readCharacteristic(
            deviceId: String,
            service: UUID,
            characteristic: UUID
        ): Single<CharOperationResult> =
            getConnection(deviceId).flatMapSingle<CharOperationResult> { connectionResult ->
                when (connectionResult) {
                    is EstablishedConnection ->
                        connectionResult.rxConnection.readCharacteristic(characteristic)
                            /*
                            On Android7 the ble stack frequently gives incorrectly
                            the error GAT_AUTH_FAIL(137) when reading char that will establish
                            the bonding with the peripheral. By retrying the operation once we
                            deviate between this flaky one time error and real auth failed cases
                            */
                            .retry(1) { BuildConfig().getVersionSDKInt() < Build.VERSION_CODES.O }
                            .map { value ->
                                CharOperationSuccessful(deviceId, value.asList())
                            }
                    is EstablishConnectionFailure ->
                        Single.just(
                            CharOperationFailed(
                                deviceId,
                                "failed to connect ${connectionResult.errorMessage}"
                            )
                        )
                }
            }.first(CharOperationFailed(deviceId, "read char failed"))
    */
    override fun writeCharacteristicWithResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult> =
        executeWriteOperation(
            deviceId,
            service,
            characteristic,
            value,
            RxBleConnection::writeCharWithResponse
        )

    override fun writeCharacteristicWithoutResponse(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray
    ): Single<CharOperationResult> =

        executeWriteOperation(
            deviceId,
            service,
            characteristic,
            value,
            RxBleConnection::writeCharWithoutResponse
        )

    override fun setupNotification(
        deviceId: String,
        service: UUID,
        characteristic: UUID
    ): Observable<ByteArray> {
        return getConnection(deviceId)
            .flatMap { deviceConnection ->
                setupNotificationOrIndication(
                    deviceConnection,
                    service,
                    characteristic
                )
            }
            // now we have setup the subscription and we want the actual value
            .flatMap { notificationObservable ->
                notificationObservable
            }
    }

    override fun negotiateMtuSize(deviceId: String, size: Int): Single<MtuNegotiateResult> =
        getConnection(deviceId).flatMapSingle { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection -> connectionResult.rxConnection.requestMtu(size)
                    .map { value -> MtuNegotiateSuccesful(deviceId, value) }

                is EstablishConnectionFailure ->
                    Single.just(
                        MtuNegotiateFailed(
                            deviceId,
                            "failed to connect ${connectionResult.errorMessage}"
                        )
                    )
            }
        }.first(MtuNegotiateFailed(deviceId, "negotiate mtu timed out"))

    override fun startAdvertising() {
        val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
        val advertiser: BluetoothLeAdvertiser =
            bluetoothAdapter.getBluetoothLeAdvertiser() ?: return

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()

        val SERVICE_UUID = "61808880-b7b3-11E4-b3a4-0002a5d5c51b"

        val advertiseData: AdvertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(SERVICE_UUID))
            .build()

        val scanResponse: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser!!.startAdvertising(
            advertiseSettings,
            advertiseData,
            scanResponse,
            advertiseCallback
        )
    }

    private fun addExampleGattService() {
        val bluetoothManager: BluetoothManager =
            ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        var UartCharRxUUID: String = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        var UartCharTxUUID: String = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

        var CharUUID1: String = "8667556c-9a37-4c91-84ed-54ee27d90049"
        var CharUUID2: String = "af0badb1-5b99-43cd-917a-a77bc549e3cc"
        var CharUUID3: String = "73a58d01-c5a1-4f8e-8f55-1def871ddc81"

        val UartCharRx: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UartCharRxUUID),
            BluetoothGattCharacteristic.PROPERTY_READ + BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )

        val UartCharTx: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UartCharTxUUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE + BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )

        val Characteristic1: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CharUUID1),
            BluetoothGattCharacteristic.PROPERTY_WRITE + BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val Characteristic2: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CharUUID2),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val Characteristic3: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CharUUID3),
            BluetoothGattCharacteristic.PROPERTY_READ + BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )

        val CCCDUartRx: BluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID.fromString(CccdUUID),
            BluetoothGattDescriptor.PERMISSION_READ + BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        val CCCD1: BluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID.fromString(CccdUUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        val CCCD2: BluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID.fromString(CccdUUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        val CCCD3: BluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID.fromString(CccdUUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        val uartService = BluetoothGattService(
            UUID.fromString(UartSrvUUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val service1 = BluetoothGattService(
            UUID.fromString(SrvUUID1),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val service2 = BluetoothGattService(
            UUID.fromString(SrvUUID2),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val service3 = BluetoothGattService(
            UUID.fromString(SrvUUID3),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val gattCallback = object : BluetoothGattCallback() {
            @Override
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                Log.i(
                    tag,
                    "GATT: onConnectionStateChange" + " status:" + status + " newState:" + newState
                )
                logProfileState(newState)
            }

            @Override
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                Log.i(tag, "onServicesDiscovered" + " status:" + status)
                super.onServicesDiscovered(gatt, status)
            }

            @Override
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
                Log.i(tag, "onCharacteristicRead")
            }

            @Override
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                Log.i(tag, "onCharacteristicWrite")
            }

            @Override
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                Log.i(tag, "onCharacteristicChanged")
            }

            @Override
            override fun onDescriptorRead(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorRead(gatt, descriptor, status)
                Log.i(tag, "onDescriptorRead")
            }

            @Override
            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                Log.i(tag, "onDescriptorWrite")
            }

            @Override
            override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                super.onReliableWriteCompleted(gatt, status)
                Log.i(tag, "onReliableWriteCompleted")
            }

            @Override
            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
                Log.i(tag, "onReadRemoteRssi")
            }

            @Override
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                Log.i(tag, "onMtuChanged")
            }

            @Override
            override fun onServiceChanged(gatt: BluetoothGatt) {
                Log.i(tag, "onServiceChanged")
                Log.i(tag, "device id: $deviceId")
                // Fixme: check if necessary
                discoverServices(deviceId)
                didModifyServicesBehaviourSubject.onNext(Random.nextInt(0, 100))
                Log.i(tag, "send update via stream")
                super.onServiceChanged(gatt)
            }
        }

        val serverCallback = object : BluetoothGattServerCallback() {

            @Override
            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(device, status, newState)
                Log.i(
                    tag,
                    "onConnectionStateChange" + " status:" + status + " newState:" + newState
                )

                Log.i(
                    tag,
                    "Device adress:" + device?.getAddress() + " bondingstate:" + device?.getBondState()
                )

                val deviceId: String = device?.getAddress().toString();

                when (device?.getBondState()) {
                    11 -> {
                        Log.i(tag, "BOND_BONDING")
                        bondedStateActiveBefore = false
                        connectionUpdateBehaviorSubject.onNext(
                            ConnectionUpdateSuccess(
                                deviceId,
                                6
                            )
                        )
                    }

                    12 -> {
                        Log.i(tag, "BOND_BONDED")
                        bondedStateActiveBefore = true
                        connectionUpdateBehaviorSubject.onNext(
                            ConnectionUpdateSuccess(
                                deviceId,
                                7
                            )
                        )
                    }

                    10 -> {
                        Log.i(tag, "BOND_NONE")
                        Log.i(tag, "BOND_NONE_CHECK")
                        /*if (bondedStateActiveBefore) {
                            connectionUpdateBehaviorSubject.onNext(
                                ConnectionUpdateSuccess(
                                    deviceId,
                                    5 *//*FORCEDISCONNECTED*//*
                                )
                            )
                        }
                        bondedStateActiveBefore = false;*/

                        //createBond()
                    }

                    else -> {
                        Log.i(tag, "BOND_UNKNOWN")
                        bondedStateActiveBefore = false;
                        connectionUpdateBehaviorSubject.onNext(
                            ConnectionUpdateSuccess(
                                deviceId,
                                4
                            )
                        )
                    }
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        //https://stackoverflow.com/questions/53574927/bluetoothleadvertiser-stopadvertising-causes-device-to-disconnect
                        //mBluetoothGattServer.connect(device, false) // prevents disconnection when advertising stops
                        // stop advertising here or whatever else you need to do
                        /*centralConnectionUpdateBehaviorSubject.onNext(
                            ConnectionUpdateSuccess(
                                device?.getAddress().toString(),
                                1 *//*CONNECTED*//*
                            )
                        )*/
                    }
                }
            }

            @Override
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                super.onServiceAdded(status, service)
                Log.i(tag, "onServiceAdded")

                //TODO initialise next service
                var ServiceUuid: String = service?.getUuid().toString()
                Log.i(tag, ServiceUuid)
                if (mBluetoothGattServer != null && ServiceUuid.equals(SrvUUID1)) {
                    while (!mBluetoothGattServer!!.addService(gattServices.get(SrvUUID2)));
                    Log.i(tag, "service2 added")
                }

                if (mBluetoothGattServer != null && ServiceUuid.equals(SrvUUID2)) {
                    while (!mBluetoothGattServer!!.addService(gattServices.get(SrvUUID3)));
                    Log.i(tag, "service3 added")
                }

                if (mBluetoothGattServer != null && ServiceUuid.equals(SrvUUID3)) {
                    while (!mBluetoothGattServer!!.addService(gattServices.get(UartSrvUUID)));
                    Log.i(tag, "serviceUart added")
                }
            }

            @Override
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                Log.i(tag, "onCharacteristicReadRequest")

                mBluetoothGattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    characteristic?.getValue()
                );
            }

            @Override
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onCharacteristicWriteRequest(
                    device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )

                Log.i(tag, "onCharacteristicWriteRequest")

                if (responseNeeded) {
                    Log.i(tag, "BLE Write Request - Response")
                    mBluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                }

                charRequestBehaviorSubject.onNext(
                    CharOperationSuccessful(
                        characteristic!!.getUuid().toString(), value!!.asList()
                    )
                )

                //val request = createCharacteristicRequest(BluetoothDevice?.getAddress().toString(), UUID.randomUUID())
                // TODO sent event to flutter with received write request
                /*
                value.asList()
                val charInfo = protoConverter.convertCharacteristicInfo(
                    characteristic,
                    value.toByteArray()
                )
                */

                //TODO add to Event sink
            }

            @Override
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onDescriptorWriteRequest(
                    device,
                    requestId,
                    descriptor,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )
                Log.i(
                    tag,
                    "onDescriptorWriteRequest" + "device: " + device?.getAddress()
                        .toString() + "\n" + "descriptor: " + descriptor?.getUuid().toString()
                )
                Log.i(
                    tag,
                    "descriptor permission: " + descriptor?.getPermissions() + "\ndescriptor value:" + value?.toString(
                        Charsets.UTF_8
                    )
                )


                var descriptorUuid: String = descriptor?.getUuid().toString()

                if (descriptorUuid.equals(CccdUUID)) {
                    Log.i(tag, "descriptor equals CccdUUID")
                    mBluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    );

                    if (BuildConfig().getVersionSDKInt() >= Build.VERSION_CODES.M) {
                        mBluetoothGatt = device?.connectGatt(
                            context,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE
                        )

                        if (Build.VERSION.SDK_INT >= 33) {
                            Log.i(tag, "device: ${device?.getAddress().toString()}")
                            Log.i(tag, "write descriptor in api 33 style")
                            if (descriptor == null || value == null || mBluetoothGatt == null) {
                                Log.i(tag, "descriptor or value is null")
                                return
                            }
                            try {
                                mBluetoothGatt?.writeDescriptor(descriptor!!, value!!)
                            } catch (e: Exception){
                                descriptor?.setValue(value);
                            }
                        } else {
                            Log.i(tag, "write descriptor in api 32 and lower style")
                            descriptor?.setValue(value);
                        }

                    }

                    // TODO sent event to flutter with central connetion changed
                    var deviceID: String = device?.getAddress().toString()
                    mCentralBluetoothDevice = device!!
                    Log.i(
                        tag,
                        "centralConnectionUpdateBehaviorSubject.onNext" + centralConnectionUpdateBehaviorSubject + " deviceID=" + deviceID
                    )
                    centralConnectionUpdateBehaviorSubject.onNext(
                        ConnectionUpdateSuccess(
                            deviceID,
                            1 /*CONNECTED*/
                        )
                    )
                }
            }

            @Override
            override fun onDescriptorReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor?
            ) {
                super.onDescriptorReadRequest(device, requestId, offset, descriptor)
                Log.i(tag, "onDescriptorReadRequest")

                Log.d(tag, "Device tried to read descriptor: " + descriptor?.getUuid());
                if (offset != 0) {
                    mBluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        /* value (optional) */ null
                    );
                    return;
                }
                mBluetoothGattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    descriptor?.getValue()
                );
            }

            @Override
            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                super.onNotificationSent(device, status)
                Log.i(tag, "onNotificationSent")
            }

            @Override
            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                super.onMtuChanged(device, mtu)
                Log.i(tag, "onMtuChanged")
            }

            @Override
            override fun onExecuteWrite(
                device: BluetoothDevice?,
                requestId: Int,
                execute: Boolean
            ) {
                super.onExecuteWrite(device, requestId, execute)
                Log.i(tag, "onExecuteWrite")
            }
        }

        CCCD1.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        Characteristic1.addDescriptor(CCCD1);

        CCCD2.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        Characteristic2.addDescriptor(CCCD2);

        CCCD3.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        Characteristic3.addDescriptor(CCCD3);

        CCCDUartRx.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        UartCharRx.addDescriptor(CCCDUartRx);

        // add characterisitic to a service
        addService(service1, Characteristic1)
        addService(service2, Characteristic2)
        addService(service3, Characteristic3)
        addService(uartService, UartCharRx)
        addService(uartService, UartCharTx)

        mBluetoothGattServer = bluetoothManager.openGattServer(
            context,
            serverCallback
        )//.also { it.addService(service1service) }

        serviceUUIDsList.add(SrvUUID1);
        serviceUUIDsList.add(SrvUUID2);
        serviceUUIDsList.add(SrvUUID3);
        serviceUUIDsList.add(UartSrvUUID);

        // add service to a local mutable key value map
        gattServices.put(SrvUUID1, service1)
        gattServices.put(SrvUUID2, service2)
        gattServices.put(SrvUUID3, service3)
        gattServices.put(UartSrvUUID, uartService)


        // add first service to the gattserver
        if (mBluetoothGattServer != null) {
            while (!mBluetoothGattServer!!.addService(service1));
        }
    }

    private fun logProfileState(newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(tag, "PROFILE: STATE_CONNECTED")
        } else if (newState == BluetoothProfile.STATE_CONNECTING) {
            Log.i(tag, "PROFILE: STATE_CONNECTING")
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(tag, "PROFILE: STATE_DISCONNECTED")
        } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
            Log.i(tag, "PROFILE: STATE_DISCONNECTING")
        }
    }

    private fun addService(
        service: BluetoothGattService,
        characteristic: BluetoothGattCharacteristic
    ) {
        var btChar: BluetoothGattCharacteristic? = null
        btChar = service.getCharacteristic(characteristic.uuid)
        if (btChar == null) {
            while (!service.addCharacteristic(characteristic)) {
                Log.d(
                    tag,
                    "could not add characteristic: ${characteristic.uuid} to service: ${service.uuid}"
                )
            }
            Log.d(tag, "characteristic ${characteristic.uuid} added to service ${service.uuid}")
        } else {
            Log.d(
                tag,
                "characteristic: ${characteristic.uuid} already exists in service ${service.uuid}"
            )
        }
    }

    override fun stopAdvertising() {
        val bluetoothManager: BluetoothManager =
            ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
        val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.getBluetoothLeAdvertiser()

        if (advertiser == null) {
            Log.d(tag, "can not stop advertising, advertiser is null")
            return;
        }
//        advertiser.stopAdvertising(advertiseCallback)
    }

    override fun startGattServer() {
        // TODO Move from addExampleGattService to startGattServer
        addExampleGattService()
    }

    override fun stopGattServer() {
        Log.d(tag, "stop gatt server!")
        try {

            if (mBluetoothGattServer == null) {
                Log.d(tag, "gatt server is null!!!!")
                return
            }

            serviceUUIDsList.remove(SrvUUID1)
            serviceUUIDsList.remove(SrvUUID2)
            serviceUUIDsList.remove(SrvUUID3)
            serviceUUIDsList.remove(UartSrvUUID)

            // remove service from the service map and use it's values
            val service1: BluetoothGattService? = gattServices.remove(SrvUUID1)
            val service2: BluetoothGattService? = gattServices.remove(SrvUUID2)
            val service3: BluetoothGattService? = gattServices.remove(SrvUUID3)
            val serviceUart: BluetoothGattService? = gattServices.remove(UartSrvUUID)

            removeService(service1)
            removeService(service2)
            removeService(service3)
            removeService(serviceUart)

            mBluetoothGattServer?.clearServices()
            mBluetoothGattServer?.close()

            mBluetoothGattServer = null
        } catch (error: Exception) {
            Log.e(tag, error.toString())
        }
    }

    private fun removeService(service: BluetoothGattService?) {
        if (mBluetoothGattServer == null || service == null) {
            Log.d(tag, "can not remove gatt service ${service}, gatt server is null")
            return
        }
        while (!mBluetoothGattServer!!.removeService(service)) {
            Log.d(tag, "gatt service $service not removed")
        }
        Log.d(tag, "gatt service $service removed")
    }

    override fun addGattService() {
        // TODO Move from addExampleGattService to addGattService
        // Note: Only add one Service add the time and wait for the onServiceAdded event.
        //       After receiving onServiceAdded add next service
    }

    override fun addGattCharacteristic() {
        // TODO Move from addExampleGattService to addGattCharacteristic
    }

    /**
     * Checks if a bonded device is of type classic bonding. This can be determined by the device type.
     * The device types value can be [BluetoothDevice.DEVICE_TYPE_CLASSIC] or [BluetoothDevice.DEVICE_TYPE_DUAL]
    */
    @TargetApi(34)
    fun isClassicBonding(device: BluetoothDevice, ctx: Context?, buildCnfg: BuildConfig?): Boolean {
        try {
            val config = buildCnfg ?: BuildConfig()
            val btPermission = when (config.getVersionSDKInt()) {
                in 1..Build.VERSION_CODES.R -> Manifest.permission.BLUETOOTH
                else -> Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ActivityCompat.checkSelfPermission(
                    ctx ?: context,
                    btPermission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(tag, "Bt permission denied");
                return false
            }
            return (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) || (device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL)
        } catch (e: Exception) {
            Log.i(tag, "An error occured while checking the iNet System bonding type")
            Log.i(tag, e.toString())
            return false;
        }
    }

    /**
     * Checks if old bonding exists, and if, allows showing the delete iNetBox Bondings pop up.
     * */
    @TargetApi(34)
    override fun checkIfOldInetBoxBondingExists(deviceId: String): Boolean {
        try {
            val bluetoothManager: BluetoothManager =
                ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

            Log.i(tag, "check bonding for device id: $deviceId");


            val btPermission =
                when (com.signify.hue.flutterreactiveble.utils.BuildConfig().getVersionSDKInt()) {
                    in 1..Build.VERSION_CODES.R -> Manifest.permission.BLUETOOTH
                    else -> Manifest.permission.BLUETOOTH_CONNECT
                }
            /*if (BuildConfig().checkSelfPermission(
                    context,
                    btPermission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(tag, "Bluetooth permission not granted!");
                return false
            }*/
            if (ActivityCompat.checkSelfPermission(
                    context,
                    btPermission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(tag, "Bluetooth permission not granted!");
                return false
            }

            val bondedDevices: Set<BluetoothDevice> = bluetoothAdapter.getBondedDevices()
            val bondedDevice = searchForBondedDevice(deviceId, bondedDevices)
            if (bondedDevice != null && isClassicBonding(bondedDevice, null, null)) {
                Log.i(tag, "found old iNet Box bonding")
                return true;
            }
            Log.i(tag, "found no iNet Box bonding")
            return false;
        } catch (e: Exception) {
            Log.i(tag, "An error occured while checking if iNet System bonding exists")
            Log.i(tag, e.toString())
            return false;
        }
    }

    /**
     * If the bonded iNetBox is from type 0x00000003 - DEVICE_TYPE_DUAL this iNetBox was
     * bonded via the old app built with cordova.
     * If the bonded iNetBox is from type 0x00000002 - DEVICE_TYPE_LE this iNetBox was
     * bonded via the new flutter app.
     * https://developer.android.com/reference/android/bluetooth/BluetoothDevice#DEVICE_TYPE_DUAL
     * If [forceDelete] is set, the bonded device will be removed regardless of the device type.
     * */
    override fun removeInetBoxBonding(
        deviceId: String,
        forceDelete: Boolean,
        context: Context?,
    ): Boolean {
        try {
            val ctx = context ?: this.context
            val btManager: BluetoothManager =
                ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val bluetoothAdapter: BluetoothAdapter = btManager.adapter

            val btPermission = when (BuildConfig().getVersionSDKInt()) {
                in 1..Build.VERSION_CODES.R -> Manifest.permission.BLUETOOTH
                else -> Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ActivityCompat.checkSelfPermission(
                    ctx,
                    btPermission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(tag, "Bt permission denied");
                return false
            }

            Log.i(tag, "Bluetooth permission not granted!")
            val bondedDevices: Set<BluetoothDevice> = bluetoothAdapter.getBondedDevices()

            Log.i(tag, "start remove bonding for device id: $deviceId");

            val foundDevice: BluetoothDevice? = searchForBondedDevice(deviceId, bondedDevices)

            if (foundDevice == null) {
                Log.i(tag, "Found no related device to remove bonding")
                return false;
            }

            val isBtClassicBonding: Boolean = isClassicBonding(foundDevice, null, null)

            Log.i(tag, "is classic bonding: $isBtClassicBonding")
            Log.i(tag, "forceDelete: $forceDelete")

            if (isBtClassicBonding || forceDelete) {
                Log.i(tag, "found iNet Box bonding - remove bond")
                val pair = foundDevice.javaClass.getMethod("removeBond")
                pair.invoke(foundDevice)
                Log.i(tag, "remove iNet Box bonding - success")
                return true;
            }
            Log.i(tag, "No classic bonding found, neither forcing deletion.")
            return false;
        } catch (e: Exception) {
            Log.i(tag, "Error removing bonding: $e");
            return false;
        }
    }


    /**
     * [deviceId] is empty, in case of an existing bonding with the iNet Box, paired through the
     * Truma app. Thus, one have to check if the deviceName contains the string "iNet Box" as the
     * iNet System app has no information about the current device address.
     *
     * After the first connection, set up with the iNet System app, the mac address of the
     * connected iNet Box is stored locally on the device.
     *
     * The deletion of the bonded device is necessary for the change from bluetooth classic on
     * Android onto bluetooth low energy. This action helps to clean up the cached bluetooth
     * services after a firmware migration.
     * */
    @SuppressLint("MissingPermission")
    fun searchForBondedDevice(
        deviceId: String,
        bondedDevices: Set<BluetoothDevice>
    ): BluetoothDevice? {
        try {
            for (device in bondedDevices) {

                val deviceName: String? = device.name
                Log.i(tag, "found device");
                Log.i(tag, deviceName.toString());
                Log.i(tag, device.type.toString());
                Log.i(tag, device.toString());
                Log.i(tag, device.address.toString());
                val isSearchedDevice: Boolean =
                    (device.getAddress() == deviceId) || (deviceName ?: "").contains("iNet Box")

                if (isSearchedDevice) {
                    Log.i(tag, "found bonded iNet Box");
                    return device;
                }
            }
            Log.i(tag, "No existing bonding found!");
            return null;
        } catch (e: Exception) {
            Log.e(tag, "Exception while iterating over bondings");
            Log.e(tag, "Exception: $e");
            return null;
        }
    }

    override fun writeLocalCharacteristic(
        deviceId: String,
        characteristic: UUID,
        value: ByteArray
    ) {
        // TODO write to local characteristic and notify
        val servicesList: List<BluetoothGattService> = mBluetoothGattServer!!.getServices()

        for (element in servicesList) {
            val bluetoothGattService: BluetoothGattService = element

            val bluetoothGattCharacteristicList: List<BluetoothGattCharacteristic> =
                bluetoothGattService.getCharacteristics()
            for (bluetoothGattCharacteristic in bluetoothGattCharacteristicList) {
                if (bluetoothGattCharacteristic.getUuid().equals(characteristic)) {
                    bluetoothGattCharacteristic.setValue(value)
                    // TODO This method was deprecated in API level 33.
                    // https://developer.android.com/reference/android/bluetooth/BluetoothGattServer
                    // mBluetoothGattServer!!.notifyCharacteristicChanged(mCentralBluetoothDevice, bluetoothGattCharacteristic, false, value)
                    mBluetoothGattServer?.notifyCharacteristicChanged(
                        mCentralBluetoothDevice,
                        bluetoothGattCharacteristic,
                        false
                    )
                }
            }
        }
    }

    override fun observeBleStatus(): Observable<BleStatus> = rxBleClient.observeStateChanges()
        .startWith(rxBleClient.state)
        .map { it.toBleState() }

    @VisibleForTesting
    internal open fun createDeviceConnector(device: RxBleDevice, timeout: Duration) =
        DeviceConnector(device, timeout, connectionUpdateBehaviorSubject::onNext, connectionQueue)

    private fun getConnection(
        deviceId: String,
        timeout: Duration = Duration(0, TimeUnit.MILLISECONDS)
    ): Observable<EstablishConnectionResult> {
        val device = rxBleClient.getBleDevice(deviceId)
        val connector =
            activeConnections.getOrPut(deviceId) { createDeviceConnector(device, timeout) }

        return connector.connection
    }

    private fun checkForActiveConnection(
        deviceId: String,
    ): Boolean {
        return activeConnections.contains(deviceId)
    }

    private fun executeReadOperation(
        deviceId: String,
        service: UUID,
        characteristic: UUID
    ): Single<CharOperationResult> {
        return getConnection(deviceId).flatMapSingle<CharOperationResult> { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection ->
                    //connectionResult.rxConnection.readCharacteristic(characteristic)
                    connectionResult.rxConnection.readChar(service, characteristic)
                        /*
                        On Android7 the ble stack frequently gives incorrectly
                        the error GAT_AUTH_FAIL(137) when reading char that will establish
                        the bonding with the peripheral. By retrying the operation once we
                        deviate between this flaky one time error and real auth failed cases
                        */
                        .retry(1) { BuildConfig().getVersionSDKInt() < Build.VERSION_CODES.O }
                        .map { value ->
                            CharOperationSuccessful(deviceId, value.asList())
                        }

                is EstablishConnectionFailure ->
                    Single.just(
                        CharOperationFailed(
                            deviceId,
                            "failed to connect ${connectionResult.errorMessage}"
                        )
                    )
            }
        }.first(CharOperationFailed(deviceId, "read char failed"))
    }

    private fun executeWriteOperation(
        deviceId: String,
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
        bleOperation: RxBleConnection.(service: UUID, characteristic: UUID, value: ByteArray) -> Single<ByteArray>
    ): Single<CharOperationResult> {
        return getConnection(deviceId)
            .flatMapSingle<CharOperationResult> { connectionResult ->
                when (connectionResult) {
                    is EstablishedConnection -> {
                        connectionResult.rxConnection.bleOperation(service, characteristic, value)
                            .map { value -> CharOperationSuccessful(deviceId, value.asList()) }
                    }

                    is EstablishConnectionFailure -> {
                        Single.just(
                            CharOperationFailed(
                                deviceId,
                                "failed to connect ${connectionResult.errorMessage}"
                            )
                        )
                    }
                }
            }.first(CharOperationFailed(deviceId, "Writechar timed-out"))
    }

    private fun setupNotificationOrIndication(
        deviceConnection: EstablishConnectionResult,
        serviceUuid: UUID,
        charUuid: UUID
    ): Observable<Observable<ByteArray>> =

        when (deviceConnection) {
            is EstablishedConnection -> {

                if (rxBleClient.getBleDevice(deviceConnection.deviceId).bluetoothDevice.bondState == BOND_BONDING) {
                    Observable.error(Exception("Bonding is in progress wait for bonding to be finished before executing more operations on the device"))
                } else {
                    deviceConnection.rxConnection.discoverServices()
                        .flatMap { services -> services.getService(serviceUuid) }
                        .map { service -> service.getCharacteristic(charUuid) }
                        .flatMapObservable { characteristic ->
                            val mode = if (characteristic.descriptors.isEmpty()) {
                                NotificationSetupMode.COMPAT
                            } else {
                                NotificationSetupMode.DEFAULT
                            }

                            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                deviceConnection.rxConnection.setupNotification(
                                    characteristic,
                                    mode
                                )
                            } else {
                                deviceConnection.rxConnection.setupIndication(characteristic, mode)
                            }
                        }
                }
            }

            is EstablishConnectionFailure -> {
                Observable.just(Observable.empty())
            }
        }

    override fun requestConnectionPriority(
        deviceId: String,
        priority: ConnectionPriority
    ): Single<RequestConnectionPriorityResult> =
        getConnection(deviceId).switchMapSingle<RequestConnectionPriorityResult> { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection ->
                    connectionResult.rxConnection.requestConnectionPriority(
                        priority.code,
                        2,
                        TimeUnit.SECONDS
                    )
                        .toSingle {
                            RequestConnectionPrioritySuccess(deviceId)
                        }

                is EstablishConnectionFailure -> Single.fromCallable {
                    RequestConnectionPriorityFailed(deviceId, connectionResult.errorMessage)
                }
            }
        }.first(RequestConnectionPriorityFailed(deviceId, "Unknown failure"))

    // enable this for extra debug output on the android stack
    private fun enableDebugLogging() = RxBleClient
        .updateLogOptions(
            LogOptions.Builder().setLogLevel(LogConstants.VERBOSE)
                .setMacAddressLogSetting(LogConstants.MAC_ADDRESS_FULL)
                .setUuidsLogSetting(LogConstants.UUIDS_FULL)
                .setShouldLogAttributeValues(true)
                .build()
        )

    override fun isDeviceConnected(deviceId: String): Boolean {
        return checkForActiveConnection(deviceId)
    }
}