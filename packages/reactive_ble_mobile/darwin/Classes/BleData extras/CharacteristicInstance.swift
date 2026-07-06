import class CoreBluetooth.CBUUID
import class CoreBluetooth.CBCharacteristic
import class CoreBluetooth.CBService

struct CharacteristicInstance: Equatable {

    let id: CharacteristicID
    let instanceID: CharacteristicInstanceID
    let serviceID: ServiceID
    let serviceInstanceID: ServiceInstanceID
    let peripheralID: PeripheralID
}

extension CharacteristicInstance {

    init(_ characteristic: CBCharacteristic) throws {
        // [refs #41814] Identity MUST use the same scheme as service discovery
        // (PluginController.makeDiscoveredService): the NUMERIC index via `.instanceId?.description`, plus the
        // real peripheral identifier. Dart's CharacteristicInstance.== compares instanceId + serviceInstanceId
        // + deviceId as raw strings, so the earlier UUID-string / random-peripheral-id encoding (aca4d7d) never
        // matched read/notify values to their request → the iOS read & notify-enable hang. Keep numeric ids +
        // real peripheral id for that (central-read) path.
        //
        // [refs #44134] BUT the connected-central / GATT-server (peripheral-role) path (Central.onCharRequest)
        // builds this from a LOCAL characteristic owned by our CBPeripheralManager, whose `service.peripheral`
        // is nil — there is no remote peripheral. The 5cbb2c8 hardening threw `peripheralNotFound` here and thus
        // dropped EVERY write the iNet Box sends to our GATT server, including the old-FW SPP data that carries
        // the firmware version (migration path could never read a FW version). For local characteristics fall
        // back to a STABLE sentinel id — never a random UUID (the random id was the #41814 root cause); the
        // peripheral-role stream is not matched by device id, so a constant is safe and does not affect the
        // central-read path (a remote characteristic always has a non-nil service.peripheral).
        guard let service = characteristic.service
        else {
            throw Failure.serviceNotFound
        }

        self.init(
            id: characteristic.uuid,
            instanceID: characteristic.instanceId?.description ?? "",
            serviceID: service.uuid,
            serviceInstanceID: service.instanceId?.description ?? "",
            peripheralID: service.peripheral?.identifier ?? CharacteristicInstance.localPeripheralID
        )
    }

    /// Stable placeholder peripheral id for LOCAL (GATT-server) characteristics, whose `service.peripheral`
    /// is nil. Constant (not random) so it can never reintroduce the #41814 identity-mismatch read hang.
    static let localPeripheralID = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!
    
    private enum Failure: Error, CustomStringConvertible {

        case serviceNotFound
        case peripheralNotFound
        case characteristicNotFound

        var description: String {
            switch self {
            case .serviceNotFound:
                return "Service not found"
            case .peripheralNotFound:
                return "Peripheral not found"
            case .characteristicNotFound:
                return "Characteristic not found"
            }
        }
    }
}

struct CharacteristicInstanceIDFactory {
    
    func make(from message: CharacteristicAddress) -> CharacteristicInstance? {
        guard
            message.hasCharacteristicUuid,
            message.hasServiceUuid,
            let peripheralID = UUID(uuidString: message.deviceID)
        else { return nil }

        let characteristicID = CBUUID(data: message.characteristicUuid.data)
        let serviceID = CBUUID(data: message.serviceUuid.data)

        return CharacteristicInstance(
            id: characteristicID,
            instanceID: message.characteristicInstanceID,
            serviceID: serviceID,
            serviceInstanceID: message.serviceInstanceID,
            peripheralID: peripheralID
        )
    }
}

public extension CBCharacteristic {
    var instanceId: Int? {
        return service?.characteristics?.filter({ c in c.uuid == uuid }).index(of: self)
    }
}

public extension CBService {
    var instanceId: Int? {
        return peripheral?.services?.filter({ s in s.uuid == uuid }).index(of: self)
    }
}
