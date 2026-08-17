# Voltarian ELM Lab v0.2.0-beta

Bluetooth Classic beta for testing the Android phone as an ELM327-compatible serial adapter.

## New in this beta

- Bluetooth Classic RFCOMM/SPP server
- Standard Serial Port Profile UUID for ELM-compatible clients
- Android 12+ Nearby Devices permission handling
- User-approved five-minute Bluetooth discoverability request
- Paired-client connection and disconnection status
- Shared, synchronized ELM engine for simultaneous TCP and Bluetooth transports
- Last-command display for both transports
- TCP port 35000 remains available as a fallback

## Bluetooth test procedure

1. Install the beta APK on the emulator phone.
2. Open the app and select **Start ELM emulator**.
3. Approve Nearby Devices and Bluetooth discoverability when Android asks.
4. Pair the client device with the emulator phone in Android Bluetooth settings.
5. Select the paired phone from an ELM327-compatible client and connect.
6. Send `ATI`, `ATRV`, `010D`, and `ATVPROFILE`.

This is a beta test emulator, not safety-certified vehicle equipment.

