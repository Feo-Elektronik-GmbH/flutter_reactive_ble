import 'dart:async';

import 'package:flutter_reactive_ble/src/connected_device_operation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mockito/annotations.dart';
import 'package:mockito/mockito.dart';
import 'package:reactive_ble_platform_interface/reactive_ble_platform_interface.dart';

import 'connected_device_operation_test.mocks.dart';

@GenerateMocks([ReactiveBlePlatform])
void main() {
  late ReactiveBlePlatform _blePlatform;
  late ConnectedDeviceOperation _sut;

  group('$ConnectedDeviceOperation', () {
    setUp(() {
      _blePlatform = MockReactiveBlePlatform();
      _sut = ConnectedDeviceOperationImpl(
        blePlatform: _blePlatform,
      );
    });
    group('Listen to char value updates', () {
      CharacteristicValue? valueUpdate;

      setUp(() {
        valueUpdate = CharacteristicValue(
          characteristic: CharacteristicInstance(
            characteristicId: Uuid.parse('FEFF'),
            characteristicInstanceId: "11",
            serviceId: Uuid.parse('FEFF'),
            serviceInstanceId: "101",
            deviceId: '123',
          ),
          result: const Result.success([1]),
        );

        when(_blePlatform.charValueUpdateStream)
            .thenAnswer((_) => Stream.fromIterable([valueUpdate!]));
      });

      test('It emits value updates received from plugincontroller', () {
        expect(
          _sut.characteristicValueStream,
          emitsInOrder(<CharacteristicValue?>[valueUpdate]),
        );
      });
    });

    group('Read characteristic', () {
      late CharacteristicInstance charDevice;
      CharacteristicInstance charOtherSameDevice;
      CharacteristicInstance charOtherDevice;
      CharacteristicValue? valueUpdate;
      CharacteristicValue? valueUpdateOtherDevice;
      CharacteristicValue? valueUpdateSameDeviceOtherChar;
      List<int>? result;

      setUp(() {
        charDevice = CharacteristicInstance(
          characteristicId: Uuid.parse('FEFF'),
          characteristicInstanceId: "11",
          serviceId: Uuid.parse('FEFF'),
          serviceInstanceId: "101",
          deviceId: '123',
        );

        charOtherSameDevice = CharacteristicInstance(
          characteristicId: Uuid.parse('FEFF'),
          characteristicInstanceId: "11",
          serviceId: Uuid.parse('FAFF'),
          serviceInstanceId: "101",
          deviceId: '123',
        );

        charOtherDevice = CharacteristicInstance(
          characteristicId: Uuid.parse('FEFF'),
          characteristicInstanceId: "11",
          serviceId: Uuid.parse('FEFF'),
          serviceInstanceId: "101",
          deviceId: '456',
        );

        valueUpdate = CharacteristicValue(
          characteristic: charDevice,
          result: const Result.success([1]),
        );

        valueUpdateSameDeviceOtherChar = CharacteristicValue(
          characteristic: charOtherSameDevice,
          result: const Result.success([3]),
        );

        valueUpdateOtherDevice = CharacteristicValue(
          characteristic: charOtherDevice,
          result: const Result.success([4]),
        );

        when(_blePlatform.readCharacteristic(charDevice)).thenAnswer(
          (_) => Stream.fromIterable([0]),
        );
      });

      group('Given multiple updates are received for specific device', () {
        setUp(() async {
          when(_blePlatform.charValueUpdateStream)
              .thenAnswer((_) => Stream.fromIterable([
                    valueUpdate!,
                    valueUpdateOtherDevice!,
                    valueUpdateSameDeviceOtherChar!,
                  ]));

          result = await _sut.readCharacteristic(charDevice);
        });

        test('It emits first value that matches', () {
          expect(result, [1]);
        });
      });

      group(
          'Given no updates are provide for characteristic of specific device',
          () {
        setUp(() async {
          when(_blePlatform.charValueUpdateStream)
              .thenAnswer((_) => Stream.fromIterable([
                    valueUpdateOtherDevice!,
                    valueUpdateSameDeviceOtherChar!,
                  ]));
        });

        test('It emits first value that matches', () async {
          expect(
            () => _sut.readCharacteristic(charDevice),
            throwsA(isInstanceOf<NoBleCharacteristicDataReceived>()),
          );
        });
      });
    });

    // Regression guard for #41814 (iOS Bluetooth Wiederverbindung schlägt fehl).
    //
    // Root cause: fork commit aca4d7d made the iOS value-update path stamp
    // CharacteristicInstance identity with UUID *strings* (plus a fabricated random
    // peripheral id), while service discovery stamps the NUMERIC instance index.
    // readCharacteristic matches a value-update to its pending request via
    // `update.characteristic == characteristic` — and CharacteristicInstance.==
    // compares characteristicInstanceId + serviceInstanceId + deviceId. The two
    // encodings never matched, so on a live (non-completing) value stream the read
    // future hung forever and the iNet Box sync stalled ("Dauersynchronisation").
    // Fixed in 5cbb2c8 by making value-update identity symmetric with discovery
    // (numeric index + the real peripheral id). These tests lock that Dart-side
    // contract deterministically, no device needed.
    group('#41814 iOS value-update identity contract', () {
      // Real Truma iNet Box characteristics (PDF spec 6.3.3). The Error message
      // characteristic exists as multiple GATT instances (one per TIN device slot),
      // which is exactly why correct instance identity matters on this device.
      final errorMessageId = Uuid.parse('00000108-0004-0001-0000-0000FE088214');
      final tinServiceId = Uuid.parse('00000000-0004-0001-0000-0000FE088214');
      const deviceId = 'iNet-Box-1';

      // A read request as built from discovery: numeric instance index.
      final readRequest = CharacteristicInstance(
        characteristicId: errorMessageId,
        characteristicInstanceId: '0',
        serviceId: tinServiceId,
        serviceInstanceId: '0',
        deviceId: deviceId,
      );

      test(
          'value-update with discovery-symmetric identity resolves the read '
          '(the fixed 5cbb2c8 behaviour)', () async {
        when(_blePlatform.readCharacteristic(readRequest))
            .thenAnswer((_) => Stream.fromIterable([0]));
        when(_blePlatform.charValueUpdateStream).thenAnswer(
          (_) => Stream.fromIterable([
            CharacteristicValue(
              characteristic: readRequest, // same numeric-index identity
              result: const Result.success([0xAB]),
            ),
          ]),
        );

        expect(await _sut.readCharacteristic(readRequest), [0xAB]);
      });

      test(
          'value-update carrying the regression identity (UUID-string ids + '
          'fabricated device id) never matches -> read never resolves '
          '(reproduces the aca4d7d hang)', () async {
        when(_blePlatform.readCharacteristic(readRequest))
            .thenAnswer((_) => Stream.fromIterable([0]));

        // Single-subscription controller that never closes: models the live value
        // stream on a real connection (it buffers the event until the read listens).
        final liveUpdates = StreamController<CharacteristicValue>();
        addTearDown(liveUpdates.close);
        when(_blePlatform.charValueUpdateStream)
            .thenAnswer((_) => liveUpdates.stream);

        final readFuture = _sut.readCharacteristic(readRequest);

        // The value the regressed native layer emitted for THIS characteristic:
        // identity stamped with UUID strings + a random peripheral id.
        liveUpdates.add(
          CharacteristicValue(
            characteristic: CharacteristicInstance(
              characteristicId: errorMessageId,
              characteristicInstanceId: '00000108-0004-0001-0000-0000fe088214',
              serviceId: tinServiceId,
              serviceInstanceId: '00000000-0004-0001-0000-0000fe088214',
              deviceId: '9f1e2d3c-0000-0000-0000-000000000000', // fabricated
            ),
            result: const Result.success([0xAB]),
          ),
        );

        // Identity never matches, so the read never resolves. On a live stream
        // this is an indefinite hang; here it surfaces as a timeout.
        await expectLater(
          readFuture.timeout(const Duration(milliseconds: 200)),
          throwsA(isA<TimeoutException>()),
        );
        // The abandoned read completes with an error when the controller closes
        // in tearDown; mark it handled so it is not reported as unhandled.
        readFuture.ignore();
      });

      test(
          'duplicate-UUID instances are disambiguated by instanceId: a read for '
          "instance 1 must not resolve with instance 0's value", () async {
        final requestInstance1 = CharacteristicInstance(
          characteristicId: errorMessageId,
          characteristicInstanceId: '1',
          serviceId: tinServiceId,
          serviceInstanceId: '1',
          deviceId: deviceId,
        );
        final updateInstance0 = CharacteristicValue(
          characteristic: CharacteristicInstance(
            characteristicId: errorMessageId,
            characteristicInstanceId: '0',
            serviceId: tinServiceId,
            serviceInstanceId: '0',
            deviceId: deviceId,
          ),
          result: const Result.success([0x00]),
        );
        final updateInstance1 = CharacteristicValue(
          characteristic: requestInstance1,
          result: const Result.success([0x11]),
        );

        when(_blePlatform.readCharacteristic(requestInstance1))
            .thenAnswer((_) => Stream.fromIterable([0]));
        when(_blePlatform.charValueUpdateStream).thenAnswer(
          (_) => Stream.fromIterable([updateInstance0, updateInstance1]),
        );

        expect(await _sut.readCharacteristic(requestInstance1), [0x11]);
      });

      test(
          'CharacteristicInstance equality is sensitive to instanceId, '
          'serviceInstanceId and deviceId (the fields the match relies on)', () {
        CharacteristicInstance withIds(
          String charInst,
          String svcInst,
          String device,
        ) =>
            CharacteristicInstance(
              characteristicId: errorMessageId,
              characteristicInstanceId: charInst,
              serviceId: tinServiceId,
              serviceInstanceId: svcInst,
              deviceId: device,
            );

        expect(withIds('0', '0', deviceId), withIds('0', '0', deviceId));
        expect(withIds('0', '0', deviceId),
            isNot(withIds('1', '0', deviceId))); // char instance differs
        expect(withIds('0', '0', deviceId),
            isNot(withIds('0', '1', deviceId))); // service instance differs
        expect(
          withIds('0', '0', deviceId),
          isNot(withIds(
              '00000108-0004-0001-0000-0000fe088214', '0', deviceId)),
        ); // numeric index vs UUID-string (the regression)
        expect(withIds('0', '0', deviceId),
            isNot(withIds('0', '0', 'other-device'))); // device differs
      });
    });

    group('Write characteristic', () {
      late CharacteristicInstance characteristic;
      WriteCharacteristicInfo info;
      const value = [1, 0];

      setUp(() {
        characteristic = CharacteristicInstance(
          characteristicId: Uuid.parse('FEFF'),
          characteristicInstanceId: "11",
          serviceId: Uuid.parse('FEFF'),
          serviceInstanceId: "101",
          deviceId: '123',
        );
      });

      group('Write characteristic with response', () {
        group('Given write characteristic succeeds', () {
          setUp(() {
            info = WriteCharacteristicInfo(
              characteristic: characteristic,
              result: const Result<Unit,
                  GenericFailure<WriteCharacteristicFailure>>.success(Unit()),
            );

            when(_blePlatform.writeCharacteristicWithResponse(
                    characteristic, value))
                .thenAnswer((_) async => info);
          });

          test('It completes without error', () async {
            await _sut.writeCharacteristicWithResponse(
              characteristic,
              value: value,
            );
            expect(true, true);
          });

          group('Given write characteristic fails', () {
            setUp(() {
              info = WriteCharacteristicInfo(
                characteristic: characteristic,
                result: const Result<Unit,
                    GenericFailure<WriteCharacteristicFailure>>.failure(
                  GenericFailure<WriteCharacteristicFailure>(
                    code: WriteCharacteristicFailure.unknown,
                    message: 'something went wrong',
                  ),
                ),
              );

              when(_blePlatform.writeCharacteristicWithResponse(
                      characteristic, value))
                  .thenAnswer((_) async => info);
            });

            test('It throws exception ', () async {
              expect(
                () => _sut.writeCharacteristicWithResponse(characteristic,
                    value: value),
                throwsException,
              );
            });
          });
        });

        group('Write characteristic without response', () {
          group('Given write characteristic succeeds', () {
            setUp(() {
              info = WriteCharacteristicInfo(
                characteristic: characteristic,
                result: const Result<Unit,
                    GenericFailure<WriteCharacteristicFailure>>.success(Unit()),
              );

              when(_blePlatform.writeCharacteristicWithoutResponse(
                      characteristic, value))
                  .thenAnswer((_) async => info);
            });

            test('It executes successfully', () async {
              await _sut.writeCharacteristicWithoutResponse(characteristic,
                  value: value);

              expect(true, true);
            });
          });

          group('Given write characteristic fails', () {
            setUp(() {
              info = WriteCharacteristicInfo(
                characteristic: characteristic,
                result: const Result<Unit,
                    GenericFailure<WriteCharacteristicFailure>>.failure(
                  GenericFailure<WriteCharacteristicFailure>(
                    code: WriteCharacteristicFailure.unknown,
                    message: 'something went wrong',
                  ),
                ),
              );

              when(_blePlatform.writeCharacteristicWithoutResponse(
                      characteristic, value))
                  .thenAnswer((_) async => info);
            });

            test('It throws exception ', () async {
              expect(
                () => _sut.writeCharacteristicWithoutResponse(characteristic,
                    value: value),
                throwsException,
              );
            });
          });
        });
      });

      group('Subscribe to characteristic', () {
        late CharacteristicInstance charDevice;
        CharacteristicInstance charOtherSameDevice;
        CharacteristicInstance charOtherDevice;
        late CharacteristicValue valueUpdate1;
        late CharacteristicValue valueUpdate2;
        late CharacteristicValue valueUpdateOtherDevice;
        late CharacteristicValue valueUpdateSameDeviceOtherChar;
        late Stream<List<int>?> result;
        late Completer<ConnectionStateUpdate> terminateCompleter;

        setUp(() {
          terminateCompleter = Completer();

          charDevice = CharacteristicInstance(
            characteristicId: Uuid.parse('FEFF'),
            characteristicInstanceId: "11",
            serviceId: Uuid.parse('FEFF'),
            serviceInstanceId: "101",
            deviceId: '123',
          );

          charOtherSameDevice = CharacteristicInstance(
            characteristicId: Uuid.parse('FEFF'),
            characteristicInstanceId: "11",
            serviceId: Uuid.parse('FAFF'),
            serviceInstanceId: "101",
            deviceId: '123',
          );

          charOtherDevice = CharacteristicInstance(
            characteristicId: Uuid.parse('FEFF'),
            characteristicInstanceId: "11",
            serviceId: Uuid.parse('FEFF'),
            serviceInstanceId: "101",
            deviceId: '456',
          );

          valueUpdate1 = CharacteristicValue(
            characteristic: charDevice,
            result: const Result.success([1]),
          );

          valueUpdate2 = CharacteristicValue(
            characteristic: charDevice,
            result: const Result.success([2]),
          );

          valueUpdateSameDeviceOtherChar = CharacteristicValue(
            characteristic: charOtherSameDevice,
            result: const Result.success([3]),
          );

          valueUpdateOtherDevice = CharacteristicValue(
            characteristic: charOtherDevice,
            result: const Result.success([4]),
          );

          when(_blePlatform.subscribeToNotifications(charDevice))
              .thenAnswer((_) => Stream.fromIterable([0]));

          when(_blePlatform.stopSubscribingToNotifications(charDevice))
              .thenAnswer((_) async => 0);
        });

        group('Given multiple updates are received for specific device', () {
          setUp(() async {
            when(_blePlatform.charValueUpdateStream)
                .thenAnswer((_) => Stream.fromIterable([
                      valueUpdate1,
                      valueUpdateOtherDevice,
                      valueUpdate2,
                      valueUpdateSameDeviceOtherChar,
                    ]));

            result = _sut.subscribeToCharacteristic(
                charDevice, terminateCompleter.future);
          });
          test('It emits all values that matches', () {
            expect(
                result,
                emitsInOrder(<List<int>>[
                  [1],
                  [2],
                ]));
          });
        });
      });

      group('Negotiate mtusize', () {
        const deviceId = '123';
        const mtuSize = 50;
        int? result;

        setUp(() async {
          when(_blePlatform.requestMtuSize(deviceId, mtuSize))
              .thenAnswer((_) async => mtuSize);

          result = await _sut.requestMtu(deviceId, mtuSize);
        });

        test('It provides result retrieved from plugin', () {
          expect(result, mtuSize);
        });
      });

      group('Change connection priority', () {
        const deviceId = '123';
        late ConnectionPriority priority;

        setUp(() {
          priority = ConnectionPriority.highPerformance;
        });

        group('Given request priority succeeds', () {
          setUp(() {
            when(_blePlatform.requestConnectionPriority(deviceId, priority))
                .thenAnswer((_) async => const ConnectionPriorityInfo(
                      result: Result.success(Unit()),
                    ));
          });

          test('It succeeds without an error', () async {
            await _sut.requestConnectionPriority(deviceId, priority);

            expect(true, true);
          });
        });

        group('Given request priority fails', () {
          setUp(() async {
            when(_blePlatform.requestConnectionPriority(deviceId, priority))
                .thenAnswer((_) async => const ConnectionPriorityInfo(
                      result: Result.failure(
                        GenericFailure<ConnectionPriorityFailure>(
                            code: ConnectionPriorityFailure.unknown,
                            message: 'whoops'),
                      ),
                    ));
          });

          test('It throws failure', () async {
            expect(
                () async => _sut.requestConnectionPriority(deviceId, priority),
                throwsException);
          });
        });
      });
    });
  });
}
