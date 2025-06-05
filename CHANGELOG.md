# TAG v2.0.0 - Test

- added force flag to force deletion of inet box bonding
- added tests
- added permission checks

# TAG v1.1.128 - Test

- Add SDK check for writing gatt descriptor, as currently used function were deprecated in sdk 33.
- Add logs

# TAG v1.1.127 - Stable
# TAG v1.1.127 - Test
# TAG v1.1.128 - Test

- check if either the connected device address matches to the bonding mac address or the device name contains "iNet Box" to remove the bonding

# TAG v1.1.126 - Stable

- remove string null check

# TAG v1.1.125 - Test

- check mac address equals to passed device id

# TAG v1.1.124 - Test

- remove unnecessary convert function

# TAG v1.1.123 - Test

- rename variable

# TAG v1.1.122 - Test

- update abstract function

# TAG v1.1.121 - Test

- fix errors

# TAG v1.1.120 - Test

- fix errors

# TAG v1.1.119 - Test

- pass device id (mac address) of connected device to the functions that either checks the bondings or removes it

# TAG v1.1.118 - Stable

- resolve compile errors

# TAG v1.1.117 - Test

- resolve compile errors

# TAG v1.1.116 - Test

- resolve compile errors

# TAG v1.1.115 - Test

- fix paranthesis error

# TAG v1.1.114 - Test

- refactored code
- added more logs

# TAG v1.1.113 - Stable

- fix wrong return value
- add return value in catch block

# TAG v1.1.112 - Test

- add toString() method

# TAG v1.1.111 - Test

- remove unnecessary check

# TAG v1.1.110 - Test

- equal to v1.1.109

# TAG v1.1.109 - Test

- add null check on device name when trying to remove bonding

# TAG v1.1.105 - Stable

- remove supressing linter

# TAG v1.1.104 - Test

- add valid config

# TAG v1.1.102 - Test

- add jitpack as replacement for polidea

# TAG v1.1.101 - Test

- try another variant of dependency implementation

# TAG v1.1.100 - Test

- change source test

# TAG v1.1.99 - Test

- use tag instead of branch

# TAG v1.1.98 - Test

- add source control to settings.gradle
- add dependency

# TAG v1.1.97 - Test

- add git repo to build.gradle

# TAG v1.1.96 - Test

- disconnect gatt

# TAG v1.1.92 - Test

- fix missing arrow

# TAG v1.1.91 - Test

- fix central connection handler

# TAG v1.1.90 - Test

- fix missing when case

# TAG v1.1.89 - Stable

- change behaviour subject to publish subject

# TAG v1.1.88 - Test

- fix disposer in did modify services handler

# TAG v1.1.87 - Test

- add overwrite value to ble client

# TAG v1.1.86 - Test

- fix event channel

# TAG v1.1.85 - Test

- refactor kotlin code to match swift implementation

# TAG v1.1.84 - Test

- implementation of method channels

# TAG v1.1.83 - Test

- fix connected device connection

# TAG v1.1.82 - Test

- fix wrong function definition

# TAG v1.1.81 - Test

- add did modify services stream

# TAG v1.1.80 - Stable

- remove suppressing lint

# TAG v1.1.79 - Test

- connect to device to get BluetoothGatt and to set the gatt callbacks
- send service discoveryr request to flutter app

# TAG v1.1.78 - Test

- Send service change update via stream to the flutter application

# TAG v1.1.77 - Stable

- try not to remove all services after stopAdvertising

# TAG v1.1.76 - Test

- revert disconnecting through task instead of directly disconnect

# TAG v1.1.75 - Test

- add checkConnectionFunction to evaluate if device id is part of an active connection

# TAG v1.1.74 - Test

- add logging for isDeviceConnected method

# TAG v1.1.73 - Test

- refactor isDeviceConnected code to a more stable version

# TAG v1.1.72 - Test

- fix missing "not"

# TAG v1.1.71 - Test

- fixed wrong type: bool -> Boolean

# TAG v1.1.70 - Test

- added convert function for pb.GetConnectionInfo

# TAG v1.1.69 - Test

- check if connection is empty

# TAG v1.1.68 - Test

- define isDeviceConnected function and return value

# TAG v1.1.67 - Test

- fix parsing data

# TAG v1.1.66 - Test

- fix import of class in PluginController.kt

# TAG v1.1.65 - Test

- print device id on error for isDeviceConnected function

# TAG v1.1.64 - Test

- add classes to bledata.proto
- generate protobuf code

# TAG v1.1.63 - Test

- change string to bool in protobuf model

# TAG v1.1.62 - Test

- code cleaning
- add missing function call

# TAG v1.1.61 - Test

- fix return type

# TAG v1.1.60 - Test

- rename function parameter deviceID to deviceId

# TAG v1.1.59 - Test

- fix function parameters

# TAG v1.1.58 - Test

- added missing function

# TAG v1.1.57 - Test

- add isDeviceConnected function call to the dart side

# TAG v1.1.56 - Test

- fix extra argument 'completion' in call

# TAG v1.1.55 - Test

- fix protobuf en- and decoding

# TAG v1.1.54 - Test

- add device connection info model

# TAG v1.1.53 - Test

- fix data serialization

# TAG v1.1.52 - Test

- save changes

# TAG v1.1.51 - Test

- rename deviceId to deviceID
- remove value from GetConnectionRequest

# TAG v1.1.50 - Test

- fix guard statement
- exception if device id couldnt be parsed

# TAG v1.1.49 - Test

- cast peripheral id string to uuid

# TAG v1.1.48 - Test

- fix GetConnectionRequest extension

# TAG v1.1.47 - Test

- add GetConnectionRequest extension

# TAG v1.1.46 - Test

- try to fix errors

# TAG v1.1.45 - Test

- try to fix errors

# TAG v1.1.44 - Test

- try to fix errors

# TAG v1.1.44 - Test

- fix ref error in pubspec.yaml

# TAG v1.1.43 - Test

- rename function

# TAG v1.1.42 - Test

- add connection check for ios and android

# TAG v1.1.41 - Stable

- code cleaning
- refactor adding services to gatt stack
- refactor removing services from gatt stack

# TAG v1.1.40 - Test

- remove characteristic from server

# TAG v1.1.39 - Test

- added import for Log-class

# TAG v1.1.38 - Test

- added import for time unit

# TAG v1.1.37 - Test

- added delay before service discovery after disconnect

# TAG v1.1.36 - Test

- clean code

# TAG v1.1.35 - Test

- keep connection in memory

# TAG v1.1.34 - Test

- fix v1.1.33

# TAG v1.1.33 - Test

- add disconnect function

# TAG v1.1.32 - Test

- bug fix

# TAG v1.1.31 - Test

- log service removal

# TAG v1.1.30 - Test

- remove services by hand

# TAG v1.1.29 - Test

- check if services are not cleared, caused by mBluetoothGattServer is null

# TAG v1.1.28 - Test

- not adding services

# TAG v1.1.27 - Test

- only disconnect

# TAG v1.1.26 - Test

- clear gatt

# TAG v1.1.25 - Test

- add logging and remove all connections

# TAG v1.1.24 - Test

- remove gatt server on disconnect

# TAG v1.1.23 - Test

- bug fix

# TAG v1.1.22 - Test

- Making the ble code more null safe

# TAG v1.1.21 - Stable

- Try to fix null error if device name is null

# TAG v1.1.20 - Stable

- Central.swift: Add removeAllServices() function call in stopAdvertising()

# Tag v1.1.19 - Stable

- Fixed changes from Tag 1.1.18

# Tag v1.1.18

- Changed BehaviorSubject to PublishSubject
- Code has errors, don't use it

# Tag v1.1.17 - Stable

- Current working version