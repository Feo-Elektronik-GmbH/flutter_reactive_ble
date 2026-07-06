import class CoreBluetooth.CBUUID
import class CoreBluetooth.CBService
import enum CoreBluetooth.CBManagerState
import var CoreBluetooth.CBAdvertisementDataServiceDataKey
import var CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import var CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import var CoreBluetooth.CBAdvertisementDataIsConnectable
import var CoreBluetooth.CBAdvertisementDataLocalNameKey
import class CoreBluetooth.CBCharacteristic
import class CoreBluetooth.CBCentral

final class PluginController {

    struct Scan {
        let services: [CBUUID]
    }

    private var central: Central?
    private var scan: StreamingTask<Scan>?
    var stateSink: EventSink? {
        didSet {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                self?.reportState()
            }
        }
    }
    var messageQueue: [CharacteristicValueInfo] = []
    var connectedDeviceSink: EventSink?
    // [refs #44134] Buffers connection-state updates produced before the connected-device EventChannel is
    // subscribed (connectedDeviceSink still nil) — e.g. the post-discovery "connected" DeviceInfo on a fast
    // bonded/cached-GATT connect, where the Dart round-trip that arms the sink loses the race. Drained in
    // connectedDeviceStreamHandler.onListen; cleared on onCancel, on disconnect, and at connectToDevice
    // start. Replaces the old fatal assert(false)-on-nil handling that crashed (debug) / silently dropped
    // the update (release), which prevented reaching the connected/ready state at all.
    var connectionUpdateQueue: [DeviceInfo] = []
    var characteristicValueUpdateSink: EventSink?
    var connectedCentralSink: EventSink?
    var characteristicCentralValueUpdateSink: EventSink?
    var servicesChangedCentralUpateSink: EventSink?

    func initialize(name: String, completion: @escaping PlatformMethodCompletionHandler) {
        if let central = central {
            central.stopScan()
            central.disconnectAll()
        }

        central = Central(
            onSubChange: papply(weak: self) { context, _, connectedCentral, characteristic in
                context.reportSub(connectedCentral, changedSub: characteristic)
            },
            onStateChange: papply(weak: self) { context, _, state in
                context.reportState(state)
            },
            onDiscovery: papply(weak: self) { context, _, peripheral, advertisementData, rssi in
                guard let sink = context.scan?.sink
                else { assert(false); return }

                let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? ServiceData ?? [:]
                let serviceUuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
                let isConnectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue
                let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data ?? Data()
                let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? String()
                let serviceDataEntries = serviceData.map { entry in
                    ServiceDataEntry.with {
                        $0.serviceUuid = Uuid.with { $0.data = entry.key.data }
                        $0.data = entry.value
                    }
                }
                let serviceUuidsEntries = serviceUuids.map { entry in
                    Uuid.with { $0.data = entry.data }
                }
                let deviceDiscoveryMessage = DeviceScanInfo.with {
                    $0.id = peripheral.identifier.uuidString
                    $0.name = name
                    $0.rssi = Int32(rssi)
                    $0.isConnectable = IsConnectable()
                    switch isConnectable {
                    case .none:
                      $0.isConnectable.code = 0
                    case .some(let isConnectable):
                      $0.isConnectable.code = isConnectable ? 2 : 1
                    }
                    $0.serviceData = serviceDataEntries
                    $0.serviceUuids = serviceUuidsEntries
                    $0.manufacturerData = manufacturerData
                }

                sink.add(.success(deviceDiscoveryMessage))
            },
            onConnectionChange: papply(weak: self) { context, central, peripheral, change in
                let failure: (code: ConnectionFailure, message: String)?

                switch change {
                case .connected:
                    // Wait for services & characteristics to be discovered
                    return
                case .failedToConnect(let underlyingError), .disconnected(let underlyingError):
                    failure = underlyingError.map { (.failedToConnect, "\($0)") }
                }

                let message = DeviceInfo.with {
                    $0.id = peripheral.identifier.uuidString
                    $0.connectionState = encode(peripheral.state)
                    if let error = failure {
                        $0.failure = GenericFailure.with {
                            $0.code = Int32(error.code.rawValue)
                            $0.message = error.message
                        }
                        print("error: ", error)
                    }
                }

                context.connectedDeviceSink?.add(.success(message))
            },
            onServicesWithCharacteristicsInitialDiscovery: papply(weak: self) { context, central, peripheral, errors in
                print("onServicesWithCharacteristicsInitialDiscovery")
                let message = DeviceInfo.with {
                    $0.id = peripheral.identifier.uuidString
                    $0.connectionState = encode(peripheral.state)
                    if !errors.isEmpty {
                        $0.failure = GenericFailure.with {
                            $0.code = Int32(ConnectionFailure.unknown.rawValue)
                            $0.message = errors.map(String.init(describing:)).joined(separator: "\n")
                        }
                        print("error: ", errors)
                    }
                }

                // [refs #44134] A nil sink here is a legitimate transient: the connected-device EventChannel
                // is subscribed only after connectToDevice's method call round-trips, which can lose the race
                // against a fast bonded/cached-GATT connect. Never assert/drop — buffer the update and let
                // connectedDeviceStreamHandler.onListen drain it once Dart subscribes. (context is a class,
                // so append mutates the stored queue directly — not a copy.)
                if let sink = context.connectedDeviceSink {
                    sink.add(.success(message))
                } else {
                    context.connectionUpdateQueue.append(message)
                }
            },
            onCharacteristicValueUpdate: papply(weak: self) { context, central, characteristic, value, error in
                let message = CharacteristicValueInfo.with {
                    $0.characteristic = CharacteristicAddress.with {
                        $0.characteristicUuid = Uuid.with { $0.data = characteristic.id.data }
                        $0.characteristicInstanceID = characteristic.instanceID
                        $0.serviceUuid = Uuid.with { $0.data = characteristic.serviceID.data }
                        $0.serviceInstanceID = characteristic.serviceInstanceID
                        $0.deviceID = characteristic.peripheralID.uuidString
                    }
                    if let value = value {
                        $0.value = value
                    }
                    if let error = error {
                        $0.failure = GenericFailure.with {
                            $0.code = Int32(CharacteristicValueUpdateFailure.unknown.rawValue)
                            $0.message = "\(error)"
                        }
                    }
                }
                print("onCharacteristicValueUpdate")
                //print("=>", message)
                let sink = context.characteristicValueUpdateSink
                if sink != nil {
                    sink!.add(.success(message))
                } else {
                    // In case message arrives before sink is created
                    context.messageQueue.append(message)
                }

            },
            onCharacteristicSubscribedByCentral: papply(weak: self) { context, central, characteristic, value, error in
                let message = CharacteristicValueInfo.with {
                    $0.characteristic = CharacteristicAddress.with {
                        $0.characteristicUuid = Uuid.with { $0.data = characteristic.id.data }
                        $0.serviceUuid = Uuid.with { $0.data = characteristic.serviceID.data }
                        $0.deviceID = characteristic.peripheralID.uuidString
                    }
                    if let value = value {
                        $0.value = value
                    }
                    if let error = error {
                        $0.failure = GenericFailure.with {
                            $0.code = Int32(CharacteristicValueUpdateFailure.unknown.rawValue)
                            $0.message = "\(error)"
                        }
                    }
                }
                print("onCharacteristicSubscribedByCentral")
                //print("=>", message)
                let sink = context.characteristicCentralValueUpdateSink
                if (sink != nil) {
                    sink!.add(.success(message))
                } else {
                    // In case message arrives before sink is created
                    context.messageQueue.append(message);
                }
            },
            onCharRequest: papply(weak: self) { context, central, peripheral, characteristic, value in
                let message = CharacteristicValueInfo.with {
                    $0.characteristic = CharacteristicAddress.with {
                        $0.characteristicUuid = Uuid.with { $0.data = characteristic.id.data }
                        $0.serviceUuid = Uuid.with { $0.data = characteristic.serviceID.data }
                        $0.deviceID = characteristic.peripheralID.uuidString
                    }
                    if let value = value {
                        $0.value = value
                    }
                }
                print("onCharRequest")
                //print("=>", message)
                let sink = context.characteristicCentralValueUpdateSink
                if (sink != nil) {
                    sink!.add(.success(message))
                } else {
                    // In case message arrives before sink is created
                    context.messageQueue.append(message);
                }
            },
            onDidModifyServices: papply(weak: self) { central in
                self.didModifyServices()
            }
        )
        print("completion!")
        completion(.success(nil))
    }

    func deinitialize(name: String, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.stopScan()
        central.disconnectAll()

        self.central = nil

        completion(.success(nil))
    }

    func scanForDevices(name: String, args: ScanForDevicesRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        assert(!central.isScanning)

        scan = StreamingTask(parameters: .init(services: args.serviceUuids.map({ uuid in CBUUID(data: uuid.data) })))

        completion(.success(nil))
    }

    func startAdvertising(name: String, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.startAdvertising()

        completion(.success(nil))
    }

    func stopAdvertising(name: String, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
                else {
                    completion(.failure(PluginError.notInitialized.asFlutterError))
                    return
                }

        central.stopAdvertising()

        completion(.success(nil))
    }

    func startGattServer(name: String, completion: @escaping PlatformMethodCompletionHandler){
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.startGattServer()

        completion(.success(nil))
    }

    func stopGattServer(name: String, completion: @escaping PlatformMethodCompletionHandler){
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.stopGattServer()

        completion(.success(nil))
    }

    func addGattService(name: String, completion: @escaping PlatformMethodCompletionHandler){
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.addGattService()

        completion(.success(nil))
    }

    func checkIfOldInetBoxBondingExists(name: String, completion: @escaping PlatformMethodCompletionHandler)
    {
        // not implemented / not needed for iOS
        completion(.success(nil))
    }
    func removeInetBoxBonding(name: String, completion: @escaping PlatformMethodCompletionHandler)
    {
        // not implemented / not needed for iOS
        completion(.success(nil))
    }

    func addGattCharacteristic(name: String, completion: @escaping PlatformMethodCompletionHandler){
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        central.addGattCharacteristic()

        completion(.success(nil))
    }

    func writeLocalCharacteristic(name: String, args: WriteCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
            else {
                completion(.failure(PluginError.notInitialized.asFlutterError))
                return
            }

        /*
            guard let characteristic = QualifiedCharacteristicIDFactory().make(from: args.characteristic)
            else {
                completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
                return
            }*/

        do {
            //try central.writeLocalCharacteristic(value: args.value, characteristic: QualifiedCharacteristic(id: characteristic.id, serviceID: characteristic.serviceID, peripheralID: characteristic.peripheralID))
            try central.writeLocalCharacteristic(value: args.value)

        }
        catch {
            completion(.failure(PluginError.notInitialized.asFlutterError))
        }
        completion(.success(nil))
    }

    func startScanning(sink: EventSink) -> FlutterError? {
        guard let central = central
        else { return PluginError.notInitialized.asFlutterError }

        guard let scan = scan
        else { return PluginError.internalInconcictency(details: "a scanning task has not been initialized yet, but a client has subscribed").asFlutterError }

        self.scan = scan.with(sink: sink)

        central.scanForDevices(with: scan.parameters.services)

        return nil
    }

    func stopScanning() -> FlutterError? {
        central?.stopScan()
        return nil
    }

    func connectToDevice(name: String, args: ConnectToDeviceRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let deviceID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "\"deviceID\" is invalid").asFlutterError))
            return
        }

        // [refs #44134] Reset any connection updates buffered for a previous (aborted/undrained) connect
        // so a stale "connected" cannot leak to this attempt's listener.
        connectionUpdateQueue.removeAll()

        let servicesWithCharacteristicsToDiscover: ServicesWithCharacteristicsToDiscover
        if args.hasServicesWithCharacteristicsToDiscover {
            let items = args.servicesWithCharacteristicsToDiscover.items.reduce(
                into: [ServiceID: [CharacteristicID]](),
                { dict, item in
                    let serviceID = CBUUID(data: item.serviceID.data)
                    let characteristicIDs = item.characteristics.map { CBUUID(data: $0.data) }

                    dict[serviceID] = characteristicIDs
                }
            )
            servicesWithCharacteristicsToDiscover = ServicesWithCharacteristicsToDiscover.some(items.mapValues(CharacteristicsToDiscover.some))
        } else {
            servicesWithCharacteristicsToDiscover = .all
        }

        let timeout = args.timeoutInMs > 0 ? TimeInterval(args.timeoutInMs) / 1000 : nil

        completion(.success(nil))

        if let sink = connectedDeviceSink {
            let message = DeviceInfo.with {
                $0.id = args.deviceID
                $0.connectionState = encode(.connected)
                //TODO ensure if connection is already established
            }
            sink.add(.success(message))
        } else {
            print("Warning! No event channel set up to report a connection update")
        }

        do {
            try central.connect(
                to: deviceID,
                discover: servicesWithCharacteristicsToDiscover,
                timeout: timeout
            )
        } catch {
            guard let sink = connectedDeviceSink
            else {
                print("Warning! No event channel set up to report a connection failure: \(error)")
                return
            }

            let message = DeviceInfo.with {
                $0.id = args.deviceID
                $0.connectionState = encode(.disconnected)
                $0.failure = GenericFailure.with {
                    $0.code = Int32(ConnectionFailure.failedToConnect.rawValue)
                    $0.message = "\(error)"
                }
            }

            sink.add(.success(message))
        }
    }

    func disconnectFromDevice(name: String, args: ConnectToDeviceRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let deviceID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "\"deviceID\" is invalid").asFlutterError))
            return
        }

        completion(.success(nil))

        // [refs #44134] Drop any buffered connection updates for this device so a stale "connected"
        // cannot be delivered to a later listener after an explicit disconnect.
        connectionUpdateQueue.removeAll()

        central.disconnect(from: deviceID)
    }

    func discoverServices(name: String, args: DiscoverServicesRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let deviceID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "\"deviceID\" is invalid").asFlutterError))
            return
        }

        func makeDiscoveredService(service: CBService) -> DiscoveredService {
            DiscoveredService.with {
                $0.serviceUuid = Uuid.with { $0.data = service.uuid.data }
                $0.serviceInstanceID = service.instanceId?.description ?? ""
                $0.characteristicUuids = (service.characteristics ?? []).map { characteristic in
                    Uuid.with { $0.data = characteristic.uuid.data }
                }
                $0.characteristics = (service.characteristics ?? []).map { characteristic in
                    DiscoveredCharacteristic.with {
                        $0.characteristicID = Uuid.with {$0.data = characteristic.uuid.data}
                        $0.characteristicInstanceID = characteristic.instanceId?.description ?? ""
                        if characteristic.service?.uuid.data != nil {
                            $0.serviceID = Uuid.with {$0.data = characteristic.service!.uuid.data}
                        }
                        $0.isReadable = characteristic.properties.contains(.read)
                        $0.isWritableWithResponse = characteristic.properties.contains(.write)
                        $0.isWritableWithoutResponse = characteristic.properties.contains(.writeWithoutResponse)
                        $0.isNotifiable = characteristic.properties.contains(.notify)
                        $0.isIndicatable = characteristic.properties.contains(.indicate)
                    }
                }
 
                $0.includedServices = (service.includedServices ?? []).map(makeDiscoveredService)
            }
        }

        do {
            try central.discoverServicesWithCharacteristics(
                for: deviceID,
                discover: .all,
                completion: { central, peripheral, errors in
                    completion(.success(DiscoverServicesInfo.with {
                        $0.deviceID = deviceID.uuidString
                        $0.services = (peripheral.services ?? []).map(makeDiscoveredService)
                    }))
                }
            )
        } catch {
            completion(.failure(PluginError.unknown(error).asFlutterError))
        }
    }

    func getDiscoveredServices(name: String, args: DiscoverServicesRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let deviceID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "\"deviceID\" is invalid").asFlutterError))
            return
        }

        func makeDiscoveredService(service: CBService) -> DiscoveredService {
            DiscoveredService.with {
                $0.serviceUuid = Uuid.with { $0.data = service.uuid.data }
                $0.serviceInstanceID = service.instanceId?.description ?? ""
                $0.characteristicUuids = (service.characteristics ?? []).map { characteristic in
                    Uuid.with { $0.data = characteristic.uuid.data }
                }
                $0.characteristics = (service.characteristics ?? []).map { characteristic in
                    DiscoveredCharacteristic.with {
                        $0.characteristicID = Uuid.with {$0.data = characteristic.uuid.data}
                        $0.characteristicInstanceID = characteristic.instanceId?.description ?? ""
                        if characteristic.service?.uuid.data != nil {
                            $0.serviceID = Uuid.with {$0.data = characteristic.service!.uuid.data}
                        }
                        $0.isReadable = characteristic.properties.contains(.read)
                        $0.isWritableWithResponse = characteristic.properties.contains(.write)
                        $0.isWritableWithoutResponse = characteristic.properties.contains(.writeWithoutResponse)
                        $0.isNotifiable = characteristic.properties.contains(.notify)
                        $0.isIndicatable = characteristic.properties.contains(.indicate)
                    }
                }

                $0.includedServices = (service.includedServices ?? []).map(makeDiscoveredService)
            }
        }

        do {
            let peripheral = try central.peripheral(for: deviceID)
            completion(.success(DiscoverServicesInfo.with {
                        $0.deviceID = deviceID.uuidString
                        $0.services = (peripheral.services ?? []).map(makeDiscoveredService)
                    }))

        } catch {
            completion(.failure(PluginError.unknown(error).asFlutterError))
        }
    }

    func enableCharacteristicNotifications(name: String, args: NotifyCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let characteristic = CharacteristicInstanceIDFactory().make(from: args.characteristic)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
            return
        }

        do {
            try central.turnNotifications(.on, for: characteristic, completion: { _, error in
                if let error = error {
                    completion(.failure(PluginError.unknown(error).asFlutterError))
                } else {
                    completion(.success(nil))
                }
            })
        } catch {
            completion(.failure(PluginError.unknown(error).asFlutterError))
        }
    }

    func disableCharacteristicNotifications(name: String, args: NotifyNoMoreCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let characteristic = CharacteristicInstanceIDFactory().make(from: args.characteristic)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
            return
        }

        do {
            try central.turnNotifications(.off, for: characteristic, completion: { _, error in
                if let error = error {
                    completion(.failure(PluginError.unknown(error).asFlutterError))
                } else {
                    completion(.success(nil))
                }
            })
        } catch {
            completion(.failure(PluginError.unknown(error).asFlutterError))
        }
    }

    func readCharacteristic(name: String, args: ReadCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let characteristic = CharacteristicInstanceIDFactory().make(from: args.characteristic)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
            return
        }

        completion(.success(nil))

        do {
            try central.read(characteristic: characteristic)
        } catch {
            guard let sink = characteristicValueUpdateSink
            else {
                print("Warning! No subscription to report a characteristic read failure: \(error)")
                return
            }

            let message = CharacteristicValueInfo.with {
                $0.characteristic = args.characteristic
                $0.failure = GenericFailure.with {
                    $0.code = Int32(CharacteristicValueUpdateFailure.unknown.rawValue)
                    $0.message = "\(error)"
                }
            }
            sink.add(.success(message))
        }
    }

    func writeCharacteristicWithResponse(name: String, args: WriteCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let characteristic = CharacteristicInstanceIDFactory().make(from: args.characteristic)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
            return
        }

        do {
            try central.writeWithResponse(
                value: args.value,
                characteristic: characteristic,
                completion: { _, characteristic, error in
                    let result = WriteCharacteristicInfo.with {
                        $0.characteristic = args.characteristic
                        if let error = error {
                            $0.failure = GenericFailure.with {
                                $0.code = Int32(WriteCharacteristicFailure.unknown.rawValue)
                                $0.message = "\(error)"
                            }
                        }
                    }

                    completion(.success(result))
                }
            )
        } catch {
            let result = WriteCharacteristicInfo.with {
                $0.characteristic = args.characteristic
                $0.failure = GenericFailure.with {
                    $0.code = Int32(WriteCharacteristicFailure.unknown.rawValue)
                    $0.message = "\(error)"
                }
            }

            completion(.success(result))
        }
    }

    func writeCharacteristicWithoutResponse(name: String, args: WriteCharacteristicRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let characteristic = CharacteristicInstanceIDFactory().make(from: args.characteristic)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "characteristic, service, and peripheral IDs are required").asFlutterError))
            return
        }

        let result: WriteCharacteristicInfo
        do {
            try central.writeWithoutResponse(
                value: args.value,
                characteristic: characteristic
            )
            result = WriteCharacteristicInfo.with {
                $0.characteristic = args.characteristic
            }
        } catch {
            result = WriteCharacteristicInfo.with {
                $0.characteristic = args.characteristic
                $0.failure = GenericFailure.with {
                    $0.code = Int32(WriteCharacteristicFailure.unknown.rawValue)
                    $0.message = "\(error)"
                }
            }
        }

        completion(.success(result))
    }

    func reportMaximumWriteValueLength(name: String, args: NegotiateMtuRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let peripheralID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "peripheral ID is required").asFlutterError))
            return
        }

        let result: NegotiateMtuInfo
        do {
            let mtu = try central.maximumWriteValueLength(for: peripheralID, type: .withoutResponse)
            result = NegotiateMtuInfo.with {
                $0.deviceID = args.deviceID
                $0.mtuSize = Int32(mtu)
            }
        } catch {
            result = NegotiateMtuInfo.with {
                $0.deviceID = args.deviceID
                $0.failure = GenericFailure.with {
                    $0.code = Int32(MaximumWriteValueLengthRetrieval.unknown.rawValue)
                    $0.message = "\(error)"
                }
            }
        }

        completion(.success(result))
    }

    func readRssi(name: String, args: ReadRssiRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let central = central
        else {
            completion(.failure(PluginError.notInitialized.asFlutterError))
            return
        }

        guard let peripheralID = UUID(uuidString: args.deviceID)
        else {
            completion(.failure(PluginError.invalidMethodCall(method: name, details: "peripheral ID is required").asFlutterError))
            return
        }

        do {
            try central.readRssi(
                for: peripheralID,
                completion: papply(weak: self) { context, result in
                    result.iif(
                        success: { rssi in
                            let result: ReadRssiResult = ReadRssiResult.with {
                                $0.rssi = Int32(rssi)
                            }
                            completion(.success(result))
                        },
                        failure: { error in
                            completion(.failure(context.makeFlutterError(error: error)))
                        }
                    )
                }
            )
        } catch let error {
            completion(.failure(makeFlutterError(error: error)))
        }
    }

    // takes an error and converts it into a Flutter error
    private func makeFlutterError(error: Error) -> FlutterError {
        if let error = error as? PluginError {
            return error.asFlutterError
        } else {
            return PluginError.unknown(error).asFlutterError
        }
    }

    private func reportState(_ knownState: CBManagerState? = nil) {
        guard let sink = stateSink
        else { return }

        let stateToReport = knownState ?? central?.state ?? .unknown
        let message = BleStatusInfo.with { $0.status = encode(stateToReport) }

        sink.add(.success(message))
    }

    private func reportSub(_ connectedCentral: CBCentral, changedSub: CBCharacteristic) {
            //print("reportSub: ", changedSub as Any)

            guard let sink = connectedCentralSink
            else { return }

            let message = DeviceInfo.with { $0.id = connectedCentral.identifier.uuidString} //TODO add DeviceID

            sink.add(.success(message))
        }

    func isDeviceConnected(name: String, args: GetConnectionRequest, completion: @escaping PlatformMethodCompletionHandler) {
        guard let deviceID = UUID(uuidString: args.deviceID)
            else {
                completion(.failure(PluginError.invalidMethodCall(method: name, details: "\(args.deviceID) is invalid").asFlutterError))
                return
            }
        guard let central = central
                else {
                    completion(.failure(PluginError.notInitialized.asFlutterError))
                    return
                }
        do {
            let deviceConnectionState: Bool = try central.isDeviceConnected(peripheralID: deviceID)
            completion(.success(GetConnectionInfo.with { $0.state = deviceConnectionState }))
        }
        catch {
            completion(.failure(PluginError.notInitialized.asFlutterError))
        }
        completion(.failure(PluginError.notInitialized.asFlutterError))
    }

    private func didModifyServices() {
        print("services changed!")

        guard let sink = servicesChangedCentralUpateSink
        else { return }

        sink.add(.success(nil))
    }
}
