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
        // [refs #44134] Regression fix: the migration commit (aca4d7d) stamped value-update identity
        // with the characteristic/service UUID string, while service discovery
        // (PluginController.makeDiscoveredService) stamps the NUMERIC index via `.instanceId?.description`.
        // Dart's CharacteristicInstance.== compares instanceId + serviceInstanceId (and deviceId) as raw
        // strings, so read/notify values could never match their discovery-built request → iOS read &
        // notify-enable futures hung. This restores the pre-regression (5.0.3.4) contract: numeric-index
        // ids symmetric with discovery, the real peripheral identifier (never a fabricated random UUID),
        // and drop-on-nil (via the `try?` call sites) instead of crashing on a force-unwrapped service.
        guard let service = characteristic.service
        else {
            throw Failure.serviceNotFound
        }

        guard let peripheral = service.peripheral
        else {
            throw Failure.peripheralNotFound
        }

        self.init(
            id: characteristic.uuid,
            instanceID: characteristic.instanceId?.description ?? "",
            serviceID: service.uuid,
            serviceInstanceID: service.instanceId?.description ?? "",
            peripheralID: peripheral.identifier
        )
    }
    
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
