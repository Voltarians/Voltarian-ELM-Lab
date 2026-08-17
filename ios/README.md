# Voltarian ELM Companion for iOS

The iOS companion connects to the Android Voltarian ELM Lab emulator over the local network. It provides an ELM command terminal, live transcript and repeatable adapter smoke test.

## Build

1. Install Xcode and XcodeGen on macOS.
2. From this directory, run `xcodegen generate`.
3. Open `VoltarianELMCompanion.xcodeproj` in Xcode.
4. Select your Apple development team and run on an iPhone or iPad.

The initial transport is TCP port `35000`. Bluetooth Classic SPP is not generally available to ordinary iOS applications, so future Apple transport work will target BLE GATT while retaining TCP.

