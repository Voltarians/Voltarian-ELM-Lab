# Voltarian ELM Lab

An open-source Android ELM327 emulator, iOS companion and vehicle simulation laboratory. It is intended to test diagnostic applications—including Voltarian—without repeatedly connecting to a vehicle.

## Current MVP

- Stateful, transport-independent Kotlin ELM327 engine
- Android TCP server on port `35000`
- SwiftUI companion for iPhone and iPad with terminal, transcript and smoke test
- Automatic Bonjour discovery and remote simulated-vehicle controls
- Core AT commands: reset, identity, echo, headers, spaces, voltage and protocol selection
- OBD-II PIDs for coolant temperature, RPM, speed, module voltage and VIN
- MIT license and unit-test foundation

## Roadmap

1. Bluetooth Classic RFCOMM server transport for Android
2. BLE GATT transport for iPhone and iPad
3. Saved vehicle profiles and scripted remote scenarios
4. Scripted timeouts, malformed replies and `NO DATA` fault injection
5. CAN and ISO-TP request/response engine
6. Gen-1 Chevrolet Volt and Cadillac ELR simulation profiles
7. Recorded-session import and deterministic replay
8. OBDLink/STN and inexpensive-clone compatibility profiles
9. USB-OTG CAN gateway mode

## Build

Open the project in Android Studio, allow Gradle synchronization, then run the `app` configuration on Android 8.0 or later.

For iOS, see [`ios/README.md`](ios/README.md). The project specification uses XcodeGen so the Xcode project can be regenerated rather than committing machine-specific project metadata.

## Testing from a second device or computer

1. Put both devices on the same network.
2. Start the emulator in the Android app.
3. Connect to the Android device IP on TCP port `35000`.
4. Send commands terminated by carriage return, for example `ATI\r` or `010D\r`.

The emulator must never be treated as safety-certified vehicle equipment. Gateway mode should remain read-only by default and require explicit authorization for writes.

## Contributing

Issues, protocol captures with sensitive data removed, tests, documentation and pull requests are welcome. Do not submit private VINs, credentials, proprietary calibration files or copyrighted service material.
