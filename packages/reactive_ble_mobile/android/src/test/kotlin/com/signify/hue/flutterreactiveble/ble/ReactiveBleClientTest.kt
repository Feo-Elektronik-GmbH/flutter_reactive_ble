package com.signify.hue.flutterreactiveble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.text.TextUtils
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.google.common.truth.Truth.assertThat
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.RxBleDeviceServices
import com.signify.hue.flutterreactiveble.BuildConfig as FBuildConfig
import com.signify.hue.flutterreactiveble.utils.Duration as FDuration
import com.signify.hue.flutterreactiveble.ble.extensions.writeCharWithResponse
import com.signify.hue.flutterreactiveble.ble.extensions.writeCharWithoutResponse
import com.signify.hue.flutterreactiveble.utils.BuildConfig
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.BehaviorSubject
import junit.framework.Assert.assertTrue
import junit.framework.TestCase.assertFalse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private class BleClientForTesting(
    val bleClient: RxBleClient,
    appContext: Context,
    val deviceConnector: DeviceConnector
) : ReactiveBleClient(appContext) {

    override fun initializeClient() {
        rxBleClient = bleClient
        activeConnections = mutableMapOf()
    }

    override fun createDeviceConnector(device: RxBleDevice, timeout: FDuration): DeviceConnector =
        deviceConnector
}

@DisplayName("BleClient unit tests")
class ReactiveBleClientTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var rxBleClient: RxBleClient

    @MockK
    private lateinit var deviceConnector: DeviceConnector

    @MockK
    private lateinit var bleDevice: RxBleDevice

    @MockK
    private lateinit var bluetoothDevice: BluetoothDevice

    @MockK
    lateinit var rxConnection: RxBleConnection

    private lateinit var subject: BehaviorSubject<EstablishConnectionResult>

    private lateinit var sut: BleClientForTesting

    private val testTimeout = FDuration(100L, TimeUnit.MILLISECONDS)

    private var testScheduler: TestScheduler? = null

    private var deviceId: String = ""

    @BeforeEach
    fun before() {
        testScheduler = TestScheduler()
        // Set calls to AndroidSchedulers.mainThread() to use the test scheduler
        RxAndroidPlugins.setMainThreadSchedulerHandler { testScheduler }
    }

    @AfterEach
    fun after() {
        RxAndroidPlugins.reset()
    }

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic("com.signify.hue.flutterreactiveble.ble.extensions.RxBleConnectionExtensionKt")
        subject = BehaviorSubject.create<EstablishConnectionResult>()

        sut = BleClientForTesting(rxBleClient, context, deviceConnector)
        sut.initializeClient()

        every { bleDevice.observeConnectionStateChanges() }.returns(Observable.just(RxBleConnection.RxBleConnectionState.CONNECTED))
        every { rxBleClient.getBleDevice(any()) }.returns(bleDevice)
        every { deviceConnector.connection }.returns(subject)

        subject.onNext(EstablishedConnection("test", rxConnection))
    }

    @AfterEach
    fun teardown() {
        subject.onComplete()
    }


    @DisplayName("Establishing a connection")
    @Nested
    inner class EstablishConnectionTest {

        @Test
        fun `should use deviceconnector when connecting to a device`() {
            sut.connectToDevice("test", testTimeout)

            verify(exactly = 1) { deviceConnector.connection }
        }
    }

    @Nested
    @DisplayName("Writing reading and subscribing to characteristics")
    inner class BleOperationsTest {
        @SuppressLint("CheckResult")
        @Test
        fun `should call readcharacteristic in case the connection is established`() {
            sut.readCharacteristic("test", UUID.randomUUID(), UUID.randomUUID()).test()

            verify(exactly = 1) { rxConnection.readCharacteristic(any<UUID>()) }
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should not call readcharacteristic in case the connection is not established`() {
            subject.onNext(EstablishConnectionFailure("test", "error"))

            sut.readCharacteristic("test", UUID.randomUUID(), UUID.randomUUID()).test()

            verify(exactly = 0) { rxConnection.readCharacteristic(any<UUID>()) }
        }

        @Test
        fun `should report failure in case reading characteristic fails`() {
            subject.onNext(EstablishConnectionFailure("test", "error"))

            val observable =
                sut.readCharacteristic("test", UUID.randomUUID(), UUID.randomUUID()).test()

            assertThat(observable.values().first()).isInstanceOf(CharOperationFailed::class.java)
        }

        @Test
        fun `should incorporate the value in case readcharacteristics succeeds`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE

            every { rxConnection.readCharacteristic(any<UUID>()) }.returns(
                Single.just(
                    byteArrayOf(
                        byteMin,
                        byteMax
                    )
                )
            )
            val observable = sut.readCharacteristic("test", UUID.randomUUID(), UUID.randomUUID())
                .map { result -> result as CharOperationSuccessful }.test()

            assertThat(observable.values().first().value).isEqualTo(listOf(byteMin, byteMax))
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should call writecharacteristicResponse in case the connection is established`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)

            sut.writeCharacteristicWithResponse("test", UUID.randomUUID(), UUID.randomUUID(), bytes)
                .test()

            verify(exactly = 1) {
                rxConnection.writeCharWithResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should not call writecharacteristicWithoutResponse in case the connection is established`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)

            sut.writeCharacteristicWithResponse("test", UUID.randomUUID(), UUID.randomUUID(), bytes)
                .test()

            verify(exactly = 0) {
                rxConnection.writeCharWithoutResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should not call writecharacteristicResponse in case the connection is not established`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)
            subject.onNext(EstablishConnectionFailure("test", "error"))

            sut.writeCharacteristicWithResponse("test", UUID.randomUUID(), UUID.randomUUID(), bytes)
                .test()

            verify(exactly = 0) {
                rxConnection.writeCharWithResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should call writecharacteristicWithoutResponse in case the connection is established`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)

            sut.writeCharacteristicWithoutResponse(
                "test",
                UUID.randomUUID(),
                UUID.randomUUID(),
                bytes
            ).test()

            verify(exactly = 1) {
                rxConnection.writeCharWithoutResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }
        }

        @SuppressLint("CheckResult")
        @Test
        fun `should not call writecharacteristicWithoutResponse in case the connection is not established`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)
            subject.onNext(EstablishConnectionFailure("test", "error"))


            sut.writeCharacteristicWithoutResponse(
                "test",
                UUID.randomUUID(),
                UUID.randomUUID(),
                bytes
            ).test()

            verify(exactly = 0) {
                rxConnection.writeCharWithoutResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }
        }

        @Test
        fun `should report failure in case writing characteristic fails`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)

            subject.onNext(EstablishConnectionFailure("test", "error"))

            val observable =
                sut.writeCharacteristicWithResponse(
                    "test",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    bytes
                ).test()

            assertThat(observable.values().first()).isInstanceOf(CharOperationFailed::class.java)
        }

        @Test
        fun `should incorporate the value in case writecharacteristic succeeds`() {
            val byteMin = Byte.MIN_VALUE
            val byteMax = Byte.MAX_VALUE
            val bytes = byteArrayOf(byteMin, byteMax)

            every {
                rxConnection.writeCharWithResponse(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    any()
                )
            }.returns(
                Single.just(
                    byteArrayOf(byteMin, byteMax)
                )
            )
            val observable = sut.writeCharacteristicWithResponse(
                "test",
                UUID.randomUUID(),
                UUID.randomUUID(),
                bytes
            )
                .map { result -> result as CharOperationSuccessful }.test()

            assertThat(observable.values().first().value).isEqualTo(bytes.toList())
        }
    }

    @Nested
    @DisplayName("Negotiate mtu")
    inner class NegotiateMtuTest {

        @Test
        fun `should return mtunegotiatesuccesful in case it succeeds`() {
            val mtuSize = 19
            every { rxConnection.requestMtu(any()) }.returns(Single.just(mtuSize))

            val result = sut.negotiateMtuSize("", mtuSize).test()

            assertThat(result.values().first()).isInstanceOf(MtuNegotiateSuccesful::class.java)
        }

        @Test
        fun `should return mtunegotiatefailed in case it fails`() {
            val mtuSize = 19
            subject.onNext(EstablishConnectionFailure("test", "error"))

            val result = sut.negotiateMtuSize("", mtuSize).test()

            assertThat(result.values().first()).isInstanceOf(MtuNegotiateFailed::class.java)
        }
    }

    @Nested
    @DisplayName("Observe status")
    inner class ObserveBleStatusTest {
        private val currentStatus = RxBleClient.State.BLUETOOTH_NOT_ENABLED
        private val changedStatus = RxBleClient.State.READY

        @BeforeEach
        fun setup() {
            every { rxBleClient.observeStateChanges() }.returns(Observable.just(changedStatus))
            every { rxBleClient.state }.returns(currentStatus)
        }

        @Test
        fun `observes status changes`() {
            val result = sut.observeBleStatus().test()

            assertThat(result.values().last()).isEqualTo(BleStatus.READY)
            assertThat(result.values().count()).isEqualTo(2)
        }

        @Test
        fun `starts with current state`() {

            val result = sut.observeBleStatus().test()
            assertThat(result.values().count()).isEqualTo(2)
            assertThat(result.values().first()).isEqualTo(BleStatus.POWERED_OFF)
        }
    }

    @Nested
    @DisplayName("Change priority")
    inner class ChangePriorityTest {

        @Test
        fun `returns prioritysuccess when  completed`() {
            val completer = Completable.fromCallable { true }

            every { rxConnection.requestConnectionPriority(any(), any(), any()) }.returns(completer)
            val result = sut.requestConnectionPriority("", ConnectionPriority.BALANCED).test()
            assertThat(
                result.values().first()
            ).isInstanceOf(RequestConnectionPrioritySuccess::class.java)
        }

        @Test
        fun `returns false when connectionfailed`() {
            subject.onNext(EstablishConnectionFailure("test", "error"))
            val result = sut.requestConnectionPriority("", ConnectionPriority.BALANCED).test()
            assertThat(
                result.values().first()
            ).isInstanceOf(RequestConnectionPriorityFailed::class.java)
        }
    }

    @Nested
    @DisplayName("Discover services")
    inner class DiscoverServicesTest {

        @BeforeEach
        fun setup() {
            every { bleDevice.bluetoothDevice }.returns(bluetoothDevice)
            every { bluetoothDevice.bondState }.returns(BOND_BONDED)
        }

        @Test
        fun `It returns success in case services can be discovered`() {
            every { rxConnection.discoverServices() }.returns(Single.just(RxBleDeviceServices(listOf())))

            val result = sut.discoverServices("test").test()

            assertThat(result.values().first()).isInstanceOf(RxBleDeviceServices::class.java)
        }

        @Test
        fun `It returns failure when connectionfailed`() {
            subject.onNext(EstablishConnectionFailure("test", "error"))
            val result = sut.discoverServices("test").test()
            result.assertError(Exception::class.java)
        }
    }

    @Nested
    @DisplayName("Search for bonded device")
    inner class SearchForBondedDeviceTest {

        @BeforeEach
        fun setup() {
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { bleDevice.bluetoothDevice }.returns(bluetoothDevice)
            every { bluetoothDevice.type } returns 2
        }

        @Test
        fun `returns device when deviceId matches`() {

            val deviceId = "00:11:22:33:44:55"
            every { bluetoothDevice.address } returns deviceId
            every { bluetoothDevice.name } returns "iNet Box"
            val bondedDevices = setOf(bluetoothDevice)
            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isEqualTo(bluetoothDevice)
        }

        @Test
        fun `returns device when device name contains iNet Box`() {
            val deviceId = "00:11:22:33:44:55"
            every { bluetoothDevice.address } returns "66:77:88:99:AA:BB"
            every { bluetoothDevice.name } returns "iNet Box"


            val bondedDevices = setOf(bluetoothDevice)

            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isEqualTo(bluetoothDevice)
        }

        @Test
        fun `returns null when no matching device found`() {
            val deviceId = "00:11:22:33:44:55"
            val device = mockk<BluetoothDevice>()
            every { device.address } returns "66:77:88:99:AA:BB"
            every { device.name } returns "Other Device"
            val bondedDevices = setOf(device)
            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isNull()
        }

        @Test
        fun `returns null when exception occurs`() {
            val deviceId = "00:11:22:33:44:55"
            val bondedDevices = setOf<BluetoothDevice>()

            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isNull()
        }

        @Test
        fun `find inet box by id`() {
            val deviceId = "00:11:22:33:44:55"
            val device = mockk<BluetoothDevice>()
            every { device.address } returns "66:77:88:99:AA:BB"
            every { device.name } returns "Other Device"
            every { device.type } returns 2

            val device2 = mockk<BluetoothDevice>()
            every { device2.address } returns deviceId
            every { device2.name } returns "unknown"
            every { device2.type } returns 2
            val bondedDevices = setOf(device, device2)

            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isEqualTo(device2)
        }

        @Test
        fun `find inet box by name`() {
            val deviceId = "00:11:22:33:44:55"
            val device = mockk<BluetoothDevice>()
            every { device.address } returns "66:77:88:99:AA:BB"
            every { device.name } returns "Other Device"
            every { device.type } returns 2

            val device2 = mockk<BluetoothDevice>()
            every { device2.address } returns "66:77:88:99:AA:BC"
            every { device2.name } returns "iNet Box"
            every { device2.type } returns 2
            val bondedDevices = setOf(device, device2)

            val result = sut.searchForBondedDevice(deviceId, bondedDevices)

            assertThat(result).isEqualTo(device2)
        }
    }

    @Nested
    @DisplayName("Check for classic bonding")
    inner class CheckForClassicBonding {

        @BeforeEach
        fun setup() {
            mockkStatic(Log::class)
            mockkStatic(TextUtils::class)
            mockkStatic(NotificationManagerCompat::class)
            mockkStatic(ActivityCompat::class)
            mockkStatic(Process::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { bleDevice.bluetoothDevice }.returns(bluetoothDevice)

        }

        @Test
        fun `test permission denied returns false`() {
            // Mock permission check to simulate denied permission
            val mockContext = mockk<Context>()
            val mBtManager = mockk<BluetoothManager>()
            val mBtAdapter = mockk<BluetoothAdapter>()
            val buildConfig = mockk<BuildConfig>()

            mockkStatic(Log::class)
            mockkStatic(TextUtils::class)
            mockkStatic(NotificationManagerCompat::class)
            mockkStatic(ActivityCompat::class)
            mockkStatic(Process::class)

            every { buildConfig.getVersionSDKInt() } returns 29

            val btPermission = when (buildConfig.getVersionSDKInt()) {
                in 1..Build.VERSION_CODES.R -> Manifest.permission.BLUETOOTH
                else -> Manifest.permission.BLUETOOTH_CONNECT
            }

            every { Process.myPid() } returns 1234
            every { Process.myUid() } returns 5678
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns mBtManager
            every {
                context.checkPermission(
                    btPermission,
                    Process.myPid(),
                    Process.myUid()
                )
            } returns PackageManager.PERMISSION_DENIED
            every { mBtManager.adapter } returns mBtAdapter

            val device = mockk<BluetoothDevice>()
            every { device.address } returns "66:77:88:99:AA:BB"
            every { device.name } returns "iNet Box"
            every { device.type } returns 2

            every {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                )
            } returns PackageManager.PERMISSION_DENIED
            every {
                ActivityCompat.checkSelfPermission(
                    mockContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } returns PackageManager.PERMISSION_DENIED

            every { TextUtils.equals(any(), any()) } returns true
            every { NotificationManagerCompat.from(context).areNotificationsEnabled() } returns true
            // Call the function
            val result = sut.isClassicBonding(device, context, buildConfig)

            // Assert that the result is false due to denied permission
            assertThat(result).isFalse()
        }

        @Test
        fun `test device is classic returns true`() {
            // Mock permission check to simulate granted permission
            val mockContext = mockk<Context>()
            every {
                ActivityCompat.checkSelfPermission(
                    mockContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } returns PackageManager.PERMISSION_GRANTED

            // Mock device type to return CLASSIC
            every { bluetoothDevice.type } returns BluetoothDevice.DEVICE_TYPE_CLASSIC

            // Call the function
            val result = sut.isClassicBonding(bluetoothDevice, null, null)

            // Assert that the result is true for a classic device
            assertThat(result).isTrue()
        }

        @Test
        fun `test device is dual returns true`() {
            // Mock permission check to simulate granted permission
            val mockContext = mockk<Context>()
            every {
                ActivityCompat.checkSelfPermission(
                    mockContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } returns PackageManager.PERMISSION_GRANTED

            // Mock device type to return DUAL
            every { bluetoothDevice.type } returns BluetoothDevice.DEVICE_TYPE_DUAL

            // Call the function
            val result = sut.isClassicBonding(bluetoothDevice, null, null)

            // Assert that the result is true for a dual device
            assertThat(result).isTrue()
        }

        @Test
        fun `test device is neither classic nor dual returns false`() {
            // Mock permission check to simulate granted permission
            val mockContext = mockk<Context>()
            every {
                ActivityCompat.checkSelfPermission(
                    mockContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } returns PackageManager.PERMISSION_GRANTED

            // Mock device type to return something else (e.g., LE)
            every { bluetoothDevice.type } returns BluetoothDevice.DEVICE_TYPE_LE

            // Call the function
            val result = sut.isClassicBonding(bluetoothDevice, null, null)

            // Assert that the result is false for non-classic/non-dual devices
            assertThat(result).isFalse()
        }

        @Test
        fun `test exception handling returns false`() {
            // Simulate an exception during permission check
            val mockContext = mockk<Context>()
            every {
                ActivityCompat.checkSelfPermission(
                    mockContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } throws RuntimeException("Simulated exception")

            // Call the function
            val result = sut.isClassicBonding(bluetoothDevice, null, null)

            // Assert that the result is false due to exception
            assertThat(result).isFalse()
        }
    }

    @Nested
    @DisplayName("Remove bonded device")
    inner class RemoveBondedDeviceTest {

        @BeforeEach
        fun setup() {
            mockkStatic(Log::class)
            mockkStatic(TextUtils::class)
            mockkStatic(NotificationManagerCompat::class)
            mockkStatic(ActivityCompat::class)
            mockkStatic(Process::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { bleDevice.bluetoothDevice }.returns(bluetoothDevice)
            every { bluetoothDevice.type } returns 2
        }

        // todo: fix test
        /*@Test
        fun removeInetBoxBonding() {
            val context = mockk<Context>()
            val mBtManager = mockk<BluetoothManager>()
            val mBtAdapter = mockk<BluetoothAdapter>()
            val buildConfig = mockk<BuildConfig>()

            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns mBtManager
//            every { context.getSystemService("android.permission.POST_NOTIFICATIONS") } returns mBtManager
            every { mBtManager.adapter } returns mBtAdapter
            every { mBtAdapter.bondedDevices } returns setOf(bluetoothDevice)

            every { buildConfig.getVersionSDKInt() } returns 34
            every { Process.myPid() } returns 1234
            every { Process.myUid() } returns 5678

            every {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            } returns PackageManager.PERMISSION_GRANTED
            every { TextUtils.equals(any(), any()) } returns true
            every { NotificationManagerCompat.from(context).areNotificationsEnabled() } returns true

            every { bluetoothDevice.address } returns deviceId
            every { bluetoothDevice.name } returns "iNet Box"


            val result =
                sut.removeInetBoxBonding(deviceId, forceDelete = false, context)
            assertThat(result).isFalse()
        }*/
    }


}
