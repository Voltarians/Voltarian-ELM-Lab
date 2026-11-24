# ELM Lab — OBD-II Simulator

A general-purpose Android test adapter for diagnostic app developers, instructors, and technicians. Run simulated vehicle responses on a phone without connecting to a vehicle. Independent of any particular car manufacturer or diagnostic client.

## Version 0.4.0 beta

- TCP on port `35000`, Bluetooth Classic SPP, and local-network discovery.
- User-started foreground service with a Stop notification; keeps connections outside the activity lifecycle.
- Separate adapter settings and response-sequence positions for each connection; shared vehicle state.
- Seven generic starter scenarios: engine off, warm idle, highway cruise, engine fault, overheating, low supply voltage, and no ECU response.
- Editable VIN, speed, RPM, temperatures, adapter/module voltage, engine load, airflow, throttle, runtime, and fuel.
- Stored, pending, and permanent DTCs; emissions clear; readiness; one captured freeze frame.
- Local saved profiles with JSON import/export and input validation.
- ECU latency and client-timeout simulation, `NO DATA`, `CAN ERROR`, and malformed-data injection.
- Custom response sequences, command logging, and export of recorded response profiles.

This is a beta implementation, not a claim of universal scan-tool compatibility. No billing, subscriptions, ads, or analytics are included.

## Start and connect

1. Install the APK on Android 8.0 or newer.
2. Load a starter profile, edit its values, and tap **Apply to simulator**.
3. Tap **Start simulator**. For a client on the same phone, select a TCP/Wi-Fi connection to `127.0.0.1:35000`.
4. For a second device, connect to the phone's Wi-Fi IP on port `35000`. The app shows detected IP addresses.
5. For Bluetooth, tap **Enable Bluetooth**, grant Nearby Devices permission when requested, pair the other device with this phone, and select the phone as an SPP adapter in the diagnostic client. Bluetooth availability depends on the device and client.
6. Send ASCII commands ending in carriage return. Try `ATI`, `ATE0`, `010C`, `010D`, `03`, `0902`.
7. Use **Stop simulator** or **Stop** in its notification to close listeners and all clients.

The TCP listener is reachable from the local network while running. Use a network you control. No account, cloud service, or vehicle hardware is required. Logs and profiles remain local unless you export them. The transcript retains the newest 500 entries; TCP clients disconnect after 60 seconds without input. Up to eight clients may connect.

## Protocol coverage

The built-in ECU uses ISO 15765-4, 11-bit / 500 kbit/s. This is an application-level ELM simulation, not a physical CAN interface.

| Requests | Behavior |
| --- | --- |
| Mode 01 | Supported-PID pages 00/20/40; PIDs 01, 04, 05, 0C, 0D, 0F, 10, 11, 1F, 2F, 42, 46 |
| Mode 02 | Frame 00: supported PIDs, triggering DTC, load, coolant, RPM, speed |
| Modes 03/07/0A | Stored, pending, permanent codes |
| Mode 04 | Clears stored/pending codes and freeze frame; resets readiness; preserves permanent codes |
| Mode 09 | Supported-PID page and VIN |
| Adapter commands | Z, WS, D, I, @1, @2, E0/1, H0/1, S0/1, L0/1, RV, DP, DPN, SP0/00/6/A6, TP0/6/A6, SH, CRA, CF, CM, AR, CAF0/1, CFC0/1, D0/1, R0/1, AL, NL, M0/1, PC, AT0/1/2, ST |

Unsupported AT commands return `?`; unsupported diagnostic requests return `NO DATA`. PID bitmaps reflect built-in responses. Receive filters/masks can include or exclude ECU 7E8; request addresses other than 7DF/7E0 yield no data. Headers include ISO-TP PCI bytes and eight-byte CAN padding; unheaded multiline responses include a length line and numbered rows. Automatic flow control is simulated internally. In raw CAF0 mode, basic manual CTS/block-size, WAIT, and overflow are supported; nonzero STmin is explicitly rejected. No physical CAN bus is used. Only the documented CAN protocol is implemented.

Legacy remote controls `ATVPROFILE`, `ATVSETSPD`, `ATVSETRPM`, `ATVSETTEMP`, and `ATVSETVOLT` remain for companion compatibility.

## Custom response sequences and replay

The editor accepts JSON such as:

```json
{
  "010D": ["41 0D 00", "41 0D 28", "41 0D 50"],
  "22F190": ["NO DATA"]
}
```

Keys are uppercase, even-length hex requests. Responses contain exact adapter text without echo or the `>` prompt. Include headers and line breaks yourself if the client expects them; adapter formatting settings do not rewrite custom responses. A matching sequence advances per request and per connection, then repeats its final entry. Reapplying a profile or resetting the adapter restarts its sequences. Global fault injection and address filters take precedence.

**Export recorded response profile** groups the latest diagnostic responses by logical request into sequences, including repeated queries. It removes response-count suffixes and raw request PCI bytes from those keys, and excludes adapter initialization, flow-control packets, echo, and the generated SEARCHING line. Import that JSON, apply it, and reconnect the client for a repeatable response test. This replays responses per command; it does not reproduce original timing, cross-command ordering, physical CAN traffic, or state changes caused by overridden commands. Adapter initialization commands are not recorded into response sequences. **Clear command log** also clears the recording. This is distinct from importing arbitrary third-party CAN capture formats, which is not implemented.

Maximum script: 128 requests, 500 responses, 4096 characters per response. Imported profiles are limited to 64 KiB. The latency field simulates 0–5000 ms ECU response time. If it exceeds the active client timeout, the client sees `NO DATA`. AT commands are immediate.

## Adapter behavior and timing

`ATSThh` sets a maximum timeout of `hh * 4 ms`; `ATST00` restores 200 ms. `ATAT0` uses that fixed wait; `ATAT1/2` use a deterministic simulated learned wait with backoff after timeouts. This is a useful test model, not a reproduction of Elm Electronics' proprietary adaptive algorithm. Without a response-count suffix, the plan includes a wait for additional responses. A count suffix such as `010D1` removes that wait and caps the returned CAN frames.

`ATCAF0` requires a raw single-frame PCI byte, for example `02010D`. Displayed raw CAN frames include padding. CAF1 requests are limited to seven payload bytes (up to six Mode 01 PIDs); raw CAF0 frames allow eight bytes without requiring AL. AL/NL are accepted length settings but do not change these CAN limits. Multi-frame outgoing requests, CAN extended addressing, and nonzero manual flow-control separation times are not implemented. Unsupported AT commands still return `?`.

`ATM0/1` control protocol memory across resets **within a connection**. Each new connection starts a new adapter session; protocol selection is not persisted across app restarts or reconnects. `ATTP6` tries protocol 6 without replacing the saved selection. `ATPC` closes the simulated protocol so an automatic connection searches again on its next diagnostic request.

See [the compatibility matrix](docs/COMPATIBILITY.md) for independently verified behavior and remaining limitations.

## Build and validate

Use JDK 17+ and Android SDK platform 35. The included wrapper downloads Gradle 8.9. Open in Android Studio or run:

```sh
./gradlew :elm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `.\gradlew.bat` with the same task arguments.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The debug beta installs as **ELM Lab Beta** (`org.voltarians.elmlab.beta`) alongside the earlier app. The release application ID remains `org.voltarians.elmlab`. Subsequent beta updates require the same signing key. Store release signing, final branding/assets, full third-party notices, current store-policy review, and commercial pricing/payment work remain before publication. No store release is configured by this change.

## Independent client probe

After installing the optional development dependency `obd==0.7.3`, run:

```sh
./gradlew :elm-core:compatibilityClasspath
python tools/compatibility_probe.py
```

The probe starts a JVM loopback server using the same engine and stream parser as Android, then connects with python-OBD. Its JSON report is written under `elm-core/build/reports/`. This is a host protocol test; it does not replace physical Android TCP/SPP testing. python-OBD is an external test dependency and is not bundled in the APK.

## iOS companion

The existing [SwiftUI companion](ios/README.md) connects over TCP and can control compatible remote readings. It is not an iOS simulator server. BLE transport is not implemented.

## License

[MIT](LICENSE). Preserve the copyright and permission notice in distributed copies. The open-source core remains MIT licensed.
